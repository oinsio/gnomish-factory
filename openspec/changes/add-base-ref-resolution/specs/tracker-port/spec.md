# tracker-port — delta for add-base-ref-resolution

## ADDED Requirements

### Requirement: Designators are classified once and derived per adapter
The port module SHALL publish the designator vocabulary — per kind, exactly
one of three shapes: absent, a single value, or a conflict listing every
value found — together with one classification function from candidate
values to a shape (no candidates → absent; one distinct value → single;
several distinct values → conflict; equal duplicates collapse). Every adapter
SHALL derive the candidate values for a kind from its own representation and
SHALL classify them only through that function — no adapter resolves a
conflict, invents a value, or classifies on its own. Kinds are open names:
kind `base` is the first user; `add-pipeline-routing` adds kind `type` with
no port change. The GitHub adapter SHALL take its rules from its own
subsection, `tracker.github.designators` (a map of kind → regular expression
with exactly one capture group), validated on the adapter's config seam like
its other keys; the in-memory adapter SHALL set designators through a test
operation and still require no subsection. The adapter factory seam SHALL
report the set of kinds an adapter is configured to extract, so core can
validate configuration that depends on it without reading adapter keys. The
port-level contract suite SHALL cover all three shapes for every adapter,
including candidates that match with different values (conflict) and data
that yields no candidate at all (absent).
<!-- implements FR3 of add-base-ref-resolution -->

#### Scenario: Single designator reported
- **WHEN** a GitHub task carries exactly one label matching the configured
  `base` rule, capturing `release/1.18`
- **THEN** `fetchTask` yields the single designator `release/1.18` for kind
  `base`

#### Scenario: Absent is absent, not empty
- **WHEN** a task carries no data yielding a `base` candidate
- **THEN** the facts yield the absent shape — never an empty string and
  never a default

#### Scenario: Conflict lists every value
- **WHEN** a task yields two `base` candidates with different values
- **THEN** the facts yield a conflict listing both values, and the contract
  suite asserts identical behavior for the in-memory and GitHub adapters

#### Scenario: Equal duplicates are one designator
- **WHEN** a task yields the same `base` value twice
- **THEN** the facts yield the single shape, not a conflict

#### Scenario: Configured kinds are reported through the seam
- **WHEN** the GitHub subsection declares a `designators.base` rule
- **THEN** the adapter factory reports `base` among its configured kinds, and
  an adapter with no rule reports none

## MODIFIED Requirements

### Requirement: Task facts from fetchTask
`fetchTask` SHALL return the task snapshot (id, title, body), the logical state
with its holder (for `Working`) or reason (for `AwaitingHuman`), the abort
facts (count since last durable progress, last abort time), the `finished`
fact (the recorded history contains a finish report), and the task's
designators per kind (absent | single | conflict, see "Designators are
classified once and derived per adapter"). Closed or nonexistent tasks SHALL
be reported as `Gone`, not as errors.
<!-- implements FR1 of add-tracker-port -->
<!-- implements FR1 of enforce-finish-terminality -->
<!-- implements FR3 of add-base-ref-resolution -->

#### Scenario: Full fact set for a working task
- **WHEN** `fetchTask` is called for a task claimed by instance A with two
  recorded aborts and one `base` designator
- **THEN** the result carries the snapshot, `Working(A)`, abort facts
  (count 2 with the last abort time), `finished` false, and the single
  `base` designator

#### Scenario: Closed task is Gone
- **WHEN** `fetchTask` is called for a closed or nonexistent task
- **THEN** the result state is `Gone` and no exception is thrown

### Requirement: Adapter author guide
The change SHALL ship an adapter author guide (`docs/guides/adapter-author-guide.md`)
covering: the state dictionary and transition matrix with the three-level
distinction (tracker state / run outcome / scheduler slot); per-operation port
semantics; the contract suite as law with the in-memory adapter as the worked
reference; physical state mapping by example (GitHub labels as-built, Redmine
statuses as a thought-through sketch); snapshot, decision, abort-fact, and
designator obligations (derive candidates from the tracker's own
representation, classify only through the port's function, declare the
configured kinds through the factory seam); config-subsection ownership
(adapter declares and validates its own subsection, never touches core keys)
including the mandatory declaration of credential env variables for the
gnome-environment scrub; and known limitations (branch-name sanitize
collisions, polling economy with the GitHub analysis as the model, the considered
and rejected surrogate-id approach).
<!-- implements FR19 of add-tracker-port -->
<!-- implements FR3 of add-base-ref-resolution -->

#### Scenario: Guide is self-sufficient
- **WHEN** a developer follows the guide to build a new adapter
- **THEN** every obligation the contract suite checks is stated in the guide,
  without reading factory core code
