# Configuration Reference

The orchestrator is configured by one YAML file. It loads the path in
`ORCHESTRATOR_CONFIG`, then `/config/orchestrator.yml`, then the classpath example.
The environment is used only when a `SecretRef` names an environment variable.

```yaml
apiVersion: smithy.ai/v1alpha1
kind: OrchestratorConfig

storage:
  database: /data/smithy.db
  metrics: /data/metrics.jsonl

runtime:
  docker:
    command: docker
    network: smithy-net
    taskImage: ghcr.io/smithy-ai/claude-task-default:dev
    caches: [pnpm, npm, maven, gradle]

agent:
  claude:
    model: claude-opus-5
    oauthToken: {env: CLAUDE_CODE_OAUTH_TOKEN}
    apiKey: {env: ANTHROPIC_API_KEY}
    turnTimeout: 60m          # budget for one agent turn; overrunning turns are killed
    takeoverTimeout: 5m       # budget for a turn a human drove from the dashboard
    takeoverTools: [Read, Glob, Grep, Bash, Edit, Write, WebFetch]
    detached: false           # run turns detached from their exec stream (see below)
    pollInterval: 20s         # longest single observation of a detached turn
    lostTurnRetries: 1        # relaunches of a turn whose process vanished

auth:
  admin:
    passwordHash: {env: ADMIN_PASSWORD_HASH}

connectors:
  forgejo-main:
    provider: forgejo
    url: http://forgejo:3000
    externalUrl: https://git.example.com
    webhookSecret: {env: FORGEJO_WEBHOOK_SECRET}
    actors:
      smithy:
        username: smithy-bot
        token: {env: SMITHY_FORGEJO_TOKEN}
        git:
          name: Smithy
          email: smithy@example.com
      architect:
        username: architect-bot
        token: {file: /run/secrets/architect_forgejo_token}
        git:
          name: Architect
          email: architect@example.com

defaults:
  vcs: forgejo-main
  issueTracker: event.source
  actor: smithy

workflows:
  definitionsDir: /config/workflows
  repositoryWorkflows: true
  defaults:
    branchPrefix: smithy/
    planApprovedLabel: Plan Approved

ci: {autofix: false}
knowledgebase:
  enabled: false
  url: http://knowledgebase:8000/mcp
  toolName: searchKnowledge
```

Unknown fields and invalid connector references fail startup. A deployment should
mount the file read-only and mount writable storage separately:

```yaml
volumes:
  - ./config/orchestrator.yml:/config/orchestrator.yml:ro
  - ./config/workflows:/config/workflows:ro
  - orchestrator-data:/data
```

## Agent turn timeout

`agent.claude.turnTimeout` caps the wall-clock time one agent turn may take —
`45m`, `2h`, `900s` and `PT45M` are all accepted, and the built-in default is
60 minutes. The deadline is enforced inside the task container, so an overrunning
turn is actually killed rather than left running after the orchestrator stops
waiting for it.

A turn that hits the cap fails its step with:

```
Claude turn on <container> (session=<id>) exceeded its 60m budget and was killed.
```

Raise the value for workflows whose build stage legitimately runs longer, or split
the step into smaller turns. Note that the budget is per turn, not per run: a
workflow with five turns can run for five times this long.

`agent.claude.takeoverTimeout` (default 5 minutes) is the budget for a turn a
person drove from the dashboard. It is deliberately much shorter: someone is
waiting on that reply in a browser, so an unanswered request is a hung dashboard
rather than a long job.

A run's turns all share one agent session, and that session cannot take two
concurrent processes. Taking control does not interrupt a turn that is already
running — the lease stops new events being dispatched, not work in flight — so a
message sent mid-turn is refused with a 409 and

```
The agent on run <id> is in the middle of a turn.
```

rather than queued behind it. Wait for the turn to land, or stop the run.

`agent.claude.takeoverTools` is the tool list a human-driven turn runs with,
defaulting to `[Read, Glob, Grep, Bash, Edit, Write, WebFetch]`. It is separate
from the stage's `tools:` on purpose: a definition scopes tools per step to
constrain the agent while it works on its own — read-only while planning, write
while building — and that does not map onto whatever a person asks for after
taking control.

Note that an **empty** tool list is not "no restrictions", it is "nothing
allowed": the CLI is run without `--allowedTools`, and a headless turn on default
permissions then refuses every tool call before executing it. The agent reports
that it cannot read a file or push a branch, which reads as an agent refusing to
cooperate rather than one that was handed no permissions.

## Detached turns

By default a turn lives exactly as long as the `exec` call that runs it: when
the orchestrator restarts mid-turn, Kubernetes ends the turn with the stream,
and Docker leaves an orphan that keeps writing the session transcript while the
orchestrator has lost its result. `agent.claude.detached: true` runs each turn
as its own process in the task container instead:

- the prompt is uploaded as a file, and a short `exec` starts the turn with
  `setsid`, its stdin, stdout and stderr on files, and returns at once;
- the orchestrator then observes it with short calls of at most
  `pollInterval` each (default `20s`), never one stream for the whole turn;
- the turn is recorded in the run state (`activeTurn`), so a step that is run
  again after a restart attaches to the turn still running for the same
  prompt instead of starting a second one;
