# git-task-persistence — delta for add-decision-inheritance

## ADDED Requirements

### Requirement: Mini-ADR fields flow through the single append owner
The additive decision-record fields (scope, status/supersedes, rejected
alternatives, premises) SHALL be written and read exclusively through the
single decision append owner established by the arbiter change; roll-up and
re-derivation writes to the epic decisions file SHALL flow through the same
owner. Old `task.json` files SHALL parse unchanged with the documented
defaults.
<!-- implements FR1, FR2 of add-decision-inheritance -->

#### Scenario: One owner for every decision write
- **WHEN** a decision lands in a task's own record set or in the epic file
- **THEN** the write goes through the single append owner, and no second
  code path serializes decision records

#### Scenario: Old task.json parses with defaults
- **WHEN** a pre-change `task.json` with bare decisions is read
- **THEN** each decision reads as task-scoped and accepted with no error

### Requirement: Epic decisions file is a branch-contract shape
The epic decisions file SHALL live in the factory-owned area of the epic's
task branch; its writes SHALL be single pushed commits; and its presence and
completeness states SHALL classify within the task-branch contract's shape
set so the resume classifier and the integration child's completeness check
can name them from the branch alone.
<!-- implements FR2, FR3, NFR-R1 of add-decision-inheritance -->

#### Scenario: Roll-up commit is one push
- **WHEN** a child's roll-up lands three exported records
- **THEN** they land as one pushed commit on the epic branch

#### Scenario: Classifier names the file state
- **WHEN** an instance reads an epic branch whose file lacks a finished
  child's exports
- **THEN** classification names the missing-roll-up shape and the child
