## MODIFIED Requirements

Layered on `Base infrastructure failure releases the claim outside the abort accounting` as
modified by `tighten-spec-contract` (sequenced before this change); the requirement itself was
introduced by `add-base-ref-resolution`, now archived. That delta cut the body and moved its
cases into scenarios, adding "The outage is classified at the fresh claim, not aborted" and
"Non-reachability base failures park the task", and aligned the THENs with the plain release
(the reaper returns the task to Ready once the claim lapses); this text replaces the plain release
with the fenced return in the body and in "Dead remote returns the task to the pool", "Escalation is
reserved for human-decidable input" and "Release leaves the abort accounting untouched", and
adds the scenarios "The return names the outage", "Unanswering tracker leaves the TTL path" and
"A resume-time refresh outage returns the task too"; both must survive the later sync.

### Requirement: Base infrastructure failure releases the claim outside the abort accounting
When base resolution or the base refresh fails infrastructurally in a
tracker-driven start — default-branch discovery or the base fetch
exhausting its retries — the take SHALL give the task back through
the fenced return (`returnToReady`) and end without creating a branch, with
a typed result naming the cause. The return SHALL write no abort marker and
no comment but its own boundary marker. Every fetch a take performs at claim
time, of whatever ref, SHALL classify under this same rule.
**Implements**: FR9 of add-base-ref-resolution; M4 of add-base-ref-resolution; FR5, NFR-R1 of add-claim-return
**Notes**: The outage is charged to the daemon, never to the task: the abort protocol itself is unchanged and never sees this failure.

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

#### Scenario: Release leaves the abort accounting untouched
- **WHEN** a task with two recorded aborts is claimed and its base refresh
  hits a dead remote
- **THEN** the task is Ready with exactly two abort facts, its backoff
  unchanged, and only the return marker new in its thread

#### Scenario: The outage is classified at the fresh claim, not aborted
- **WHEN** the base fetch of a fresh claim exhausts its bounded retries
- **THEN** the failure is caught at the fresh-claim step and the run ends with the
  released-on-outage result, never through the abort protocol as an uncaught exception

#### Scenario: Non-reachability base failures park the task
- **WHEN** the base names a ref that does not exist, the remote refuses authentication, a
  local tag under the base's name diverges, or the remote refuses fetch-by-SHA — whichever
  step observed the failure
- **THEN** the task is parked with a report per base-ref-resolution, and its claim is not
  silently released

#### Scenario: The return names the outage
- **WHEN** the take gives a task back because its base refresh found origin unreachable
- **THEN** the return's reason reads "base refresh: origin unreachable"

#### Scenario: Unanswering tracker leaves the TTL path
- **WHEN** the base fetch hits a dead remote and the tracker also fails the
  return
- **THEN** the run still ends with the infrastructure result, the failure is
  logged under its own code, and the reaper returns the task one TTL later

#### Scenario: A resume-time refresh outage returns the task too
- **WHEN** a resumed task's pinned base is refreshed against a configured origin that never
  answers
- **THEN** the task is given back through the same fenced return, with no abort marker and no
  comment beyond the return marker

## ADDED Requirements

### Requirement: Deliberate give-back after a claim returns the task
When a tracker-driven invocation ends by a deliberate, dedicated-exit-code
failure right after its claim — a usage error, a configuration refusal —
without any branch write, the take SHALL give the task back through the
fenced return with the failure's sanitized summary as the reason, so the
task is `Ready` for any instance at once rather than after the claim TTL.
**Implements**: FR7, NFR-S1 of add-claim-return
**Notes**: The revocation protocol is unchanged and keeps the plain `release`, because a human may already have acted on a lost claim or a moved task.

#### Scenario: Usage error hands the task straight back
- **WHEN** `take` claims a task and then fails on a usage error before any
  round
- **THEN** the task is `Ready` with a "returned to ready" marker carrying the
  usage error's summary, and the invocation exits with its usage exit code

#### Scenario: Revocation still releases
- **WHEN** a round boundary finds the claim held by another instance
- **THEN** the salvage protocol runs, the claim is released, and no
  "returned to ready" marker is written

#### Scenario: A task a human moved is released, not returned
- **WHEN** a round boundary finds the task moved out of `Working` by a human
- **THEN** the revocation protocol releases the claim and no "returned to ready" marker is
  written
