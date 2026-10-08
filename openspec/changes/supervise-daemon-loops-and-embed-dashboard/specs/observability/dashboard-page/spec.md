# Spec Delta

## ADDED Requirements

### Requirement: Status card shows the WIP stat from the board
The status card SHALL show a WIP stat beside the slot stat: the board's
open-front count (Working plus AwaitingHuman) against the WIP limit the board
judged eligibility by, with a tooltip giving the exact counts and the
working/waiting split. The stat SHALL carry no alarm styling at any value.
<!-- implements FR12, UX3 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: WIP stat with its split
- **WHEN** the board holds 2 Working and 3 AwaitingHuman tasks under a WIP
  limit of 10
- **THEN** the status card shows `WIP 5 / 10` and its tooltip reads `5 of 10
  open fronts: 2 working, 3 waiting for a human`

#### Scenario: Full WIP is not an alarm
- **WHEN** the open-front count equals the WIP limit
- **THEN** the WIP stat renders in ordinary styling

#### Scenario: Limit matches the held rows
- **WHEN** a ready row is annotated WIP-held
- **THEN** the WIP stat's limit is the limit that row was judged against

### Requirement: The WIP stat degrades with the board, not the snapshot
The WIP stat SHALL render whenever a board model exists, including a cached
model after a failed refresh, and SHALL render even when no snapshot exists.
It SHALL be absent when no board fetch has ever succeeded. The snapshot-fed
stats SHALL keep their own degradation.
<!-- implements FR13 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: No daemon yet
- **WHEN** no snapshot has been written and the board loads
- **THEN** the status card says the daemon has not run here and shows the WIP
  stat, without slot or failure stats

#### Scenario: Board never loaded
- **WHEN** no board fetch has succeeded
- **THEN** the status card shows no WIP stat and no placeholder for it

### Requirement: A watch render loop that keeps dying is disabled
The watch render loop SHALL be respawned when its thread dies, and SHALL be
disabled after more than 5 deaths within 10 minutes with one ERROR line.
A standalone `gnomish dashboard --watch` whose loop is disabled SHALL exit
with status 1. Data-source failures SHALL NOT count as deaths.
<!-- implements FR3, FR9, UX4 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Standalone renderer gives up
- **WHEN** the standalone watch loop's thread dies a sixth time within 10 minutes
- **THEN** one ERROR says the dashboard is disabled and the process exits 1

#### Scenario: Tracker outage is not a death
- **WHEN** the tracker is unreachable for an hour during `--watch`
- **THEN** the loop keeps rendering with the cached board and is never disabled
