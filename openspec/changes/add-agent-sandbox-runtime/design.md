## Context

`KubernetesRuntime` (change `add-kubernetes-runtime`) owns a StatefulSet per task: a pod named
`<id>-0`, one PVC from a claim template mounted three times with `subPath`, an init container that
seeds `/root`, no service-account token, and shared cache PVCs. Everything above the pod — exec,
logs, the stdin upload workaround for fabric8 — only needs the pod's name.

agent-sandbox v1beta1 (`agents.x-k8s.io`) offers a `Sandbox` whose `spec.podTemplate` is a full
`PodSpec`, whose `volumeClaimTemplates` become PVCs named `<template>-<sandbox>` owned by the
Sandbox, whose pod is named like the Sandbox, and whose status is a list of conditions (`Ready`,
`Suspended`, `PodScheduled`, `Finished`) — there is no `status.phase` or `status.podName`.
`operatingMode: Suspended` deletes the pod and keeps the volumes; `shutdownTime` with
`shutdownPolicy: Delete` makes the controller remove the Sandbox at a given time. The controller
has no exec of its own: the project's clients talk to a server inside the sandbox image through a
router, which the `claude-task-*` images do not have.

## Goals / Non-Goals

**Goals:**
- A `sandbox` backend with the same observable behaviour as the StatefulSet backend.
- No change to the StatefulSet backend's manifests or to Docker.
- One pod definition and one exec implementation for both Kubernetes backends.
- Only fields every v1beta1 controller accepts are sent by default.

**Non-Goals:**
- Warm starts through `SandboxClaim`/`SandboxWarmPool`.
- Replacing `pods/exec` with the sandbox router or `sandboxd`.
- Detached turns (an orchestrator restart still kills a running turn).
- A generated Java model for the CRD.

## Decisions

### 1. A second `ContainerRuntime`, chosen by `runtime.kubernetes.backend`

`SandboxRuntime implements ContainerRuntime`, next to `KubernetesRuntime`, and
`ContainerRuntimeConfiguration` picks one from `backend` (`statefulset` by default). Both read the
same `runtime.kubernetes` block, because namespace, image, storage class, caches, pull secrets and
resources are the same whichever object owns the pod. The validator rejects an unknown backend
and a `failedPodPolicy` other than `Retain`/`Recreate`.

*Alternative:* one class with an `if` per method hides which fields each backend reads and makes
the manifest tests harder to tell apart.

### 2. The pod is built once

`TaskPods.podSpec(spec, config)` returns the typed `PodSpec` that `KubernetesRuntime` puts in its
StatefulSet template and `SandboxRuntime` serialises into `spec.podTemplate.spec`. A test asserts
the two are byte-for-byte the same JSON. `TaskPods` also holds the labels, the annotation, the
claim spec, the cache claim and the "is this pod running" predicate. `runtimeClassName` and
`nodeSelector` are added here, so both backends get them.

### 3. The CRD is a `GenericKubernetesResource`

fabric8 has no model for `Sandbox` and generating one for a single resource is not worth a build
step. The manifest is a map built in `SandboxRuntime.sandboxFor`; the typed `PodSpec` and
`PersistentVolumeClaimSpec` are converted with fabric8's own Jackson mapper so their JSON matches
what the API server expects. Reads use `GenericKubernetesResource.get("spec", "operatingMode")`
and a small parser for `status.conditions[type=Ready]`. Field names are checked by unit tests that
assert the whole manifest.

### 4. Names and the volume

`KubernetesNames.objectName` stays (at most 40 characters), so the Sandbox, its pod and its PVC
(`task-<id>`) are well inside the controller's limits (v1.0.12 caps the claim template name at 63
and derives a Service name only when `service: true`, which is never set). The pod is `<id>`, not
`<id>-0`; `exec`, `logs` and `remove` use that.

### 5. Running, stopped, started

- `isRunning`: `operatingMode == Suspended` is stopped; otherwise the pod named `<id>` must be
  running with the `task` container up, the same predicate as the StatefulSet backend. A pod the
  controller is recreating after a drain is "not running" until it is, and `ensureRunning` then
  calls `start`, which just waits.
- `start`: patch `operatingMode` to `Running` when suspended, then wait for the pod with the
  configured `startTimeout`.
- `create`: refuse when the Sandbox exists, ensure cache claims, create the Sandbox, wait for the
  pod. A timeout reports the Sandbox's `Ready` condition (reason and message) and the pod summary,
  because the condition is where the controller says what is wrong (`DependenciesNotReady`,
  `InvalidConfiguration`, `ReconcilerError`).
- `remove`: delete the Sandbox with foreground propagation and wait; then delete the pod and the
  PVC explicitly, which only matters when the controller is not there to do it.
- `listManaged`: Sandboxes labelled `smithy.managed=true`; "running" is decided by the labelled
  pods, as for StatefulSets.

### 6. Expiry is opt-in

`maxLifetime` sets `shutdownTime = now + maxLifetime` and `shutdownPolicy: Delete`. A run that
waits days for a human would be killed by it, so it is unset by default and documented as a backstop
against leaked runs, not as the cleanup path. `failedPodPolicy` is also only sent when configured:
it exists from agent-sandbox v1.0.12 and an earlier controller would reject the unknown field.

### 7. Configuration

```yaml
runtime:
  kubernetes:
    namespace: smithy-tasks
    taskImage: ghcr.io/smithy-ai/claude-task-default:dev
    backend: sandbox            # statefulset (default) | sandbox
    runtimeClassName: gvisor    # optional, both backends
    nodeSelector: {node-pool: agents}   # optional, both backends
    maxLifetime: 172800         # optional, sandbox only, seconds
    failedPodPolicy: Recreate   # optional, sandbox only, agent-sandbox >= v1.0.12
    storageClass: standard
    volumeSize: 10Gi
    caches: [npm, go]
    imagePullSecrets: [ghcr-creds]
    resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {memory: 4Gi}}
```

### 8. Testing

`SandboxRuntimeTest` runs against fabric8's mock server in CRUD mode, which serves any
group/version/plural: manifest fields, create with cache claim, duplicate refused, start timeout
message, listing with an unlabelled Sandbox present, suspended/resumed, pod gone, remove keeps
caches. `OrchestratorConfigTest` covers the backend switch, the default and the rejection of an
unknown backend. Exec is unchanged code and stays covered by `KubernetesLifecycleIT` on a cluster.

## Risks / Trade-offs

- [Controller version skew] → Only fields present since v1.0.0 are sent by default; v1.0.12-only
  fields are opt-in. `v1alpha1` is not supported.
- [Controller down] → Existing pods keep running and exec works; new tasks time out with a message
  that includes the Ready condition, which then says why.
- [`shutdownTime` kills a legitimately long run] → Opt-in, default off.
- [`volumeClaimTemplates` is immutable] → A changed `volumeSize` affects new tasks only, as with
  StatefulSets.
- [Typos in a map-built manifest] → Whole-manifest unit tests, and a live smoke test on a cluster
  before the backend is switched on.

## Migration Plan

Switching an existing deployment's backend does not migrate task containers: finish or cancel
running runs, change `backend`, restart. Rollback is setting it back. Both backends can coexist in
one namespace during the switch, because each lists only its own kind of object.

## Open Questions

- Should the orchestrator read `Finished` (pod reached a terminal phase) and report it, rather than
  treating a finished pod like any stopped one? Today `smithy-init` never exits on its own, so the
  condition only appears when something is wrong, and the pod summary already says what.
