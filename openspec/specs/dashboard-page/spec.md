# dashboard-page

## Purpose

Define the `gnomish dashboard` command: a single self-contained HTML file
that composes the serve daemon **snapshot**, the ledger **history**, and the
tracker **board** into one operator wall view. The page inlines all data,
styles, and scripts, references no external resources, and degrades each
section independently. It runs one-shot by default or in `--watch` mode,
re-rendering on a cadence with a baked meta-refresh and an inline
self-staleness banner — so an operator gets an at-a-glance view of factory
health from a `file://` tab with no server and no extra tracker calls.

## Requirements

### Requirement: Dashboard command renders a single HTML file
`gnomish dashboard` SHALL be a subcommand that renders one HTML file. It
SHALL resolve configuration (tracker, instance name, backoff parameters)
from `--dir <clone>` exactly as `gnomish board` and `gnomish status` do.
The default output path SHALL be `dashboard.html` in the instance's
observability directory (keyed by the configured instance name); `--out`
SHALL override it.
<!-- implements FR1 of add-dashboard-page -->

#### Scenario: Default output lands beside the observability files
- **WHEN** `gnomish dashboard` runs without `--out`
- **THEN** the page is written to `~/.gnomish/serve/<instance-name>/dashboard.html`

#### Scenario: Explicit output path
- **WHEN** `gnomish dashboard --out incident.html` runs
- **THEN** the page is written to the given path

### Requirement: Page is fully self-contained
The generated page SHALL inline all data, styles, and scripts and SHALL
reference no external resources; opening it from `file://` SHALL trigger
zero network requests. The page SHALL contain no credentials, prompts,
or task content beyond what the composed surfaces already expose (ids,
titles, states, counters, timestamps, token counts).
<!-- implements FR2, NFR-S1 of add-dashboard-page -->

#### Scenario: No external references in the output
- **WHEN** the generated HTML is scanned for `http://`, `https://`, and
  protocol-relative resource URLs
- **THEN** none are found

#### Scenario: Portable one-shot file
- **WHEN** the one-shot output file is copied to another machine and opened
- **THEN** it renders identically with no companion files or network

#### Scenario: Content stays within the composed surfaces
- **WHEN** the rendered page is inspected
- **THEN** it contains only data present in the snapshot, the ledger
  aggregate, and the board model — no credentials, prompts, or stage
  artifacts

### Requirement: Three sections degrade independently
The page SHALL contain a daemon section (snapshot), a history section
(ledger aggregate), and a tracker board section. A missing snapshot SHALL
render as "daemon has not run here"; an absent or unreadable ledger SHALL
render an empty history; on tracker failure the board section SHALL keep
the last cached board model, marked with its fetch time and a
refresh-failure notice, or render as unavailable with the failure
summarized when no fetch has succeeded. A degraded section SHALL NOT
fail the rendering of the others.
<!-- implements FR3 of add-dashboard-page -->

#### Scenario: Tracker outage degrades only the board
- **WHEN** the tracker is unreachable at render time and no board fetch
  has succeeded yet
- **THEN** the board section shows "unavailable" with the failure, while
  the daemon and history sections render normally

#### Scenario: Fresh install renders a page
- **WHEN** no snapshot and no ledger files exist and the tracker is
  reachable
- **THEN** the page renders with "daemon has not run here", an empty
  history, and a populated board

### Requirement: Daemon section computes staleness and flags alert conditions
The daemon section SHALL compute snapshot staleness from the snapshot's
own `writtenAt` and `intervalSeconds` and SHALL visually flag the
operator-guide alert conditions observable from a single snapshot —
rules 1–5 of the guide's six: stale snapshot in any non-`stopped`
lifecycle state (daemon dead), occupied slots with heartbeat not
`running`, long `idleBlocked`, growing tracker `consecutiveFailures`,
stale reaper `lastRunAt` or growing `restartCount`. Rule 6 (`heldClaims`
vs slot-count desync on two consecutive checks) needs check-to-check
history and stays with the external dead-man's-switch monitor.
<!-- implements FR4 of add-dashboard-page -->

#### Scenario: Dead daemon reddens the section
- **WHEN** the snapshot's age exceeds `k × intervalSeconds` and its last
  `lifecycle.state` is not `stopped` (`running`, `draining`, or
  `stopping`)
- **THEN** the daemon section is flagged as "daemon dead" on the page

#### Scenario: Clean stop is not an alert
- **WHEN** the snapshot is stale and its last `lifecycle.state` is `stopped`
- **THEN** the section shows the stopped state and reason without a
  dead-daemon flag

### Requirement: Board section mirrors the CLI board via the same composition
The board section SHALL present Ready (queue order, the full eligibility
annotation — backoff with deadline, `finished`, WIP-held — and the
truncation marker), Working (holder, claim age), and AwaitingHuman (park
reason) with the same semantics as `gnomish board`, produced by the same
in-process composition and eligibility policy — not by shelling out and
not by a reimplementation.
<!-- implements FR5 of add-dashboard-page -->

#### Scenario: Backoff annotation matches the board command
- **WHEN** a ready task is in backoff at render time
- **THEN** the page's Ready row shows the same backoff deadline that
  `gnomish board` would show for it

