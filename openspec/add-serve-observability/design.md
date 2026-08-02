# Design: add-serve-observability

## Context

The serve daemon must answer three operator questions with three owners
(proposal FR3): liveness/occupancy → instance, history → ledger, queue depth →
tracker (out of scope, `add-board-command`). Constraints: no inbound HTTP
(add-factory-serve NG3), tight tracker write budget (its NFR-P2), minimal
Spring (ADR 0001 — no actuator). Raw material already exists: per-task token
totals in status-report v1, `TakeResult` terminal variants recorded by
`DrainReport` in `TakeSlotRunner`, the claim-heartbeat state, the feed
automaton state. This change aggregates and publishes; it collects nothing new.
Depends on `fix-reaper-idle-liveness` (proposed on the
`enforce-finish-terminality` branch): it makes the reaper a standing
supervised thread while the heartbeat stays claim-driven; the heartbeat's
death handler produces the `died` vital.

## Goals / Non-Goals

Goals: file contract for FR1–FR15; single-instance now, fleet-consumable later.
Non-goals: NG1–NG7 of the proposal (no metrics SDK, no fleet logic, no
in-daemon alerting, board/dashboard are separate changes).

## Decisions

**D1 — Files are the contract; every "where" lives in an external collector.**
The daemon writes a snapshot (overwritten gauges) and a ledger (append-only
events); fleet extension = a collector (node_exporter textfile / vector / OTel
filelog / cron+scp) reading these files. Push vs pull is a collector deployment
choice, not a daemon concern; pull does not violate NG3 because any inbound
endpoint belongs to a sidecar. *Rationale:* fleet-ready costs only format
discipline (FR2, FR9, FR10), not daemon features. *Rejected:* Micrometer/OTel
SDK in-process (drags a tag system and delivery config into the daemon);
heartbeat issue in the tracker (write budget, noise); admin socket / SIGUSR1
dump (snapshot covers it more simply).

**D2 — Directory keyed by configured instance *name*; full id in the data.**
`~/.gnomish/serve/<instance-name>/` holds `snapshot.json` and the ledger files.
`InstanceId` = `<name>-<suffix>` with a per-process suffix (add-tracker-port
D6), so a path keyed by full id would move on every restart — the cron monitor
would go blind on the old file and the ledger would fragment across dirs.
*Rationale:* FR9; the stable half in the path, the unique half inside records —
restarts stay distinguishable by content. *Rejected:* path by full id (above);
flock against same-name daemons — two daemons with one configured name on a
host is documented misconfiguration (implements FR9).

**D3 — Snapshot answers only "alive?" and "busy?"; everything else is a
pointer to its canon.** Sections per FR3–FR8. Deliberate exclusions:
ready-queue depth (feed does not poll in Full — the field would lie), config
dump (only `wipLimit`, which the feed already holds for its own decisions),
per-task detail (canon = task branch / `gnomish status <id>`), since-start
counters (ledger territory). Vitals follow the thread inventory: heartbeat
(three-valued: `idle`/`running`/`died` — claim-heartbeat's designed
degradation death path, documented in its Risks section, becomes a field, not
just an ERROR line); reaper (`lastRunAt`, `restartCount` — after
`fix-reaper-idle-liveness` it is a standing supervised thread with unbounded
restarts whose only other surface is ERROR logs); janitor (`lastRunAt`). Feed
has no vitals entry — the `feed` section fully describes its health and is
only readable together with its state (`lastPollAt` freshness is mandatory in
Filling/Idle, meaningless in Full). The writer is its own vital: `writtenAt`
is its pulse. *Rejected:* timestamp-only heartbeat health (an idle heartbeat
looks dead); a `feed` vitals duplicate.

**D4 — One writer thread; timer beat plus immediate write on transitions via a
dirty flag.** Transitions (lifecycle, feed state, slot assign/release,
heartbeat state change) set a dirty flag and wake the writer; only the writer
writes — two trigger points, one write point (FR1). Immediate writes make the
final `stopped` snapshot and the `died` vital timely; the timer alone cannot.
Staleness math survives: `intervalSeconds` is the maximum gap, immediate
writes only tighten it (FR2). Atomicity = temp file + rename in the same
directory. The writer must be a new dedicated thread: the loops that do tick
unconditionally (the hourly `WorktreeJanitor`, the standing reaper on the
beat interval) have the wrong cadence and own other duties, and the feed
legally sleeps in Full without a timer.
*Rejected:* acting threads writing directly (races the atomic replace);
timer-only (late terminal records).

**D5 — Ledger semantics: disposable history, write-only.** Not a cache to
rebuild (canon per task is the state file on the mortal task branch) and not a
canon to protect. The daemon never reads it back, never compacts, never
recovers from it; no daemon decision depends on it; losing it is operationally
harmless. Crash consistency is minimal: flush per line, no fsync; a torn last
line is legal and readers MUST tolerate it (NFR-R2). *Rejected:* read-back /
recovery on start (imports history bugs into daemon behavior for zero value).

