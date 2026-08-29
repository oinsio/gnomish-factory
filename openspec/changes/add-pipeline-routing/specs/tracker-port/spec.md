# tracker-port — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Task facts carry the type designator
Task facts SHALL include the task's type: absent, a single designator, or a
conflict fact listing the designators found. The adapter derives it from its
tracker's representation and never resolves conflicts itself. The port-level
contract suite SHALL cover all three shapes for every adapter.
<!-- implements FR2 of add-pipeline-routing -->

#### Scenario: Single designator reported
- **WHEN** a task carries one type designator in its tracker
- **THEN** `fetchTask` facts carry exactly that designator

#### Scenario: Absent type reported as absent
- **WHEN** a task carries no type information
- **THEN** the facts report no type, not an empty-string type

#### Scenario: Conflict reported with all designators
- **WHEN** the tracker data yields two designators
- **THEN** the facts carry a conflict listing both, and the contract suite
  asserts identical behavior for the in-memory and GitHub adapters
