## MODIFIED Requirements

### Requirement: Runtime selection

The orchestrator SHALL run task containers on Docker unless the configuration contains a
`runtime.kubernetes` block, in which case it SHALL run them on Kubernetes. A configuration that
contains both `runtime.docker` and `runtime.kubernetes` SHALL be rejected at startup with an
error naming both blocks. On Kubernetes, `runtime.kubernetes.backend` SHALL select what owns a
task pod: `statefulset` (the default) or `sandbox`, an `agents.x-k8s.io/v1beta1` Sandbox. Any
other value SHALL be rejected at startup with an error naming the allowed values.

#### Scenario: No runtime block

- **WHEN** the orchestrator starts with a configuration without a `runtime` block
- **THEN** task containers are created through the Docker CLI, as before this change

#### Scenario: Kubernetes block present

- **WHEN** the orchestrator starts with `runtime.kubernetes.namespace: smithy-tasks` and no
  `backend`
- **THEN** task containers are created as StatefulSets in namespace `smithy-tasks` and no Docker
  CLI is invoked

#### Scenario: Sandbox backend

- **WHEN** the orchestrator starts with `runtime.kubernetes.backend: sandbox`
- **THEN** task containers are created as Sandboxes in the task namespace and no StatefulSet is
  created

#### Scenario: Both blocks present

- **WHEN** the configuration contains both `runtime.docker` and `runtime.kubernetes`
- **THEN** startup fails with an error that names both blocks

#### Scenario: Unknown backend

- **WHEN** the configuration contains `runtime.kubernetes.backend: job`
- **THEN** startup fails with an error that names `statefulset` and `sandbox`

### Requirement: Least privilege on Kubernetes

The Kubernetes runtime SHALL need no permissions outside the task namespace, and inside it only on
pods, pods/exec, pods/log, persistent volume claims and, for the StatefulSet backend, StatefulSets
or, for the sandbox backend, `sandboxes.agents.x-k8s.io`. Task pods SHALL NOT receive a Kubernetes
service account token.

#### Scenario: Token in task pod

- **WHEN** a command in a task container lists `/var/run/secrets/kubernetes.io/serviceaccount`
- **THEN** the directory does not exist

#### Scenario: Sandbox backend without StatefulSet rights

- **WHEN** the orchestrator runs with `backend: sandbox` under a Role that grants no access to
  StatefulSets
- **THEN** task containers are created, listed, started and removed without error

## ADDED Requirements

### Requirement: Sandbox-backed task containers

With `backend: sandbox`, each task container SHALL be one Sandbox named after the sanitized
container name, carrying the `smithy.managed` and `smithy.ai/container-id` labels and the
`smithy.ai/container-name` annotation, with `service: false`, one claim template named `task` for
the per-task volume, and a pod template identical to the StatefulSet backend's (same image,
command, environment, mounts, init container, resources, pull secrets, no service account token).
The task container SHALL be reported as running only while the Sandbox's `operatingMode` is
`Running` and the pod named after the Sandbox has its `task` container running. A stopped task
container SHALL be a Sandbox with `operatingMode: Suspended`; starting it SHALL set
`operatingMode: Running` and wait for the pod. Removing it SHALL delete the Sandbox and wait until
its pod and volume are gone.

#### Scenario: Create

- **WHEN** a run creates task container `smithy-orders-12`
- **THEN** a Sandbox `smithy-orders-12` exists with a `task` claim template and a pod
  `smithy-orders-12`, and the orchestrator waits until that pod's `task` container runs before
  polling for `smithy-init`

#### Scenario: Pod never runs

- **WHEN** the pod of a new Sandbox is not running after `startTimeout`
- **THEN** creation fails with an error that includes the Sandbox's `Ready` condition (status,
  reason and message) and the pod's phase

#### Scenario: Resume

- **WHEN** a step needs a task container whose Sandbox is `Suspended`
- **THEN** the orchestrator sets `operatingMode: Running` and continues once the pod runs

#### Scenario: Remove

- **WHEN** a run ends and its task container is destroyed
- **THEN** the Sandbox, its pod and the `task-<name>` claim are gone, and cache claims remain

### Requirement: Sandbox options

With `backend: sandbox`, `runtime.kubernetes.maxLifetime` (seconds) SHALL set the Sandbox's
`shutdownTime` to creation time plus that duration with `shutdownPolicy: Delete`, and SHALL be
unset by default so a Sandbox never expires on its own. `runtime.kubernetes.failedPodPolicy` SHALL
be sent as the Sandbox's `failedPodPolicy` only when configured, and SHALL accept only `Retain`
or `Recreate`. `runtime.kubernetes.runtimeClassName` and `nodeSelector` SHALL be applied to the
task pod on both Kubernetes backends.

#### Scenario: Expiry configured

- **WHEN** `maxLifetime: 3600` is set and a task container is created at 10:00:00Z
- **THEN** its Sandbox has `shutdownTime: 11:00:00Z` and `shutdownPolicy: Delete`

#### Scenario: Older controller

- **WHEN** `failedPodPolicy` is not configured
- **THEN** the Sandbox manifest contains no `failedPodPolicy` field

#### Scenario: RuntimeClass

- **WHEN** `runtimeClassName: gvisor` is set
- **THEN** the task pod's spec has `runtimeClassName: gvisor` on either backend
