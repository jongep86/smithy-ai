## Why

`add-kubernetes-runtime` runs each task container as a single-replica StatefulSet, because a bare
pod does not come back after a node drain. A cluster that runs AI agents increasingly has
[kubernetes-sigs/agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) installed for
exactly that job: a `Sandbox` is a pod with a stable name and its own volumes, kept alive by a
controller, with a stopped state that keeps the volumes, an optional expiry, and the same
`podTemplate` a StatefulSet takes. Platforms that standardise on it (quotas, network policy,
RuntimeClass per Sandbox) want Smithy's task pods to be Sandboxes too, so one policy covers every
agent workload on the cluster.

## What Changes

- Add `runtime.kubernetes.backend: statefulset | sandbox`. The default stays `statefulset`;
  `sandbox` creates one `agents.x-k8s.io/v1beta1` `Sandbox` per task container, with the same pod,
  volume, labels and annotation the StatefulSet backend uses.
- Add `runtime.kubernetes.runtimeClassName` and `nodeSelector` for both Kubernetes backends, so a
  task pod can be placed on a gVisor or Kata RuntimeClass and on specific nodes.
- Add `runtime.kubernetes.maxLifetime` (sandbox only): the Sandbox's `shutdownTime`, after which
  the controller deletes a task the orchestrator forgot. Off by default.
- Add `runtime.kubernetes.failedPodPolicy` (sandbox only, agent-sandbox v1.0.12+): sent only when
  set, so an older controller never sees the field.
- Share the pod template and the exec/log code between the two Kubernetes backends, so neither can
  drift from the other.
- Example manifests (RBAC, NetworkPolicy, a SandboxTemplate for platform-side defaults, a sample
  Sandbox) under `examples/kubernetes/agent-sandbox/`.

No breaking changes: without `backend: sandbox`, the StatefulSet backend builds the same manifests
as before this change, and Docker deployments are untouched.

## What it does not change

- Exec: a Sandbox's pod is reached with `pods/exec`, as before. agent-sandbox's own exec story
  (`sandbox-router` and an in-image `sandboxd`) needs a different task image and is not used.
- A Claude turn still runs over one exec stream and still dies with the orchestrator; detached
  turns stay a separate change.
- Warm pools and claims (`SandboxClaim`, `SandboxWarmPool`) are not used: a task pod carries the
  run's repository and tokens as environment, which the controller only honours on a cold start.

## Capabilities

### Modified Capabilities

- `container-runtime`: runtime selection grows a Kubernetes backend switch; least privilege on
  Kubernetes names Sandboxes instead of StatefulSets for the sandbox backend; a new requirement
  describes what a Sandbox-backed task container is.

## Impact

- Code: `orchestrator/backend/.../service/docker/` (new `SandboxRuntime`, `TaskPods`, `PodExec`,
  `TaskNamespace`; `KubernetesRuntime` delegates to them), `RuntimeConfig`,
  `OrchestratorConfigValidator`, `ContainerRuntimeConfiguration`.
- No new dependency: the CRD is addressed through fabric8's `GenericKubernetesResource`.
- Cluster: the sandbox backend needs the agent-sandbox CRDs and controller (v1.0.0 or later,
  serving `v1beta1`) installed by the platform.
- Tests: unit tests for the manifest and for create/list/suspend/resume/remove against fabric8's
  mock server; the existing `KubernetesRuntimeTest` is unchanged and still passes.
