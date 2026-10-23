# Observing and accounting

Group 9 of [`../use-cases.md`](../use-cases.md): the events of looking at the factory rather
than of working a task: the morning check, a wall display, an alert in the night, an incident
read afterwards, the bill. The voices are the factory operator, the factory instance, the task
author, the reviewer, the repository owner, the security owner and the incident analyst; the
stories follow the form and rules of the index. Reading one task's attempts from its branch is
`UC-READ-THE-ATTEMPTS` (group 4), the verdicts `UC-READ-THE-VERDICTS` (group 5), the decisions
`UC-READ-EVERY-DECISION` (group 6) and a repair's log line `UC-READ-WHY-RECOVERY-ACTED` (group 8).

## Factory operator (`SH-FACTORY-OPERATOR`)

### UC-CHECK-THE-NIGHT-IN-ONE-LOOK — Learn what the night did from one page
**When** I sit down in the morning,
**I want** one page whose loudest element is the tasks waiting for a human, then whether the
daemon is alive, what is running, and what finished overnight with its outcome mix and tokens,
each block in its place whether it is empty or not,
**so that** the morning check is one look, never a log and a tracker search.

### UC-BE-WOKEN-WITHOUT-WATCHING — Be alerted by something outside the daemon
**When** the daemon dies, its claims die under it, or the tracker goes away in the night,
**I want** a snapshot that says its own freshness and a written set of rules a cron job can
evaluate from the snapshot and the board alone, with a dead-man's switch that fires when the
job itself stops running,
**so that** I am woken by an alert, never by a task author asking why nothing moved.

### UC-KEEP-A-WALL-DISPLAY — Leave a page on a screen and trust it
**When** I leave the dashboard open on a wall screen,
**I want** it re-rendered on a cadence onto one screen, to say loudly when its renderer has
gone silent, and to keep rendering the other sections when one data source fails,
**so that** a quiet page always means a quiet factory.

### UC-READ-A-QUIET-CONSOLE — See only what needs a look
**When** a dependency fails and keeps failing,
**I want** the console to carry the first failure, a counted roll-up, and one line when it
recovers, with one line per failure written by the layer that decided, and a warning only
where I should look,
**so that** a persistent warning means act and a flood never hides it.

## Another factory instance (`AC-FACTORY-INSTANCE`)

### UC-SAY-I-AM-ALIVE-OR-WHY-I-STOPPED — Report myself at no cost to the work
**When** I serve a queue,
**I want** to write my snapshot and my ledger with no tracker call and no token, to go on
working when a write fails, to survive the death of my own writer, and to leave a stopped
record with its reason as my last word,
**so that** observing me never costs a task, and my silence is never ambiguous.

## Task author (`SH-TASK-AUTHOR`)

### UC-SEE-WHERE-MY-TASK-STANDS — Learn why my task is not moving
**When** my task has been claimed and I look for it later,
**I want** the board to show its column, who holds it and for how long, and the one reason it
is not moving when it is not: in backoff, held by the limit, parked with its reason,
**so that** I never ask in the thread what the factory already knows.

## Reviewer of task output (`SH-REVIEWER`)

### UC-SEE-WHAT-THE-TASK-COST — Read the task's cost beside its diff
**When** I review a finished task,
**I want** its wall time and tokens per stage and per round, failed rounds included, on the
pull request and from the branch, with no number invented where none was reported,
**so that** I judge the change against what it cost without adding anything up.

## Repository owner (`SH-REPOSITORY-OWNER`)

### UC-WATCH-THE-PROJECT-SPEND — Watch what the factory spends on my project
**When** the factory has worked my project for a week from several machines and modes,
**I want** one view of the project's tokens by day and model, and of the CI minutes it used,
**so that** I can set a bound before a bill surprises me.

## Security owner (`SH-SECURITY-OWNER`)

### UC-SEE-WHAT-LEFT-THE-BOX — Read every request that crossed the boundary
**When** a task has run in a box,
**I want** a record of what left it, the denied requests and the allowed ones, with host and
path and never a body, readable from the task's report,
**so that** an exfiltration is a line I can find, not an absence I must prove.

### UC-RECORDS-HOLD-NO-SECRETS-AND-NO-FORGERY — Trust the records I read
**When** I read the log, the snapshot, the ledger or the console,
**I want** no secret, prompt or task content in any of them, and every text from the gnome,
a subprocess or the tracker rendered as one inert line,
**so that** a record never leaks and a forged line never passes for the factory's.

