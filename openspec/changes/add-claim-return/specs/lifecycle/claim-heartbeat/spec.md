## ADDED Requirements

### Requirement: A return marker ends the tenure it names
The "claim returned" boundary marker SHALL count as a claim boundary for
every reader: it ends the tenure of the holder it names, so the task it
sits on classifies by its labels afterward — `Ready` once the flip landed,
`IndexLagging` while the marker is newer than a working label still on the
issue — and never `ClaimAbandoned` or `Foreign`.
**Implements**: FR9, NFR-R1, NFR-R2 of add-claim-return

#### Scenario: Returned task is plainly Ready
- **WHEN** a holder's return has landed and the reaper lists the task
- **THEN** it classifies `Ready` with the return marker as history, and no
  repair is attempted

#### Scenario: Half-landed return is a known window
- **WHEN** the return marker is posted but the label flip never landed
- **THEN** the task classifies `IndexLagging` and the reaper completes the
  flip toward the marker's target, `Ready`

#### Scenario: A return adds no shape
- **WHEN** a return's write sequence is interrupted after any of its writes
- **THEN** the frozen state classifies to a shape of the existing set, and the reaper converges
  it exactly as it converges an interrupted stale-claim removal

#### Scenario: Failed return falls back to the TTL
- **WHEN** a holder's return fails on the tracker and the holder exits
- **THEN** the claim goes stale on schedule and the reaper returns the task
  one TTL later, as if no return had been attempted

## MODIFIED Requirements

Layered on `Zombie fencing` as modified by `tighten-spec-contract` (sequenced before this
change): that delta gives the block its `**Implements**:` line and changes nothing else; this
text adds the fence the deliberate return carries inside the operation, as the note and the
scenario "Zombie return is fenced by the claim identity", and both must survive the later sync.

### Requirement: Zombie fencing
The task branch SHALL NEVER be force-pushed by any party — the git
non-fast-forward refusal is the hard fence: of two writers holding the same
task, the late pusher gets a persist refusal and follows the normal `Aborted`
path. Tracker writes that git does not fence (park, finish, release) SHALL be
preceded by a cheap conditional "claim still ours" check; the residual
window may cost a stray label or comment, never data corruption, and
converges with the new holder's next write.
**Implements**: FR7 of add-claim-heartbeat; FR2 of add-claim-return
**Notes**: The deliberate return (`returnToReady`) needs no separate pre-write check: the adapter re-reads the claim inside the operation, so a zombie's return is a no-op by construction.

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
