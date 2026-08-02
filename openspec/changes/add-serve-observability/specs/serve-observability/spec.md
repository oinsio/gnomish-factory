# serve-observability — delta spec

## ADDED Requirements

### Requirement: Snapshot file is written atomically on a timer and immediately on transitions
The serve daemon SHALL write a snapshot file on a configurable interval and
additionally immediately on state transitions (lifecycle, feed state, slot
assign/release, heartbeat state). Transitions set a dirty flag and wake the
single writer thread; only the writer writes. Every write SHALL be atomic:
temp file + rename in the target directory.
<!-- implements FR1 of add-serve-observability -->

#### Scenario: Timer beat
- **WHEN** the interval elapses with no transitions
- **THEN** the writer overwrites the snapshot with fresh `writtenAt`

#### Scenario: Transition triggers an immediate write
- **WHEN** a slot is assigned between two timer beats
- **THEN** a snapshot reflecting the new slot entry is written without waiting
  for the next beat

#### Scenario: Reader never observes a partial file
- **WHEN** a reader opens the snapshot concurrently with a write
- **THEN** it sees either the previous or the new complete document, never a
  truncated one

### Requirement: Snapshot is self-describing for staleness
The snapshot SHALL carry `version: 1`, `writtenAt`, and `intervalSeconds` so
that a reader computes staleness as `now − writtenAt > k × intervalSeconds`
without access to the daemon configuration.
<!-- implements FR2 of add-serve-observability -->

#### Scenario: Staleness computed from the file alone
- **WHEN** a monitor reads only the snapshot file
- **THEN** it can decide "stale" using `writtenAt` and `intervalSeconds`

### Requirement: Snapshot content is limited to instance, lifecycle, feed, slots, vitals, tracker
The snapshot SHALL contain the top-level scalars `version`, `writtenAt`,
`intervalSeconds` and exactly the sections `instance` (full instance id,
host, factory version), `lifecycle`, `feed`, `slots`, `vitals`, `tracker`.
It SHALL NOT contain ready-queue depth, per-task detail, or configuration
beyond `wipLimit`.
<!-- implements FR3 of add-serve-observability -->

#### Scenario: Pointers instead of payloads
- **WHEN** an operator needs per-task detail for a slot entry
- **THEN** the snapshot offers only the task id (canon: `gnomish status <id>`
  / the task branch), not stage artifacts or logs

### Requirement: Lifecycle state and final stopped record
`lifecycle.state` SHALL be one of `running | draining | stopping | stopped`.
On graceful exit the daemon SHALL write a final snapshot with `stopped` and a
reason, and SHALL NOT delete the file.
<!-- implements FR4 of add-serve-observability -->

#### Scenario: Graceful stop leaves a stopped snapshot
- **WHEN** the daemon exits via SIGTERM or drain completion
- **THEN** the last snapshot on disk has `lifecycle.state: stopped` with a
  reason

#### Scenario: Crash is distinguishable from stop
- **WHEN** the snapshot is stale
- **THEN** last state `running` means the daemon died, last state `stopped`
  means it exited cleanly

### Requirement: Feed section
`feed` SHALL expose the feed automaton state as `feed.state`, serialized as
`filling | idleEmpty | idleBlocked | full` (factory-serve's Filling /
Idle-empty / Idle-blocked / Full), plus `since` (state entry time),
`lastPollAt`, `openFronts`, and `wipLimit`.
<!-- implements FR5 of add-serve-observability -->

#### Scenario: Full state without poll freshness
- **WHEN** the feed is in Full
- **THEN** the snapshot shows `feed.state` with `since`, and an old
  `lastPollAt` is not an anomaly

### Requirement: Slots section lists occupied slots plus capacity
`slots` SHALL expose `capacity` and one entry per occupied slot with
`taskId`, `stage`, `attempt`, `since`; free slots have no entries. `stage`
and `attempt` are refreshed via the runner's progress path and MAY lag up to
`intervalSeconds`; entry presence (occupancy) never lags — slot
assign/release are immediate-write triggers.
<!-- implements FR6 of add-serve-observability -->

#### Scenario: Occupancy at a glance
- **WHEN** 2 of 3 slots run tasks
- **THEN** the snapshot shows `capacity: 3` and two entries; load =
  `entries.length / capacity`