## Incident analyst (`SH-INCIDENT-ANALYST`)

### UC-FOLLOW-ONE-TASK-THROUGH-THE-LOG — Read one task's story with one search
**When** I search the log by one task's id,
**I want** the claim as the first line and the summary with outcome, attempts, time and tokens
as the last, and every line between them, helper threads and the reaper's included,
**so that** one search is the task's whole story in order.

### UC-KEY-ON-THE-CODE-NOT-THE-WORDS — Key my alerts and searches on a stable code
**When** I write an alert rule or a saved search on a warning,
**I want** every warning and error to open with a code that is minted once, never reused and
never spelled by hand, while its wording may change freely,
**so that** a reworded message never silences an alert.

## Coverage

The second column is the dry run of the `**Serves**:` lines: the capability and the
requirement headings that would carry the key. The third names entries of
[`../gaps.md`](../gaps.md).

| Story | Carried by today | Gaps |
|---|---|---|
| `UC-CHECK-THE-NIGHT-IN-ONE-LOOK` | `observability/dashboard-page`: Page presents priority layers with persistent blocks, Waiting-for-a-human block is the loudest element, Daemon section computes staleness and flags alert conditions, History section aggregates recent ledger files; `observability/tracker-board`: board subcommand surface | none |
| `UC-BE-WOKEN-WITHOUT-WATCHING` | `observability/serve-observability`: Snapshot is self-describing for staleness, Vitals cover heartbeat, reaper, and janitor, Tracker section exposes outage at a glance; `observability/tracker-board`: Operator guide covers the board; `operator-guide-observability.md` (the six rules and the dead-man's switch) | none |
| `UC-KEEP-A-WALL-DISPLAY` | `observability/dashboard-page`: One-shot by default, watch mode re-renders on a cadence, Watch page detects its own staleness with an inline script, Three sections degrade independently, Rows are pointers and the page targets one screen, Watch loop survives data-source failures | none |
| `UC-READ-A-QUIET-CONSOLE` | `observability/factory-logging`: Written level policy governs every emitter, Repeated failures log edges, not floods, No silent degradation | none |
| `UC-SAY-I-AM-ALIVE-OR-WHY-I-STOPPED` | `observability/serve-observability`: Observability adds no tracker calls and no AI tokens, Observability failures never harm task work, Snapshot writer survives the death of its thread, Lifecycle state and final stopped record, The final stopped snapshot is the last write across respawns | none |
| `UC-SEE-WHERE-MY-TASK-STANDS` | `observability/tracker-board`: Ready column with eligibility annotations, Working column with holder and claim freshness, AwaitingHuman column with park reasons; `cli/task-inspection`: External status reader; `tracker/github-tracker`: Structural comments carry coordination facts | none |
| `UC-SEE-WHAT-THE-TASK-COST` | `cli/task-inspection`: Usage report; `tracker/tracker-take`: Delivery posts a final report and ends the factory's involvement; `observability/status-report`: Optional usage; `reference-pipelines.md` (the `usage-report` stage) | `GP-DELIVERY-IS-A-GNOME-STAGE` |
| `UC-WATCH-THE-PROJECT-SPEND` | `observability/dashboard-page`: History section aggregates recent ledger files; `observability/serve-observability`: Ledger is typed append-only JSONL with status-report conventions | `GP-NO-PROJECT-SPEND-VIEW` |
| `UC-SEE-WHAT-LEFT-THE-BOX` | `sandbox/sandbox-egress`: Denials are captured as structured findings, Denial read-back is durable across processes, Denial loss is visible in the report | `GP-NO-EGRESS-ACCESS-LOG` |
| `UC-RECORDS-HOLD-NO-SECRETS-AND-NO-FORGERY` | `observability/serve-observability`: Snapshot and ledger carry no task content or credentials; `observability/factory-logging`: Untrusted text enters logs only sanitized; `cli/operator-console`: Human-readable console output is neutralized visibly | none |
| `UC-FOLLOW-ONE-TASK-THROUGH-THE-LOG` | `observability/factory-logging`: Lifecycle anchor lines, Canonical per-task summary line, Complete and leak-free MDC context | none |
| `UC-KEY-ON-THE-CODE-NOT-THE-WORDS` | `observability/factory-logging`: Operator lines carry a stable event identity, Every operator line is pinned by a spec | none |
