# serve-observability — delta

## ADDED Requirements

### Requirement: Sweeper vitals in the snapshot
The snapshot's vitals SHALL carry a sweeper entry: the last tick's completion time, its per-category verdict counts (checked-alive, kept-under-threshold, stopped-orphan, disposed-aged, disposed-reconstructible, skipped-no-verdict), and an inventory of kept environments — each with task key, age, and time remaining until the reap threshold. The inventory SHALL be bounded in size, truncation stated in the snapshot.
<!-- implements NFR-O1 of add-serve-sandbox-lifecycle -->

#### Scenario: Kept inventory shows what waits for resume
- **WHEN** two escalated tasks left kept environments 2 and 6 days old under a 7-day threshold
- **THEN** the snapshot lists both with their ages and 5-day and 1-day remaining margins

#### Scenario: Skip is visible as its own count
- **WHEN** the last tick could not obtain claim verdicts
- **THEN** the sweeper vitals show the skipped-no-verdict count — distinguishable from a tick where everything was checked alive

### Requirement: Sweep actions in the ledger
Every stop and dispose performed by the sweep or reaper SHALL append one ledger line carrying object name, role, ownership mode, task key, verdict category, reason, and age. Each tick SHALL append one summary line with per-category counts. Untouched objects SHALL never be itemized in the ledger. Sweep lines follow the ledger's existing rotation and retention.
<!-- implements NFR-O2 of add-serve-sandbox-lifecycle -->

#### Scenario: History answers "what was cleaned yesterday"
- **WHEN** the operator reads yesterday's ledger file
- **THEN** every disposed and stopped object of that day appears with its task, reason, and age, and tick summaries carry the untouched counts

#### Scenario: Hourly ticks do not flood the ledger
- **WHEN** ticks find nothing actionable for a day
- **THEN** the ledger gains only per-tick summary lines, no per-object entries
