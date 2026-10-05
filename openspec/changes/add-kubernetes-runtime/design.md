## Context

`ContainerService` owns the task-container logic: it assembles the environment, waits for
`smithy-init`, reads and writes `/tmp/smithy-state.json`, and moves files with `exec cat`. Every
call to the outside world goes through `DockerCli`, a thin wrapper that runs the `docker` binary.
The Docker calls used are `create`, `start`, `inspect`, `ps --filter label=...`, `exec`, `logs`,
`stop` and `rm`. Tests replace `DockerCli` with `FakeDockerCli`.

Task containers are long-lived (`smithy-init` ends in `sleep infinity`), are restarted with
`--restart unless-stopped`, and keep their state in the container filesystem. Recovery after an
orchestrator restart (`WorkflowService`, `RunEnvironments`) depends on finding the container by
name, starting it if stopped, and reading its state file.

## Goals / Non-Goals

**Goals:**
- A Kubernetes runtime with the same observable behaviour as Docker (see specs).
- Zero behaviour change for Docker deployments, and existing tests unchanged.
- A small, reviewable seam that can go upstream.

**Non-Goals:**
- Running Claude turns detached from the exec stream. Turns already carry an in-container
  `timeout`; long exec streams are a risk we measure first (see Risks).
- Autoscaling, multi-cluster, or per-task resource tuning from workflow definitions.
- Helm charts. Example manifests only.

## Decisions

### 1. A `ContainerRuntime` interface below `ContainerService`, not above it

`ContainerService` keeps all its logic and calls a `ContainerRuntime` with these primitives:
`create(ContainerSpec)`, `exists`, `isRunning`, `start`, `listManaged(includeStopped)`, `logs`,
`exec(name, command, env, workdir, stdin, timeout)`, `execForBytes`, `remove`. `DockerRuntime`
holds today's `DockerCli` calls verbatim. `KubernetesRuntime` implements them with the fabric8
client. `ContainerSpec` is a runtime-neutral record (name, image, command, env, labels, cache
mounts), built where the Docker args are built today.

The existing constructor that takes a `DockerCli` stays and wraps it in a `DockerRuntime`, so
`FakeDockerCli`-based tests keep working unchanged.

*Alternatives:* an interface over the whole `ContainerService` duplicates the init, state and env
logic per runtime. A `docker` shim script that calls `kubectl` (possible, since `runtime.docker.command`
is configurable) needs no Java change but breaks on `-e`/`-w` and output parsing, and is not
something upstream would take.

### 2. One single-replica StatefulSet per task, with a per-task PVC

A bare pod is deleted by a node drain and never comes back, which would lose the run. A
StatefulSet with `replicas: 1` brings the pod back under the same name, and its
`volumeClaimTemplate` gives the task a volume that follows it. `persistentVolumeClaimRetentionPolicy:
{whenDeleted: Delete}` removes the volume with the StatefulSet. `remove` also deletes the claim
explicitly, in case the policy is unavailable.

The volume is mounted three times with `subPath`: `workspace` at `/workspace`, `tmp` at `/tmp`,
`root` at `/root`. An init container seeds `root` from the image's `/root` on first start, so
images that install tools under `/root` keep working.

`start` and `isRunning` map to the pod phase. Docker's "stopped but present" state is represented
by `replicas: 0`, which `start` scales back to 1.

*Alternatives:* a pod with `emptyDir` survives container restarts but not pod deletion. A
Deployment does not keep a stable pod name and handles RWO volumes poorly during rollout.

### 3. Names: sanitized and stable, original kept in an annotation

Container names can exceed Kubernetes limits or contain characters a DNS label does not allow. The
StatefulSet name is the lower-cased name with invalid characters replaced by `-`, cut to 40
characters, and suffixed with an 8-character hash of the original when it was changed or cut. The
original name is stored in the annotation `smithy.ai/container-name`, and `listManaged` returns
that. Lookups go through the label `smithy.ai/container-id=<k8s name>`. The pod is `<k8s name>-0`
in container `task`.

### 4. Exec without shell quoting

Kubernetes exec has no `-w` or `-e`. Commands run as
`sh -c 'cd "$0" && exec env "$@"' /workspace K=V ... <command...>`, passing the working directory
and environment as positional arguments, so nothing is interpolated into a shell string. This works
with busybox images as well as GNU coreutils.

Stdin is written and then closed, as with `docker exec -i`. Exit code comes from the exec status
channel. On timeout the stream is closed and the result is exit code 124, matching `DockerCli`.

### 5. Caches as namespace-wide PVCs

Each configured cache becomes a claim `smithy-cache-<name>`, created on first use with
`runtime.kubernetes.cacheAccessMode` (default `ReadWriteOnce`, which is enough on a single node;
use `ReadWriteMany` on multi-node clusters) and `cacheSize`. The `go` entry
(`/root/go/pkg/mod`) is added to the shared cache table, so Docker gets it too.

### 6. Configuration

```yaml
runtime:
  kubernetes:
    namespace: smithy-tasks          # default: the orchestrator pod's namespace
    taskImage: ghcr.io/smithy-ai/claude-task-default:dev
    storageClass: longhorn           # default: cluster default
    volumeSize: 10Gi
    caches: [npm, go]
    cacheSize: 20Gi
    cacheAccessMode: ReadWriteOnce
    imagePullSecrets: [ghcr-creds]
    resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {memory: 4Gi}}
```

The client uses in-cluster credentials when present and the local kubeconfig otherwise.
`fetchOwnLogs` reads the orchestrator pod (`HOSTNAME`) in `POD_NAMESPACE`, supplied through the
downward API.

### 7. Testing

Pure parts (name mapping, StatefulSet and claim manifests) get unit tests. Create, list, start and
remove run against fabric8's mock server in CRUD mode. Exec is checked by a
`KubernetesLifecycleIT` that runs only when `SMITHY_K8S_IT_NAMESPACE` is set, mirroring
`DockerLifecycleIT`.

## Risks / Trade-offs

- [A Claude turn of up to 60 minutes runs over one exec stream; an API-server restart or kubelet
  streaming timeout cuts it] → The turn is already bounded inside the container by `timeout`, and
  a cut stream returns a failed step that the workflow can retry. If this shows up in practice,
  run turns detached and poll, as a follow-up change.
- [The task namespace is shared with other workloads, and `pods/exec` there reaches them too] →
  Run tasks in a dedicated namespace. The orchestrator also refuses to exec, read logs from or
  remove anything without `smithy.managed=true`.
- [Per-task volumes add a few seconds of provisioning and need cleanup] → Retention policy plus an
  explicit delete. Orphans are visible with `kubectl get pvc -l smithy.managed=true`.
- [Task pods run arbitrary code with VCS and Claude tokens] → No service-account token, and a
  NetworkPolicy in the example manifests that blocks cluster-internal traffic except to the
  knowledgebase.
- [Seeding `/root` from the image hides later image updates to `/root`] → Seeding happens once per
  task, and tasks are short-lived compared to image releases.

## Migration Plan

Nothing changes for Docker deployments. To move a deployment to Kubernetes, finish or cancel the
running runs first (task containers are not migrated), deploy with `runtime.kubernetes`, and point
the webhooks at the new ingress. Rollback is redeploying on Docker. Both use their own state.

## Open Questions

- Should the orchestrator and its task pods share a namespace in the example manifests? This only
  affects the example RBAC and NetworkPolicy, not the code.
