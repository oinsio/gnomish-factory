# git-task-persistence

## MODIFIED Requirements

### Requirement: Decision records carry author and scope additively
The decision record wire shape SHALL gain `scope` (one of item, stage,
task; absent reads as task) and `supersedes` (optional reference to an
earlier decision) additively under contract v1; existing documents parse
unchanged. The `author` field SHALL distinguish at least tracker (human
via tracker), operator (console dialog), and the arbiter identity.
<!-- implements FR9 of add-decision-arbiter -->

#### Scenario: Old documents parse unchanged
- **WHEN** a task.json written before this change is read
- **THEN** its decisions read with scope task and no supersedes

#### Scenario: Arbiter decision attributed
- **WHEN** an arbiter verdict is appended
- **THEN** the record's author names the arbiter identity, not a human

### Requirement: Decision append has one owner
All decision appends — tracker resume, operator dialog, arbiter verdict —
SHALL flow through a single application-side owner that stamps author and
scope and preserves the commit-before-acknowledge ordering; both
persistence media SHALL receive identical record content for the same
append.
<!-- implements FR10 of add-decision-arbiter -->

#### Scenario: Both media write identical records
- **WHEN** the same decision is appended in host mode and in container
  mode
- **THEN** the serialized records are field-for-field identical
