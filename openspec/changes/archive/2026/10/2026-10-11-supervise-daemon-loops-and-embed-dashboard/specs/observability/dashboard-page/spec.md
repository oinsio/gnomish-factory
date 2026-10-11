# Spec Delta

## MODIFIED Requirements

### Requirement: Three sections degrade independently
The page SHALL compose three independently degrading data sources — the
snapshot (status card), the ledger (outcomes-by-day and tokens blocks),
and the tracker board (waiting-for-a-human and in-progress blocks, and the
status card's WIP stat). A missing snapshot SHALL render as "daemon has not
run here"; an absent or unreadable ledger SHALL render the blocks'
empty-state sentences; on tracker failure the board-fed blocks SHALL keep
the last cached board model, marked with its fetch time and a
refresh-failure notice, or render as unavailable with the failure
summarized when no fetch has succeeded. A degraded source SHALL NOT fail
the rendering of the others.
<!-- implements FR1, FR2 of redesign-dashboard -->
<!-- implements FR13 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Tracker outage degrades only the board
- **WHEN** the tracker is unreachable at render time and no board fetch
  has succeeded
- **THEN** the waiting-for-a-human and in-progress blocks show
  "unavailable" with the failure summarized, while the status card and
  the ledger blocks render normally

#### Scenario: Fresh install renders a page
- **WHEN** no snapshot and no ledger files exist and the tracker is
  reachable
- **THEN** the page renders with "daemon has not run here", the ledger
  blocks' empty-state sentences, and populated board-fed blocks

### Requirement: Daemon section computes staleness and flags alert conditions
The snapshot's surface is the status card (the page's second priority
layer). A snapshot whose last `lifecycle.state` is `stopped` SHALL show the
stopped state and its reason at any age: `stopped` is terminal, so
staleness adds nothing to it, and its vitals, frozen at the stop, SHALL
raise none of the daemon alert rules below. For every other lifecycle
state the card SHALL compute snapshot staleness from the snapshot's own
`writtenAt` and `intervalSeconds` and SHALL flag the operator-guide alert
conditions observable from a single snapshot — rules 1–5 of the guide's
six: stale snapshot in any non-`stopped` lifecycle state (daemon dead),
occupied slots with heartbeat not `running`, long `idleBlocked`, growing
tracker `consecutiveFailures`, stale reaper `lastRunAt` or growing
`restartCount` — each triggered condition rendered as a short alarm-palette
line inside the card. The sandbox-hygiene alert conditions SHALL surface as
the same kind of alarm lines in this card, not in the sandbox-hygiene
block. Rule 6 (`heldClaims` vs slot-count desync on two consecutive
checks) needs check-to-check history and stays with the external
dead-man's-switch monitor.
<!-- implements FR1, FR2 of redesign-dashboard -->
<!-- implements FR11 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Dead daemon reddens the section
- **WHEN** the snapshot's age exceeds `k × intervalSeconds` and its last
  `lifecycle.state` is not `stopped` (`running`, `draining`, or
  `stopping`)
- **THEN** the status card carries a "daemon dead" alarm line

#### Scenario: Clean stop is not an alert
- **WHEN** the snapshot is stale and its last `lifecycle.state` is `stopped`
- **THEN** the card shows the stopped state and reason without a
  dead-daemon alarm line

#### Scenario: Fresh stopped snapshot shows the stopped state
- **WHEN** the snapshot is within `k × intervalSeconds` of its `writtenAt`
  and its last `lifecycle.state` is `stopped` — the page `serve` renders
  right after its final snapshot
- **THEN** the card shows the stopped state and reason with the stopped
  marker, never "running", and carries no daemon alarm line

#### Scenario: Hygiene alert surfaces in the status card
- **WHEN** a sandbox-hygiene alert condition triggers at render time
- **THEN** it renders as an alarm line in the status card, and the
  sandbox-hygiene block itself carries no alert styling

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
