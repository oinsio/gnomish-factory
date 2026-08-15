# Change: add-sandbox-gha-executor

## Why

Some operators want cloud execution without owning any infrastructure —
no cluster, no VM host, no always-on services. GitHub-hosted Actions
runners are "cloud without the ops burden": an ephemeral VM per run with
Docker preinstalled, already proven by our own Docker-strategy ladder
step 0 (integration tests as CI `external` checks). This change is
ladder step E — an operator decision, independent of change D: a full
GHA adapter of the `TaskExecutionEnvironment` port, so whole stages
(heavy Docker-needing checks first of all) can run on runners. The
honest core of this change is its **risk passport**: GHA has the weakest
egress control in the adapter family (no factory-owned guard in front of
the runner), the local gateway and depot are unreachable, and workflows
on gnome branches can expose repository secrets (threat #26 — a deceived
gnome exfiltrating CI secrets is a documented industry incident class).
The adapter is built so these risks are structural non-events where
possible (workflow definitions from the default branch only, read-only
tokens, no privileged secrets, harvest without push credentials) and
plainly documented where not.

## What Changes

- **ADDED**: `gha` adapter of the `TaskExecutionEnvironment` port: exec
  = `workflow_dispatch` of a factory-owned workflow living in the
  default branch, one ephemeral runner VM per exec, results consumed as
  platform-authored run conclusions plus artifacts; every exec is fresh
  by construction (no environment state survives between execs).
- **ADDED**: harvest without push credentials: the runner uploads the
  task branch as a git-bundle artifact; the factory downloads it and
  applies with the fixed-refspec fast-forward-only fetch — the push
  monopoly holds even on GHA.
- **ADDED**: GHA credential rules: minimal workflow `permissions`,
  read-only `GITHUB_TOKEN`, no privileged repo/environment secrets
  reachable from gnome-branch-parameterized runs, OIDC federation for
  any short-lived credential the run needs.
- **ADDED**: the GHA risk passport: weakest-in-family egress control
  (best-effort runner-level filtering only), direct registry access (no
  depot), execution on GitHub-owned infrastructure — reconciliation
  treats these as passport facts, so stages whose needs exceed them are
  refused fail-closed.
- **MODIFIED**: sandbox provisioning — for GHA-bound stages the
  toolchain arrives via the workflow's setup steps (no snapshot cache,
  no factory-built image on the runner).
- **REMOVED**: nothing; all other adapters and the step-0 `external`
  check pattern remain unchanged.

## Capabilities

### New Capabilities

- `gha-environment`: the GHA adapter — dispatch/poll exec model,
  ephemeral runner semantics, bundle-artifact harvest, workflow and
  credential rules, risk passport, orphan/cost handling for runs.

### Modified Capabilities

- `sandbox-provisioning`: GHA-bound stages provision via workflow setup
  steps; the snapshot cache does not extend to runners.

## Goals

- G1: an operator with nothing but a GitHub repository can run heavy
  stages (Docker-needing integration checks first) on ephemeral runner
  VMs, with zero self-hosted infrastructure.
- G2: the gnome cannot shape its own GHA cage: the executed workflow
  definition always comes from the default branch, and nothing a gnome
  branch contains can grant the run privileged secrets or write tokens.
- G3: push monopoly and branch integrity survive the remote executor:
  no push credential exists on the runner; the factory remains the only
  writer to the real remote.
- G4: the adapter's weaknesses are structural knowledge, not fine
  print: the passport declares them machine-readably and
  reconciliation refuses stages whose needs exceed them.
- G5: the engine, port, and other adapters are untouched; GHA is one
  more operator-bindable adapter.

## Non-Goals

