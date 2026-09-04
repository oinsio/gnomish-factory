# tracker-port — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Task facts carry label-derived designators per kind
Task facts SHALL yield, for a requested designator kind, one of exactly three
shapes: absent, a single value, or a conflict listing every value found. The
mechanism is kind-generic — a kind is identified by name and extracted from
the tracker's representation by a configured rule (for the label-backed
adapters, a label pattern with one capture group); the adapter or extraction
seam never resolves conflicts and never invents a value for an unmatched
task. Kind `base` is the first user; `add-pipeline-routing` adds kind `type`
on the same mechanism. The port-level contract suite SHALL cover all three
shapes for every adapter, including labels that match the rule with
different captured values (conflict) and labels that do not match the rule
at all (absent).
<!-- implements FR3 of add-base-ref-resolution -->

#### Scenario: Single designator reported
- **WHEN** a task carries exactly one label matching the configured `base`
  rule, capturing `release/1.18`
- **THEN** the task's facts yield the single designator `release/1.18` for
  kind `base`

#### Scenario: Absent is absent, not empty
- **WHEN** a task carries no label matching the `base` rule
- **THEN** the facts yield the absent shape — never an empty string and
  never a default

#### Scenario: Conflict lists every value
- **WHEN** a task carries two labels whose captures differ
- **THEN** the facts yield a conflict listing both captured values, and the
  contract suite asserts identical behavior for the in-memory and GitHub
  adapters
