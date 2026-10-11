# Spec Delta: tracker-port

## MODIFIED Requirements

### Requirement: Task facts from fetchTask
`fetchTask` SHALL return the task snapshot (id, title, body), the logical state
with its holder (for `Working`) or reason (for `AwaitingHuman`), the abort
facts (count since last durable progress, last abort time), the task's
designators per kind (absent | single | conflict, see "Designators are
classified once and derived per adapter"), and the task's **latest tenure
boundary** — `PARK`, `FINISH`, `ABORT`, `STALE_CLAIM_REMOVED`, or none — derived
from the tracker's durable history (the boundary markers on the thread), so a
claim that follows the boundary does not erase it. Closed or nonexistent tasks
SHALL be reported as `Gone`, not as errors.
<!-- implements FR1, NFR-C1 of fix-terminal-receipt-convergence -->
<!-- implements FR1 of add-tracker-port; FR3 of add-base-ref-resolution -->

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
A single contract spec suite (abstract Spock base class instantiated per adapter)
SHALL verify at minimum: `listReady` returns only ready tasks (never
`Working`/`AwaitingHuman`/`Finished`/`Gone`, never non-task artifacts, no
readiness-criterion failures) in adapter queue order with abort facts, and does
NOT filter by backoff; observable claim atomicity (two concurrent claims → exactly
one `Acquired`); structural-marker round-trip (abort facts and ack semantics
as specified above); and the **latest tenure boundary** `fetchTask` reports —
after a park, a finish, an abort and a stale-claim removal, and its persistence
across a following claim. Every shipped adapter SHALL pass the identical suite.
<!-- implements FR1 of fix-terminal-receipt-convergence -->
<!-- implements FR4, NFR-R1 of add-tracker-port -->

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
