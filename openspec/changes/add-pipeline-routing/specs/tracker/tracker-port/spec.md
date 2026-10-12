# tracker/tracker-port — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Designator kind `type` in the task facts
The `fetchTask` facts SHALL carry designator kind `type` through the
designator mechanism introduced by `add-base-ref-resolution`: absent, a
single designator, or a conflict listing every designator found — never a
resolved pick — classified only through the port's shared function. The
port-level contract suite SHALL cover all three shapes of kind `type` for
every adapter, beside its existing kind `base` coverage.
**Implements**: FR2 of add-pipeline-routing

#### Scenario: Absent type reported as absent
- **WHEN** a task yields no `type` candidate
- **THEN** the facts report the absent shape, not an empty-string type

#### Scenario: Conflict reported with all designators
- **WHEN** a task yields two different `type` candidates
- **THEN** the facts carry a conflict listing both, and the contract suite
  asserts identical behavior for the in-memory and GitHub adapters

### Requirement: Adapters derive kind `type` from their own representation
The GitHub adapter SHALL derive the `type` candidates from labels through the
`tracker.github.designators.type` rule (one capture group, validated on its
subsection seam like every other entry of the map, no built-in default) and
SHALL report `type` among its configured kinds through the adapter factory
seam only when the rule is declared; the in-memory adapter SHALL set the kind
through its designator test operation.
**Implements**: FR2 of add-pipeline-routing; FR4 of tighten-spec-contract
**Notes**: A future adapter may fulfill the same three-shape contract from a native field.

#### Scenario: Single designator reported
- **WHEN** a GitHub task carries one label matching the configured `type`
  rule, capturing `bugfix`
- **THEN** the facts yield the single designator `bugfix` for kind `type`

#### Scenario: Kind is configured only when the rule is declared
- **WHEN** the GitHub subsection declares no `designators.type` rule
- **THEN** the adapter factory does not report `type` among its configured
  kinds and every task's `type` designator is absent

#### Scenario: The in-memory adapter sets the kind through its test operation
- **WHEN** a spec sets kind `type` on an in-memory task through the designator test operation
- **THEN** the facts of that task carry the designator set, in the same shape the GitHub adapter reports

#### Scenario: An invalid type rule is refused on its subsection seam
- **WHEN** the GitHub subsection declares a `designators.type` rule without exactly one capture group
- **THEN** the subsection seam refuses it with a located error, like any other entry of the map
