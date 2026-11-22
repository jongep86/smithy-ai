## 1. Spike gate

- [ ] 1.1 Prove detachment: on Docker and on a Kubernetes pod (`pods/exec` through fabric8), start the launcher with a short exec, close the client, and show the child still runs and writes `rc`; repeat while killing the whole JVM. Verify: an integration test (Docker, runs in CI) and a recorded manual run on a cluster. If it fails, pick the fallback in design Risks before going on.
- [ ] 1.2 Confirm `flock`, `setsid`, `timeout` exist in every `claude-task-*` image. Verify: a test step in the image build or a one-line `command -v` check in the lifecycle IT.

## 2. Turn mechanics

- [ ] 2.1 Add `DetachedTurn`: turn paths, launcher command, status command, status parser (`RUNNING`, `DONE <n>`, `LOST`), cancel command. Verify: unit tests on the generated shell and parser, shell-quoting tests with prompts and arguments containing quotes and newlines.
- [ ] 2.2 Upload `prompt` with the existing `copyToContainer`, mode 0600, directory 0700. Verify: unit test against a mock `ContainerRuntime`.
- [ ] 2.3 Split `ClaudeSession.execute` into build-argv, launch, await, collect, keeping result interpretation (124, nonzero, JSON, `structured_output`) in one shared method used by both paths. Verify: the existing `ClaudeSession` tests pass with the flag off, unchanged.

## 3. State and attach

- [ ] 3.1 Add optional `activeTurn` to `ContainerState`; old state files read as none. Verify: JSON round-trip tests with and without the field.
- [ ] 3.2 Implement the attach table (design 4) in `send`/`sendStructured`/`startPlan`, including cancelling a stale turn with a different prompt hash. Verify: one mock-runtime test per row of the table.
- [ ] 3.3 Lost-turn handling with `lostTurnRetries` and `ClaudeTurnLostException`; remaining-budget relaunch. Verify: tests for one loss then success, two losses then exception, loss past the deadline then timeout.

## 4. Recovery and cancel

- [ ] 4.1 Add `ClaudeSession.cancel()` and use it from the dashboard path that cancels runs. Verify: test that the lock drops and `await` reports `DONE 143` or `LOST`.
- [ ] 4.2 (Optional, separable) In the engine's post-restart sweep, attach to unfinished turns so a run waiting on a result progresses without a new event. Verify: restart test with a fake runtime holding a finished turn.

- [ ] 4.3 Make suspend paths cancel or refuse while a turn is active. Verify: test that suspend with an active turn does not leave a lost turn.
- [ ] 4.4 In the await loop, classify exec failures from `PodExec.describe` (401/403 permanent, others transient) and call `ensureRunning` while the pod is absent. Verify: tests with a runtime that fails with 403, and one whose pod returns after two failures.

## 5. Configuration and verification

- [ ] 5.1 Add `agent.claude.detached`, `pollInterval`, `lostTurnRetries` to config, validation (positive durations), defaults (`false`, `20s`, `1`). Verify: config tests, including rejection of a zero interval.
- [ ] 5.2 Docker integration test: start a detached turn with a stub `claude` that sleeps then prints JSON, kill the orchestrator-side client, attach from a new `ClaudeSession`, assert the result and that no second process started.
- [ ] 5.3 Cluster verification on the Kubernetes and Sandbox backends (extend `KubernetesLifecycleIT`, which already selects the backend with `SMITHY_K8S_IT_BACKEND` and passed against agent-sandbox v1.0.12; run as the example ServiceAccount, not only as admin): start a long turn, roll the orchestrator, assert the run completes without a comment; delete the pod mid-turn and assert one relaunch then a clear error on a second loss.
- [ ] 5.4 Docs: a short page on the flag, the files under `/tmp/smithy-turns`, and how to inspect a turn by hand.
