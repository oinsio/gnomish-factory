## ADDED Requirements

### Requirement: A return marker ends the tenure it names
The "claim returned" boundary marker SHALL count as a claim boundary for
every reader: it ends the tenure of the holder it names, so the task it
sits on classifies by its labels afterward — `Ready` once the flip landed,
`IndexLagging` while the marker is newer than a working label still on the
issue — and never `ClaimAbandoned` or `Foreign`. The shape set and the
recovery owners are unchanged: a return adds no shape, and every window of
its write sequence is converged by the reaper exactly as a stale-claim
removal's is. The explicit return SHALL never be the only path back to the
queue — a return that fails to land leaves the claim on the TTL path, and
the reaper returns the task as before.
<!-- implements FR9, NFR-R1, NFR-R2 of add-claim-return -->

#### Scenario: Returned task is plainly Ready
- **WHEN** a holder's return has landed and the reaper lists the task
- **THEN** it classifies `Ready` with the return marker as history, and no
  repair is attempted

#### Scenario: Half-landed return is a known window
- **WHEN** the return marker is posted but the label flip never landed
- **THEN** the task classifies `IndexLagging` and the reaper completes the
  flip toward the marker's target, `Ready`

#### Scenario: Failed return falls back to the TTL
- **WHEN** a holder's return fails on the tracker and the holder exits
- **THEN** the claim goes stale on schedule and the reaper returns the task
  one TTL later, as if no return had been attempted

## MODIFIED Requirements

### Requirement: Zombie fencing
The task branch SHALL NEVER be force-pushed by any party — the git
non-fast-forward refusal is the hard fence: of two writers holding the same
task, the late pusher gets a persist refusal and follows the normal `Aborted`
path. Tracker writes that git does not fence (park, finish, release) SHALL be
preceded by a cheap conditional "claim still ours" check; the residual
window may cost a stray label or comment, never data corruption, and
converges with the new holder's next write. The deliberate return
(`returnToReady`) carries its fence inside the operation: the adapter
re-reads the claim and acts only on the caller's own holder and epoch, so a
zombie's return is a no-op by construction rather than by a separate
pre-write check.
<!-- implements FR7 of add-claim-heartbeat -->
<!-- implements FR2 of add-claim-return -->

#### Scenario: Zombie push is rejected
- **WHEN** a reaped instance thaws and pushes its round while the new holder
  has already pushed
- **THEN** the zombie's push fails as non-fast-forward, its run ends via the
  normal abort path, and the new holder's branch is untouched

#### Scenario: Zombie park is stopped by the pre-write check
- **WHEN** a zombie attempts to park a task whose claim now belongs to another
  instance
- **THEN** the pre-write check detects the foreign claim and the park is not
  written

#### Scenario: Zombie return is fenced by the claim identity
- **WHEN** a zombie returns a task whose claim now belongs to another
  instance or a newer epoch
- **THEN** the adapter's own re-read finds a foreign claim and writes nothing
