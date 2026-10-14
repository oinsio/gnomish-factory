# Spec Delta: tracker/tracker-port

## MODIFIED Requirements

Layered on "Task facts from fetchTask" and "Port contract spec suite binds every adapter" as
modified by `tighten-spec-contract` (sequenced before this change). That delta gives the first
block its `**Implements**:` line and changes nothing else; it moved the second block's "abstract
Spock base class" into a note and left the backoff case to the scenario "Feed filtering
property". This text adds the latest tenure boundary to both bodies and the scenarios "A park
survives the claim that follows it", "A reaped claim is not a park", "Tenure boundary survives a
claim on every adapter" and "Every tenure boundary kind is reported on every adapter".

### Requirement: Task facts from fetchTask
`fetchTask` SHALL return the task snapshot (id, title, body), the logical state
with its holder (for `Working`) or reason (for `AwaitingHuman`), the abort
facts (count since last durable progress, last abort time), the task's
designators per kind (absent | single | conflict), and the task's **latest tenure
boundary** — `PARK`, `FINISH`, `ABORT`, `STALE_CLAIM_REMOVED`, or none — derived
from the tracker's durable history. Closed or nonexistent tasks SHALL be reported
as `Gone`, not as errors.
**Implements**: FR1, NFR-C1 of fix-terminal-receipt-convergence; FR1 of add-tracker-port; FR3 of add-base-ref-resolution
**Notes**: Designator kinds are classified as in "Designators are classified once and derived per adapter"; the boundary is the thread's last boundary marker, so a claim that follows it does not erase it — it is not the claim-scoped boundary the shape facts carry for the reaper.

#### Scenario: Full fact set for a working task
- **WHEN** `fetchTask` is called for a task claimed by instance A with two
  recorded aborts and one `base` designator
- **THEN** the result carries the snapshot, `Working(A)`, abort facts
  (count 2 with the last abort time), and the single `base` designator

#### Scenario: Closed task is Gone
- **WHEN** `fetchTask` is called for a closed or nonexistent task
- **THEN** the result state is `Gone` and no exception is thrown

#### Scenario: A park survives the claim that follows it
- **WHEN** a task was parked, returned to `Ready` by a human, and claimed by instance B
- **THEN** `fetchTask` reports `Working(B)` with latest boundary `PARK`

#### Scenario: A reaped claim is not a park
- **WHEN** a task's claim went stale with no park written, the reaper returned it, and instance B claimed it
- **THEN** `fetchTask` reports `Working(B)` with latest boundary `STALE_CLAIM_REMOVED`

### Requirement: Port contract spec suite binds every adapter
A single contract spec suite SHALL verify at minimum: `listReady` returns only
ready tasks (never `Working`/`AwaitingHuman`/`Finished`/`Gone`, never non-task
artifacts, no readiness-criterion failures) in adapter queue order with abort
facts; observable claim atomicity (two concurrent claims → exactly one
`Acquired`); structural-marker round-trip (abort facts and ack semantics as
specified above); and `fetchTask`'s latest tenure boundary. Every
shipped adapter SHALL pass the identical suite.
**Implements**: FR4 of add-tracker-port; NFR-R1 of add-tracker-port; FR1 of fix-terminal-receipt-convergence
**Notes**: The suite is an abstract Spock base class instantiated once per adapter.

#### Scenario: Feed filtering property
- **WHEN** the tracker holds tasks in every logical state plus a non-task artifact
- **THEN** `listReady` returns only the `Ready` tasks, including one with
  unexpired backoff (backoff filtering is core policy, not the adapter's)

#### Scenario: Claim atomicity property
- **WHEN** the contract race test runs repeatedly for an adapter
- **THEN** every run yields exactly one winner — no run yields zero or two

#### Scenario: Tenure boundary survives a claim on every adapter
- **WHEN** the suite parks a task, returns it to `Ready` and claims it again, on each adapter
- **THEN** `fetchTask` reports latest boundary `PARK` with the new holder, on each adapter
- **AND** after a second return and a second claim it still reports `PARK`

#### Scenario: Every tenure boundary kind is reported on every adapter
- **WHEN** the suite parks one task, finishes one, aborts one and has the reaper remove one stale claim, on each adapter
- **THEN** `fetchTask` reports latest boundary `PARK`, `FINISH`, `ABORT` and `STALE_CLAIM_REMOVED` respectively, on each adapter
