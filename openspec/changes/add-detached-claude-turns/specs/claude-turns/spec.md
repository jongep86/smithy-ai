## ADDED Requirements

### Requirement: Detached turns

When `agent.claude.detached` is true, the orchestrator SHALL run each Claude turn as a process in
the task container that does not depend on the exec stream that started it. The prompt SHALL be
delivered as a file, not on the stream. The process SHALL hold a lock for as long as it lives and
SHALL write its exit status to a file as its last action, atomically. The orchestrator SHALL NOT
keep one exec call open for the duration of the turn.

#### Scenario: Orchestrator restarts mid-turn

- **WHEN** the orchestrator process stops while a turn is running and a new orchestrator starts
- **THEN** the turn process keeps running, and the new orchestrator can collect its result

#### Scenario: Exec client times out

- **WHEN** an exec call used to observe a running turn fails or times out
- **THEN** the turn is unaffected and the observation is retried

#### Scenario: Flag off

- **WHEN** `agent.claude.detached` is false or unset
- **THEN** turns run over a single exec stream exactly as before and no turn state is written

### Requirement: Turn identity and re-attach

The orchestrator SHALL record the active turn (id, a hash of the prompt, schema, permission mode and
model, session id, start time, deadline, phase) in the run state before launching it. When a turn is
requested and the recorded turn is unfinished or finished but not collected and has the same hash,
the orchestrator SHALL attach to it instead of starting another. At most one turn per session SHALL
run at a time: a request with a different hash SHALL first cancel the unfinished recorded turn.

#### Scenario: Same prompt after restart

- **WHEN** a step is re-run after a restart with the prompt of a turn that is still running
- **THEN** no new process starts and the step receives the running turn's result

#### Scenario: Different prompt while a turn is running

- **WHEN** a turn is requested on a session whose recorded turn is running with a different hash
- **THEN** the recorded turn is cancelled and its lock released before the new turn starts

#### Scenario: Finished and collected turn

- **WHEN** a turn has already been collected and the same prompt is requested again
- **THEN** a new turn starts (with `--resume`) and the old result is not reused

#### Scenario: Old state

- **WHEN** the run state was written by a version without `activeTurn`
- **THEN** it is read as having no active turn

### Requirement: Turn outcome

A turn SHALL be interpreted as it is today: exit status 124 is a timeout, any other nonzero status is
a failure carrying the captured error output, and zero yields the parsed result. The wall-clock
budget SHALL be enforced inside the task container.

#### Scenario: Budget exceeded

- **WHEN** a turn runs past its budget while the orchestrator is not connected
- **THEN** the in-container timeout stops it and the next observation reports a timeout

#### Scenario: Result too large

- **WHEN** the result file exceeds the configured cap
- **THEN** collection fails with an error that names the cap and the turn directory

### Requirement: Lost turns

A turn whose lock is free and whose exit-status file is absent SHALL be reported as lost. A lost
turn SHALL be relaunched with `--resume` and the remaining budget, at most `lostTurnRetries` times
(default 1); after that the orchestrator SHALL raise a turn-lost error that names the turn
directory and includes the tail of the error output. A turn past its deadline SHALL be reported as a
timeout, not relaunched.

#### Scenario: Pod recreated mid-turn

- **WHEN** the task pod is recreated while a turn runs and the volume survives
- **THEN** the turn is reported lost and relaunched once with the same session

#### Scenario: Second loss

- **WHEN** a relaunched turn is also lost
- **THEN** the orchestrator raises a turn-lost error instead of waiting

### Requirement: Turn files are private and bounded

Turn files SHALL be created readable only by the task user, SHALL live under the task volume's
temporary directory, and SHALL be removed when the next turn on the container starts or the
container is removed.

#### Scenario: Next turn

- **WHEN** a new turn starts on a container
- **THEN** directories of earlier turns are removed

### Requirement: Observation survives a recreated pod, not a permission error

While a turn is observed, an exec failure with a transient cause (pod absent, connection lost)
SHALL be retried and SHALL NOT mark the turn lost. An authorization failure SHALL fail the
observation at once with its cause. Suspending a container SHALL NOT be done while a turn is
active unless that turn is cancelled first.

#### Scenario: Pod being recreated

- **WHEN** the task pod is deleted and the controller recreates it while a turn is observed
- **THEN** the observation waits for the pod, and the turn is reported lost only if the pod comes back without the process

#### Scenario: Missing exec permission

- **WHEN** observation exec fails with HTTP 403
- **THEN** the observation fails immediately and the error names the status
