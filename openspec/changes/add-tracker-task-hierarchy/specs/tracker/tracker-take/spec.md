# tracker/tracker-take — delta for add-tracker-task-hierarchy

## MODIFIED Requirements

Layered on `Bare auto mode takes the head of the queue` as modified by `tighten-spec-contract`
(sequenced before this change): that delta cut the body to the pick and the eligibility rule and
added the scenarios "Backoff grows exponentially up to its cap", "Returned task claimable at the
WIP limit", "Nothing eligible is a clean no-op naming the reason" and "A lost claim race falls
through"; this text adds dependency blocking to the eligibility rule and the scenarios
"Dependency-blocked task skipped without side effects" and "Fully dependency-blocked queue is a
named no-op". All must survive the later sync.

### Requirement: Bare auto mode takes the head of the queue
Bare `gnomish take` SHALL fetch the ready queue via `listReady`, claim one
eligible task from the head zone — a random pick among the first K eligible,
oldest-first as a soft preference — process exactly that one task to its
terminal result, and exit. A task is eligible when its abort backoff has
expired, it is not finished, it is not dependency-blocked (the adapter's
`dependencyBlocked` fact), and, if fresh, the WIP limit admits it;
returned tasks are preferred over fresh ones.
**Implements**: FR10 of add-tracker-port; NFR-C1 of add-tracker-port; FR6, FR9 of add-factory-serve; FR3 of enforce-finish-terminality; FR4 of add-tracker-task-hierarchy

#### Scenario: One task per run
- **WHEN** the queue holds three ready tasks and bare `take` runs
- **THEN** exactly one task from the head zone is processed and the process
  exits after its terminal result

#### Scenario: Backoff hides a task
- **WHEN** the queue head aborted moments ago and its backoff has not expired
- **THEN** bare `take` claims the next eligible task instead

#### Scenario: WIP limit blocks a fresh start
- **WHEN** open fronts equal W and only fresh tasks are ready
- **THEN** bare `take` exits as a clean no-op naming the WIP limit

#### Scenario: Returned task preferred
- **WHEN** the queue holds an older fresh task and a younger returned task
- **THEN** bare `take` claims the returned task

#### Scenario: Finished task never claimed from the feed
- **WHEN** the queue lists a reopened finished task ahead of a fresh task
- **THEN** bare `take` declines the finished task and claims the fresh one

#### Scenario: Backoff grows exponentially up to its cap
- **WHEN** core computes a ready task's backoff from the adapter's abort facts
- **THEN** the delay is the configured base doubled per consecutive abort after the first,
  capped at the configured cap, and the task stays hidden until that delay has passed since
  its last abort

#### Scenario: Returned task claimable at the WIP limit
- **WHEN** open fronts equal W and a returned task is ready
- **THEN** bare `take` claims the returned task

#### Scenario: Nothing eligible is a clean no-op naming the reason
- **WHEN** every ready task is hidden by its unexpired backoff
- **THEN** bare `take` exits as a clean no-op naming that nothing is eligible

#### Scenario: A lost claim race falls through
- **WHEN** another instance claims the picked task between the queue read and this run's claim
- **THEN** bare `take` tries the next eligible task instead of ending the run

#### Scenario: Dependency-blocked task skipped without side effects
- **WHEN** the queue head is dependency-blocked and a later task is eligible
- **THEN** bare `take` claims the later task and writes nothing to the
  blocked one

#### Scenario: Fully dependency-blocked queue is a named no-op
- **WHEN** every ready task is dependency-blocked
- **THEN** bare `take` exits as a clean no-op naming dependency blocking as
  the reason
