# dashboard-page

## Purpose

Define the `gnomish dashboard` command: a single self-contained HTML file
that composes the serve daemon **snapshot**, the ledger **history**, and the
tracker **board** into one operator wall view, laid out as fixed-order
priority layers so the loudest problem is always the first thing seen. The
page inlines all data, and its styles and script ship as standalone resource
files inlined at render time; it references no external resources and
degrades each block independently. It runs one-shot by default or in
`--watch` mode, re-rendering on a cadence with a baked meta-refresh and a
persistent freshness strip that detects a dead renderer without a full-page
banner — so an operator gets an at-a-glance view of factory health from a
`file://` tab with no server and no extra tracker calls.

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

### Requirement: Styles and script ship as resources inlined at render time
The page's stylesheet and script SHALL be maintained as standalone resource
files and inlined into the output at render time; the rendered page SHALL
remain one self-contained HTML file. A `--watch` page keeps its 10-second
meta-refresh; a one-shot page carries none, and the page's mode is baked as
a `data-mode` attribute the static script reads. The renderer SHALL load
both resources once at construction and SHALL fail at construction when
either is missing or blank; inlining SHALL guard against content that would
terminate the inline `<script>` block early. Every such refusal SHALL name
the resource and what to do about it. The meta-refresh interval is
written in whole seconds, so the renderer SHALL reject a render cadence
shorter than one second rather than emit `content="0"`.
<!-- implements FR10, NFR-R1 of redesign-dashboard -->

#### Scenario: Sub-second cadence is rejected
- **WHEN** a render cadence shorter than one second is passed to the
  renderer
- **THEN** the render fails, rather than producing a page that reloads
  without pause

#### Scenario: Missing resource fails fast
- **WHEN** the stylesheet resource is absent from the classpath
- **THEN** constructing the renderer fails, naming the file to restore,
  rather than rendering an unstyled page

#### Scenario: Blank resource fails fast
- **WHEN** the stylesheet resource is present on the classpath but holds
  nothing but whitespace
- **THEN** constructing the renderer fails on the same terms — a present but
  empty resource reaches the very unstyled page the missing-resource guard
  exists to prevent

#### Scenario: Output stays a single file
- **WHEN** the rendered page is scanned for external stylesheet, script,
  font, or image references
- **THEN** none are found and the page renders fully styled over `file://`

### Requirement: Page presents priority layers with persistent blocks
The page SHALL present its content as fixed-order priority layers — a
freshness strip, a status line, a waiting-for-a-human block, an in-progress
block — followed by visually quieter reference blocks: outcomes by day,
tokens, and sandbox hygiene. No later layer may be visually louder than an
earlier one. Blocks SHALL NOT appear or disappear with data: each block
occupies the same position regardless of content and renders an explicit
empty-state sentence instead of an empty list or a missing section.
<!-- implements FR1, FR2 of redesign-dashboard -->

#### Scenario: Empty queue keeps its block
- **WHEN** no task is awaiting a human at render time
- **THEN** the waiting-for-a-human block still renders in its position with
  a count of 0 and an "all clear" sentence, not a blank region

#### Scenario: Every empty data source has a sentence
- **WHEN** the escalation queue, the board, the outcome history, and the
  sweep data are all empty or absent
- **THEN** each corresponding block renders its own explicit empty-state
  sentence, and no bare empty list is present in the page

### Requirement: Three sections degrade independently
The page SHALL compose three independently degrading data sources — the
snapshot (status card), the ledger (outcomes-by-day and tokens blocks),
and the tracker board (waiting-for-a-human and in-progress blocks). A
missing snapshot SHALL render as "daemon has not run here"; an absent or
unreadable ledger SHALL render the blocks' empty-state sentences; on
tracker failure the board-fed blocks SHALL keep the last cached board
model, marked with its fetch time and a refresh-failure notice, or render
as unavailable with the failure summarized when no fetch has succeeded. A
degraded source SHALL NOT fail the rendering of the others.
<!-- implements FR1, FR2 of redesign-dashboard -->

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
layer). It SHALL compute snapshot staleness from the snapshot's own
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

#### Scenario: Dead daemon reddens the section
- **WHEN** the snapshot's age exceeds `k × intervalSeconds` and its last
  `lifecycle.state` is not `stopped` (`running`, `draining`, or
  `stopping`)
