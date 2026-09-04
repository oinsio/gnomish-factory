# tracker-port — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Designator kind `type` in the task facts
Task facts SHALL yield designator kind `type` through the kind-generic
label-derived designator mechanism (introduced by `add-base-ref-resolution`):
absent, a single designator, or a conflict listing every designator found —
never a resolved pick. Label-backed adapters report raw labels and the
configured extraction rule produces the shapes; a future adapter MAY fulfill
the same three-shape contract from a native field. The port-level contract
suite SHALL cover all three shapes of kind `type` for every adapter, beside
its existing kind `base` coverage.
<!-- implements FR2 of add-pipeline-routing -->

#### Scenario: Single designator reported
- **WHEN** a task carries one label matching the configured `type` rule,
  capturing `bugfix`
- **THEN** the facts yield the single designator `bugfix` for kind `type`

#### Scenario: Absent type reported as absent
- **WHEN** a task carries no label matching the `type` rule
- **THEN** the facts report the absent shape, not an empty-string type

#### Scenario: Conflict reported with all designators
- **WHEN** the task's labels yield two different captured designators
- **THEN** the facts carry a conflict listing both, and the contract suite
  asserts identical behavior for the in-memory and GitHub adapters