- NG1: self-hosted runners — changes the trust model entirely (that is
  the operator's own infrastructure; use the local/cloud adapters).
- NG2: making GHA egress match the guard (harden-runner-class filtering
  is best-effort defense-in-depth, never claimed as layer 2).
- NG3: reaching the local gateway/depot from runners — requires
  operator-exposed endpoints; noted as an option in docs, not built.
- NG4: agent-CLI rounds on runners as a recommended mode — the adapter
  can execute them, but the default recommendation is check-heavy
  stages; interactive-latency workloads stay local.
- NG5: GitHub Enterprise Server specifics.

## Users & Scenarios

- U1: operator binds the Testcontainers-heavy integration stage to the
  GHA adapter; each check runs on a fresh runner VM with preinstalled
  Docker while lighter stages stay on local adapters.
- U2: a deceived gnome commits a modified workflow file to its branch;
  the run is dispatched from the default-branch definition, so the
  modification never executes with any privilege — and the pin-check
  still flags it as a quality failure.
- U3: the factory needs the round's result: it polls the run's
  platform-authored conclusion (never forgeable check-run statuses),
  downloads the bundle artifact, and fast-forward-fetches the branch.
- U4: a stage needing guard-enforced egress is accidentally bound to
  GHA; reconciliation refuses fail-closed naming the unmet need before
  anything runs.

## Requirements

### Functional

- FR1: a `gha` adapter SHALL implement the `TaskExecutionEnvironment`
  port with exec realized as dispatching a factory-owned workflow via
  `workflow_dispatch` and polling the run to completion; streamed
  output SHALL be delivered from run logs and the exit code from the
  platform-authored run conclusion.
- FR2: the executed workflow definition SHALL always come from the
  repository's default branch; task-branch content SHALL enter the run
  only as checked-out working data, never as workflow definition.
- FR3: every exec SHALL run on a fresh ephemeral runner; the adapter
  SHALL declare fresh-per-exec semantics in its passport (no
  environment state between execs; segment reuse is impossible by
  construction).
- FR4: materialization SHALL be per-run checkout of the task branch on
  the runner; untrusted branch content SHALL materialize only inside
  the runner VM (never in factory-owned filesystem), consistent with
  the change-A invariant.
- FR5: harvest SHALL NOT require push credentials on the runner: the
  run uploads the resulting task branch as a git-bundle artifact; the
  factory SHALL download and apply it with the factory-fixed refspec,
  fast-forward-only, and push to the real remote itself.
- FR6: factory-owned workflows SHALL run with minimal `permissions` and
  a read-only `GITHUB_TOKEN`; runs parameterized by gnome branches
  SHALL NOT have access to privileged repository or environment
  secrets; any credential a run legitimately needs SHALL be
  short-lived, obtained via OIDC federation, and scoped through a
  GitHub Environment whose protection rules restrict its use to the
  factory-owned default-branch workflow.
- FR7: the adapter SHALL consume only platform-authored run conclusions
  (workflow-run conclusion API), never check-run statuses, and SHALL
  apply the change-A findings funnel (size caps, sanitization, fenced
  publication) to run logs and outputs.
- FR8: the adapter passport SHALL declare: ephemeral-VM task↔task
  boundary, Docker available, egress control best-effort only (no
  factory guard), direct registry access (no depot), no snapshot cache,
  execution on GitHub infrastructure; reconciliation SHALL refuse
  stages whose declared needs exceed the passport, fail-closed.
- FR9: dispatch, polling, artifact download, and run cancellation SHALL
  be rate-limited and budgeted factory-side (API quota discipline);
  `dispose()` SHALL cancel an in-flight run and startup cleanup SHALL
  cancel orphaned factory-dispatched runs.
- FR10: for GHA-bound stages, toolchain provisioning SHALL be expressed
  in the factory-owned workflow's setup steps (setup actions, cache
  actions); the change-B snapshot cache SHALL NOT extend to runners.
- FR11: operator docs SHALL ship the required repository configuration
  as a verifiable checklist: workflow file installation, Actions
  permissions (read-only default token), no privileged secrets exposed
  to workflows reachable from `gnomish/*` branches, a protected GitHub
  Environment for any OIDC credential (deployment branch policy limited
  to the default branch), optional runner-level egress filtering as
  defense-in-depth.

