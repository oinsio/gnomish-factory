# tracker-port — delta for harden-task-branch-contract

## MODIFIED Requirements

### Requirement: Single Tracker port speaking the factory's language
The application layer SHALL expose one `Tracker` port with exactly the
operations `listReady`, `fetchTask`, `collectDecisions`, `claim`, `release`,
`park`, `finish`, `recordAbort`, `acknowledgeDecision`, `postNote`,
`recordProgress`, `declineFinished`, plus the lease-maintenance operations
`listOpen`, `heartbeat`, `removeStaleClaim`, and `repairIndex`.
`repairIndex` SHALL, given a task and the caller's observed tracker facts,
bring the task's indexed state to the state its recorded truth implies —
restoring `Ready` when a working-state task carries no claim footprint
(`ClaimPending`), or completing the transition toward the newest boundary
marker (`IndexLagging`) — and SHALL record a structural repair marker naming
the observed shape. It SHALL re-check the facts before acting and be a safe
no-op reporting the current facts when they no longer match the observed
ones, so concurrent repairs converge. It SHALL NOT claim the task for the
caller and SHALL NOT re-execute any work the markers record as done.
The port vocabulary SHALL be the factory's (tasks, states, decisions, abort
facts, claim facts, boundary markers); all tracker-specific mapping SHALL be
confined to adapters. Report rendering (domain report → text) SHALL happen in
core: the port accepts finished text plus structural fields, never engine
domain models.
<!-- implements FR1 of add-tracker-port -->
<!-- implements FR5 of add-claim-heartbeat -->
<!-- implements FR4 of enforce-finish-terminality -->
<!-- implements FR19, FR12 of harden-task-branch-contract -->

#### Scenario: Core compiles against the port alone
- **WHEN** the take runner drives a full task lifecycle
- **THEN** every tracker interaction goes through the `Tracker` port, and no core
  class references an adapter type or a tracker-specific concept (label, issue,
  transition id)

#### Scenario: Adapter receives rendered text
- **WHEN** the factory parks a task with an escalation report
- **THEN** the adapter receives the report as finished text plus structural fields,
  not an engine report object

#### Scenario: Index repair restores a claimless working task to Ready
- **WHEN** `repairIndex` is invoked for a task observed as working-state with
  no claim footprint after its grace
- **THEN** the task ends `Ready` with a structural repair marker recorded, and
  a re-check finding a live claim footprint instead makes the call a safe
  no-op reporting the current facts

#### Scenario: Index repair completes a marker's transition
- **WHEN** `repairIndex` is invoked for a task whose newest boundary marker is
  a finish marker while the indexed state still reads working
- **THEN** the task ends `Finished` — the transition the marker implies is
  completed and the finished work is never re-executed

### Requirement: Stale-claim removal returns the task to circulation
`removeStaleClaim` SHALL, as one operation given the task and the observed
claim facts — a live claim's stale version, or a dead footprint whose live
version is absent (`ClaimAbandoned`): record a structural holder-transition
marker ("stale claim removed", naming the dead or last-known holder), remove
the dead claim marker, and return the task to `Ready`. It SHALL NOT claim the
task for the caller. When the current claim facts no longer match the
observed ones (the claim was beaten, already removed, or replaced by a newer
live claim), the operation SHALL be a safe no-op reporting the current facts
rather than an error — making concurrent removals converge. An absent
observed version is an eligible input, never a filtered-out one: the removal
of a dead footprint follows the same guard, comparing footprint identity
instead of a live version.
<!-- implements FR4, FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2 of add-claim-heartbeat -->
<!-- implements FR19, FR12 of harden-task-branch-contract -->

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

#### Scenario: Dead footprint is removable without a live version
- **WHEN** `removeStaleClaim` is called for a graced `ClaimAbandoned` task
  with the observed dead footprint and no live version
- **THEN** the holder-transition marker names the last-known holder, the dead
  claim marker is removed, and the task returns to `Ready` — the absent
  version never disqualifies the removal

### Requirement: Open-task listing with claim versions
`listOpen` SHALL return the open tasks — `Working` and `AwaitingHuman` — each
with its tracker facts: the logical state labels present, the claim facts (a
live claim with holder and opaque version, a dead footprint with its
last-known holder, or none), and the latest boundary-marker kind. Adapters
SHALL report facts only and SHALL NOT omit a task whose combination they
cannot interpret; observation memory, TTL policy, staleness judgment, and
shape classification live in core. `listReady` SHALL keep its contract — only
`Ready` tasks — and each entry SHALL additionally carry the same claim facts,
so core can classify a ready-labeled task that still carries a claim
footprint.
<!-- implements FR5 of add-claim-heartbeat -->
<!-- implements FR19 of harden-task-branch-contract -->

#### Scenario: Listing carries versions
- **WHEN** `listOpen` is called while one task is `Working` and one is
  `AwaitingHuman`
- **THEN** both tasks are returned with their states, and the `Working` entry
  carries holder and claim version; no `Ready` or `Finished` task appears

#### Scenario: Version changes are observable across instances
- **WHEN** instance A beats its claim and instance B calls `listOpen` before
  and after
