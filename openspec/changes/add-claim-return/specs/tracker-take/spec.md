## MODIFIED Requirements

Layered on `tracker-take` as modified by `add-base-ref-resolution`
(sequenced before this change): that delta introduces this requirement and
its plain-release wording; this text replaces the verb with the fenced
return and must survive the later sync.

### Requirement: Base infrastructure failure releases the claim outside the abort accounting
When base resolution or the base refresh fails infrastructurally in a
tracker-driven start — default-branch discovery or the base fetch
exhausting its bounded retries — the take SHALL give the task back through
the fenced return (`returnToReady`, reason "base refresh: origin
unreachable") and end without creating a branch: the task is `Ready` for
any instance to claim as soon as the return lands, no stage attempt is
burned, no escalation is posted to the tracker, and the run ends with a
typed result naming the infrastructure cause. The return SHALL write no
abort marker and no comment beyond its own boundary marker, so the task's
abort facts, backoff, and K-fuse accounting stay exactly as they were: the
outage is charged to the daemon, never to the task. A return the tracker
does not accept is best-effort — logged, and the task left to the reaper's
TTL path. The failure is caught and classified at the fresh-claim step and
never reaches the abort protocol as an uncaught exception; the abort
protocol itself is unchanged. Only reachability failures are this class,
classified by cause and never by the step that observed them: a ref that
does not exist, an authentication refusal, a diverging local tag, a remote
refusing fetch-by-SHA, and underdetermined input (a disallowed or
conflicting designator) park the task with a report per
base-ref-resolution. Every fetch a take performs at claim time, of whatever
ref, SHALL classify under this same rule. The same return applies to the
resume-time refresh of a pinned base that a configured origin never
answered.
<!-- implements FR9 of add-base-ref-resolution -->
<!-- implements M4 of add-base-ref-resolution -->
<!-- implements FR5, NFR-R1 of add-claim-return -->

#### Scenario: Dead remote returns the task to the pool
- **WHEN** the base fetch exhausts retries during an auto-take claim
- **THEN** the claim is gone, the task shows Ready in the tracker with one
  "returned to ready" marker and no other new comment, and a later take of
  the same task succeeds once the remote returns

#### Scenario: Escalation is reserved for human-decidable input
- **WHEN** one claimed task has a conflicting designator and another hits a
  dead remote
- **THEN** the first parks with a human-facing report while the second is
  returned, and only the first counts as an escalation

#### Scenario: Return leaves the abort accounting untouched
- **WHEN** a task with two recorded aborts is claimed and its base refresh
  hits a dead remote
- **THEN** the task is Ready with exactly two abort facts, its backoff
  unchanged, and only the return marker new in its thread

#### Scenario: Unanswering tracker leaves the TTL path
- **WHEN** the base fetch hits a dead remote and the tracker also fails the
  return
- **THEN** the run still ends with the infrastructure result, the failure is
  logged under its own code, and the reaper returns the task one TTL later

## ADDED Requirements

### Requirement: Deliberate give-back after a claim returns the task
When a tracker-driven invocation ends by a deliberate, dedicated-exit-code
failure right after its claim — a usage error, a configuration refusal —
without any branch write, the take SHALL give the task back through the
fenced return with the failure's sanitized summary as the reason, so the
task is `Ready` for any instance at once rather than after the claim TTL.
The revocation protocol is unchanged and keeps the plain `release`: a claim
found lost or a task a human moved is never returned, because the human may
already have acted on it.
<!-- implements FR7 of add-claim-return -->
<!-- implements NFR-S1 of add-claim-return -->

#### Scenario: Usage error hands the task straight back
- **WHEN** `take` claims a task and then fails on a usage error before any
  round
- **THEN** the task is `Ready` with a "returned to ready" marker carrying the
  usage error's summary, and the invocation exits with its usage exit code

#### Scenario: Revocation still releases
- **WHEN** a round boundary finds the claim held by another instance
- **THEN** the salvage protocol runs, the claim is released, and no
  "returned to ready" marker is written
