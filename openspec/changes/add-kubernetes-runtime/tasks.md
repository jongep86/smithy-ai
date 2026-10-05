## 1. Runtime seam (no behaviour change)

- [x] 1.1 Add `ContainerSpec` and the `ContainerRuntime` interface; move the Docker calls from `ContainerService` into `DockerRuntime`; `ContainerService` builds a `ContainerSpec` instead of Docker args. Verify: `./gradlew :backend:test` passes with `FakeDockerCli` tests unchanged, and the recorded Docker argument lists are identical to before.
- [x] 1.2 Add the `go` cache entry (`/root/go/pkg/mod`) to the shared cache table. Verify: unit test resolving `caches: [go]` to that mount.

## 2. Configuration

- [x] 2.1 Add `RuntimeConfig.KubernetesRuntimeConfig` and reject configs with both `docker` and `kubernetes` in `OrchestratorConfigValidator`. Verify: config tests for no block, kubernetes block, and both blocks (startup error names both).
- [x] 2.2 Select the `ContainerRuntime` bean from the config. Verify: Spring context test that gets `DockerRuntime` without the block and `KubernetesRuntime` with it.

## 3. Kubernetes runtime

- [x] 3.1 Add `io.fabric8:kubernetes-client` and the mock-server test dependency. Verify: `./gradlew :backend:dependencies` resolves and the build passes.
- [x] 3.2 Implement name mapping (sanitize, cut, hash, annotation). Verify: unit tests for a short valid name, nested-path names, over-long names, and stability of the mapping.
- [x] 3.3 Build the StatefulSet manifest (labels, annotation, env, `smithy-init`, subPath mounts, `/root` seeding init container, cache claims, no SA token, retention policy, pull secrets, resources). Verify: unit test asserting the manifest fields.
- [x] 3.4 Implement `create`, `exists`, `isRunning`, `start`, `listManaged`, `remove` (including cache claim creation and per-task claim deletion). Verify: tests against the fabric8 mock server in CRUD mode, including "unrelated pod is not listed" and "remove keeps cache claims".
- [x] 3.5 Implement `exec`, `execForBytes` and `logs` with the positional-argument wrapper, stdin, exit status, and timeout as exit code 124. Verify: unit test of the built command line; behaviour checked in 5.2.
- [ ] 3.6 Make `fetchOwnLogs` use `POD_NAMESPACE`. Verify: unit test with the env set and unset.

## 4. Packaging and examples

- [ ] 4.1 Keep the Docker CLI in the orchestrator image (Docker stays the default). Verify: image builds with `docker build -f orchestrator/Dockerfile .`.
- [ ] 4.2 Add `examples/kubernetes/` (namespace, ServiceAccount, Role, RoleBinding, orchestrator Deployment + Service + PVC for `/config`, knowledgebase, NetworkPolicy for task pods, config). Verify: `kubectl kustomize examples/kubernetes` renders and `kubectl apply --dry-run=server` succeeds against a cluster.
- [ ] 4.3 Add a Kubernetes setup page to the docs. Verify: docs build succeeds.

## 5. Verification on a cluster

- [x] 5.1 Add `KubernetesLifecycleIT`, run only when `SMITHY_K8S_IT_NAMESPACE` is set: create, init, exec with env and stdin, timeout, file round-trip, pod deletion with state surviving, remove. Verify: passes against the test cluster.
- [ ] 5.2 Deploy the fork to the test cluster and run one issue end to end (plan, approve, build, PR). Verify: PR created by the bot, task pod and per-task claim removed after the run, cache claim still present.
- [x] 5.3 Check least privilege: no service-account token in a task pod, and `kubectl auth can-i` for the orchestrator account is denied outside the task namespace. Verify: command output recorded in the PR description.