- **THEN** B observes a different claim version after the beat

#### Scenario: Uninterpretable combinations are reported as facts
- **WHEN** `listOpen` encounters a `Working`-labeled task with no claim
  footprint at all
- **THEN** the entry is returned with its label facts and an absent claim
  footprint — the adapter neither omits it nor invents a holder

#### Scenario: Ready entry carries claim facts
- **WHEN** `listReady` lists a ready task whose thread still carries a live
  claim marker
- **THEN** the entry reports the live claim fact, and core — not the
  adapter — decides what the combination means

### Requirement: Contract suite covers lease maintenance
The shared contract spec suite SHALL be extended to verify on every adapter:
`listOpen` filtering (only `Working`/`AwaitingHuman`, never `Ready`/
`Finished`/`Gone`, never non-task artifacts); fact reporting for
out-of-protocol combinations (a working-state task with no claim footprint is
reported with its facts, never omitted); claim facts on `listReady` entries;
heartbeat version observability (a beat changes the version another instance
reads); the heartbeat "claim gone" signal, including a beat against a task
the tracker no longer holds; `removeStaleClaim` round-trip, version-mismatch
no-op, dead-footprint removal without a live version, removal of a task the
tracker no longer holds, and concurrent-removal convergence; `repairIndex`
restoring a claimless working task and completing a marker's transition, with
its changed-facts no-op; and the holder-transition marker round-trip. Kill windows of the
multi-write tracker sequences cannot be expressed against the atomic
in-memory reference adapter; each adapter whose writes are physically
non-atomic SHALL cover them in its own suite by fault injection after every
write (see the kill-point harness).
<!-- implements FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2, NFR-R3 of add-claim-heartbeat -->
<!-- implements FR19 of harden-task-branch-contract -->

#### Scenario: Suite passes on both adapters
- **WHEN** the extended contract suite runs against the in-memory reference
  and the GitHub adapter
- **THEN** every lease-maintenance and fact-reporting property passes without
  adapter-specific exemptions

#### Scenario: Concurrent removal race
- **WHEN** the harness schedules two `removeStaleClaim` calls for the same
  stale claim with an adversarial interleaving
- **THEN** the task ends `Ready` exactly once and both calls return without
  error

#### Scenario: Non-atomic adapter covers its kill windows
- **WHEN** the GitHub adapter's suite runs the claim, abort, finish, park, and
  reap sequences with the connection failing after each write
- **THEN** every frozen intermediate state classifies to a named tracker shape
  owned by a retry or the sweep — none is unreachable by both

## ADDED Requirements

### Requirement: The tenure record is published to writers
The claim epoch a tenure was issued SHALL be readable by every writer that
stamps it, through one published read-only seam on the contract — "which epoch
does this instance hold on this task right now" — filled at the single claim
choke point and never re-derived. An adapter whose writes are physically
non-atomic SHALL be handed that seam when it is constructed, so its own writers
stamp the tenure they write under; an adapter that does not stamp epochs SHALL
be constructible without one. No component SHALL keep a second tenure record of
its own: a duplicated fencing token is exactly the divergence the epoch exists
to detect.
<!-- implements FR13 of harden-task-branch-contract -->

#### Scenario: An adapter's own writes carry the tenure it holds
- **WHEN** an instance holding a claim on a task performs a tracker write for
  that task through an adapter that stamps epochs
- **THEN** the write carries the epoch that claim was issued, readable back by
  any instance

#### Scenario: A claimless writer stamps no epoch and still writes
- **WHEN** a path that holds no claim on the task performs a tracker write —
  correspondence after the claim was dropped, or a reaper acting on another
  instance's tenure
- **THEN** the write carries no tenure stamp and still succeeds; an absent
  tenure is an ordinary state, never a failure

#### Scenario: An adapter that does not stamp epochs is still constructible
- **WHEN** an adapter that carries no epoch of its own is built
- **THEN** it is constructed without a tenure record and its behavior is
  unchanged — epoch stamping is adapter-optional, and the claim token contract
  above is what every adapter still owes

### Requirement: Claim issues a monotonic claim token
A successful `claim` SHALL return an opaque claim token that is strictly
increasing per task across successive (re)claims, and the same token SHALL be
readable in the claim facts of `listOpen`, `listReady`, and `heartbeat`
observations. Each adapter chooses its own monotonic source (the GitHub
adapter uses the tracker-assigned claim comment id); core compares tokens only
for order — it never interprets their structure. This token is the claim epoch
of the `task-branch-contract` fencing: holders stamp it into commits and
tracker writes, and readers classify older-token artifacts as stale-epoch.
<!-- implements FR13 of harden-task-branch-contract -->

#### Scenario: Reclaim returns a greater token
- **WHEN** a task is claimed, reaped, and claimed again by any instance
- **THEN** the second claim's token compares strictly greater than the first's

#### Scenario: Token is observable by other instances
- **WHEN** instance A holds a claim and instance B reads the task's claim
  facts from a listing
- **THEN** B obtains the same token A was issued, as an opaque ordered value
