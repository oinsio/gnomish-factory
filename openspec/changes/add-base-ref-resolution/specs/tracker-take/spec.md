# tracker-take — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Base resolution runs between claim and task creation
For a fresh start, `take` (explicit, batch, and auto forms alike) SHALL
resolve the base after the factory clone is hardened (the branch hardening
step that precedes task creation today) and before the task is created on
the branch: take the `task-branch.base` configuration from the trusted tier bound at
startup from the refreshed default branch (never re-read per claim),
evaluate the task's `base` designator, apply the priority order, and
refresh the resolved ref (see base-ref-resolution and git-task-persistence),
then load the task tier of the law from the refreshed base's commit (see
pipeline-config) — a load error parks the task with a configuration report.
`--base` keeps its existing surface — single explicit-mode start only — and,
where given, is the top priority tier. Resume of a task whose branch already
exists SHALL perform no base resolution: the pin governs, and the law binds
from the tip of the pinned ref name.
<!-- implements FR4 of add-base-ref-resolution -->

#### Scenario: Auto mode resolves without flags
- **WHEN** bare `take` claims a task carrying a valid `base` designator
- **THEN** the task branch starts from the refreshed designated ref with no
  command-line flag involved

#### Scenario: Resume never re-resolves
- **WHEN** `take <ref>` resumes a task whose branch carries a pinned base
- **THEN** no base resolution runs, the run continues per the pin, and the
  law binds from the pinned ref's current tip — so a human fix on the base
  reaches the resumed task

### Requirement: Base infrastructure failure releases the claim outside the abort accounting
When base resolution or the base refresh fails infrastructurally in a
tracker-driven start — default-branch discovery or the base fetch
exhausting its bounded retries — the take SHALL release the
claim through the plain claim-release path and end without creating a
branch: the task returns to Ready for any instance to claim later, no stage
attempt is burned, no escalation is posted to the tracker, and the run ends
with a typed result naming the infrastructure cause. The release SHALL write
no abort marker and no comment, so the task's abort facts, backoff, and
K-fuse accounting stay exactly as they were: the outage is charged to the
daemon, never to the task. The failure is caught and classified at the
fresh-claim step and never reaches the abort protocol as an uncaught
exception; the abort protocol itself is unchanged. Only reachability
failures are this class, classified by cause and never by the step that
observed them: a ref that does not exist, an authentication refusal, a
diverging local tag, a remote refusing fetch-by-SHA, and underdetermined
input (a disallowed or conflicting designator) park the task with a
report per base-ref-resolution. Every fetch a take performs at claim time,
of whatever ref, SHALL classify under this same rule.
<!-- implements FR9 of add-base-ref-resolution -->
<!-- implements M4 of add-base-ref-resolution -->

#### Scenario: Dead remote returns the task to the pool
- **WHEN** the base fetch exhausts retries during an auto-take claim
- **THEN** the claim is removed, the task shows Ready in the tracker with no
  new comment, and a later take of the same task succeeds once the remote
  returns

#### Scenario: Escalation is reserved for human-decidable input
- **WHEN** one claimed task has a conflicting designator and another hits a
  dead remote
- **THEN** the first parks with a human-facing report while the second is
  silently released, and only the first counts as an escalation

#### Scenario: Release leaves the abort accounting untouched
- **WHEN** a task with two recorded aborts is claimed and its base refresh
  hits a dead remote
- **THEN** the task returns to Ready with exactly two abort facts, its
  backoff unchanged, and no new marker in its thread

## MODIFIED Requirements

### Requirement: Exit codes by take result
`gnomish take` SHALL exit with: 0 — Delivered, or a clean bare-mode no-op
(empty queue); 1 — failure outside a claimed run (tracker unreachable at
startup, label provisioning); 2 — usage error; 3 — pipeline load failure;
10 — parked as escalation; 11 — parked as checkpoint; 12 — infrastructure
abort below the fuse; 13 — parked as infra (fuse trip or infrastructure
escalation); 14 — revoked; 15 — refused or skipped (held by another instance,
already done, closed or nonexistent, foreign repo); 16 — claim released on a
base-refresh infrastructure failure before any work started (the task is
back in Ready, nothing was recorded against it). Codes shared with
`gnomish run` SHALL keep the same meaning. An uncaught exception follows the
abort protocol and exits 12 or 13, never a bare 1.
<!-- implements FR9 of add-tracker-port -->
<!-- implements FR10 of add-tracker-port -->
<!-- implements FR15 of add-tracker-port -->
<!-- implements FR9 of add-base-ref-resolution -->

#### Scenario: Empty queue exits clean
- **WHEN** bare `take` finds no eligible ready task
- **THEN** the process exits 0 reporting an empty queue

#### Scenario: Escalation park exit
- **WHEN** a take run parks its task as an escalation
- **THEN** the process exits 10

#### Scenario: Refusal exit
- **WHEN** `take <ref>` refuses a task held by another instance
- **THEN** the process exits 15 naming the holder

#### Scenario: Released-on-outage exit
- **WHEN** a single-shot `take` releases its claim because the base refresh
  hit a dead remote
- **THEN** the process exits 16 naming the remote and the cause, and the
  task is Ready with no new marker