**D6 — Ledger vocabulary derives from `TakeResult`; a line is written only for
variants carrying a `finalState`.** `taskOutcome.outcome` ∈ `delivered |
awaitingHuman | aborted | revoked` (sealed-variant names; `parkReason` only
for `awaitingHuman`); all four carry a final state, so totals are derivable
the same way `StatusReport` builds them. `EmptyQueue`/`Skipped` produce no
line — rule: engine run happened ⇔ spend happened ⇔ line exists (FR11). Write
point: `TakeSlotRunner`, beside `drainReport.record()`. `SlotLedger` learns
each slot's `since`/`startedAt` (shared need of FR6 and FR11). `lifecycle`
lines `started`/`stopped(reason)` are included: the snapshot keeps only the
last state, and a silent crash-loop on an empty queue is otherwise invisible
in history (FR12). `runSummary` only for drain runs — a natural boundary;
standing-mode "uptime totals" are the since-start counters rejected in D3
(FR13). Its outcome counters and token sums accumulate in memory at the same
write point (beside `DrainReport`); the ledger is never read back to build it
(D5). *Rejected:* lines for `EmptyQueue`/`Skipped`; `runSummary` on
standing-mode stop.

**D7 — Rotation by name, retention as writer duty.** Daily files
`ledger-YYYY-MM-DD.jsonl`, UTC day boundary; a live file is never renamed —
rotation is the appender switching names, so external tails never chase
renames (FR14). Retention sweep ("delete ledgers older than N days", `0` =
keep forever) runs on the snapshot writer's tick: that thread already ticks
unconditionally, and the observability writer owns observability files (FR15).
*Rejected:* rename-based rotation (breaks tail -F semantics); giving the sweep
to `WorktreeJanitor` (worktree-specific by charter).

**D8 — Concurrency: one shared appender, `synchronized` append+flush per
line.** Slots finish concurrently; a single appender object serializes lines
(NFR-R3). No cross-process locking — one daemon per directory follows from D2.
*Rejected:* per-slot files (pushes merging onto every reader); a queue plus
drainer thread (an extra thread for a contention level `synchronized` handles).

**D9 — Alerting stays outside: dead man's switch over the snapshot.** The
operator guide documents a cron script that checks snapshot freshness and
invariants and sends an outbound ping (healthchecks style); a missed ping
alerts. Documented rules (UX3): stale `writtenAt` at `running` → daemon dead;
occupied slots with heartbeat not `running` → claims dying under a live
daemon; long `idleBlocked` → escalations not being handled; growing
`consecutiveFailures` → tracker outage; stale `reaper.lastRunAt` or growing
`restartCount` → reaping degraded, stale claims will linger; `heldClaims ≠
slots.entries.length` beyond seconds → desync. *Rationale:* outbound-only
respects NG3/G4. *Rejected:* alerting in the daemon (delivery config
in-process, NG6).

**D10 — Defaults (closes Q1, Q2).** Snapshot interval 30 s (matches the idle
poll cadence; cheap local write), documented staleness multiplier `k = 3`;
ledger retention 30 days, `0` = keep forever. Both in `ServeProperties`.

**D11 — Slot `stage`/`attempt` come from the runner's progress path and may
lag one beat.** The engine's existing durable-progress reporting (the hook
that already resets the abort counter) is where the slot registry learns
`stage`/`attempt`. Stage transitions are deliberately not immediate-write
triggers — FR1's trigger list is closed: no alert rule reads `stage`, and a
fast pipeline must not become a snapshot write storm. `stage`/`attempt` may
therefore lag up to `intervalSeconds`; occupancy itself never lags
(assign/release are triggers). *Rejected:* stage transitions as dirty-flag
triggers (write amplification for a purely informational field).

**D12 — Tracker health counters wrap the port, not the feed.**
`lastSuccessAt`/`consecutiveFailures` are updated by a thin decorator over
the `Tracker` port shared by every daemon caller — feed, heartbeat, reaper —
not at the feed's call boundary alone. In Full the feed legally stops
polling; heartbeat and reaper keep calling, so an outage under saturation
still moves the counters — exactly when D9's tracker rule must fire.
*Rejected:* feed-boundary counters (blind in Full, understating outages).

## Risks / Trade-offs

- Two same-named daemons on one host clobber each other's files → documented
  misconfiguration (D2); no locking by design.
- Torn last ledger line after a crash → contract obliges readers to skip it;
  per-line flush bounds loss to one line.
- Transition storms could hammer the snapshot → dirty-flag coalescing in the
  single writer bounds the write rate to one in-flight write at a time.
- `fix-reaper-idle-liveness` lives on an unmerged branch
  (`enforce-finish-terminality`) → this change is sequenced after that branch
  merges; until then the standing reaper (and its vitals) has no producer and
  `heldClaims` is not exposed.

## Migration Plan

Additive: new files, new config knobs with defaults, no format or CLI breaks.
Rollback = stop writing (files remain, readers see staleness). The snapshot and
ledger schemas follow the status-report v1 amendment policy: pre-release,
in-repo consumers only — amend in place with regenerated reference files.

## Open Questions

None — Q1/Q2 closed by D10.
