# tracker-board

## Purpose

The `gnomish board` subcommand: a read-only, three-column view of the tracker
(Ready, Working, AwaitingHuman) for operators and external monitors, rendered
as text or as a versioned JSON document, built from exactly two tracker list
calls with no daemon dependency.

## Requirements

### Requirement: board subcommand surface
`gnomish board` SHALL be a subcommand that renders the tracker as a
read-only board of three columns in order: Ready, Working, AwaitingHuman.
It SHALL accept `--dir` (default: current directory) naming the project
config root and SHALL resolve the tracker section from that root's
`.gnomish/config.yaml` exactly as `take`/`serve` do (same configuration and
credentials). Its one tracker write SHALL be the GitHub adapter's creation,
at construction, of a factory state label the repository lacks.
**Implements**: FR1 of add-board-command; NFR-S1 of add-board-command; UX3 of add-board-command
**Notes**: The tracker adapter constructor mints an instance id at construction time, so the board supplies a throwaway one purely locally; the id stays in the process.

#### Scenario: Board renders three columns without a daemon
- **WHEN** `gnomish board` runs against a tracker while no serve instance
  exists
- **THEN** the output contains the Ready, Working, and AwaitingHuman
  columns built from tracker data alone, with no reference to daemon state

#### Scenario: Tracker resolved from the --dir config root
- **WHEN** `gnomish board --dir <clone>` runs against a project whose
  `.gnomish/config.yaml` under `<clone>` declares a tracker section
- **THEN** the board resolves and reads that tracker, the same section
  `take`/`serve` would resolve from the same `--dir`

#### Scenario: No write operations reach the tracker
- **WHEN** a board invocation completes against the GitHub adapter fixture
  on a repository that already carries the factory's state labels
- **THEN** every recorded HTTP request is a read (GET); no write request
  was issued

#### Scenario: The board never writes to the tracker
- **WHEN** a board invocation completes against any tracker adapter
- **THEN** no tracker write operation was invoked: no claim, park, finish,
  marker, or comment write; the only write a board run can make is the
  adapter's creation of a missing state label at construction

### Requirement: Ready column with eligibility annotations
The Ready column SHALL list ready tasks in the adapter's queue order, each
row carrying the task id, title, and the returned/fresh distinction. When
the feed would not claim a task now, the row SHALL carry an eligibility
annotation naming the reason in the feed's own precedence order: in backoff,
then `finished`, then WIP-held. The column SHALL be summarized as total
queued, eligible now, and the ineligible count broken down by reason.
**Implements**: FR2 of add-board-command; FR3 of add-board-command; UX2 of add-board-command
**Notes**: `finished` is terminal and defensively dropped by the feed; WIP-held is a fresh task skipped while the open-front count is at or above the configured WIP limit.

#### Scenario: Backed-off task is annotated with its deadline
- **WHEN** the ready queue holds a task with abort facts placing it in
  backoff until instant T
- **THEN** its Ready row is marked as in backoff until T, while eligible
  rows carry no eligibility annotation

#### Scenario: Finished task is not counted eligible
- **WHEN** the ready queue holds a reopened `finished` task that is not in
  backoff
- **THEN** its Ready row is annotated `finished` and it is excluded from the
  eligible-now count, matching the feed's defensive drop

#### Scenario: Fresh task is WIP-held when the front is full
- **WHEN** the open-front count is at or above the WIP limit and the ready
  queue holds a fresh task and a returned task, neither in backoff
- **THEN** the fresh row is annotated WIP-held and excluded from the
  eligible-now count, while the returned row stays eligible

#### Scenario: Summary counts reconcile
- **WHEN** the ready queue holds 7 tasks — 2 in backoff, 1 `finished`, and,
  with the open-front count at the WIP limit, 1 fresh task WIP-held
- **THEN** the Ready summary reads 7 queued, 3 eligible, 2 in backoff, 1
  finished, 1 WIP-held

#### Scenario: Returned task is distinguished
- **WHEN** the ready queue holds one returned task and one fresh task
- **THEN** the returned row is visibly marked as returned

#### Scenario: Truncated window is honest
- **WHEN** `listReady` returns exactly as many tasks as the requested limit
- **THEN** the Ready column states that only the first entries are shown
- **AND** the `--json` document carries `truncated: true`, with the summary
  counts describing that shown window

#### Scenario: The backoff deadline is the feed's own
- **WHEN** a ready task is in backoff
- **THEN** its annotated deadline is the one computed by the same core
  backoff policy and parameter resolution the take feed uses

#### Scenario: Each task counts under exactly one reason
- **WHEN** a ready task meets several ineligibility reasons at once
- **THEN** it is annotated and counted under the first reason in the feed's
  precedence order only, so the parts of the summary sum to total queued

#### Scenario: A full window states no truncation
- **WHEN** `listReady` returns fewer tasks than the requested limit
- **THEN** the render states no truncation and the `--json` document carries
  `truncated: false`

