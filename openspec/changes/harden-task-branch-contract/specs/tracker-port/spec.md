# tracker-port — delta for harden-task-branch-contract

## MODIFIED Requirements

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
no-op, removal of a task the tracker no longer holds, and concurrent-removal
convergence; and the holder-transition marker round-trip. Kill windows of the
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
