## Why

A Claude turn runs for up to 60 minutes over one `exec` stream (`ClaudeSession.execute` ->
`ContainerSession.exec`). The turn is only as durable as that stream:

- On Kubernetes, when the orchestrator restarts, the stream closes and the CLI dies with it. The
  plan file may already be written, but the run waits for a result that never comes, until someone
  comments on the issue. This was observed on a test cluster (see `add-kubernetes-runtime`,
  Risks). Moving task pods to a `Sandbox` (`add-agent-sandbox-runtime`) does not change this: the
  pod and volume survive, the turn does not.
- On Docker the CLI survives as an orphan. It keeps writing the session transcript while the
  orchestrator, which has lost the result, starts a second turn on the same session.

A rolling deploy of the orchestrator is a normal event; it must not cost a running turn.

## What Changes

- Add a detached turn: the orchestrator uploads the prompt as a file, starts a small launcher in the
  task container with a short `exec` that returns at once, and records the turn in the run state.
  The launcher runs `timeout ... claude -p ...` with stdin, stdout and stderr on files under
  `/tmp/smithy-turns/<turn-id>/`, holds a lock for as long as it lives, and writes an exit-code file
  last.
- The orchestrator collects the result with short, bounded waits (`exec` calls that return when the
  exit-code file appears or after a poll interval), never with one stream that spans the turn.
- Re-attach: `ClaudeSession.send` first looks at the run state. A turn that is still running, or
  finished but not yet collected, for the same prompt is attached to instead of started again. This
  makes a step that the engine re-runs after a restart cheap and safe.
- A turn whose process is gone without an exit code (pod recreated, OOM kill) is reported as lost
  and, within a small budget, restarted with `--resume`, which is what a retried turn does today.
- Add `agent.claude.detached` (default `false` for one release, then `true`) and
  `agent.claude.pollInterval`. With `false`, behaviour is exactly as before.
- Works on every `ContainerRuntime` (Docker, StatefulSet, Sandbox): it needs only `exec` and a
  filesystem that outlives the exec.

No breaking changes while the flag is off. `ContainerState` gains one optional field; state written
by an older version reads as "no active turn".

## What it does not change

- Prompt upload and the 48 KiB base64 chunk workaround for fabric8 stdin (the prompt already
  travels as a file; this change stops piping it into `claude` over the stream).
- A turn does not survive the loss of its pod or container. A node drain or an OOM kill still stops
  the process. This change makes that visible and retryable instead of a silent hang.
- Streaming progress to the dashboard. Output stays `--output-format json` and arrives at the end.
- agent-sandbox's own exec API, warm pools, and the run state file location.

## Capabilities

### New Capabilities

- `claude-turns`: how an agent turn is started, observed, collected, bounded and recovered.

## Impact

- Code (all in the orchestrator backend): `service/claude/ClaudeSession` (split `execute` into
  launch, await, collect), a new `service/claude/DetachedTurn` (launcher script, paths, status
  parsing), `service/docker/dto/ContainerState` (optional `activeTurn`), startup recovery in
  `RunEnvironments` / the run engine's sweep, `ConfigLoader` for the two settings.
- Images: none, provided `flock`, `setsid` and `timeout` exist. All three come from the Debian
  base (`util-linux`, `coreutils`) that every `claude-task-*` image already installs.
- Runtime interface: unchanged.
- Cluster / RBAC: unchanged; more, shorter `pods/exec` calls.
- Tests: unit tests for the launcher command and status parsing; mock-runtime tests for
  attach/lost/collect; a Docker integration test that kills the exec client mid-turn.
