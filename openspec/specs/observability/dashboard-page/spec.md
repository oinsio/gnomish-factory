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
from `--dir <clone>` exactly as `gnomish board` and `gnomish status` do,
through the registered project that clone belongs to.
The default output path SHALL be `dashboard.html` in the instance's
observability directory (`GNOMISH_HOME/projects/<name>/serve/<instance>/`);
`--out` SHALL override it.
**Implements**: FR1 of add-dashboard-page; FR10 of add-project-registry

#### Scenario: Default output lands beside the observability files
- **WHEN** `gnomish dashboard` runs without `--out` for project `widgets` with the default instance name
- **THEN** the page is written to `GNOMISH_HOME/projects/widgets/serve/default/dashboard.html`

#### Scenario: Explicit output path
- **WHEN** `gnomish dashboard --out=incident.html` runs
- **THEN** the page is written to the given path

### Requirement: Page is fully self-contained
The generated page SHALL inline all data, styles, and scripts and SHALL
reference no external resources; opening it from `file://` SHALL trigger
zero network requests. The page SHALL contain no credentials, prompts,
or task content beyond what the composed surfaces already expose (ids,
titles, states, counters, timestamps, token counts).
**Implements**: FR2, NFR-S1 of add-dashboard-page

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
remain one self-contained HTML file. The renderer SHALL load both resources
once at construction and SHALL fail at construction when either is missing
or blank.
**Implements**: FR10, NFR-R1 of redesign-dashboard
**Notes**: The meta-refresh interval is written in whole seconds, which is why a sub-second render cadence is rejected rather than emitted as `content="0"`.

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

#### Scenario: Only a watch page carries a meta-refresh
- **WHEN** the same data is rendered once as a `--watch` page and once as a
  one-shot page
- **THEN** the `--watch` page carries its 10-second meta-refresh and the
  one-shot page carries none

#### Scenario: The page mode is baked for the static script
- **WHEN** a page is rendered in either mode
- **THEN** its mode is baked as a `data-mode` attribute that the static
  script reads

#### Scenario: Content that would close its inline block early is refused
- **WHEN** a resource holds content that would terminate its inline
  `<script>` block early
- **THEN** inlining refuses it, and the refusal names the resource and what
  to do about it

