# tracker-take — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Base resolution runs between claim and task creation
For a fresh start, `take` (explicit, batch, and auto forms alike) SHALL
resolve the base after the claim is hardened and before the task is created
on the branch: read the `base:` configuration from the refreshed default
branch, evaluate the task's `base` designator, apply the priority order, and
refresh the resolved ref (see base-ref-resolution and git-task-persistence).
`--base` keeps its existing surface — single explicit-mode start only — and,
where given, is the top priority tier. Resume of a task whose branch already
exists SHALL perform no base resolution: the pin governs.
<!-- implements FR4 of add-base-ref-resolution -->

#### Scenario: Auto mode resolves without flags
- **WHEN** bare `take` claims a task carrying a valid `base` designator
- **THEN** the task branch starts from the refreshed designated ref with no
  command-line flag involved

#### Scenario: Resume never re-resolves
- **WHEN** `take <ref>` resumes a task whose branch carries a pinned base
- **THEN** no base resolution runs, no base fetch of the pinned ref's
  upstream is required to proceed, and the run continues per the pin

### Requirement: Base infrastructure failure releases the claim
When base resolution or the base refresh fails infrastructurally in a
tracker-driven start — default-branch discovery, configuration refresh, or
the base fetch exhausting its bounded retries — the take SHALL release the
claim through the existing claim-removal path and end without creating a
branch: the task returns to Ready for any instance to claim later, no stage
attempt is burned, no escalation is posted to the tracker, and the run's
outcome names the infrastructure cause. Underdetermined input (an
out-of-menu or conflicting designator) is NOT this class: it parks the task
with a report per base-ref-resolution.
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
