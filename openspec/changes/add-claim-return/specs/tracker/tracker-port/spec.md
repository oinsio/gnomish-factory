## ADDED Requirements

### Requirement: Deliberate return gives the task back now
The port SHALL expose `returnToReady(ref, ownClaim, reason)`. Given the
task, the caller's own claim identity, and a short factory-authored reason,
the adapter SHALL as one operation record a structural "claim returned"
boundary marker naming the returning holder and the reason, remove the
caller's claim marker, and transition the task to `Ready`, without claiming
it for anyone. The operation SHALL act only when the current claim is the
caller's own: same holder, same claim epoch.
**Implements**: FR1, FR2, FR3, NFR-R1 of add-claim-return
**Notes**: A return is the holder's way of giving a task back on purpose when nothing is wrong with it ("nothing is wrong with this task and I hold it"); `release` is unchanged and keeps the revocation case, where a human may already have acted ("I no longer hold it").

#### Scenario: Return round-trip
- **WHEN** the holder of a `Working` task returns it with reason "daemon
  shutting down"
- **THEN** `fetchTask` reports `Ready` in the same call's wake, the thread
  carries one "claim returned" marker naming the holder and the reason, the
  claim marker is gone, and another instance's claim succeeds immediately

#### Scenario: Stale holder's return is a fenced no-op
- **WHEN** a reaped former holder returns a task another instance now holds
- **THEN** nothing is written, the live claim and the `Working` state are
  untouched, and the result reports the live claim's facts

#### Scenario: Return of a claim already gone is a no-op
- **WHEN** a holder returns a task whose claim was reaped and that no instance holds now
- **THEN** nothing is written, and the result reports the current facts rather than an error

#### Scenario: A repeated return is a no-op
- **WHEN** the holder returns a task a second time after its first return landed
- **THEN** nothing is written and the result reports the current facts

#### Scenario: Return of a vanished task
- **WHEN** a holder returns a task the tracker no longer holds
- **THEN** the result reports absent facts and nothing is thrown

#### Scenario: Return racing a removal converges
- **WHEN** a holder's return and a reaper's `removeStaleClaim` for the same
  claim run concurrently
- **THEN** the task ends `Ready` exactly once, the thread carries exactly one
  boundary marker for that tenure, and both calls return without error

## MODIFIED Requirements

Layered on `Contract suite covers lease maintenance` as modified by `tighten-spec-contract`
(sequenced before this change): that delta cut the body to the four operations and moved the
covered properties into the scenarios "The suite covers the open listing and its facts", "The
suite covers the heartbeat", "The suite covers stale-claim removal" and "The suite covers index
repair"; this text adds the plain `release` and `returnToReady` to the operations, the scenarios
"The suite covers the plain release", "The suite covers the deliberate return" and "Return and
removal race", and the return sequence to "Non-atomic adapter covers its kill windows"; both must
survive the later sync.

### Requirement: Contract suite covers lease maintenance
The shared contract spec suite SHALL be extended to verify on every adapter the
lease-maintenance operations — `listOpen`, `heartbeat`, `removeStaleClaim`,
`repairIndex`, the plain `release` and `returnToReady` — and the claim facts
they report. Each adapter whose writes are physically non-atomic SHALL also
cover the kill windows of its multi-write tracker sequences in its own suite,
by fault injection after every write (see the kill-point harness).
**Implements**: FR5 of add-claim-heartbeat; NFR-R2, NFR-R3 of add-claim-heartbeat; FR19 of harden-task-branch-contract; FR8 of add-claim-return
**Notes**: The kill windows live in each non-atomic adapter's own suite because the atomic in-memory reference adapter cannot express them.

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

#### Scenario: Return and removal race
- **WHEN** the harness schedules a holder's `returnToReady` and a reaper's
  `removeStaleClaim` for the same claim with an adversarial interleaving
- **THEN** the task ends `Ready` exactly once with one boundary marker, and
  both calls return without error

#### Scenario: Non-atomic adapter covers its kill windows
- **WHEN** the GitHub adapter's suite runs the claim, abort, finish, park,
  reap, and return sequences with the connection failing after each write
- **THEN** every frozen intermediate state classifies to a named tracker shape
  owned by a retry or the sweep — none is unreachable by both

#### Scenario: The suite covers the open listing and its facts
- **WHEN** the lease-maintenance suite runs against an adapter
- **THEN** it verifies `listOpen` filtering (only `Working`/`AwaitingHuman`,
  never `Ready`/`Finished`/`Gone`, never non-task artifacts), the fact
  reporting for out-of-protocol combinations (a working-state task with no
  claim footprint is reported with its facts, never omitted), and the claim
  facts on `listReady` entries

#### Scenario: The suite covers the heartbeat
- **WHEN** the lease-maintenance suite runs against an adapter
- **THEN** it verifies that a beat changes the version another instance reads,
  and the "claim gone" signal, including a beat against a task the tracker no
  longer holds

#### Scenario: The suite covers stale-claim removal
- **WHEN** the lease-maintenance suite runs against an adapter
- **THEN** it verifies the `removeStaleClaim` round-trip, the version-mismatch
  no-op, dead-footprint removal without a live version, removal of a task the
  tracker no longer holds, concurrent-removal convergence, and the
  holder-transition marker round-trip

#### Scenario: The suite covers index repair
- **WHEN** the lease-maintenance suite runs against an adapter
- **THEN** it verifies `repairIndex` restoring a claimless working task and
  completing a marker's transition, with its changed-facts no-op

#### Scenario: The suite covers the plain release
- **WHEN** the lease-maintenance suite runs against an adapter
- **THEN** it verifies that `release` leaves the task `Working` and out of the
  ready listing, that only a removal returns it to `Ready`, and that a repeated
  release is a no-op

#### Scenario: The suite covers the deliberate return
- **WHEN** the lease-maintenance suite runs against an adapter
- **THEN** it verifies the `returnToReady` round-trip (`Ready` within one call,
  a fresh claim by another instance succeeding), the stale-holder no-op, the
  repeated-return no-op, the vanished-task no-op, and return-versus-removal
  convergence
