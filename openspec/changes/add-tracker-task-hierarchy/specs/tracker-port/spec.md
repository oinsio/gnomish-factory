# tracker-port — delta for add-tracker-task-hierarchy

## ADDED Requirements

### Requirement: Task model carries hierarchy facts
Task facts returned by the port SHALL include hierarchy facts: the parent
task's ref when the task is a subtask (absent otherwise), the ordered list of
the task's child refs each with an open-or-terminal state fact, and a
`dependencyBlocked` fact — true while the task has at least one unresolved
blocked-by edge. Hierarchy facts are adapter-reported facts; the core never
derives them from task content.
<!-- implements FR1 of add-tracker-task-hierarchy -->

#### Scenario: Subtask reports its parent
- **WHEN** `fetchTask` is called for a task created as a subtask of a parent
- **THEN** the returned facts carry the parent's ref

#### Scenario: Parent reports children with states
- **WHEN** `fetchTask` is called for a task with two children, one finished
  and one open
- **THEN** the returned facts list both child refs in creation order, one
  marked terminal and one open

#### Scenario: Task with no relationships reports none
- **WHEN** `fetchTask` is called for a task that is neither a parent nor a
  subtask
- **THEN** hierarchy facts are empty: no parent, no children, not
  dependency-blocked

### Requirement: Create-subtask operation with stable-key idempotency
The port SHALL offer a create-subtask operation: given a parent ref, a
caller-supplied stable child key, a title, a body, and blocked-by edges to
previously created sibling refs, it creates the child task linked under the
parent and returns the child's ref. The stable key SHALL round-trip: listing
a parent's children reveals each child's stable key, so a caller that crashed
after creating some children can enumerate existing children and create only
the missing ones. Creating a child whose stable key already exists under the
same parent SHALL be rejected as an `AlreadyExists` result carrying the
existing child's ref, never a duplicate task.
<!-- implements FR2, NFR-R1 of add-tracker-task-hierarchy -->

#### Scenario: Child created under parent with dependency edge
- **WHEN** create-subtask is called with parent P, key `k2`, and a blocked-by
  edge to sibling ref C1
- **THEN** the new task exists as a child of P, is dependency-blocked while
  C1 is open, and the returned ref resolves via `fetchTask`

#### Scenario: Recovery finds existing children by stable key
- **WHEN** a caller lists the children of P after a crash mid-decomposition
- **THEN** each existing child's stable key is present in the listing, so the
  caller can compute the set of missing children

#### Scenario: Duplicate stable key is not a duplicate task
- **WHEN** create-subtask is called twice with parent P and key `k1`
- **THEN** the second call returns `AlreadyExists` with the first child's ref
  and no second task is created

### Requirement: Ready listing carries the dependency-blocked fact
`listReady` entries SHALL carry the `dependencyBlocked` fact for each task.
The adapter reports the fact and never filters by it — selection policy is
core-owned, mirroring the existing backoff and finished facts.
<!-- implements FR3 of add-tracker-task-hierarchy -->

#### Scenario: Blocked task appears in the feed with the fact set
- **WHEN** a ready task has an unresolved blocked-by edge
- **THEN** `listReady` includes it with `dependencyBlocked` true rather than
  omitting it

#### Scenario: Fact clears when the blocker resolves
- **WHEN** the task's last open blocker reaches a terminal state
- **THEN** a subsequent `listReady` reports `dependencyBlocked` false

### Requirement: Contract suite covers the hierarchy surface
The port-level contract spec suite SHALL exercise hierarchy facts, the
create-subtask operation (including stable-key idempotency and
`AlreadyExists`), and the dependency-blocked feed fact, and every adapter —
the in-memory reference and GitHub — SHALL pass the same suite.
<!-- implements FR5 of add-tracker-task-hierarchy -->

#### Scenario: Both adapters pass the hierarchy contract
- **WHEN** the contract suite runs against the in-memory adapter and against
  the GitHub adapter's test double harness
- **THEN** every hierarchy scenario passes identically for both
