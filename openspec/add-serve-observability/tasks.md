# Tasks: add-serve-observability

Sequenced after `fix-reaper-idle-liveness` (standing supervised reaper
thread; the heartbeat stays claim-driven — its death handler produces the
`died` state consumed by FR7). That change lives on the
`enforce-finish-terminality` branch and must merge first. TDD per
`.claude/rules/testing.md`: failing Spock spec first for every task.

## 1. Contracts and configuration

- [ ] 1.1 Snapshot document model + Jackson mapper with reference JSON file and
  byte-identical serialization spec (status-report v1 conventions: `version: 1`,
  camelCase, ISO-8601 UTC) — sections instance/lifecycle/feed/slots/vitals/tracker
  (FR2, FR3, FR10 conventions)
- [ ] 1.2 Ledger record models (`taskOutcome`, `lifecycle`, `runSummary`) +
  mapper with reference JSONL lines and serialization spec (FR10–FR13 shapes;
  `parkReason` serializes `AwaitingHuman.reason` (`ParkReason`))
- [ ] 1.3 `ServeProperties`: `snapshotInterval` (default 30 s) and
  `ledgerRetentionDays` (default 30, `0` = forever) with binding spec (D10)
- [ ] 1.4 Observability path resolver: `~/.gnomish/serve/<instance-name>/`,
  name from `FactoryProperties.instanceName`, full id only in data (FR9)
- [ ] 1.5 Field-inventory spec over the snapshot and ledger reference
  documents: every field is an identifier, state, counter, timestamp, or
  token count — no task content, prompts, or credentials (NFR-S1)

## 2. State sources

- [ ] 2.1 `SlotLedger` tracks `since` per occupied slot; expose occupied
  entries `{taskId, since}` (FR6, feeds FR11 `startedAt`)
- [ ] 2.2 Slot entry enrichment: current `stage`/`attempt` via the runner's
  durable-progress hook; may lag one beat (FR6, D11)
- [ ] 2.3 Feed observability view: state (`filling|idleEmpty|idleBlocked|full`),
  `since`, `lastPollAt`, `openFronts`, `wipLimit` exposed from the feed
  automaton/cycle (FR5)
- [ ] 2.4 Tracker health counters: `lastSuccessAt` / `consecutiveFailures` via
  a `Tracker`-port decorator shared by feed, heartbeat, and reaper (FR8, D12)
- [ ] 2.5 Vitals sources: heartbeat state `idle|running|died` + `lastTickAt` +
  `heldClaims` from the claim-driven heartbeat (death handler → `died`);
  reaper `lastRunAt` + `restartCount` from the standing reaper; janitor
  `lastRunAt` (FR7)

## 3. Snapshot writer

- [ ] 3.1 Writer thread: timer beat + dirty-flag wake, single write point,
  atomic temp+rename (FR1); spec: reader never sees a partial file
- [ ] 3.2 Transition triggers: lifecycle, feed state, slot assign/release,
  heartbeat state set dirty and wake the writer (FR1); spec: transition write
  lands without waiting for the beat
- [ ] 3.3 `writtenAt` + `intervalSeconds` self-description; staleness
  computable from the file alone (FR2)
- [ ] 3.4 Lifecycle states `running|draining|stopping|stopped`; final
  `stopped` snapshot with reason on graceful exit, file retained (FR4)
- [ ] 3.5 Retention sweep on writer tick: delete `ledger-*.jsonl` older than
  N days, `0` = keep forever (FR15)
- [ ] 3.6 Failure isolation: write errors logged, never propagate to slots or
  the feed (NFR-R1)

## 4. Ledger appender

- [ ] 4.1 Shared appender: `synchronized` append+flush per line, no fsync,
  write-only (NFR-R2, NFR-R3); spec: concurrent appends never interleave
- [ ] 4.2 Daily UTC rotation by name switch, live file never renamed (FR14)
- [ ] 4.3 `taskOutcome` write point in `TakeSlotRunner` beside
  `drainReport.record()` — only `TakeResult` variants with `finalState`;
  `EmptyQueue`/`Skipped` write nothing (FR11)
- [ ] 4.4 `lifecycle` lines `started`/`stopped(reason)` from serve
  startup/shutdown (FR12)
- [ ] 4.5 `runSummary` on drain completion only; standing stop writes none;
  aggregates from the in-memory accumulator at the `taskOutcome` write point,
  never from ledger read-back (FR13, D6)
- [ ] 4.6 Reader tolerance spec: torn last line is skipped by a sample reader
  (NFR-R2 contract test)

## 5. Wiring

- [ ] 5.1 `ServeAssembly`/`ServeCommand`: construct writer + appender, start
  beside `WorktreeJanitor`, stop in `ServeShutdown` after the final `stopped`
  snapshot and `stopped` ledger line (FR1, FR4, FR12)
- [ ] 5.2 Serve integration spec: full daemon pass writes snapshot + ledger
  with consistent identity; restart keeps paths, new suffix in data (FR9, M3)
- [ ] 5.3 Write-economy spec: steady-state tracker interactions identical with
  observability enabled and disabled (NFR-P1, M4)

## 6. Documentation and verification

- [ ] 6.1 Operator guide: observability files section + external dead-man's-
  switch monitor with the six alert rules over snapshot fields (UX1–UX4, D9)
- [ ] 6.2 Traceability grep: every FR/NFR of the proposal has an implementing
  spec/test reference (grep-based per `.claude/rules/traceability.md`; the
  openspec CLI does not index root-level changes)
- [ ] 6.3 Coverage gate: JaCoCo + PIT green on new Java classes (100% target
  per testing rule; justify any exception)
