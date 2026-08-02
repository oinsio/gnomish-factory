# Proposal: add-serve-observability

## Why

`gnomish serve` is a long-running daemon whose only observability surface today is
the rolling log and per-task tracker reports. An operator cannot answer "is the
daemon alive and what are its slots doing?" without log archaeology, and "what
was completed and spent overnight?" without walking task branches one by one.
The tracker cannot serve as the daemon's dashboard: the GitHub write budget is
already tight (add-factory-serve NFR-P2) and NG3 forbids inbound HTTP. The
daemon must publish its state and history as local files.

## What Changes

- **ADDED**: the serve daemon writes two local files per instance:
  - a **snapshot** — an atomically overwritten JSON gauge file answering
    "alive? what are the slots doing?" (sections: instance, lifecycle, feed,
    slots, vitals, tracker);
  - a **ledger** — an append-only JSONL history of terminal task outcomes and
    daemon lifecycle events, with daily UTC rotation and retention sweep.
- **ADDED**: operator guide section describing an external dead-man's-switch
  monitor (cron over the snapshot file) with concrete alert rules.
- No tracker port, adapter, or CLI surface changes.

## Capabilities

### New Capabilities

- `serve-observability`: the daemon's file-based observability contract —
  snapshot content, write triggers and atomicity; ledger record types,
  rotation, retention, and concurrency; file locations and instance identity.

### Modified Capabilities

_None._ Serve wiring changes internally, but no existing spec-level
requirement of `factory-serve` changes; all new requirements live in
`serve-observability`.

## Goals

- **G1**: "Alive? What are the slots doing?" is answerable from one local file,
  without access to daemon config or logs.
- **G2**: "What ran and what was spent overnight?" is answerable by aggregating
  1–2 local history files — no new data collection, only aggregation of
  already-produced per-task data.
- **G3**: The files are a fleet-ready contract: an external collector
  (node_exporter textfile / vector / OTel filelog) can consume them without any
  daemon change; the daemon never learns about the fleet.
- **G4**: Zero added tracker writes and zero inbound HTTP.

## Non-Goals

- **NG1**: No metrics SDK (Micrometer / OTel), tag system, or "where to send"
  config inside the daemon — the daemon owns only the "what"; every "where" is
  the collector's deployment concern.
- **NG2**: No fleet aggregation inside the factory — the fleet's shared plane
  is the tracker.
- **NG3**: No heartbeat issue in the tracker (write budget, operational noise).
- **NG4**: No admin socket / SIGUSR1 dump — the snapshot covers it more simply.
- **NG5**: No since-start counters in the snapshot and no run totals in the
  `stopped` record — cumulative history is the ledger reader's job.
- **NG6**: No alerting inside the daemon — alerting is an external monitor over
  the snapshot; this change only documents it.
- **NG7**: Tracker board command (`gnomish board`) and the HTML dashboard —
  separate changes (`add-board-command`, `add-dashboard-page`).

## Users & Scenarios

- **U1** — operator, morning check: opens the snapshot (path predictable from
  instance name), sees lifecycle state, slot occupancy, feed state, tracker
  health at a glance.
- **U2** — operator, overnight audit: aggregates the last ledger file(s) for
  outcomes and tokens by model.
- **U3** — external monitor: a cron script evaluates snapshot invariants and
  emits an outbound ping (healthchecks style); missed ping = alert.
- **U4** — future fleet collector: tails the same files on each host; instance
  identity is inside the data.

## Requirements

### Functional

- **FR1**: The serve daemon SHALL write a snapshot file on a configurable
  interval, atomically (temp file + rename), and SHALL additionally write
  immediately on state transitions (lifecycle, feed state, slot assign/release,
  heartbeat state) via a dirty-flag wake of the single writer thread — two
  trigger points, one write point.
- **FR2**: The snapshot SHALL be self-describing: it carries `version: 1`,
  `writtenAt`, and `intervalSeconds`, so a reader computes staleness as
  `now − writtenAt > k × intervalSeconds` without daemon config access.
- **FR3**: The snapshot SHALL contain the FR2 scalars plus sections `instance`
  (full instance id, host, factory version), `lifecycle`, `feed`, `slots`,
  `vitals`, `tracker` — and nothing else; per-task detail, queue depth, and
  history stay with their owners (task branch, tracker, ledger).
- **FR4**: `lifecycle.state` SHALL be one of `running | draining | stopping |
  stopped`; on graceful exit the daemon writes a final `stopped` snapshot with
  a reason and does not delete the file.
- **FR5**: `feed` SHALL expose the automaton state (serialized
  `filling | idleEmpty | idleBlocked | full`), `since`, `lastPollAt`,
  `openFronts`, and `wipLimit`; ready-queue depth SHALL NOT appear (not
  polled in Full — the field would lie).
- **FR6**: `slots` SHALL expose `capacity` and one entry per occupied slot:
  `taskId`, `stage`, `attempt`, `since` — pointers, not reports.
