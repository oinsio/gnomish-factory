# Gaps: filing and claiming

Entries of [`../gaps.md`](../gaps.md) about a task in the tracker before and between its
claims. The form and the vocabulary are in the index.

### GP-NO-PRECLAIM-TEXT-CHECK — An ambiguous task text costs a round before anyone asks
**Breaks:** `UC-FILE-TASK`
**Where today:** `tracker/tracker-take`: Snapshot at first claim; the project's conventions are enforced only by the gnome's round
**Kind:** `missing`
**Resolution:** `open`

### GP-READY-APPLIER-UNRECORDED — The factory never reads who applied the ready label
**Breaks:** `UC-KEEP-READY-GRANT-TRUSTED`
**Where today:** nothing; `operator-guide-autonomy-gate.md` is guidance only
**Kind:** `missing`
**Resolution:** `open`

### GP-NO-TASK-TYPE — A task cannot choose its pipeline
**Breaks:** `UC-STEER-TASK`
**Where today:** `git/base-ref-resolution` (the base only); `tracker/tracker-port` (designators)
**Kind:** `missing`
**Resolution:** `planned: add-pipeline-routing`

### GP-NO-IMMEDIATE-RETURN — A task given back on purpose waits out the lease
**Breaks:** `UC-STOP-AND-RESTART-WITHOUT-DEBT`, `UC-RIDE-OUT-AN-OUTAGE`
**Where today:** `serve/factory-serve`: SIGTERM stops cleanly within grace promises "an instant return to Ready with no TTL wait", while the port's release verb drops the claim and moves nothing (a documented no-op on the GitHub adapter, pinned by its release contract); `tracker/tracker-take`: Base infrastructure failure releases the claim outside the abort accounting (the same release); the task returns only when a reaper finds the claim stale
**Kind:** `contradiction` (the main spec's scenario and the port's release contract promise opposite things; the scenario's test is a pending feature naming finding F97 and the planned change)
**Resolution:** `planned: add-claim-return` (a fenced return verb for the deliberate callers: shutdown, pre-work outage, usage error)
