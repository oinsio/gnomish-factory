## MODIFIED Requirements

### Requirement: Every (re)claim issues a monotonically increasing epoch
<!-- implements FR13 of harden-task-branch-contract; FR7 of fix-claim-epoch-fence -->
Each successful claim or reclaim of a task SHALL be issued an epoch strictly
greater than every epoch previously issued for that task; the epoch SHALL be
recorded with the claim and SHALL be available to the holder for stamping into
every commit and tracker write of that tenure. The stamp is provenance on the
branch and the holder's identity at the tracker; a reader SHALL NOT classify
an artifact carrying an older epoch than the current claim as stale (see the
`task-branch-contract` capability, "Claim-epoch fencing").

#### Scenario: Reclaim after a reap advances the epoch
- **WHEN** a task claimed at epoch N is reaped and later claimed again by any
  instance
- **THEN** the new claim carries an epoch strictly greater than N, and any
  instance reading the claim can obtain that epoch

#### Scenario: Epoch is available for stamping
- **WHEN** a holder performs a commit or tracker write during its tenure
- **THEN** the epoch recorded with its claim is available to stamp into that
  write, unchanged for the whole tenure