### Requirement: History section aggregates recent ledger files
The history section SHALL aggregate the last 7 days of ledger files
(bounded by what retention left) into outcomes per day and tokens by
model, rendered as tables with CSS bars.
The reader SHALL tolerate a torn or unparseable ledger line by skipping
it and processing the rest.
<!-- implements FR6 of add-dashboard-page -->

#### Scenario: Overnight totals at a glance
- **WHEN** last night's ledger files contain taskOutcome lines
- **THEN** the history section shows per-day outcome counts and summed
  tokens by model covering them

#### Scenario: Torn last line is skipped
- **WHEN** the newest ledger file ends in an incomplete line
- **THEN** the history section renders from all complete lines

### Requirement: One-shot by default, watch mode re-renders on a cadence
Without `--watch` the command SHALL render once and exit. With `--watch`
it SHALL re-render the file on a fixed cadence (default 10 s) and bake a
matching `<meta http-equiv="refresh">` into the page so a `file://`
browser tab reloads itself.
<!-- implements FR7 of add-dashboard-page -->

#### Scenario: One-shot exits after a single render
- **WHEN** `gnomish dashboard` runs without `--watch`
- **THEN** exactly one page is written and the process exits zero

#### Scenario: Watch page reloads itself
- **WHEN** the `--watch` output is open in a browser tab
- **THEN** the baked meta-refresh reloads the file on the render cadence
  with no server involved

### Requirement: Watch page detects its own staleness with an inline script
A `--watch`-rendered page SHALL bake `generatedAt` and its refresh
cadence, and an inline script SHALL compare `generatedAt` against the
browser clock on each load; when the page's age exceeds `k = 3` times
the render cadence, a full-page banner SHALL declare the view stale.
Meta-refresh SHALL NOT be the mechanism for this detection. A one-shot
page SHALL display its `generatedAt` age as plain information and SHALL
NOT render the banner.
<!-- implements FR8 of add-dashboard-page -->

#### Scenario: Dead renderer banners the page
- **WHEN** the `--watch` process dies while the tab keeps meta-refreshing
  the last written file
- **THEN** once the page age exceeds the staleness multiple, the banner
  covers the page

#### Scenario: Fresh page shows no banner
- **WHEN** the page is reloaded within the staleness window
- **THEN** no banner is shown

#### Scenario: One-shot snapshot never banners
- **WHEN** a one-shot page is opened long after it was generated
- **THEN** the data and the page's `generatedAt` age are shown, with no
  full-page banner

### Requirement: Watch cadences split local and tracker sources
In `--watch` mode the snapshot and ledger SHALL be re-read on every
render cycle; the tracker board SHALL be refreshed on its own slower
cadence (default 60 s) with the last board model cached between board
refreshes. The board section SHALL display when its data was fetched.
<!-- implements FR9, NFR-P1 of add-dashboard-page -->

#### Scenario: Render cycles between board refreshes reuse the cache
- **WHEN** a render cycle fires before the board cadence has elapsed
- **THEN** the page is rebuilt with fresh snapshot/ledger data and the
  cached board model, whose fetch time is shown

#### Scenario: Tracker reads bounded by the board cadence
- **WHEN** `--watch` runs for an hour with default cadences
- **THEN** at most one pair of list calls (`listReady` + `listOpen`)
  occurs per board interval, not one per render cycle

### Requirement: Rows are pointers and the page targets one screen
Task rows SHALL carry only id, title, and the columns' own fields — no
embedded per-task reports; per-task depth stays with `gnomish status
<id>` and the tracker. The page SHALL target a single screen at typical
queue sizes.
<!-- implements FR10 of add-dashboard-page -->

#### Scenario: Slot entry stays a pointer
- **WHEN** the operator needs detail on a task shown in a slot row
- **THEN** the page offers the task id to feed the existing canon, not
  stage artifacts or logs

### Requirement: Watch loop survives data-source failures
In `--watch` mode any data-source failure — tracker outage, missing or
torn files — SHALL degrade the affected section (the board keeping its
cached model where one exists) and the loop SHALL continue rendering;
transient failures SHALL NOT terminate the process.
<!-- implements NFR-R1 of add-dashboard-page -->

#### Scenario: Outage during the wall display
- **WHEN** the tracker becomes unreachable while `--watch` runs
- **THEN** subsequent renders keep the last fetched board, marked with
  its fetch time and a refresh-failure notice, and the loop keeps
  running; when the tracker recovers, the next board refresh replaces
  the cached model

### Requirement: Output file is replaced atomically
Every render SHALL write the page to a temp file and rename it into
place, so a concurrent browser reload observes either the previous or
the new complete page.
<!-- implements NFR-R2 of add-dashboard-page -->

#### Scenario: Reload during a render
- **WHEN** the browser reloads while a render is in progress
- **THEN** it reads a complete page, never a truncated one

### Requirement: Every section carries its data timestamp
Each section SHALL display the timestamp of its underlying data —
snapshot `writtenAt`, board fetch time, ledger day range — and the page
SHALL display its `generatedAt`.
<!-- implements NFR-O1 of add-dashboard-page -->

#### Scenario: Observation times are explicit
- **WHEN** the operator reads any section
- **THEN** the age of that section's data is determinable from the page
  alone
