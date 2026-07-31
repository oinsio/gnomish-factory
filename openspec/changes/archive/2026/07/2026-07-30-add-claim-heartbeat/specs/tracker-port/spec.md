# tracker-port — delta

## MODIFIED Requirements

### Requirement: Single Tracker port speaking the factory's language
The application layer SHALL expose one `Tracker` port with exactly the
operations `listReady`, `fetchTask`, `collectDecisions`, `claim`, `release`,
`park`, `finish`, `recordAbort`, `acknowledgeDecision`, `postNote`,
`recordProgress`, plus the lease-maintenance operations `listOpen`,
`heartbeat`, and `removeStaleClaim`.
The port vocabulary SHALL be the factory's (tasks, states, decisions, abort
facts, claim versions); all tracker-specific mapping SHALL be confined to
adapters. Report rendering (domain report → text) SHALL happen in core: the
port accepts finished text plus structural fields, never engine domain models.
<!-- implements FR1 of add-tracker-port -->
<!-- implements FR5 of add-claim-heartbeat -->

#### Scenario: Core compiles against the port alone
- **WHEN** the take runner drives a full task lifecycle
- **THEN** every tracker interaction goes through the `Tracker` port, and no core
  class references an adapter type or a tracker-specific concept (label, issue,
  transition id)

#### Scenario: Adapter receives rendered text
- **WHEN** the factory parks a task with an escalation report
- **THEN** the adapter receives the report as finished text plus structural fields,
  not an engine report object

## ADDED Requirements

### Requirement: Open-task listing with claim versions
`listOpen` SHALL return the open tasks — `Working` and `AwaitingHuman` — each
with its logical state and, for `Working`, the claim facts: holder and the
opaque claim version (claim-marker identity plus last-update fact). Adapters
SHALL report version facts only; observation memory, TTL policy, and staleness
judgment live in core. `listReady` SHALL remain unchanged — its contract stays
"only `Ready` tasks".
<!-- implements FR5 of add-claim-heartbeat -->

#### Scenario: Listing carries versions
- **WHEN** `listOpen` is called while one task is `Working` and one is
  `AwaitingHuman`
- **THEN** both tasks are returned with their states, and the `Working` entry
  carries holder and claim version; no `Ready` or `Finished` task appears

#### Scenario: Version changes are observable across instances
- **WHEN** instance A beats its claim and instance B calls `listOpen` before
  and after
- **THEN** B observes a different claim version after the beat

### Requirement: Heartbeat write updates the claim marker in place
`heartbeat` SHALL update the existing claim marker of a task the caller holds
— refreshing its version and progress payload without creating any new
coordination artifact. Its failure mode SHALL distinguish "claim marker gone"
(the claim was removed or taken over — a protocol signal) from infrastructure
failure (network, 5xx — retryable); the two are different results, not one
exception.
<!-- implements FR5, FR8 of add-claim-heartbeat -->

#### Scenario: Beat refreshes the version
- **WHEN** the holder calls `heartbeat` with a progress payload
- **THEN** a subsequent `listOpen` shows the same claim identity with a new
  version and the payload readable in the claim marker

#### Scenario: Gone claim is a signal, not an error
- **WHEN** `heartbeat` is called after the claim marker was deleted by a
  reaper
- **THEN** the result reports the claim as lost, distinguishable from an
  infrastructure failure

#### Scenario: Beat of a task the tracker no longer holds is claim-gone
- **WHEN** `heartbeat` is called for a task the tracker no longer holds at all
- **THEN** the result reports the claim as lost — the strongest form of "claim
  gone" — not an infrastructure failure and never a thrown exception

### Requirement: Stale-claim removal returns the task to circulation
`removeStaleClaim` SHALL, as one operation given the task and the observed
stale claim version: record a structural holder-transition marker ("stale
claim removed", naming the dead holder), remove the dead claim marker, and
return the task to `Ready`. It SHALL NOT claim the task for the caller. When
the claim version no longer matches the observed one (the claim was beaten,
already removed, or replaced), the operation SHALL be a safe no-op reporting
the current state rather than an error — making concurrent removals converge.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2 of add-claim-heartbeat -->

#### Scenario: Removal round-trip
- **WHEN** `removeStaleClaim` succeeds for a stale `Working` task
- **THEN** `fetchTask` reports `Ready`, the old claim marker is gone, and the
  holder-transition marker is recoverable from the tracker by any instance

#### Scenario: Version mismatch is a no-op
- **WHEN** `removeStaleClaim` is called with a version that no longer matches
  (the holder beat the claim meanwhile)
- **THEN** nothing is removed and the result reports the live claim

#### Scenario: Removal of a task the tracker no longer holds is a no-op
- **WHEN** `removeStaleClaim` is called for a task the tracker no longer holds
  at all
- **THEN** nothing is removed and the result reports an absent claim (a null
  current version), never a thrown exception — so a foreign reaper observing a
  vanished task converges without burning a retry

### Requirement: Contract suite covers lease maintenance
The shared contract spec suite SHALL be extended to verify on every adapter:
`listOpen` filtering (only `Working`/`AwaitingHuman`, never `Ready`/
`Finished`/`Gone`, never non-task artifacts); heartbeat version observability
(a beat changes the version another instance reads); the heartbeat "claim
gone" signal, including a beat against a task the tracker no longer holds;
`removeStaleClaim` round-trip, version-mismatch no-op, removal of a task the
tracker no longer holds, and concurrent-removal convergence; and the
holder-transition marker round-trip.
<!-- implements FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2, NFR-R3 of add-claim-heartbeat -->

#### Scenario: Suite passes on both adapters
- **WHEN** the extended contract suite runs against the in-memory reference
  and the GitHub adapter
- **THEN** every lease-maintenance property passes without adapter-specific
  exemptions

#### Scenario: Concurrent removal race
- **WHEN** the harness schedules two `removeStaleClaim` calls for the same
  stale claim with an adversarial interleaving
- **THEN** the task ends `Ready` exactly once and both calls return without
  error
