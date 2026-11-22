## Context

`ClaudeSession.execute` builds `timeout --kill-after=10s <budget> /usr/bin/claude -p - --model ...
--resume|--session-id <id> ...`, runs it through `ContainerSession.exec(command, budget + 60s,
prompt)` and parses stdout. The deadline already lives inside the container (`timeout`), so the
only thing tying the turn to the orchestrator is the exec stream: its stdin carries the prompt, its
stdout carries the result, its lifetime is the turn's lifetime.

Run state is `/tmp/smithy-state.json` in the task container (on the task volume, so it survives pod
recreation). After a restart the orchestrator finds containers (`listManaged`), reads the state,
and the workflow engine carries on from the recorded stage. A turn that was in flight is simply
absent: a step is re-run, or the run waits for an event.

Facts this design leans on, all from the existing code: `/tmp` is a `subPath` of the task PVC on
Kubernetes and the container filesystem on Docker; `copyToContainer` and `execForBytes` already move
files in and out without stdin; `--resume <session-id>` plus the transcript under `/root/.claude`
makes a repeated turn continue; the images are Debian based.

## Goals / Non-Goals

**Goals:**
- An orchestrator restart, a dropped API connection or a client timeout does not stop a running
  turn, and the result is collectable afterwards.
- One mechanism for Docker, StatefulSet and Sandbox backends.
- No second turn on a session while one is running (closes the Docker orphan case).
- Off by default at first; identical behaviour with the flag off.

**Non-Goals:**
- Surviving pod or container loss (the process is in the pod).
- Live output streaming; a persistent in-pod agent or daemon; `sandboxd`.
- Changing what a step is or how the engine schedules steps.

## Decisions

### 1. A turn is a directory of files, with an exit-code file written last

```
/tmp/smithy-turns/<turn-id>/
  prompt        uploaded by the orchestrator (copyToContainer), mode 0600
  cmd           the argv, one argument per line (for debugging, not re-executed from here)
  out           claude stdout (the JSON result)
  err           claude stderr
  lock          held with flock for the lifetime of the launcher
  rc            exit status, written as rc.tmp then renamed
```

`<turn-id>` is a random UUID created by the orchestrator. The launcher (a short `sh` script passed
as the `exec` command, not an image file, so no image change) does:

```
cd <turn-dir>
setsid sh -c 'exec 9>lock; flock -n 9 || exit 99;
  timeout --kill-after=10s <budget> claude <args> < prompt > out 2> err;
  echo $? > rc.tmp; mv rc.tmp rc' > /dev/null 2>&1 < /dev/null &
```

and returns immediately (exit 0) once the lock exists. Properties:

- `setsid` and closed stdio detach the turn from the exec's process group and stream, so closing
  the stream cannot deliver SIGHUP or SIGPIPE to it. This is the claim the spike (task 1.1) must
  prove on both Docker and Kubernetes; the research that motivated this change observed the
  opposite on a plain exec, which is why nothing here relies on the shell's behaviour by default.
- The lock is the liveness signal. `flock -n` on the lock file fails while the launcher lives and
  succeeds once it is gone, however it went. This is immune to pid reuse and needs no `/proc`
  parsing, unlike a pid file.
- `rc` is renamed into place, so a reader sees either no file or a complete one. `rc` exists only
  if the launcher got to run its last line; a SIGKILL of the whole group leaves no `rc`, and that
  absence plus a free lock is exactly what "lost" means.
- The budget stays `timeout` inside the pod, as now: `rc` = 124 is a timeout.

Alternative: `docker exec -d` / a Kubernetes Job per turn. Rejected: not available on the exec path
for pods, and a Job needs the PVC mounted by a second pod (RWO), which breaks sharing the workspace.
Alternative: a pid file. Rejected for pid reuse after a pod restart.

### 2. Observe with bounded waits, never a turn-long stream

`await` runs `exec` with a command that returns as soon as `rc` exists or after `pollInterval`
(default 20 s):

```
d=/tmp/smithy-turns/<id>; i=0
while [ ! -f $d/rc ] && [ $i -lt <pollInterval> ]; do
  flock -n $d/lock true 2>/dev/null && [ ! -f $d/rc ] && { echo LOST; exit 0; }
  sleep 1; i=$((i+1)); done
[ -f $d/rc ] && echo "DONE $(cat $d/rc)" || echo RUNNING
```

One call = one status word. The orchestrator loops until `DONE` or `LOST` or its own deadline.
Reading the lock inside the loop is why `LOST` is prompt. A failed `exec` (API server restart,
network) is retried with backoff and does not affect the turn. Why long-poll rather than a tight
poll: it costs one exec per ~20 s per running turn rather than one per second, and it returns
promptly at the end of a turn.

On `DONE <n>` the orchestrator reads `out` and `err` with `execForBytes` (capped, see Risks),
applies today's interpretation (124 -> `ClaudeTimeoutException`, nonzero -> `IllegalStateException`,
JSON parsing as now), and marks the turn collected.

### 3. The run state records the active turn

`ContainerState` gains an optional `activeTurn`:

```
{ id, promptHash, sessionId, resume, startedAt, deadline, phase: LAUNCHED | COLLECTED, attempts }
```

Order of writes, chosen so a crash at any point is recoverable:

1. Upload `prompt` and write the state with `phase=LAUNCHED` (and the generated id), then launch.
   If the orchestrator dies between the state write and the launch, the directory has no lock; the
   next attach sees "no such turn / no lock, no rc" and launches (see 4). Launching is idempotent
   per id: `flock -n` makes a second launcher for the same id exit at once.
