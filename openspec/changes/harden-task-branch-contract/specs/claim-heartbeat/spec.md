# claim-heartbeat — delta for harden-task-branch-contract

## MODIFIED Requirements

### Requirement: Beat failures are classified, not counted
A failed beat SHALL be handled by cause: network errors and 5xx are logged as
WARN and work continues — the next round boundary resolves the situation; a
"claim marker gone" answer means the claim is lost — at the nearest round
boundary the run SHALL stop, salvage-push best-effort (the fence arbitrates),
free the slot, and write no tracker state for the task that is no longer ours.
A holder that cannot confirm its own heartbeat — its beats have failed long
enough that it no longer knows its claim is live — SHALL stop writing at the
next boundary: no push and no tracker write until it re-verifies the claim.
Re-verification confirming the claim resumes writing; a gone claim follows the
lost-claim path above (self-fencing).
<!-- implements FR8 of add-claim-heartbeat -->
<!-- implements FR13 of harden-task-branch-contract -->

#### Scenario: Transient outage does not stop work
- **WHEN** three consecutive beats fail with 5xx while the gnome works
- **THEN** the run continues, each failure logged as WARN, and beats resume
  when the tracker recovers

#### Scenario: Lost claim ends the run at the boundary
- **WHEN** a beat reports the claim marker gone (task reaped or taken over)
- **THEN** at the next round boundary the run stops, pushes best-effort, and
  performs no tracker transition for the lost task

#### Scenario: Unconfirmed heartbeat freezes writes at the boundary
- **WHEN** every beat has failed for longer than the lost-detection threshold
  and the round reaches its boundary
- **THEN** the holder writes nothing — no push, no tracker write — until a
  re-verification confirms the claim is still its own; a confirmed claim
  resumes the run, a gone claim follows the lost-claim path

## ADDED Requirements

### Requirement: The reaper owns the orphaned working-label shape
The shape "working label without a live claim comment" SHALL be owned by the
reaper: after a grace period the task returns to `Ready` through the ordinary
stale-claim removal. The factory can produce this shape itself — a killed
claim sequence or a killed reap freezes exactly this state — so the shape
SHALL never be attributed to human mislabeling and SHALL never require
operator surgery to re-enter circulation.
<!-- implements FR12 of harden-task-branch-contract -->

#### Scenario: Killed reap leaves an orphan the next tick resolves
- **WHEN** a reaper deletes a dead claim comment and dies before flipping the
  working label back to ready
- **THEN** a later reaper tick, after the grace period, returns the task to
  `Ready` — the orphan costs one grace window, never a stuck task

#### Scenario: Orphan is recovered, not blamed on a human
- **WHEN** an issue wears the working label with no live claim comment for the
  grace period
- **THEN** the reaper returns it to `Ready` without any path that treats the
  shape as a human mislabel to be ignored

### Requirement: Every (re)claim issues a monotonically increasing epoch
Each successful claim or reclaim of a task SHALL be issued an epoch strictly
greater than every epoch previously issued for that task; the epoch SHALL be
recorded with the claim and SHALL be available to the holder for stamping into
every commit and tracker write of that tenure, so readers can classify
artifacts carrying an older epoch than the current claim as stale-epoch.
<!-- implements FR13 of harden-task-branch-contract -->

#### Scenario: Reclaim after a reap advances the epoch
- **WHEN** a task claimed at epoch N is reaped and later claimed again by any
  instance
- **THEN** the new claim carries an epoch strictly greater than N, and any
  instance reading the claim can obtain that epoch

#### Scenario: Epoch is available for stamping
- **WHEN** a holder performs a commit or tracker write during its tenure
- **THEN** the epoch recorded with its claim is available to stamp into that
  write, unchanged for the whole tenure

### Requirement: Lost-detection strictly precedes reassignment
Reap timing SHALL keep two distinct thresholds: the holder's lost-detection
threshold (after which it self-fences) SHALL be strictly earlier than the
reaper's reassignment threshold, leaving a grace window during which the
original holder may re-verify and reclaim its own claim before the task is
returned to `Ready`. A self-fenced holder therefore stops writing before any
reaper can hand the task to another instance.
<!-- implements FR13 of harden-task-branch-contract -->

#### Scenario: Holder recovers within the grace window
- **WHEN** a holder's beats fail past the lost-detection threshold, it
  self-fences at the boundary, and connectivity returns before the
  reassignment threshold elapses
- **THEN** the holder re-verifies its still-live claim, resumes writing, and
  the task is never reaped

#### Scenario: Fencing wins the race against reassignment
- **WHEN** a holder's heartbeat is unconfirmed and the reassignment threshold
  is approaching
- **THEN** the holder has already stopped writing at its earlier lost-detection
  threshold, so no write of the old tenure lands after the reaper returns the
  task to `Ready`
