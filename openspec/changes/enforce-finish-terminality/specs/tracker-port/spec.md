# tracker-port — delta

## ADDED Requirements

### Requirement: Ready listing carries the finished fact
Each `listReady` entry SHALL carry an adapter-derived "finished" fact — true
when the task's recorded history contains a finish report, regardless of how
the task came back to `Ready`. The fact SHALL be reconstructed from tracker
history alone on every listing (no adapter-local state), and `fetchTask`'s
result SHALL carry the same fact so the explicit-take path can consult it.
Adapters SHALL report the fact only; refusing and declining is core policy.
<!-- implements FR1 of enforce-finish-terminality -->

#### Scenario: Reopened finished task carries the fact
- **WHEN** a task is finished with a final report and a human later moves it
  back to `Ready`, and `listReady` runs on a fresh instance
- **THEN** the entry reports the finished fact true, derived from history alone

#### Scenario: fetchTask agrees
- **WHEN** `fetchTask` is called for that same reopened task
- **THEN** the result carries the finished fact true alongside state `Ready`

### Requirement: Decline-finished operation restores terminal status
The port SHALL expose a decline operation for a finished task found in
circulation: it restores the task's terminal status (so the task leaves the
`Ready` feed) and posts a core-supplied human-readable explanation. The
status restore SHALL happen before the explanation is posted, and the
explanation SHALL be posted only when the call actually performed the
restore: declining an already-terminal task is a state-level no-op that
posts nothing. Concurrent declines by racing instances SHALL NOT corrupt
state (at worst a duplicate explanation, when both observe the task as
non-terminal before either restores it). The explanation write SHALL NOT
use a marker kind that feeds the `returned` or `finished` derivations.
<!-- implements FR4 of enforce-finish-terminality -->
<!-- implements NFR-R1, NFR-R2, NFR-O1 of enforce-finish-terminality -->

#### Scenario: Decline round-trip
- **WHEN** core declines a reopened finished task with an explanation message
- **THEN** the task's terminal status is restored, the message is visible to a
  human in the tracker, and the next `listReady` no longer lists the task

#### Scenario: Decline of an already-terminal task is a no-op
- **WHEN** decline is called for a task already in its terminal status
- **THEN** the status is unchanged, no explanation is posted, and no error is
  raised

#### Scenario: Decline does not pollute derivations
- **WHEN** a declined task's history is re-read by a fresh instance
- **THEN** the decline explanation contributes to neither the returned nor the
  finished fact — only the original finish report does

## MODIFIED Requirements

### Requirement: Single Tracker port speaking the factory's language
The application layer SHALL expose one `Tracker` port with exactly the
operations `listReady`, `fetchTask`, `collectDecisions`, `claim`, `release`,
`park`, `finish`, `recordAbort`, `acknowledgeDecision`, `postNote`,
`recordProgress`, `declineFinished`, plus the lease-maintenance operations
`listOpen`, `heartbeat`, and `removeStaleClaim`.
The port vocabulary SHALL be the factory's (tasks, states, decisions, abort
facts, claim versions); all tracker-specific mapping SHALL be confined to
adapters. Report rendering (domain report → text) SHALL happen in core: the
port accepts finished text plus structural fields, never engine domain models.
<!-- implements FR1 of add-tracker-port -->
<!-- implements FR5 of add-claim-heartbeat -->
<!-- implements FR4 of enforce-finish-terminality -->

#### Scenario: Core compiles against the port alone
- **WHEN** the take runner drives a full task lifecycle
- **THEN** every tracker interaction goes through the `Tracker` port, and no core
  class references an adapter type or a tracker-specific concept (label, issue,
  transition id)

### Requirement: Ready listing carries the returned fact
Each `listReady` entry SHALL carry an adapter-derived "returned" fact — true
when the task's recorded history shows it was previously worked and given
back for more work: a park report (human-returned) or a holder-transition
marker (reaper-returned) exists. A finish report SHALL NOT count as
returned — a finished task is terminal, not given back (see the finished
fact). All adapters SHALL agree on this derivation. Adapters SHALL report the
fact only; the prioritization policy (returned first, WIP accounting) lives
in core. The open-front count remains the size of the existing `listOpen`
listing — no dedicated counting operation exists.
<!-- implements FR6, FR7 of add-factory-serve -->
<!-- implements FR2 of enforce-finish-terminality -->

#### Scenario: Fresh task is not returned
- **WHEN** `listReady` lists a task that was never claimed
- **THEN** its returned fact is false

#### Scenario: Park round-trip sets the fact
- **WHEN** a task is parked with an escalation report and later moved back to
  ready
- **THEN** `listReady` lists it with the returned fact true

#### Scenario: Reaped task is returned
- **WHEN** a stale claim is removed and its task returns to `Ready`
- **THEN** `listReady` lists it with the returned fact true

#### Scenario: Finish-reopen is not returned
- **WHEN** a task is finished and a human moves it back to `Ready`
- **THEN** `listReady` lists it with the returned fact false and the finished
  fact true

### Requirement: Contract suite covers the returned fact
The shared contract spec suite SHALL be extended to verify on every adapter:
the returned fact is false for never-claimed tasks, true after a
park-and-return round-trip, true after a stale-claim removal, and false
after a finish-and-reopen round-trip; the finished fact is true after a
finish-and-reopen round-trip and false otherwise; the decline operation
restores terminal status, removes the task from `listReady`, leaves the
derivations unpolluted, and is a silent no-op (status and thread unchanged)
on an already-terminal task; and `listOpen`'s size equals the open-front
count the WIP policy consumes.
<!-- implements FR7 of add-factory-serve -->
<!-- implements NFR-R1 of add-factory-serve -->
<!-- implements FR6 of enforce-finish-terminality -->

#### Scenario: Suite passes on both adapters
- **WHEN** the extended contract suite runs against the in-memory reference
  and the GitHub adapter
- **THEN** every returned-fact, finished-fact, and decline property passes
  without adapter-specific exemptions

#### Scenario: Finish-reopen property is adapter-equivalent
- **WHEN** the same finish-then-human-reopen history is arranged on each
  adapter
- **THEN** both report identical facts (returned false, finished true) —
  the divergence the suite previously missed