- **THEN** the status card carries a "daemon dead" alarm line

#### Scenario: Clean stop is not an alert
- **WHEN** the snapshot is stale and its last `lifecycle.state` is `stopped`
- **THEN** the card shows the stopped state and reason without a
  dead-daemon alarm line

#### Scenario: Hygiene alert surfaces in the status card
- **WHEN** a sandbox-hygiene alert condition triggers at render time
- **THEN** it renders as an alarm line in the status card, and the
  sandbox-hygiene block itself carries no alert styling

### Requirement: Waiting-for-a-human block is the loudest element
When at least one task awaits a human, its block SHALL be the most visually
prominent element on the page (accent border and background), showing the
count and one row per task: an icon distinguishing the park reason category
(escalation / checkpoint / infra), the task id, a one-line reason truncated
with an ellipsis, and the escalation age. A per-task field the tracker does
not expose SHALL be dropped from the row, not replaced with a placeholder.
When empty, the block SHALL use ordinary card chrome with a calm all-clear
sentence and a check glyph.
<!-- implements FR4, UX1 of redesign-dashboard -->

#### Scenario: Escalated task dominates the page
- **WHEN** one task is awaiting a human
- **THEN** its block carries the accent treatment and shows the task's id,
  reason, and escalation age, and no other block is more visually prominent

#### Scenario: Missing field shortens the row
- **WHEN** the tracker exposes no escalation reason for a task
- **THEN** the row renders without a reason cell and contains no "n/a"
  placeholder

### Requirement: In-progress block is a single compact list
The in-progress block SHALL show ready and working items as one compact row
list built from the board model's own fields — status marker, task id, for
working rows the holding instance and claim age, for ready rows a short
eligibility note (backoff deadline, finished, WIP-held) — keeping Ready
and Working
items distinguishable without separate headings or separate empty states.
Its single empty state SHALL state that the slot is free and no ready tasks
exist.
<!-- implements FR5 of redesign-dashboard -->

#### Scenario: Ready and working share one list
- **WHEN** one task is working and one is ready
- **THEN** both render as rows of the same list, visually distinguishable,
  under a single heading

### Requirement: Board section mirrors the CLI board via the same composition
The tracker board SHALL feed the waiting-for-a-human and in-progress
blocks, produced by the same in-process composition and eligibility policy
as `gnomish board` — not by shelling out and not by a reimplementation.
In-progress rows SHALL present Working entries (holding instance, claim
age) and Ready entries in queue order with a short eligibility note
(backoff with deadline, `finished`, WIP-held); when the ready window was
capped, the block SHALL still indicate the truncation. AwaitingHuman
entries render in the waiting-for-a-human block with their park reason
category. The compact rows consciously drop the CLI board's full
annotation text, but the semantics of every field shown SHALL match what
`gnomish board` reports for the same task.
<!-- implements FR4, FR5 of redesign-dashboard -->

#### Scenario: Backoff annotation matches the board command
- **WHEN** a ready task is in backoff at render time
- **THEN** the in-progress row's eligibility note shows the same backoff
  deadline that `gnomish board` would show for it

#### Scenario: Capped ready window stays visible
- **WHEN** the ready window was truncated at the requested limit
- **THEN** the in-progress block indicates that more ready tasks exist
  beyond the listed rows

### Requirement: History section aggregates recent ledger files
The history section SHALL aggregate the last 7 days of ledger files
(bounded by what retention left) into outcomes per day and tokens by model.
Outcomes SHALL render as one full-width stacked bar per day showing the mix
of delivered / awaitingHuman / aborted / revoked proportionally within that
day, with the date and the day's absolute total as numbers beside the bar
and one shared legend for all days; bars SHALL NOT be scaled by daily
volume. Tokens SHALL render per model as a stacked bar of cacheRead /
cacheCreation / input / output with a caption leading with the integer
cache share; when cache reads and creation are both zero the caption SHALL
state that cache is not used instead of showing 0%. The reader SHALL
tolerate a torn or unparseable ledger line by skipping it and processing
the rest.
<!-- implements FR6, FR7 of redesign-dashboard -->

#### Scenario: Overnight totals at a glance
- **WHEN** last night's ledger files contain taskOutcome lines
- **THEN** the history blocks show per-day outcome-mix bars with totals and
  summed tokens by model covering them

