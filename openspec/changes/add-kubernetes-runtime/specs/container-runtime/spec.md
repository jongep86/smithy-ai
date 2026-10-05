## ADDED Requirements

### Requirement: Runtime selection

The orchestrator SHALL run task containers on Docker unless the configuration contains a
`runtime.kubernetes` block, in which case it SHALL run them on Kubernetes. A configuration that
contains both `runtime.docker` and `runtime.kubernetes` SHALL be rejected at startup with an
error naming both blocks.

#### Scenario: No runtime block

- **WHEN** the orchestrator starts with a configuration without a `runtime` block
- **THEN** task containers are created through the Docker CLI, as before this change

#### Scenario: Kubernetes block present

- **WHEN** the orchestrator starts with `runtime.kubernetes.namespace: smithy-tasks`
- **THEN** task containers are created in namespace `smithy-tasks` and no Docker CLI is invoked

#### Scenario: Both blocks present

- **WHEN** the configuration contains both `runtime.docker` and `runtime.kubernetes`
- **THEN** startup fails with an error that names both blocks

### Requirement: Equivalent task container behaviour

On both runtimes a task container SHALL be created from the configured task image with the same
environment variables and the `smithy-init` command, SHALL be reported as initialized only after
`/tmp/smithy-init-done` exists, and SHALL be reported as failed when `/tmp/smithy-init-failed`
exists or the container stops during init. Commands SHALL run in `/workspace` with the requested
environment variables and optional standard input, and SHALL return exit code, standard output and
standard error. A command that exceeds its timeout SHALL return exit code 124.

#### Scenario: Command with environment and stdin

- **WHEN** a step runs `cat` with stdin `hello` and environment `FOO=bar` in a task container
- **THEN** the command runs with working directory `/workspace`, sees `FOO=bar`, and returns exit
  code 0 with stdout `hello`

#### Scenario: Command timeout

- **WHEN** a command runs longer than its timeout
- **THEN** the result has exit code 124 and the orchestrator does not wait for it further

#### Scenario: Init failure

- **WHEN** `smithy-init` writes `/tmp/smithy-init-failed`
- **THEN** container creation fails with an error that includes the last log lines of the task
  container

### Requirement: Task state survives restarts

On Kubernetes, the contents of `/workspace`, `/tmp` and `/root` of a task container SHALL persist
across a restart of the task container, a rescheduling of its pod, and a restart of the
orchestrator, until the task container is removed. A task container that is not running SHALL be
started again when a step needs it.

#### Scenario: Pod deleted during a run

- **WHEN** the pod of a task container is deleted while its run is waiting for review
- **THEN** a replacement pod starts with the same workspace, `/tmp/smithy-state.json` and Claude
  session transcripts, and `smithy-init` skips setup because `/tmp/smithy-init-done` exists

#### Scenario: Orchestrator restart

- **WHEN** the orchestrator restarts while task containers exist
- **THEN** it lists them as managed containers and re-attaches them to their runs

### Requirement: Managed container inventory

Each task container SHALL carry the label `smithy.managed=true` and, when known, a
`smithy.workflow` label. Listing managed containers SHALL return only containers with that label in
the task namespace (Kubernetes) or on the Docker host (Docker), by the container name the
orchestrator assigned.

#### Scenario: Unrelated workloads

- **WHEN** the task namespace contains a pod without `smithy.managed=true`
- **THEN** it is not listed, its logs cannot be fetched through the dashboard, and it is never
  removed by the orchestrator

### Requirement: Removal

Removing a task container SHALL delete the container and its per-task storage. Shared cache
volumes SHALL NOT be deleted.

#### Scenario: Run finished

- **WHEN** a run ends and its task container is destroyed
- **THEN** no workload and no per-task volume for that container remains, and cache volumes still
  exist

### Requirement: Kubernetes cache volumes

On Kubernetes, each configured cache (`npm`, `pnpm`, `maven`, `gradle`, `go`) SHALL be a persistent
volume claim in the task namespace, created on first use if absent, and mounted at the same path as
on Docker (`go`: `/root/go/pkg/mod`). The `go` cache SHALL also be available on Docker.

#### Scenario: First task with npm cache

- **WHEN** the first task container is created with cache `npm` and claim `smithy-cache-npm` does
  not exist
- **THEN** the claim is created and mounted at `/root/.npm`, and later task containers reuse it

### Requirement: Least privilege on Kubernetes

The Kubernetes runtime SHALL need no permissions outside the task namespace, and inside it only on
StatefulSets, pods, pods/exec, pods/log and persistent volume claims. Task pods SHALL NOT receive a
Kubernetes service account token.

#### Scenario: Token in task pod

- **WHEN** a command in a task container lists `/var/run/secrets/kubernetes.io/serviceaccount`
- **THEN** the directory does not exist