### Requirement: Vitals cover heartbeat, reaper, and janitor
`vitals.heartbeat` SHALL be `state: idle | running | died` with `lastTickAt`
and `heldClaims`; `vitals.reaper` SHALL carry `lastRunAt` and
`restartCount`; `vitals.janitor` SHALL carry `lastRunAt`. The feed and the
writer SHALL NOT have vitals entries (feed health lives in `feed`;
`writtenAt` is the writer's pulse).
<!-- implements FR7 of add-serve-observability -->

#### Scenario: Heartbeat death is a field
- **WHEN** the heartbeat worker thread dies abnormally and its death handler
  fires
- **THEN** an immediate snapshot write records `vitals.heartbeat.state: died`

#### Scenario: Reaper degradation is a field
- **WHEN** the standing reaper thread dies and is respawned by its supervisor
- **THEN** a subsequent snapshot shows a grown `vitals.reaper.restartCount`

### Requirement: Tracker section exposes outage at a glance
`tracker` SHALL expose `lastSuccessAt` and `consecutiveFailures`, updated on
every tracker-port call by any daemon caller (feed, heartbeat, reaper) — an
outage stays visible while a saturated feed is not polling.
<!-- implements FR8 of add-serve-observability -->

#### Scenario: Outage visible without logs
- **WHEN** tracker calls have failed repeatedly
- **THEN** `consecutiveFailures` grows and `lastSuccessAt` stops advancing

### Requirement: Files live in a per-instance-name directory stable across restarts
Observability files SHALL live in `~/.gnomish/serve/<instance-name>/` —
the snapshot at `snapshot.json`, ledgers under their daily names — keyed
by the configured instance name. The full instance id (with per-process
suffix) SHALL appear inside the data, never in the path. Two daemons sharing
one configured name on a host is a documented misconfiguration; no locking.
<!-- implements FR9 of add-serve-observability -->

#### Scenario: Restart keeps the paths
- **WHEN** the daemon restarts (new instance-id suffix)
- **THEN** snapshot and ledger paths are unchanged and records carry the new
  full id

### Requirement: Ledger is typed append-only JSONL with status-report conventions
Each ledger line SHALL carry `version: 1`, a `type` discriminator, camelCase
fields, ISO-8601 UTC timestamps, and the instance identity (full id, host,
factory version).
<!-- implements FR10 of add-serve-observability -->

#### Scenario: Line self-identifies its writer
- **WHEN** a collector merges ledgers from several hosts
- **THEN** every line is attributable without path context

### Requirement: taskOutcome lines for terminal results carrying a final state
A `taskOutcome` line SHALL be appended for each terminal slot result with a
final state — `outcome` ∈ `delivered | awaitingHuman | aborted | revoked`,
`parkReason` only with `awaitingHuman` — with `taskId`, `stage` (null at
pipeline end), `attemptsUsed`, `startedAt`, `finishedAt`, `wallMillis`,
`tokensByModel`. `EmptyQueue` and `Skipped` results SHALL NOT produce lines.
<!-- implements FR11 of add-serve-observability -->

#### Scenario: Delivered task leaves one line
- **WHEN** a slot delivers a task
- **THEN** one `taskOutcome` line with `outcome: delivered` and its token
  totals is appended

#### Scenario: Lost claim race leaves no line
- **WHEN** a slot ends with `Skipped`
- **THEN** no ledger line is written (no engine run — no spend)

### Requirement: Lifecycle lines record starts and stops
The ledger SHALL record `lifecycle` lines `started` and `stopped` (with a
reason). The `stopped` line SHALL NOT carry run totals.
<!-- implements FR12 of add-serve-observability -->

#### Scenario: Crash loop visible in history
- **WHEN** the daemon restarts repeatedly on an empty queue
- **THEN** the ledger shows the `started` sequence even though no
  `taskOutcome` lines exist

### Requirement: runSummary only for drain runs
A drain run SHALL append one `runSummary` line (counters by outcome, summed
`tokensByModel`, duration), aggregated in memory at the `taskOutcome` write
point — never by reading the ledger back. Standing mode SHALL NOT write
`runSummary` on any stop; readers aggregate `taskOutcome` lines themselves.
<!-- implements FR13 of add-serve-observability -->

#### Scenario: Drain boundary gets a summary
- **WHEN** a `--drain` run finishes its last slot
- **THEN** exactly one `runSummary` line follows the outcome lines

### Requirement: Daily UTC rotation without renames
Ledger files SHALL be named `ledger-YYYY-MM-DD.jsonl` with the day boundary
in UTC. A live file SHALL never be renamed — rotation is the appender
switching to the new day's name.
<!-- implements FR14 of add-serve-observability -->

#### Scenario: Overnight history by filename
- **WHEN** an operator asks "what happened last night"
- **THEN** the answer is 1–2 files identifiable by name

### Requirement: Retention sweep is the snapshot writer's duty
On its tick the snapshot writer SHALL delete ledger files older than the
configured retention in days; `0` SHALL mean keep forever.
<!-- implements FR15 of add-serve-observability -->

#### Scenario: Old ledgers expire
- **WHEN** retention is N days and older files exist
- **THEN** a subsequent writer tick removes them

### Requirement: Observability failures never harm task work
Snapshot or ledger write failures SHALL NOT crash the daemon, fail a task, or
burn a stage attempt — the daemon logs and continues.
<!-- implements NFR-R1 of add-serve-observability -->

#### Scenario: Disk full
- **WHEN** appending a ledger line throws an I/O error
- **THEN** the slot's task result is unaffected and the daemon keeps serving

### Requirement: Ledger is disposable, write-only history
The daemon SHALL never read the ledger back (no recovery, no compaction);
lines are flushed per line without fsync; readers MUST tolerate a torn last
line.
<!-- implements NFR-R2 of add-serve-observability -->

#### Scenario: Torn last line after a crash
- **WHEN** a reader hits an incomplete final line
- **THEN** it skips it and processes the rest

### Requirement: One appender serializes concurrent slot completions
All ledger writes SHALL go through one shared appender that appends and
flushes line-atomically under synchronization; no cross-process locking.
<!-- implements NFR-R3 of add-serve-observability -->

#### Scenario: Simultaneous finishes
- **WHEN** two slots finish at the same moment
- **THEN** the ledger contains two complete, uninterleaved lines

### Requirement: Observability adds no tracker calls and no AI tokens
Producing the snapshot and the ledger SHALL add zero tracker API calls and
zero AI tokens; the only added I/O is local file writes.
<!-- implements NFR-P1 of add-serve-observability -->

#### Scenario: Write economy unchanged
- **WHEN** the daemon serves the same task load with observability enabled
  and disabled
- **THEN** the steady-state tracker write count is identical

### Requirement: Snapshot and ledger carry no task content or credentials
Snapshot and ledger records SHALL contain only identifiers, states, counters,
timestamps, and token counts — no task content, prompts, or credentials.
<!-- implements NFR-S1 of add-serve-observability -->

#### Scenario: Field inventory stays leak-free
- **WHEN** the snapshot and ledger reference documents are reviewed field by
  field
- **THEN** every field is an identifier, state, counter, timestamp, or token
  count