2. Await and collect.
3. Write `phase=COLLECTED` (keeping the id) before returning the result to the caller. The
   directory is kept until the next turn on this container starts, which deletes older turn
   directories. This keeps the last result inspectable and the disk bounded.

State is on the same volume as the turn files, so they are consistent with each other across pod
recreation. `ContainerState` already tolerates new fields (see `extraDirs`).

### 4. Re-attach by prompt hash, at the point the engine already re-runs a step

The engine does not resume a Java call stack after a restart; it re-runs the step or waits for an
event. So the re-attach point is `ClaudeSession.send`, not a new scheduler. `send` computes
`sha256(prompt + schema + permissionMode + model)` and compares with `activeTurn`:

| State found | Action |
| --- | --- |
| no `activeTurn`, or `COLLECTED` | start a new turn (new id) |
| `LAUNCHED`, same hash, lock held | attach: await, collect |
| `LAUNCHED`, same hash, `rc` present | attach: collect |
| `LAUNCHED`, same hash, no lock, no `rc` | lost (see 5) |
| `LAUNCHED`, different hash, lock held | cancel the old turn (SIGTERM the process group via `exec`, wait for the lock to drop), then start a new one. Two live turns on one session must never exist |
| `LAUNCHED`, different hash, finished or lost | discard it, start a new turn |

Consequence worth stating: a step that is re-run after a restart with the same prompt picks up the
live turn's result. A step re-run with a different prompt cancels the stale turn first. A turn
already `COLLECTED` is not deduplicated, so if the orchestrator dies between collecting and the
engine persisting the step's outcome, the step runs the turn again with `--resume`. That is
today's retry behaviour and is accepted; see Risks.

Recovery needs no separate startup pass for correctness (the step's own re-run attaches). One
optional addition: the existing sweep ("what a restart forgot") may attach to unfinished turns of
runs that would otherwise wait for an event, so the result lands without a comment. This is task 4.2
and is separable.

### 5. Lost turns are retried once, then fail loudly

A lost turn (no lock, no `rc`) means the process died with its pod or was killed. The session
transcript on the volume is intact up to the last write, so `--resume` continues it. `attempts` is
incremented and one relaunch is allowed (`agent.claude.lostTurnRetries`, default 1); the next loss
throws `ClaudeTurnLostException` with the turn directory and the last 2 KiB of `err`. This replaces
today's silent hang with an error the run engine already knows how to surface. The deadline is not
reset: a relaunch gets the remaining budget, and a turn past its deadline is reported as a timeout.

### 6. Cancellation and removal

`ClaudeSession.cancel()` (new; today there is none) signals the process group
(`kill -TERM -<pgid>`, pgid written to `pgid` by the launcher) and waits for the lock to drop. The
dashboard path that reports `ClaudeTimeoutException` keeps working unchanged. Removing a container
removes its turns with it.

### 7. Rollout flag and the no-op path

`agent.claude.detached: false` keeps the current single-stream `execute` untouched, behind one
`if`, so the change is reversible by configuration. Default flips to `true` one release later, after
the cluster verification in task 5. With the flag off no new state is written.

## Risks / Trade-offs

- [The detach claim is wrong on some runtime] -> Task 1.1 is a spike gate on Docker and
  Kubernetes (kill the client mid-turn, assert the process lives). If `setsid` is not enough,
  the fallbacks are `nohup` with `disown`, or starting the launcher through the container's init;
  the design above is otherwise unchanged.
- [Output size] `out` for a build turn can be large, and `execForBytes` pulls it through the API
  server. The reader caps at 16 MiB and fails with a clear error; this matches the existing cap on
  stdout in practice. Streaming JSON is a non-goal.
- [Duplicate turn after collect, before step persisted] -> Accepted, equals today's retry. The
  transcript makes the repeat a continuation. Documented rather than fixed because fixing it needs
  the engine to persist outcomes atomically with the collect mark, which is a larger change.
- [Many runs polling] One exec per running turn per `pollInterval`; the API server sees the same
  order of load as the 15 s init-marker polling on create. `pollInterval` is configurable.
- [Prompt and result stay on the volume] They hold repository content and are on the task volume
  next to the checkout and the Claude credentials already, with mode 0600, and are deleted with the
  container. Nothing leaves the pod that did not before.
- [Disk growth] One directory is kept per container, replaced by the next turn.
- [Clock] The deadline in state is orchestrator time; the in-pod `timeout` is the enforcement.
  Skew only affects the reported "started at" and the remaining-budget calculation on relaunch.

## Migration Plan

1. Ship with `detached: false`; no behaviour change.
2. Enable on the test cluster; verify (tasks 5.x), including rolling the orchestrator during a turn.
3. Flip the default; keep the flag for one more release as an escape hatch, then remove the old
   path.
In-flight turns at the moment of enabling are not tracked (they were started attached); finish or
cancel them first, as for the Kubernetes migration.

## Open Questions

- Is the `prompt + schema + permissionMode + model` hash the right identity, or should the engine
  pass an explicit step key? The hash needs no engine change; a step key would also deduplicate a
  collected turn. Leaning towards the hash first.
- Should `lostTurnRetries` default to 0 on the sandbox backend, where the controller recreates the
  pod and a relaunch is more likely to succeed, or the same everywhere? Leaning towards the same.
- Do we want `stream-json` output later to give the dashboard live progress? If yes, `out` becomes
  line-oriented and `await` can return a tail; the file layout above already allows it.
