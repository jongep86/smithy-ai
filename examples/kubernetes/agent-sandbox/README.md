# Task containers as agent-sandbox Sandboxes

With `runtime.kubernetes.backend: sandbox` the orchestrator creates one
[`Sandbox`](https://github.com/kubernetes-sigs/agent-sandbox) (`agents.x-k8s.io/v1beta1`) per task
container instead of a StatefulSet. The controller keeps the pod alive, owns the task's volume, and
can suspend or expire the task. The pod itself — image, `smithy-init`, mounts, no service-account
token — is the same as on the StatefulSet backend.

## Prerequisites

- agent-sandbox v1.0.0 or later installed cluster-wide by the platform (CRDs plus controller).
  Only `v1beta1` is supported; `v1alpha1` was removed in v1.0.0. `failedPodPolicy` needs v1.0.12.
- A task namespace (here `smithy-tasks`) where the orchestrator's ServiceAccount is allowed to
  manage Sandboxes, pods and claims. The manifests in this directory are that namespace's part.

## Files

| File | What it is |
| --- | --- |
| `rbac.yaml` | Role and RoleBinding for the orchestrator's ServiceAccount in the task namespace. No StatefulSet rights: the sandbox backend does not need them. |
| `networkpolicy.yaml` | Task pods reach the internet and the knowledgebase, nothing else in the cluster, and nothing reaches them. |
| `sandboxtemplate.yaml` | Platform defaults for Sandboxes in this namespace (RuntimeClass, network policy, resource ceiling). The orchestrator creates plain Sandboxes and does not reference it; it is here for the platform to attach to warm pools or claims of its own, and as the documented shape. |
| `sandbox-smoke.yaml` | One Sandbox shaped like the orchestrator's, for a manual check before switching the backend on. |

Render with `kubectl kustomize examples/kubernetes/agent-sandbox`.

## Orchestrator configuration

```yaml
runtime:
  kubernetes:
    namespace: smithy-tasks
    taskImage: ghcr.io/smithy-ai/claude-task-default:dev
    backend: sandbox
    storageClass: standard
    volumeSize: 10Gi
    caches: [npm, go]
    imagePullSecrets: [ghcr-creds]
    resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {memory: 4Gi}}
    # Optional, both Kubernetes backends:
    runtimeClassName: gvisor
    nodeSelector: {node-pool: agents}
    # Optional, sandbox only. maxLifetime is a backstop against tasks the
    # orchestrator forgot, not the cleanup path: a run that waits for a human
    # longer than this is deleted with its workspace.
    maxLifetime: 172800
    failedPodPolicy: Recreate   # agent-sandbox >= v1.0.12
```

## Smoke test

```bash
kubectl apply -k examples/kubernetes/agent-sandbox
kubectl -n smithy-tasks get sandbox smoke -o jsonpath='{.status.conditions[?(@.type=="Ready")]}'
kubectl -n smithy-tasks exec smoke -c task -- sh -c 'ls /workspace; id'
kubectl -n smithy-tasks patch sandbox smoke --type merge -p '{"spec":{"operatingMode":"Suspended"}}'
kubectl -n smithy-tasks get pod smoke          # gone after a moment
kubectl -n smithy-tasks get pvc task-smoke     # still there
kubectl -n smithy-tasks delete sandbox smoke   # pod and claim go with it
```

## What the sandbox backend does not change

- Exec goes through `pods/exec`, as before; the sandbox router and `sandboxd` are not used.
- A Claude turn runs over one exec stream and dies when the orchestrator restarts.
- Warm pools are not used: a task's environment (repository, tokens) is only honoured on a cold
  start.