#### Scenario: Mix comparable across unequal days
- **WHEN** one day recorded 2 outcomes and another 20
- **THEN** both days' bars span the same full width, their mixes directly
  comparable, and the absolute totals differ only in the numbers

#### Scenario: Model without cache
- **WHEN** a model's ledger lines carry zero cacheRead and cacheCreation
- **THEN** its caption reads that cache is not used, not "0%"

#### Scenario: Torn last line is skipped
- **WHEN** the newest ledger file ends in an incomplete line
- **THEN** the history blocks render from all complete lines

### Requirement: Numbers are formatted compactly server-side
Counts of 1000 and above SHALL be rendered in compact form computed
server-side at three significant digits with trailing zeros dropped
(`25.6K`, `4.79M`, `5M` — never `5.0M`), and the exact value placed in the
element's `title`. Counts below
1000 print as-is. Percentages SHALL be integers. Numeric cells SHALL use
tabular numerals so columns do not shift horizontally across refreshes. No
displayed value SHALL be computed only in client script.
<!-- implements FR9, NFR-R2, UX3 of redesign-dashboard -->

#### Scenario: Compact count with exact hover
- **WHEN** a token total of 4,790,000 is rendered
- **THEN** the cell shows `4.79M` and its `title` holds the exact value

#### Scenario: Trailing zero is dropped
- **WHEN** a count of exactly 5,000,000 is rendered
- **THEN** the cell shows `5M`, not `5.0M`

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
A `--watch`-rendered page SHALL bake `generatedAt` and its stale threshold
— three times the render cadence — as data attributes, and an inline script
SHALL re-evaluate the page's age against the browser clock every second;
when the age exceeds that threshold (30 seconds at the
fixed 10-second cadence), a persistent full-width freshness strip at
the top of the page SHALL turn to the alarm palette with an upward-counting
"renderer silent" duration, and the content cards SHALL dim while remaining
readable. While fresh, the strip SHALL show an upward-ticking "updated N s
ago" age. Staleness SHALL degrade the page, never cover or block it; no
full-viewport staleness banner SHALL exist. Meta-refresh SHALL NOT be the
mechanism for this detection. A one-shot page SHALL display its
`generatedAt` age as plain information and SHALL NOT render the stale
degradation.
<!-- implements FR3 of redesign-dashboard -->

#### Scenario: Dead renderer banners the page
- **WHEN** the `--watch` process dies while the tab stays open and even the
  meta-refresh fails to fire
- **THEN** within the staleness threshold the strip turns to the alarm
  palette without a page reload, its counter keeps climbing, and every card
  remains readable through the dimming

#### Scenario: Fresh page shows no banner
- **WHEN** the page is within the staleness window
- **THEN** the strip shows the ok palette with an age counter ticking
  upward every second, and no card is dimmed

#### Scenario: One-shot snapshot never banners
- **WHEN** a one-shot page is opened long after it was generated
- **THEN** the data and the page's `generatedAt` age are shown, with no
  stale degradation

### Requirement: Reload keeps the reading position
With scripting enabled the page SHALL restore its scroll position across
the meta-refresh reload, so the 10-second refresh does not reset what the
operator was reading. Without scripting, or where session storage is
unavailable, the reload behaves as a plain page load.
<!-- implements UX5 of redesign-dashboard -->

#### Scenario: Refresh does not reset scroll
- **WHEN** the operator has scrolled down and the meta-refresh fires
- **THEN** the reloaded page restores the previous scroll position

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

### Requirement: Sandbox hygiene section
The sandbox-hygiene surface is the page's quietest reference block, placed
last. When sweep data exists it SHALL render as a normal card carrying the
last tick's breakdown in four groups over the verdict categories — cleaned
(disposed-aged + disposed-reconstructible), stopped (stopped-orphan),
checked-and-untouched (checked-alive + kept-under-threshold),
skipped-without-verdict (skipped-no-verdict). When no sweep data exists it
SHALL render as a dashed-border footnote stating that the sweep has not
run. The kept-environment inventory (ages, time-to-reap) and the table of
recent stop/dispose actions are consciously dropped from the page:
per-object depth stays with the snapshot, the ledger, and `gnomish status
<id>`. Alert conditions do not render in this block — they surface in the
status card. The block SHALL degrade independently like every other block
when its inputs are missing or stale.
<!-- implements FR1, FR2 of redesign-dashboard -->