### Requirement: Working column with holder and claim freshness
The Working column SHALL list open tasks in `Working` state in the order
`listOpen` returns them (the adapter's deterministic creation order), each
row carrying the task id, title, the holder, and the claim-marker freshness
— the claim version's last-update instant, rendered as an age in text
output. The board SHALL NOT judge staleness: no stale/healthy verdict is
emitted.
**Implements**: FR4 of add-board-command
**Notes**: A Working task with no claim footprint at all (e.g. a human-mislabeled issue) is still reported by `listOpen`, with the adapter's unknown-holder label (`unknown` on GitHub) and no claim version, so it appears in this column with freshness unknown.

#### Scenario: Working rows show holder and claim age
- **WHEN** a task is `Working` with holder `factory-a-1b2c` and a claim
  version updated at instant T
- **THEN** its Working row shows the holder and the freshness derived from
  T, with no staleness verdict

#### Scenario: Working row with a missing claim marker
- **WHEN** a task is `Working` with a holder but its `OpenTask` carries a
  null claim version (the live marker went missing)
- **THEN** its Working row shows the holder with freshness marked unknown
  and no age, and still emits no staleness verdict

#### Scenario: The column invents no holder
- **WHEN** the board builds the Working column
- **THEN** it holds exactly the `Working` entries `listOpen` returned, each
  with the holder `listOpen` reported, and no row or holder of its own

### Requirement: AwaitingHuman column with park reasons
The AwaitingHuman column SHALL list parked tasks in the order `listOpen`
returns them (the adapter's deterministic creation order), each row carrying
the task id, title, and the park reason (`escalation`, `infra`, or
`checkpoint`).
**Implements**: FR5 of add-board-command

#### Scenario: Park reasons are spelled out
- **WHEN** the tracker holds one task parked for escalation and one for a
  checkpoint
- **THEN** the AwaitingHuman column shows both rows with their respective
  reasons

### Requirement: Dual text and JSON rendering of one model
The board SHALL render human-readable text by default and, under
`--json`, a JSON document following the status-report v1 conventions:
`"version": 1`, camelCase fields, ISO-8601 UTC instants, and a
`generatedAt` timestamp. Both renders SHALL be projections of the same
underlying board model — no column, row, or annotation is exclusive to
one surface.
**Implements**: FR6 of add-board-command; NFR-O1 of add-board-command; UX4 of add-board-command
**Notes**: The JSON materializes every resolved fact so that consumers need no factory configuration to interpret the document.

#### Scenario: JSON is versioned and self-describing
- **WHEN** `gnomish board --json` runs against a queue with a backed-off
  task
- **THEN** the document carries `"version": 1`, `generatedAt`, and the
  task's backoff deadline as an ISO-8601 UTC instant

#### Scenario: Text and JSON agree
- **WHEN** the same tracker state is rendered as text and as JSON
- **THEN** every task id, title, annotation, and summary count present in
  one render is present in the other

#### Scenario: JSON needs no factory configuration to interpret
- **WHEN** `gnomish board --json` runs
- **THEN** the document materializes each ready entry's resolved eligibility
  (eligible, or the skip reason: backoff, `finished`, or WIP-held), its
  backoff deadline, the observed open-front count against the WIP limit,
  claim-version update instants (or an explicit unknown when the marker is
  absent), and a `truncated` flag for the ready window

#### Scenario: JSON is byte-stable for a fixed tracker state
- **WHEN** the same tracker state is rendered as JSON twice
- **THEN** the rows of every column appear in the adapter's list order and
  the two documents are byte-identical, so a reference fixture can pin them

### Requirement: Two list port calls, no per-row fetch
A board invocation SHALL perform exactly one `listReady` port call and one
`listOpen` port call, and SHALL NOT issue any per-row `fetchTask`,
regardless of queue size. Title enrichment SHALL add no tracker request
over each list operation's pre-enrichment shape.
**Implements**: NFR-P1 of add-board-command
**Notes**: The board adds no read cost beyond those two list operations; the HTTP-request count of the operations themselves is the adapter's existing behavior (for GitHub, internal per-task comment fetches absorbed by conditional `304`s) and not a board concern.

#### Scenario: No per-row fetch beyond the two list calls
- **WHEN** the board renders a tracker holding 30 ready and 5 open tasks
- **THEN** exactly one `listReady` and one `listOpen` are invoked and no
  `fetchTask` is called
- **AND** on the GitHub adapter fixture the recorded requests after title
  enrichment match those the two list operations already made before it,
  with no issue-detail request added

#### Scenario: The ready window defaults to 50
- **WHEN** `gnomish board` runs without `--limit`, and again with `--limit=10`
- **THEN** the first invocation's `listReady` window is 50 and the second's
  is 10

### Requirement: Tracker outage fails plainly
When the tracker is unreachable or persistently failing, the board SHALL
print a single clear error line and exit non-zero. Transient-failure
handling SHALL be whatever the tracker adapter already provides; the board
SHALL add no retry loop of its own.
**Implements**: NFR-R1 of add-board-command

#### Scenario: Unreachable tracker
- **WHEN** `gnomish board` runs while the tracker endpoint refuses
  connections
- **THEN** the command prints one error line naming the tracker as the
  cause and exits with a non-zero code

### Requirement: Operator guide covers the board
The operator guide SHALL gain a board section: the three columns and their
sources, the backoff annotation semantics, and a cron-monitor recipe over
`gnomish board --json` for tracker-side alert rules (queue growth,
long-waiting escalations) as the complement to the daemon-snapshot rules.
**Implements**: UX1 of add-board-command; G3 of add-board-command

#### Scenario: Guide enables the external monitor
- **WHEN** an operator follows the guide's cron recipe
- **THEN** they can alert on queue growth and escalation age using only
  the documented JSON fields, without reading factory source
