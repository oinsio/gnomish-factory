# gha-environment

## ADDED Requirements

### Requirement: GHA adapter of the environment port
A `gha` adapter SHALL implement the `TaskExecutionEnvironment` port
with exec realized as dispatching a factory-owned workflow via
`workflow_dispatch` on an ephemeral GitHub-hosted runner, polling the
run to completion, and delivering output from run logs with the exit
code taken from the platform-authored run conclusion. Check-run
statuses SHALL never be consumed as verdicts.
<!-- implements FR1, FR7 of add-sandbox-gha-executor -->

#### Scenario: An exec is a run
- **WHEN** the factory execs a command in a GHA environment
- **THEN** one factory-stamped workflow run executes it on a fresh runner, and the port consumer receives output and the conclusion-derived exit code

#### Scenario: Forgeable statuses are ignored
- **WHEN** a token-created check-run status reports success while the workflow-run conclusion reports failure
- **THEN** the adapter's verdict follows the platform-authored conclusion

### Requirement: Workflow definition comes from the default branch only
The executed workflow definition SHALL always be the factory-owned
template from the repository's default branch; task-branch content
SHALL enter the run only as checked-out working data. Workflow files
modified in a gnome branch SHALL have no effect on execution.
<!-- implements FR2 of add-sandbox-gha-executor -->

#### Scenario: The gnome cannot edit its cage
- **WHEN** a gnome branch contains a modified workflow file and the factory dispatches an exec for that branch
- **THEN** the run executes the default-branch definition, the modification never runs, and the pin-check independently flags the changed file

### Requirement: Fresh-per-exec semantics declared in the passport
Every exec SHALL run on a fresh ephemeral runner with no environment
state surviving between execs; the passport SHALL declare
fresh-per-exec semantics (segment reuse impossible;
freshness-requiring knobs satisfied by construction), Docker
availability, best-effort-only egress control, direct registry access,
no snapshot cache, and execution on GitHub infrastructure.
Reconciliation SHALL refuse fail-closed any stage whose declared needs
exceed the passport. <!-- implements FR3, FR8 of add-sandbox-gha-executor -->

#### Scenario: Guard-dependent stage cannot land on GHA
- **WHEN** a stage declaring guard-enforced egress needs is bound to the GHA adapter
- **THEN** reconciliation refuses fail-closed naming the unmet need before any run is dispatched

#### Scenario: Freshness knobs are free
- **WHEN** a stage bound to GHA declares `requires-fresh` or `verify-in: fresh-box`
- **THEN** reconciliation passes without extra mechanics — every exec is already a fresh box materialized from the branch

### Requirement: Harvest without push credentials
The run SHALL upload the resulting task branch as a git-bundle
artifact; the factory SHALL download it and apply it with the
factory-fixed refspec, fast-forward-only, and push to the real remote
itself. No push-capable credential SHALL exist on the runner; bundle
application SHALL be idempotent.
<!-- implements FR5, NFR-R2 of add-sandbox-gha-executor -->

#### Scenario: Push monopoly holds on GHA
- **WHEN** a run completes with new commits on the task branch
- **THEN** the branch reaches the real remote only through the factory's own push, and no credential on the runner could have pushed it

#### Scenario: Re-applying a bundle is safe
- **WHEN** a factory restart re-downloads and re-applies an already-applied bundle
- **THEN** the apply is a no-op and no history is duplicated or rewritten

### Requirement: Credential floor
Factory-owned workflows SHALL run with minimal permissions and a
read-only `GITHUB_TOKEN`; runs parameterized by gnome branches SHALL
have no access to privileged repository or environment secrets; any
credential a run legitimately needs SHALL be short-lived,
OIDC-federated, and scoped through a protected GitHub Environment
whose deployment branch policy admits only the default branch. No
factory secret SHALL be stored in GitHub Actions secrets.
<!-- implements FR6, NFR-S1 of add-sandbox-gha-executor -->

#### Scenario: A compromised run has nothing durable to steal
- **WHEN** a run executing gnome-influenced code is fully compromised
- **THEN** it holds only a read-only token and expiring credentials — no push rights, no privileged secrets, no factory secrets

#### Scenario: A gnome-parameterized run cannot mint the OIDC credential
- **WHEN** a run parameterized by a `gnomish/*` branch attempts to use the protected environment's OIDC subject
- **THEN** the environment's protection rules refuse it — only the factory-owned default-branch workflow qualifies

### Requirement: Run bookkeeping, cancellation, and cost visibility
Dispatched runs SHALL carry factory metadata sufficient for a
restarted instance to re-associate or cancel them; `dispose()` SHALL
cancel the environment's in-flight run; startup cleanup SHALL cancel
orphaned factory-stamped runs. Dispatch, polling, and artifact
downloads SHALL respect factory-side rate limits and API quotas.
Per-task run counts, durations, and run URLs SHALL be attached to the
task report. <!-- implements FR9, NFR-C1, NFR-O1 of add-sandbox-gha-executor -->

#### Scenario: Crash leaves no ghost runs
- **WHEN** a factory instance dies with a run in flight and an instance starts later
- **THEN** the run is re-associated and completed, or cancelled and re-dispatched from branch state — and no result is applied twice

#### Scenario: The operator sees the bill and the trail
- **WHEN** a task that used GHA execs completes or escalates
- **THEN** its report lists each run with its URL and minutes consumed

### Requirement: Repository configuration checklist
Operator docs SHALL ship the GHA repository setup as a verifiable
checklist: template workflow installed in the default branch, Actions
token defaulting to read-only, no privileged secrets reachable from
workflows triggered by or parameterized with `gnomish/*` branches, a
protected GitHub Environment (default-branch-only deployment policy)
for any OIDC credential, and optional runner-level egress filtering as
documented defense-in-depth.
<!-- implements FR11 of add-sandbox-gha-executor -->

#### Scenario: Misconfiguration is caught by the checklist
- **WHEN** the operator runs the checklist against a repository whose Actions settings grant write default-token permissions
- **THEN** the checklist step fails with the exact setting to change before the adapter is used
