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

Live findings from the cluster test of `add-agent-sandbox-runtime` (agent-sandbox v1.0.12, PR #6) that
shape this design:

- Files on the task volume (`/tmp`, `/workspace`, `/root`, the state file) survive pod deletion and
  the controller recreates the pod. So turn files outlive a lost pod, and "lost" is detectable and
  recoverable, but the process itself does not survive.
- Exec as the orchestrator's own ServiceAccount needs `get` as well as `create` on `pods/exec`
  (fabric8 opens exec as a WebSocket upgrade). The example Role now has both; detached turns make
  many more short execs, so this must hold for the polling path too.
- A failed exec used to surface as an opaque `KubernetesClientException`; `PodExec.describe` now
  names the cause (for example HTTP 403). The await loop can tell a permanent failure from a
  transient one.
- Large payloads work: a 512 KiB stdin upload arrived whole, so the prompt upload needs no change.
- `maxLifetime` makes the controller delete the Sandbox, pod and claim on its own, and
  `failedPodPolicy: Recreate` replaces a failed pod with backoff of 5 s, 10 s, 20 s.
- `operatingMode: Suspended` deletes the pod.

## Spike result (task 1.1): passed

Run on 2026-11-23 with the real task image (`claude-task-go`, Ubuntu 24.04, PID 1 `sleep infinity`
as left by `smithy-init`) and a stub in place of claude that reads stdin, sleeps, then prints JSON.
The launcher was the one in Decision 1, plus a `linger` argument that keeps the exec open after the
launch so the client can be killed while the stream is still live.

| Case | Docker 29 (Linux host) | Kubernetes 1.36, containerd, via `kubectl exec` | Kubernetes, via the orchestrator's own `PodExec` (fabric8) |
| --- | --- | --- | --- |
| Kill the exec client (`kill -9`) 3 s into an open exec, re-exec | turn `RUNNING`, then `DONE 0`, `out` complete | same | JVM `kill -9`'d 6 s in; a new JVM saw `RUNNING`, one stub process, then `DONE 0` |
| `kill -9` the exec's own process group inside the container | `RUNNING`, then `DONE 0` | n/a | n/a |
| SIGTERM to the turn (cancel) | `DONE 143` | not repeated | not repeated |
| SIGKILL of the whole turn session (OOM / pod death of the process) | `LOST` | not repeated | not repeated |
| Budget 3 s on a 60 s turn | `DONE 124` | not repeated | not repeated |
| Second launcher for the same directory | starts nothing | not repeated | not repeated |
| Status of a directory that was never launched | `NONE` | not repeated | not repeated |

Two findings that shape the implementation:

- On this cluster even a plain background child **without** `setsid` and with stderr on the exec
  stream survived the client being killed: containerd keeps an exec running when its stream goes.
  The opposite observation that motivated this change was not reproduced. `setsid` plus files for
  stdio stay, as defence in depth for runtimes that do signal the exec's group.
- PID 1 in the task container (`sleep infinity`) does not reap. Every finished turn leaves at least one
  zombie entry (the orphaned launcher shell, plus anything claude itself orphans). Zombies hold no
  memory or files and do not affect the lock, but they count against the pod's pid limit, so this
  takes thousands of turns per container to matter. A reaping init (`tini`, or `docker run --init`)
  in the task image is the clean fix and is left as a follow-up.

The scripts are now covered by `DetachedTurnScriptTest` (real `sh`, `flock`, `setsid`, `timeout` on
the build host, including killing the exec's process tree after launch), `DetachedTurnDockerIT`
(Docker) and two cases in `KubernetesLifecycleIT` (StatefulSet and Sandbox backends).

## Implementation notes (where the code differs from the decisions above)

- **No `cd` into the turn directory.** Claude must run in `/workspace` (the exec workdir); the
  launcher uses absolute paths for its files instead.
- **`pid` holds the pid of `timeout`, and cancel signals its process group.** GNU `timeout` puts
  itself in a new process group, so the launcher's own pgid would miss claude. `kill -TERM -<pid>`
  reaches `timeout` and claude; `timeout` then exits 143, which the waiting shell writes to `rc`.
- **Status has a fourth word, `NONE`**: no `pid` and no `rc`. That is "recorded but never
  launched" (crash between the state write and the launch, Decision 3), which is started under the
  recorded id. During a wait, `NONE` (the directory is gone, the volume was replaced) is treated
  as lost.
- **A relaunch gets a new turn id** (and directory), `attempts + 1`, the same hash and deadline.
  It uses `--resume` only when the session transcript exists, because a turn lost before claude
  wrote it would otherwise fail on "no conversation found".
- **The prompt is written with `umask 077; cat >`**, not `copyToContainer` (which leaves 0644).
  On Kubernetes it still travels through `PodExec`'s chunked upload.
- **A new turn stops every other live turn in the container first** (`PREPARE`), whatever the
  state file says, then deletes their directories. The state file is read-modify-written from disk
  for every turn write, but another `ContainerSession` with a stale cache can still overwrite it;
  this guard keeps "never two turns on one session" true even then.
- **Observation failures**: 401/403 (as `PodExec.describe` reports them) fail at once; anything
  else is retried with backoff (1 s doubling, capped at `pollInterval`) and `ensureRunning` in
  between; after three failures a container that no longer exists stops the wait.
- **Run cancel** (`RunEnvironments.destroyContainer`, used by the dashboard) stops the active turn
  before removing the container when the flag is on.
- **Suspend (Decision 7)**: no orchestrator code path suspends a Sandbox today (`SandboxRuntime`
  only resumes one in `start`), so there is nothing to guard yet. Any future suspend path must call
  `ClaudeSession.cancelActiveTurn` first.
- **Takeover messages** check `ClaudeSession.hasRunningTurn()` and answer busy (409) when a
  detached turn runs: the run lock is in-process, so after a restart it no longer covers a turn
  started before it, and the attach rules would otherwise stop that turn for the person's prompt.
- The engine sweep that attaches to unfinished turns without a new event (task 4.2) is not built.

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
network, pod being recreated) is retried with backoff and does not affect the turn, but only while
the cause is transient: an authorization failure (HTTP 401/403, as found live with an incomplete
Role) fails the await at once with the cause from `PodExec.describe`, instead of retrying until the
deadline. While the pod is absent or not running, the loop calls `ensureRunning` rather than
reporting `LOST`: a turn is lost only when the exec succeeds, the lock is free and `rc` is absent. Why long-poll rather than a tight
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

The relaunch waits for the pod first: on the sandbox backend the controller recreates a pod
within seconds to a minute (backoff 5 s, 10 s, 20 s under `failedPodPolicy: Recreate`), and a
relaunch exec against a missing pod would fail for the wrong reason. A lost turn (no lock, no `rc`) means the process died with its pod or was killed. The session
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

### 7. Interaction with suspend, expiry and removal

`Suspended` and `maxLifetime` expiry remove the pod, so they end a running turn. The runtime
callers that suspend (`stop`-style paths) SHALL cancel an active turn first, or refuse while one is
running; they must not silently produce a lost turn. `maxLifetime` is not aware of turns: an
operator who enables it must set it above the longest turn budget plus the longest human wait. A
turn found after the Sandbox was recreated from nothing (claim gone) has no files and is simply
absent, which is the existing "container not found" path.

### 8. Rollout flag and the no-op path

`agent.claude.detached: false` keeps the current single-stream `execute` untouched, behind one
`if`, so the change is reversible by configuration. Default flips to `true` one release later, after
the cluster verification in task 5. With the flag off no new state is written.

## Risks / Trade-offs

- [Zombies] PID 1 of the task image does not reap; see the spike result. Follow-up: a reaping init.
- [The detach claim is wrong on some runtime] -> Passed on Docker and Kubernetes (see Spike result). Task 1.1 was a spike gate on Docker and
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
