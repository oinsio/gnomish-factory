# Gaps: observing and accounting

Entries of [`../gaps.md`](../gaps.md) about what the factory shows of itself and what it
counts. The form and the vocabulary are in the index.

### GP-NO-PROJECT-SPEND-VIEW — Spend is counted per daemon instance, never per project
**Breaks:** `UC-WATCH-THE-PROJECT-SPEND`
**Where today:** `observability/serve-observability`: Files live in a per-instance-name directory stable across restarts (one ledger per `serve` instance on one machine); `observability/dashboard-page`: History section aggregates recent ledger files (the last seven days of that one ledger, in tokens, never in money); a `take` run or a manual run writes no ledger and leaves only its summary log line and its tracker report; nothing merges ledgers across machines, and CI minutes are counted nowhere
**Kind:** `missing`
**Resolution:** `open`
