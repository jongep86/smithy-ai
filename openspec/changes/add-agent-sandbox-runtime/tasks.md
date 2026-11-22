## 1. Shared pod and exec

- [x] 1.1 Move the pod template, labels, claim specs and the running predicate into `TaskPods`; move exec, stdin upload and logs into `PodExec`; move namespace resolution, cache claims and the running-pod listing into `TaskNamespace`. `KubernetesRuntime` delegates. Verify: `KubernetesRuntimeTest` passes unchanged.
- [x] 1.2 Add `runtimeClassName` and `nodeSelector` to `KubernetesRuntimeConfig` and the pod template. Verify: unit test asserting both appear in the Sandbox pod spec when set and are absent when not.

## 2. Configuration

- [x] 2.1 Add `backend`, `maxLifetime` and `failedPodPolicy` to `KubernetesRuntimeConfig`, keep the previous constructor shape, validate `backend` and `failedPodPolicy`. Verify: config tests for the default backend, `backend: sandbox`, and an unknown backend (startup error names the allowed values).
- [x] 2.2 Select `SandboxRuntime` from `backend: sandbox` in `ContainerRuntimeConfiguration`. Verify: bean test gets `SandboxRuntime` with the block and `KubernetesRuntime` without `backend`.

## 3. Sandbox runtime

- [x] 3.1 Build the Sandbox manifest (`service: false`, `operatingMode: Running`, one `task` claim template, the shared pod template with labels and annotation, optional `shutdownTime`/`shutdownPolicy`, optional `failedPodPolicy`). Verify: unit test asserting the fields, and one asserting the pod spec equals the StatefulSet's.
- [x] 3.2 Implement `create`, `exists`, `isRunning`, `start`, `listManaged`, `remove` against `GenericKubernetesResource`, with `Ready`-condition parsing in error messages. Verify: tests against the fabric8 mock server in CRUD mode.
- [x] 3.3 Route `exec`, `execForBytes`, `logs` and `ownLogs` through `PodExec` with pod name `<id>`. Verify: naming unit test; behaviour by `KubernetesLifecycleIT` on a cluster.

## 4. Examples and docs

- [x] 4.1 Add `examples/kubernetes/agent-sandbox/` with RBAC for the orchestrator account, a task-namespace NetworkPolicy, a SandboxTemplate for platform defaults, a sample Sandbox for a manual smoke test, and a README with the configuration. Verify: `kubectl kustomize examples/kubernetes/agent-sandbox` renders.

## 5. Verification on a cluster

- [ ] 5.1 Run `KubernetesLifecycleIT` with `backend: sandbox` against a cluster with agent-sandbox v1.0.12: create, init, exec with env and stdin, timeout, file round-trip, pod deletion with state surviving, suspend/resume, remove (Sandbox, pod and PVC gone, cache claim kept).
- [ ] 5.2 Run one issue end to end on the sandbox backend. Verify: pull request created by the bot, no Sandbox or per-task claim left afterwards.
- [ ] 5.3 `kubectl auth can-i` for the orchestrator account: `sandboxes` create/get/list/patch/delete and `pods/exec` allowed in the task namespace, `statefulsets` not needed, nothing outside the namespace.