- a turn whose process disappeared without an exit status (its pod was
  recreated, the kernel killed it) is relaunched with `--resume` and the
  remaining budget, at most `lostTurnRetries` times (default `1`), then fails
  with `Claude turn on <container> was lost <n> time(s)` naming the turn
  directory and the end of its stderr.

The budget (`turnTimeout`) is still enforced inside the container, and exit
status 124 is still a timeout. An observation that fails with HTTP 401/403 fails
the step at once (check the `pods/exec` verbs: fabric8 needs `get` as well as
`create`); other failures, such as a pod being recreated, are retried. Off is
exactly the old behaviour; no turn state is written.

A turn is a directory in the task container, `/tmp/smithy-turns/<turn-id>/`,
private to the task user. Only the latest turn's directory is kept; the next
turn removes it, and stops any older turn still running first.

| File | What it holds |
| --- | --- |
| `prompt` | the prompt, read by claude as stdin |
| `cmd` | the command line, one argument per line |
| `out`, `err` | claude's stdout (the JSON result) and stderr |
| `lock` | held with `flock` while any process of the turn lives |
| `pid` | pid of the `timeout` process, leader of the turn's process group |
| `rc` | exit status, written last |

To look at a turn by hand:

```sh
d=$(ls -dt /tmp/smithy-turns/*/ | head -1)
flock -n "$d/lock" true && echo "not running" || echo "running"
cat "$d/rc" 2>/dev/null        # absent while running, or if the turn was lost
tail "$d/err"
kill -TERM -- -"$(cat "$d/pid")"   # stop it; rc becomes 143
```

Needs `flock`, `setsid` and `timeout` in the task image (util-linux and
coreutils; every `claude-task-*` image has them, and the base image build checks).

## Secrets

Every credential accepts exactly one secret source:

```yaml
token: {env: SMITHY_GITHUB_TOKEN}
token: {file: /run/secrets/smithy_github_token}
token: {literal: local-development-only}
```

Environment and file references keep values out of the deployment file. Literal
values are intended only for local development. Resolved values are never included
in configuration diagnostics.

## Connectors and actors

A connector ID such as `github-main` is the stable routing identity. `provider`
selects the implementation (`forgejo`, `gitlab`, `github`, or `jira`). Webhooks use
the connector ID:

```text
https://smithy.example.com/webhooks/github-main
```

Actor identities are connector-specific and never inherit another actor's
credentials. Every workflow actor must be configured on each connector it acts
through; a missing identity fails the action instead of posting as the default
actor.

Actors are logical identities scoped to a connector. Workflows refer to `smithy`,
`architect`, or another logical name; the connector resolves it to a username,
Jira account ID, credentials, and git identity. An actor omitted from a connector
uses the default actor's credentials for outbound actions and is not recognized as
an inbound assignee on that connector.

GitLab connectors may set `tokenType: oauth2` (the default) or
`tokenType: private-token`.

## Jira with a VCS connector

Jira is an issue connector, never a VCS connector. A split deployment defines both:

```yaml
connectors:
  gitlab-main:
    provider: gitlab
    url: https://gitlab.example.com
    webhookSecret: {env: GITLAB_WEBHOOK_SECRET}
    actors:
      smithy:
        username: smithy-bot
        token: {env: SMITHY_GITLAB_TOKEN}
        git: {name: Smithy, email: smithy@example.com}

  jira-product:
    provider: jira
    url: https://company.atlassian.net
    webhookSecret: {env: JIRA_WEBHOOK_SECRET}
    actors:
      smithy:
        accountId: abc123
        email: smithy@example.com
        apiToken: {env: SMITHY_JIRA_API_TOKEN}
    issueMapping:
      repositoryField: customfield_12345
      allowStoriesWithoutRepository: true
      planApprovedLabel: plan-approved
      planApprovedStatus: Ready for Smithy

defaults:
  vcs: gitlab-main
  issueTracker: event.source
  actor: smithy
```

Issue actions answer the connector that produced the event. Repository actions use
that source when it is a VCS connector and otherwise use `defaults.vcs`.

## Repository catalogs

Reusable coordinator catalogs belong in deployment configuration:

```yaml
repositoryCatalogs:
  acme-product:
    - {source: gitlab-main, owner: acme, repo: api, description: HTTP API}
    - {source: gitlab-main, owner: acme, repo: web, description: Web client}
```

Workflow customization remains in `/config/workflows`, not in the main config. A
small workflow file can extend the built-in coordinator and import a catalog:

```yaml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: acme-coordinator
  extends: feature-coordinator
vars:
  repositoryCatalog: acme-product
  storyRepos: [PRODUCT/PRODUCT]
```

The registry resolves `repositoryCatalog` into the workflow's `vars.catalog` and
rejects unknown catalog names.

## Repository-local configuration

Repository behavior stays with the repository. `.smithy/config.yml` currently
supports the context repository:

```yaml
context:
  repository: shared-guidelines
```

Repository-owned workflows live under `.smithy/workflows/*.yml` and can be disabled
deployment-wide with `workflows.repositoryWorkflows: false`.
