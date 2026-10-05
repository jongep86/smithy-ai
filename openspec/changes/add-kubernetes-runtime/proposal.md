## Why

The orchestrator can only run task containers through a mounted Docker socket. That rules out
Kubernetes clusters, where there is no socket to mount, and it hands the orchestrator root on the
host it runs on. A cluster deployment also removes the need for a tunnel (ngrok) to receive
webhooks, because the cluster already has ingress and TLS.

## What Changes

- Add a `runtime.kubernetes` configuration block next to `runtime.docker`. Its presence selects
  the Kubernetes runtime; without it the Docker runtime is used, as today. Configuring both is an
  error.
- With `kubernetes`, each task container becomes a single-replica StatefulSet in a configured
  namespace, with a per-task persistent volume for `/workspace`, `/tmp` and `/root`, so a task
  survives a container restart, a node drain and an orchestrator restart, as it does on Docker.
- Exec, file transfer, logs, listing managed containers and adopting existing ones work the same
  on both runtimes; workflows and the dashboard do not know which runtime is active.
- Cache volumes (`npm`, `pnpm`, `maven`, `gradle`, and a new `go` entry) become shared persistent
  volume claims on Kubernetes.
- The orchestrator needs only namespaced permissions (StatefulSets, pods, pods/exec, pods/log,
  persistent volume claims) in the task namespace. No Docker socket, no cluster-wide rights.
- Example manifests for running the orchestrator and knowledgebase on Kubernetes.

No breaking changes: without `runtime.kubernetes`, the orchestrator behaves exactly as before.

## Capabilities

### New Capabilities

- `container-runtime`: how task containers are created, reached, persisted, listed and removed,
  and what a deployment must provide for each runtime kind.

### Modified Capabilities

None. No existing specs are published in `openspec/specs/`.

## Impact

- Code: `orchestrator/backend/.../service/docker/` (ContainerService, DockerCli), configuration
  binding for `runtime.*`, the orchestrator image.
- New dependency: `io.fabric8:kubernetes-client`.
- Docs: a Kubernetes setup page and example manifests under `examples/kubernetes/`.
- Tests: existing tests keep using `FakeDockerCli`; new unit tests for the Kubernetes runtime
  against fabric8's mock server.