- **FR7**: `vitals` SHALL expose `heartbeat` (`idle | running | died`,
  `lastTickAt`, `heldClaims`), `reaper` (`lastRunAt`, `restartCount`), and
  `janitor` (`lastRunAt`); the feed and the snapshot writer have no vitals
  entries (the `feed` section and `writtenAt` are their pulses).
- **FR8**: `tracker` SHALL expose `lastSuccessAt` and `consecutiveFailures`,
  fed by every tracker-port caller (feed, heartbeat, reaper), so an outage
  stays visible while a saturated feed is not polling.
- **FR9**: Observability files (`snapshot.json`, the daily ledger files) SHALL
  live in a per-instance directory keyed by the configured instance *name*
  (stable across restarts); the full instance id (with the per-process suffix)
  appears inside the data, never in the path.
- **FR10**: The ledger SHALL be append-only JSONL: each line carries
  `version: 1`, a `type` discriminator, status-report v1 conventions
  (camelCase, ISO-8601 UTC), and the instance identity (full id, host, factory
  version).
- **FR11**: A `taskOutcome` line SHALL be written for every terminal slot
  result that carries a final state (`delivered | awaitingHuman | aborted |
  revoked`), with `taskId`, `outcome`, `parkReason` (awaitingHuman only),
  `stage` (nullable at pipeline end), `attemptsUsed`, `startedAt`,
  `finishedAt`, `wallMillis`, `tokensByModel`; `EmptyQueue`/`Skipped`
  results SHALL NOT produce lines.
- **FR12**: The ledger SHALL record `lifecycle` lines: `started`, and `stopped`
  with a reason and without totals — so a crash-loop on an empty queue is
  visible in history.
- **FR13**: A drain run SHALL append one `runSummary` line (outcome counters,
  summed `tokensByModel`, duration); standing mode SHALL NOT write
  `runSummary` on any stop.
- **FR14**: Ledger rotation SHALL be daily files `ledger-YYYY-MM-DD.jsonl` with
  the day boundary in UTC; a live file is never renamed — rotation is the
  writer switching to a new name.
- **FR15**: The snapshot writer thread SHALL sweep ledger files older than a
  configurable retention (in days; `0` = keep forever).

### Non-Functional — Reliability

- **NFR-R1**: Observability write failures SHALL never crash the daemon, fail
  a task, or burn a stage attempt — log and continue.
- **NFR-R2**: The ledger is disposable history: the daemon is write-only (no
  read-back, no compaction, no recovery on start), flushes per line without
  fsync; a torn last line is acceptable and readers MUST tolerate it.
- **NFR-R3**: Concurrent slot completions SHALL serialize through one shared
  appender (synchronized per-line append+flush); no cross-process locking —
  one daemon per directory follows from FR9.

### Non-Functional — Performance

- **NFR-P1**: Observability SHALL cost zero additional tracker API calls and
  zero AI tokens; the only added I/O is local files.

### Non-Functional — Security

- **NFR-S1**: Snapshot and ledger SHALL contain no task content, prompts, or
  credentials — only identifiers, states, counters, timestamps, and token
  counts.

## Operator Experience Criteria

- **UX1**: One `cat`/`jq` of a predictable path answers "alive? busy?"; the
  path survives daemon restarts.
- **UX2**: "Last night" is 1–2 ledger files identifiable by filename.
- **UX3**: The operator guide documents the external monitor pattern with
  concrete rules over snapshot fields (dead daemon, dying claims under a live
  daemon, stuck escalations, tracker outage, reaper degradation, slots/claims
  desync).
- **UX4**: Degradation is visible as data, not only as log lines: `died`
  heartbeat, `stopped` with reason, stale `writtenAt` semantics.

## Success Metrics

- **M1**: Each documented alert rule is computable from snapshot fields alone.
- **M2**: Overnight outcome/token totals are reproducible with `jq` over the
  daily ledger files, matching per-task status reports.
- **M3**: After a daemon restart, the snapshot path is unchanged and the ledger
  continues in the same directory (restarts distinguishable by instance id in
  the data).
- **M4**: Steady-state tracker write count is identical with and without
  observability enabled.

## Open Questions

- **Q1**: Default snapshot interval and staleness multiplier `k` for the
  documented monitor rules — fixed in design.
- **Q2**: Default ledger retention in days — fixed in design.

## Impact

- New code: snapshot writer thread + ledger appender under the serve/app layer;
  path resolution for `~/.gnomish/serve/<instance-name>/`.
- Touched: `SlotLedger` (track `since` per occupied slot), `TakeSlotRunner`
  (ledger write point beside `drainReport.record()`), a `Tracker`-port health
  decorator (FR8), `ServeCommand` / `ServeShutdown` (writer lifecycle),
  `ServeProperties` (interval, retention), operator guide.
- Dependencies: builds on `add-factory-serve` (archived 2026-08-02) and on
  `fix-reaper-idle-liveness` (on the unmerged `enforce-finish-terminality`
  branch — standing supervised reaper, claim-driven heartbeat whose death
  handler produces `died`); implement after that branch merges.
- No new libraries; no tracker port or adapter changes.
