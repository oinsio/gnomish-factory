# tracker/tracker-take — delta for add-epic-decomposition

## ADDED Requirements

### Requirement: Decomposed outcome drives child creation before release
When an engine run ends `Decomposed`, take SHALL drive the decomposition
transition to completion while still holding the claim: create the plan's
children via create-subtask (stable keys, sibling edges, integration child
last), record receipts on the task branch, transition the parent to its
waiting state with a report naming the children, and only then release.
**Implements**: FR3, FR5 of add-epic-decomposition

#### Scenario: Decomposition completes under the claim
- **WHEN** the run ends Decomposed with a four-child plan
- **THEN** all four children exist with edges before the parent's park
  report is posted, and the claim is released last

#### Scenario: A tracker outage mid-transition never half-releases
- **WHEN** the tracker fails during the decomposition transition and the terminal-write retry discipline exhausts its retries
- **THEN** the task is left claimed and aborted, never half-released

### Requirement: Resume completes a frozen decomposition first
An instance resuming an epic whose branch carries a pushed plan without a
complete receipt set SHALL complete the decomposition (reconcile children by
stable key, create missing ones, record receipts, transition the parent)
before considering any other work on the task. Re-running the engine on such
a task SHALL be impossible — the pushed plan is the point of no return.
**Implements**: FR4, NFR-R1 of add-epic-decomposition

#### Scenario: Resumed epic converges without re-planning
- **WHEN** an instance claims an epic frozen after two of four children were
  created
- **THEN** it creates the missing two, records receipts, parks the parent,
  and never starts an engine round
