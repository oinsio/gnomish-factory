## ADDED Requirements

### Requirement: Deliberate return gives the task back now
The port SHALL expose `returnToReady(ref, ownClaim, reason)`: the holder's
way of giving a task back on purpose when nothing is wrong with the task.
Given the task, the caller's own claim identity (holder and claim epoch),
and a short factory-authored reason, the adapter SHALL as one operation
record a structural "claim returned" boundary marker naming the returning
holder and the reason, remove the caller's claim marker, and transition the
task to `Ready`. It SHALL NOT claim the task for anyone.

The operation SHALL be fenced by the caller's claim identity: before acting
the adapter re-reads the current claim and proceeds only when it is the
caller's own — same holder, same epoch. When it is not (the claim was
reaped, taken over, or is already gone, or the task no longer exists) the
operation SHALL be a safe no-op reporting the current facts, never an error
and never a write. A repeated return by the same holder SHALL be a no-op,
and a return racing a reaper's removal SHALL converge to one `Ready` task
with one boundary marker.

`release` is unchanged: it drops the claim and moves no label, for the
revocation case where a human may already have acted. The two verbs are
distinct and a caller chooses by situation — a return says "nothing is
wrong with this task and I hold it"; a release says "I no longer hold it".
<!-- implements FR1, FR2, FR3 of add-claim-return -->
<!-- implements NFR-R1 of add-claim-return -->

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

#### Scenario: Return of a vanished task
- **WHEN** a holder returns a task the tracker no longer holds
- **THEN** the result reports absent facts and nothing is thrown

#### Scenario: Return racing a removal converges
- **WHEN** a holder's return and a reaper's `removeStaleClaim` for the same
  claim run concurrently
- **THEN** the task ends `Ready` exactly once, the thread carries exactly one
  boundary marker for that tenure, and both calls return without error

## MODIFIED Requirements

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
its changed-facts no-op; the holder-transition marker round-trip; the plain
`release` leaving the task `Working` and out of the ready listing, returned
to `Ready` only by a removal, and idempotent; and `returnToReady` round-trip
(`Ready` within one call, a fresh claim by another instance succeeding), the
stale-holder no-op, the repeated-return no-op, the vanished-task no-op, and
return-versus-removal convergence. Kill windows of the multi-write tracker
sequences cannot be expressed against the atomic in-memory reference adapter;
each adapter whose writes are physically non-atomic SHALL cover them in its
own suite by fault injection after every write (see the kill-point harness).
<!-- implements FR5 of add-claim-heartbeat -->
<!-- implements NFR-R2, NFR-R3 of add-claim-heartbeat -->
<!-- implements FR19 of harden-task-branch-contract -->
<!-- implements FR8 of add-claim-return -->

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