### Non-Functional

- NFR-S1: no factory secret SHALL be stored in GitHub Actions secrets
  for this adapter's operation; the tracker token stays factory-side;
  anything the run holds SHALL be short-lived and scoped.
- NFR-S2: the weakest-egress reality SHALL be stated in the passport
  and docs, including: a compromised run can reach arbitrary hosts
  within GitHub's runner network policy — binding decisions must
  assume it.
- NFR-R1: GitHub API and runner outages SHALL be infrastructure
  failures (retries, no stage attempt burned); a lost or cancelled run
  is recoverable by re-dispatch from the branch state.
- NFR-R2: run bookkeeping SHALL be crash-safe: a factory restart
  re-associates or cancels in-flight runs via factory-set run metadata;
  no run result is double-applied (bundle apply is idempotent by
  fast-forward semantics).
- NFR-O1: dispatched runs, conclusions, cancellations, and orphan
  cleanups SHALL be logged; run URLs SHALL appear in the task report
  for one-click operator access.
- NFR-P1: dispatch-to-start latency (queue time) SHALL be treated as
  normal round latency; polling SHALL respect API quotas without
  starving conclusion detection.
- NFR-C1: runner minutes are billable: per-task run counts and
  durations SHALL be attached to the task report; orphaned-run
  cancellation SHALL run at startup.

## Operator Experience Criteria

- UX1: enabling GHA is factory config plus one workflow file installed
  in the default branch from a shipped template; the repository
  checklist (FR11) is verifiable step by step.
- UX2: a stage refused on passport grounds names the exact gap ("needs
  guard-enforced egress; GHA provides best-effort only").
- UX3: the task report links each exec to its run URL and shows run
  minutes; a failed run's findings arrive through the same funnel as
  local check output.
- UX4: docs position the adapter honestly: best for check-heavy
  stages; weakest egress in the family; what leaks if a run is
  compromised and what structurally cannot (push, privileged secrets).

## Success Metrics

- M1: contract suite (adapted to fresh-per-exec passport semantics)
  passes against a mocked GitHub API; an optional live profile passes
  against a real repository.
- M2: E2E (live profile): a Docker-needing check stage completes on a
  runner; the branch result arrives via bundle artifact and
  fast-forward apply; no push credential ever exists on the runner.
- M3: E2E: a workflow file modified in the gnome branch does not affect
  the executed definition (default-branch dispatch proven) and is
  flagged by the pin-check.
- M4: E2E assertion: the dispatched run has read-only token
  permissions and no access to privileged secrets; OIDC-obtained
  credentials expire.
- M5: crash test: factory killed with a run in flight; restart either
  re-associates and completes or cancels and re-dispatches — no double
  apply, no orphaned run left running.

## Open Questions

- Q1: AI credential delivery for agent rounds on runners (NG4 caveat):
  operator-exposed gateway endpoint vs GitHub OIDC→gateway exchange vs
  "check-only stages, no AI credential" as the supported v1 — decide in
  design/implementation; v1 leans check-only.
- Q2: exec granularity — one workflow run per exec call vs one per
  round with internal steps; API quota and latency trade-off.
- Q3: log streaming fidelity — GitHub exposes logs with delay; decide
  what "streamed output" means here (incremental poll vs
  post-completion delivery) and reflect it in the passport.
- Q4: harden-runner-class egress filtering — include the third-party
  action in the shipped template (supply-chain trade-off) or document
  as operator opt-in.
- Q5: live-E2E placement — a dedicated test repository and its
  secrets/permissions hygiene.

## Impact

- New adapter package over the existing tracker-port GitHub client
  machinery (REST, polling discipline); no engine or port changes.
- New repo-side surface: one factory-owned workflow template installed
  in the default branch.
- No new always-on services; costs shift to GitHub-billed runner
  minutes (NFR-C1 visibility).
- Depends on change A (port, findings funnel, external-check integrity
  precedents); composes with B (unchanged); independent of C and D.