### Requirement: Page presents priority layers with persistent blocks
The page SHALL present its content as fixed-order priority layers — a
freshness strip, a status line, a waiting-for-a-human block, an in-progress
block — followed by visually quieter reference blocks: outcomes by day,
tokens, and sandbox hygiene. No later layer may be visually louder than an
earlier one. Blocks SHALL NOT appear or disappear with data: each block
occupies the same position regardless of content.
**Implements**: FR1, FR2 of redesign-dashboard

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
and the tracker board (waiting-for-a-human and in-progress blocks, and the
status card's WIP stat). A degraded source SHALL NOT fail the rendering of
the others.
**Implements**: FR1, FR2 of redesign-dashboard; FR13 of supervise-daemon-loops-and-embed-dashboard

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

#### Scenario: Unreadable ledger renders the empty states
- **WHEN** a ledger file in the window is unreadable at render time — it
  cannot be read, or a line before its last is malformed
- **THEN** the outcomes-by-day and tokens blocks render their empty-state
  sentences, while the status card and the board-fed blocks render normally

#### Scenario: Tracker failure keeps the cached board
- **WHEN** the tracker fails at render time after an earlier board fetch
  succeeded
- **THEN** the board-fed blocks keep the last cached board model, marked
  with its fetch time and a refresh-failure notice

### Requirement: Daemon section computes staleness and flags alert conditions
The snapshot's surface is the status card. For every lifecycle state other than `stopped` the card SHALL
compute snapshot staleness from the snapshot's own `writtenAt` and
`intervalSeconds` and SHALL flag the operator-guide alert conditions
observable from a single snapshot — rules 1–5 of the guide's six — each
triggered condition rendered as a short alarm-palette line inside the card.
A snapshot whose last `lifecycle.state` is `stopped` SHALL show the stopped
state and its reason at any age.
**Implements**: FR1, FR2 of redesign-dashboard; FR11 of supervise-daemon-loops-and-embed-dashboard
**Notes**: The status card is the page's second priority layer.
**Notes**: `stopped` is terminal, so staleness adds nothing to it, and its vitals are frozen at the stop; rule 6 needs check-to-check history, which one snapshot cannot give.

#### Scenario: Dead daemon reddens the section
- **WHEN** the snapshot's age exceeds `k × intervalSeconds` and its last
  `lifecycle.state` is not `stopped` (`running`, `draining`, or
  `stopping`)
- **THEN** the status card carries a "daemon dead" alarm line

#### Scenario: Clean stop is not an alert
- **WHEN** the snapshot is stale and its last `lifecycle.state` is `stopped`
- **THEN** the card shows the stopped state and reason without a
  dead-daemon alarm line

#### Scenario: Fresh stopped snapshot shows the stopped state
- **WHEN** the snapshot is within `k × intervalSeconds` of its `writtenAt`
  and its last `lifecycle.state` is `stopped` — the page `serve` renders
  right after its final snapshot
- **THEN** the card shows the stopped state and reason with the stopped
  marker, never "running", and carries no daemon alarm line

#### Scenario: Hygiene alert surfaces in the status card
- **WHEN** a sandbox-hygiene alert condition triggers at render time
- **THEN** it renders as an alarm line in the status card, and the
  sandbox-hygiene block itself carries no alert styling

#### Scenario: Single-snapshot rules raise their own alarm lines
- **WHEN** a non-`stopped` snapshot shows occupied slots with a heartbeat not
  `running`, a long `idleBlocked`, growing tracker `consecutiveFailures`, or
  a stale reaper `lastRunAt` or growing `restartCount` (rules 2–5)
- **THEN** each triggered condition renders as its own alarm line in the
  status card

#### Scenario: Slot-count desync is left to the external monitor
- **WHEN** the snapshot's `heldClaims` disagrees with its slot count
- **THEN** the card raises no alarm line for it: rule 6 (desync on two
  consecutive checks) stays with the external dead-man's-switch monitor

### Requirement: Waiting-for-a-human block is the loudest element
When at least one task awaits a human, its block SHALL be the most visually
prominent element on the page (accent border and background), showing the
count and one row per task: an icon distinguishing the park reason category
(escalation / checkpoint / infra), the task id and title, and a one-line
reason truncated with an ellipsis when the tracker exposes one.
**Implements**: FR4, UX1 of redesign-dashboard

#### Scenario: Escalated task dominates the page
- **WHEN** one task is awaiting a human
- **THEN** its block carries the accent treatment and shows the task's
  park-reason glyph, id, and title, and no other block is more visually
  prominent

#### Scenario: Missing field shortens the row
- **WHEN** the tracker exposes no escalation reason for a task
- **THEN** the row renders without a reason cell and contains no "n/a"
  placeholder

#### Scenario: Empty waiting block is calm
- **WHEN** no task awaits a human
- **THEN** the block uses ordinary card chrome, without the accent
  treatment, with a calm all-clear sentence and a check glyph

### Requirement: In-progress block is a single compact list
The in-progress block SHALL show ready and working items as one compact row
list built from the board model's own fields — status marker, task id, for
working rows the holding instance and claim age, for ready rows a short
eligibility note (backoff deadline, finished, WIP-held) — keeping Ready
and Working
items distinguishable without separate headings or separate empty states.
Its single empty state SHALL state that the slot is free and no ready tasks
exist.
**Implements**: FR5 of redesign-dashboard

#### Scenario: Ready and working share one list
- **WHEN** one task is working and one is ready
- **THEN** both render as rows of the same list, visually distinguishable,
  under a single heading

### Requirement: Board section mirrors the CLI board via the same composition
The tracker board SHALL feed the waiting-for-a-human and in-progress
blocks, produced by the same in-process composition and eligibility policy
as `gnomish board` — not by shelling out and not by a reimplementation.
The semantics of every field shown SHALL match what `gnomish board` reports
for the same task.
**Implements**: FR4, FR5 of redesign-dashboard
**Notes**: The compact rows consciously drop the CLI board's full annotation text, while the meaning of what they show stays bound to the board.

#### Scenario: Backoff annotation matches the board command
- **WHEN** a ready task is in backoff at render time
- **THEN** the in-progress row's eligibility note shows the same backoff
  deadline that `gnomish board` would show for it

#### Scenario: Capped ready window stays visible
- **WHEN** the ready window was truncated at the requested limit
- **THEN** the in-progress block indicates that more ready tasks exist
  beyond the listed rows

#### Scenario: In-progress rows carry the board's entry fields
- **WHEN** the board model holds Working and Ready entries
- **THEN** each Working row presents its holding instance and claim age, and
  the Ready rows follow in the board's queue order, each with a short
  eligibility note (backoff with deadline, `finished`, WIP-held) where one
  applies

#### Scenario: Awaiting entries feed the waiting block
- **WHEN** the board model holds AwaitingHuman entries
- **THEN** they render in the waiting-for-a-human block, each with its park
  reason category

### Requirement: History section aggregates recent ledger files
The history section SHALL aggregate the last 7 days of ledger files
(bounded by what retention left) into outcomes per day and tokens by model.
The reader SHALL skip a torn last line, and a well-formed outcome line whose
outcome it does not know, and process the rest; a malformed line before the
tail degrades the history blocks to their empty states.
**Implements**: FR6, FR7 of redesign-dashboard

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

#### Scenario: An unknown outcome is skipped
- **WHEN** a well-formed taskOutcome line carries a missing or unrecognized
  outcome
- **THEN** the line is skipped, not counted, and the history renders from the
  other lines

#### Scenario: A malformed earlier line empties the history
- **WHEN** a ledger file in the window holds a malformed line that is not its
  last
- **THEN** the history blocks render their empty states and the render does
  not fail

#### Scenario: Outcome bars share one legend
- **WHEN** the window holds days with outcomes
- **THEN** each day renders as one full-width stacked bar showing its mix of
  delivered / awaitingHuman / aborted / revoked proportionally, with the
  date and the day's absolute total as numbers beside the bar, under one
  legend shared by all days

#### Scenario: Token bars lead with the cache share
- **WHEN** a model's ledger lines carry token counts
- **THEN** the model renders as a stacked bar of cacheRead / cacheCreation /
  input / output, with a caption leading with the integer cache share

### Requirement: Numbers are formatted compactly server-side
Counts of 1000 and above SHALL be rendered in compact form computed
server-side at three significant digits with trailing zeros dropped
(`25.6K`, `4.79M`, `5M` — never `5.0M`), and the exact value placed in the
element's `title`. Counts below
1000 print as-is. Percentages SHALL be integers. Numeric cells SHALL use
tabular numerals so columns do not shift horizontally across refreshes. No
displayed value SHALL be computed only in client script.
**Implements**: FR9, NFR-R2, UX3 of redesign-dashboard

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
**Implements**: FR7 of add-dashboard-page

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
when the age exceeds that threshold, a persistent full-width freshness strip
at the top of the page SHALL turn to the alarm palette with an
upward-counting "renderer silent" duration, and the content cards SHALL dim
while remaining readable.
**Implements**: FR3 of redesign-dashboard
**Notes**: At the fixed 10-second cadence the threshold is 30 seconds.

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

#### Scenario: Staleness never covers the page
- **WHEN** a `--watch` page has gone stale
- **THEN** the page is degraded, never covered or blocked, and no
  full-viewport staleness banner exists

### Requirement: Reload keeps the reading position
With scripting enabled the page SHALL restore its scroll position across
the meta-refresh reload, so the 10-second refresh does not reset what the
operator was reading. Without scripting, or where session storage is
unavailable, the reload behaves as a plain page load.
**Implements**: UX5 of redesign-dashboard

#### Scenario: Refresh does not reset scroll
- **WHEN** the operator has scrolled down and the meta-refresh fires
- **THEN** the reloaded page restores the previous scroll position

### Requirement: Watch cadences split local and tracker sources
In `--watch` mode the snapshot and ledger SHALL be re-read on every
render cycle; the tracker board SHALL be refreshed on its own slower
cadence (default 60 s) with the last board model cached between board
refreshes. The board section SHALL display when its data was fetched.
**Implements**: FR9, NFR-P1 of add-dashboard-page

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
**Implements**: FR10 of add-dashboard-page

#### Scenario: Slot entry stays a pointer
- **WHEN** the operator needs detail on a task shown in a slot row
- **THEN** the page offers the task id to feed the existing canon, not
  stage artifacts or logs

### Requirement: Watch loop survives data-source failures
In `--watch` mode any data-source failure — tracker outage, missing or
torn files — SHALL degrade the affected section (the board keeping its
cached model where one exists) and the loop SHALL continue rendering;
transient failures SHALL NOT terminate the process.
**Implements**: NFR-R1 of add-dashboard-page

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
**Implements**: NFR-R2 of add-dashboard-page

#### Scenario: Reload during a render
- **WHEN** the browser reloads while a render is in progress
- **THEN** it reads a complete page, never a truncated one

### Requirement: Sandbox hygiene section
The sandbox-hygiene surface is the page's quietest reference block, placed
last. When sweep data exists it SHALL render as a normal card carrying the
last tick's breakdown in four groups over the verdict categories — cleaned
(disposed-aged + disposed-reconstructible), stopped (stopped-orphan),
checked-and-untouched (checked-alive + kept-under-threshold),
skipped-without-verdict (skipped-no-verdict).
**Implements**: FR1, FR2 of redesign-dashboard
**Notes**: The kept-environment inventory (ages, time-to-reap) and the table of recent stop/dispose actions are consciously dropped from the page: per-object depth stays with the snapshot, the ledger, and `gnomish status <id>`.

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

#### Scenario: The footnote says the sweep has not run
- **WHEN** the block renders as its no-data footnote
- **THEN** the footnote states that the sweep has not run

#### Scenario: Hygiene alerts stay out of the block
- **WHEN** a sandbox-hygiene alert condition triggers
- **THEN** the alarm line renders in the status card, and the hygiene block
  carries no alert of its own

#### Scenario: Hygiene block degrades on its own
- **WHEN** the hygiene block's inputs are missing or stale while the other
  sources are healthy
- **THEN** only the hygiene block degrades, like every other block, and the
  rest of the page renders normally, except that a stale sweep vital raises
  its alarm line, `sandbox sweep not running`, in the status card

### Requirement: Sandbox hygiene alerts
The dashboard's alert conditions SHALL include: the sweep has not completed a tick for longer than a threshold; consecutive skipped-no-verdict ticks (cleanup silently stalled); and any stopped-orphan event with ownership mode `tracked` in the rendered window — surfaced as a symptom of a dead or hung instance, naming the object and task, not as routine cleanup.
**Implements**: NFR-O3, UX2 of add-serve-sandbox-lifecycle

#### Scenario: Silent stall becomes loud
- **WHEN** three consecutive ticks report skipped-no-verdict
- **THEN** the dashboard raises an alert stating cleanup is not actually running, with the failing verdict source named

#### Scenario: Zombie stop reads as an incident
- **WHEN** a tick stopped an abandoned running box
- **THEN** the alert names the box and its task and reads as "an instance died or hung", distinct in presentation from aged disposals

#### Scenario: A manual age stop is routine
- **WHEN** a tick stopped an orphan whose ownership mode is `manual`
- **THEN** the stop counts as a routine age-policy stop in the breakdown and raises no dead-instance alert

### Requirement: Timestamps render relative with an absolute fallback
Every server-written timestamp that the page presents as an age SHALL be a
`<time>` element carrying the full ISO instant in `datetime`, epoch
milliseconds in a data attribute, and a server-rendered absolute time as its
text. Client script SHALL rewrite the text to a short relative form every
second and move the absolute value into `title`, so hovering reveals the
exact instant.
**Implements**: FR8, NFR-R2, UX2 of redesign-dashboard
**Notes**: Instants the page presents as a future deadline rather than an age are outside this requirement, since the relative form measures elapsed time.

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
**Implements**: NFR-O1 of add-dashboard-page

#### Scenario: Observation times are explicit
- **WHEN** the operator reads any section
- **THEN** the age of that section's data is determinable from the page
  alone

### Requirement: Page fits a narrow viewport
At a viewport width of 375 pixels the page SHALL show no horizontal
overflow: every block, row, bar, and numeric cell SHALL fit or wrap within
the viewport width.
**Implements**: UX4 of redesign-dashboard

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
**Implements**: FR7, NFR-O1 of redesign-dashboard

#### Scenario: Both themes legible
- **WHEN** the page is viewed with the OS in light mode and again in dark
  mode
- **THEN** every element — including bar segments and the freshness strip —
  remains legible against its background in both

### Requirement: Open remote outage gate is an alert condition in the status card
The status card SHALL flag an open remote outage gate as an alarm line
naming the target, the instant the gate opened ("open since <instant>"), the
last error, and the next probe time — the same alarm-palette line the tracker-failure and
hygiene conditions use, rendered in the status card and not in a block of
its own.
**Implements**: NFR-O3, UX6 of add-base-ref-resolution

#### Scenario: Blocked on the remote reads from one place
- **WHEN** the dashboard renders a snapshot whose `remote[origin].state` is
  `open`
- **THEN** the status card carries one alarm line stating the target, the
  instant the gate opened ("open since <instant>"), the last error, and the
  next probe time

#### Scenario: Closed gate is quiet
- **WHEN** the snapshot's `remote` section shows every target `closed`
- **THEN** no remote alarm line renders

#### Scenario: A sustained outage escalates the wording
- **WHEN** a gate has been open for at least the sustained-open duration
- **THEN** its alarm line reads "blocked on the remote" instead of "remote
  outage, probing"

#### Scenario: A snapshot without a remote section raises no line
- **WHEN** the snapshot carries no `remote` section
- **THEN** no remote alarm line renders

### Requirement: History section counts closed outages per day
The history section SHALL aggregate `remoteOutage` ledger lines into an
outages-per-day count with the day's total blocked duration, rendered as a
compact line under the outcomes bars — not as a bar of its own, since an
outage is not an outcome. Days with no outage SHALL render nothing for it.
**Implements**: NFR-O3 of add-base-ref-resolution

#### Scenario: Overnight outage in the morning view
- **WHEN** one `remoteOutage` line of three hours landed last night
- **THEN** that day's history shows one outage totalling three hours beside
  its outcome bar, and no other day mentions outages

### Requirement: Status card shows the WIP stat from the board
The status card SHALL show a WIP stat beside the slot stat: the board's
open-front count (Working plus AwaitingHuman) against the WIP limit the board
judged eligibility by, with a tooltip giving the exact counts and the
working/waiting split. The stat SHALL carry no alarm styling at any value.
**Implements**: FR12, UX3 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: WIP stat with its split
- **WHEN** the board holds 2 Working and 3 AwaitingHuman tasks under a WIP
  limit of 10
- **THEN** the status card shows `WIP 5 / 10` and its tooltip reads `5 of 10
  open fronts: 2 working, 3 waiting for a human`

#### Scenario: Full WIP is not an alarm
- **WHEN** the open-front count equals the WIP limit
- **THEN** the WIP stat renders in ordinary styling

#### Scenario: Limit matches the held rows
- **WHEN** a ready row is annotated WIP-held
- **THEN** the WIP stat's limit is the limit that row was judged against

### Requirement: The WIP stat degrades with the board, not the snapshot
The WIP stat SHALL render whenever a board model exists, including a cached
model after a failed refresh, and SHALL render even when no snapshot exists.
It SHALL be absent when no board fetch has ever succeeded. The snapshot-fed
stats SHALL keep their own degradation.
**Implements**: FR13 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: No daemon yet
- **WHEN** no snapshot has been written and the board loads
- **THEN** the status card says the daemon has not run here and shows the WIP
  stat, without slot or failure stats

#### Scenario: Board never loaded
- **WHEN** no board fetch has succeeded
- **THEN** the status card shows no WIP stat and no placeholder for it

### Requirement: A watch render loop that keeps dying is disabled
The watch render loop SHALL be respawned when its thread dies, and SHALL be
disabled after more than 5 deaths within 10 minutes with one ERROR line.
A standalone `gnomish dashboard --watch` whose loop is disabled SHALL exit
with status 1. Data-source failures SHALL NOT count as deaths.
**Implements**: FR3, FR9, UX4 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Standalone renderer gives up
- **WHEN** the standalone watch loop's thread dies a sixth time within 10 minutes
- **THEN** one ERROR says the dashboard is disabled and the process exits 1

#### Scenario: Tracker outage is not a death
- **WHEN** the tracker is unreachable for an hour during `--watch`
- **THEN** the loop keeps rendering with the cached board and is never disabled
