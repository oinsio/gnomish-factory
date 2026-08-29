# tracker-take — delta for add-tracker-task-hierarchy

## MODIFIED Requirements

### Requirement: Bare auto mode takes the head of the queue
Bare `gnomish take` SHALL fetch the ready queue via `listReady`, hide tasks
whose abort backoff (exponential from base, capped; computed by core from
adapter abort facts) has not expired, exclude finished tasks entirely —
declining each via the decline protocol instead of claiming — exclude
dependency-blocked tasks (adapter-reported `dependencyBlocked` fact) without
declining or otherwise touching them, prefer returned tasks over fresh ones,
respect the WIP limit for fresh tasks (claimed only while open fronts < W;
returned tasks always claimable), claim from the head zone — a random pick
among the first K eligible, oldest-first as a soft preference — process
exactly one task to its terminal result, and exit. An empty or fully blocked
queue SHALL be a clean no-op run naming the reason (nothing eligible, the WIP
limit, or all remaining tasks dependency-blocked). Losing the claim race
SHALL fall through to the next eligible task.
<!-- implements FR10 of add-tracker-port -->
<!-- implements NFR-C1 of add-tracker-port -->
<!-- implements FR6, FR9 of add-factory-serve -->
<!-- implements FR3 of enforce-finish-terminality -->
<!-- implements FR4 of add-tracker-task-hierarchy -->

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

#### Scenario: Dependency-blocked task skipped without side effects
- **WHEN** the queue head is dependency-blocked and a later task is eligible
- **THEN** bare `take` claims the later task and writes nothing to the
  blocked one

#### Scenario: Fully dependency-blocked queue is a named no-op
- **WHEN** every ready task is dependency-blocked
- **THEN** bare `take` exits as a clean no-op naming dependency blocking as
  the reason