#### Scenario: One glance answers the four questions
- **WHEN** the operator opens the dashboard after a daemon has run
  overnight
- **THEN** the hygiene block shows what was disposed, what was stopped,
  how many objects were verified alive or kept, and whether any tick
  skipped without a verdict

#### Scenario: No per-object rows on the page
- **WHEN** the last tick kept environments and stopped an orphan
- **THEN** the block shows only the four-group counts, with no
  kept-environment inventory rows and no stop/dispose actions table

#### Scenario: No sweep data renders a footnote
- **WHEN** no sweep vitals and no sweep ledger lines exist
- **THEN** the block renders as a dashed-border footnote sentence, not as
  an empty card

### Requirement: Sandbox hygiene alerts
The dashboard's alert conditions SHALL include: the sweep has not completed a tick for longer than a threshold; consecutive skipped-no-verdict ticks (cleanup silently stalled); and any stopped-orphan event with ownership mode `tracked` in the rendered window — surfaced as a symptom of a dead or hung instance, naming the object and task, not as routine cleanup. A stopped-orphan event with mode `manual` is a routine age-policy stop: it appears in the breakdown and the actions table but SHALL NOT raise the dead-instance alert.
<!-- implements NFR-O3, UX2 of add-serve-sandbox-lifecycle -->

#### Scenario: Silent stall becomes loud
- **WHEN** three consecutive ticks report skipped-no-verdict
- **THEN** the dashboard raises an alert stating cleanup is not actually running, with the failing verdict source named

#### Scenario: Zombie stop reads as an incident
- **WHEN** a tick stopped an abandoned running box
- **THEN** the alert names the box and its task and reads as "an instance died or hung", distinct in presentation from aged disposals

### Requirement: Timestamps render relative with an absolute fallback
Every server-written timestamp that the page presents as an age SHALL be a
`<time>` element carrying the full ISO instant in `datetime`, epoch
milliseconds in a data attribute, and a server-rendered absolute time as its
text. Client script SHALL rewrite the text to a short relative form every
second and move the absolute value into `title`, so hovering reveals the
exact instant. With scripting disabled the absolute text SHALL remain
visible. Instants the page presents as a future deadline rather than an age
are outside this requirement, since the relative form measures elapsed time.
<!-- implements FR8, NFR-R2, UX2 of redesign-dashboard -->

#### Scenario: Relative time ticks
- **WHEN** the page is open with scripting enabled
- **THEN** each timestamp shows a relative age that updates every second,
  and hovering it reveals the full ISO instant

#### Scenario: No-JS reader sees absolute time
- **WHEN** the page is opened with scripting disabled
- **THEN** every timestamp is legible as a server-rendered absolute time

### Requirement: Every section carries its data timestamp
Each section SHALL display the timestamp of its underlying data —
snapshot `writtenAt`, board fetch time, ledger day range — and the page
SHALL display its `generatedAt`.
<!-- implements NFR-O1 of add-dashboard-page -->

#### Scenario: Observation times are explicit
- **WHEN** the operator reads any section
- **THEN** the age of that section's data is determinable from the page
  alone

### Requirement: Page fits a narrow viewport
At a viewport width of 375 pixels the page SHALL show no horizontal
overflow: every block, row, bar, and numeric cell SHALL fit or wrap within
the viewport width.
<!-- implements UX4 of redesign-dashboard -->

#### Scenario: Phone-width render does not overflow
- **WHEN** the page is rendered in a 375-pixel-wide viewport
- **THEN** no element extends beyond the viewport and no horizontal
  scrollbar appears

### Requirement: Page themes automatically from design tokens
The page SHALL render legibly in both light and dark themes selected
automatically via `prefers-color-scheme`, with no theme toggle and no
persisted preference. Every colour SHALL come from a design-token
declaration; no colour literal appears outside the token blocks. Token spend
SHALL NOT be coloured with the status (alarm) palette.
<!-- implements FR7, NFR-O1 of redesign-dashboard -->

#### Scenario: Both themes legible
- **WHEN** the page is viewed with the OS in light mode and again in dark
  mode
- **THEN** every element — including bar segments and the freshness strip —
  remains legible against its background in both
</content>
