# tracker-port (delta)

## ADDED Requirements

### Requirement: List entries carry the task title
`ReadyTask` and `OpenTask` SHALL carry the task title alongside their
existing facts. Every adapter SHALL populate the title from the data its
list calls already receive — enriching a list result SHALL NOT add
tracker requests (no per-task `fetchTask` fan-out). The port contract
spec suite SHALL verify title propagation on both list operations for
every adapter.
<!-- implements FR7 of add-board-command -->
<!-- implements NFR-P1 of add-board-command -->

#### Scenario: Ready listing carries titles
- **WHEN** `listReady` is called against a tracker holding ready tasks
  with known titles
- **THEN** every returned entry carries its task's title

#### Scenario: Open listing carries titles
- **WHEN** `listOpen` is called while one task is `Working` and one is
  `AwaitingHuman`
- **THEN** both returned entries carry their tasks' titles

#### Scenario: Titles cost no extra requests
- **WHEN** `listReady` and `listOpen` run against the GitHub adapter
  fixture
- **THEN** the recorded requests are the same list calls as before the
  enrichment — no issue-detail request was added

#### Scenario: Contract suite binds title propagation
- **WHEN** the shared contract suite runs against the in-memory reference
  and the GitHub adapter
- **THEN** the title-propagation properties pass on both without
  adapter-specific exemptions
