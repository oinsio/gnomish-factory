# tracker-board (delta)

## ADDED Requirements

### Requirement: board subcommand surface
`gnomish board` SHALL be a subcommand that renders the tracker as a
read-only board of three columns in order: Ready, Working, AwaitingHuman.
It SHALL accept `--dir` (default: current directory) naming the project
config root and SHALL resolve the tracker section from that root's
`.gnomish/config.yaml` exactly as `take`/`serve` do (same configuration and
credentials), SHALL work with zero daemon instances
running, and SHALL never invoke a tracker write operation (no claim, park,
finish, marker, or comment writes). To satisfy the tracker adapter
constructor — which mints an instance id at construction time — the board
supplies a throwaway instance id purely locally; it is never written to the
tracker, preserving the read-only guarantee.
<!-- implements FR1 of add-board-command -->
<!-- implements NFR-S1 of add-board-command -->
<!-- implements UX3 of add-board-command -->

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
- **THEN** every recorded HTTP request is a read (GET); no write request
  was issued

### Requirement: Ready column with eligibility annotations
The Ready column SHALL list ready tasks in the adapter's queue order, each
row carrying the task id, title, and the returned/fresh distinction. When
the feed would not claim a task now, the row SHALL carry an eligibility
annotation naming the reason in the feed's own precedence order: in backoff
(with the materialized deadline, computed by the same core backoff policy
and parameter resolution the take feed uses), then `finished` (terminal,
defensively dropped by the feed), then WIP-held (a fresh task skipped while
the open-front count is at or above the configured WIP limit; returned tasks
are never WIP-held). The column SHALL be summarized as total queued, eligible
now, and the ineligible count broken down by reason, with each ready task
counted under exactly one reason so the parts sum to total queued. All
summary counts are scoped to the fetched window: when the window is
truncated at the requested limit, "total queued" describes the shown window,
not the tracker's full queue, and the render SHALL both say so and expose a
`truncated` flag distinguishing a capped window from a fully-shown one.
<!-- implements FR2 of add-board-command -->
<!-- implements FR3 of add-board-command -->
<!-- implements UX2 of add-board-command -->

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

### Requirement: Working column with holder and claim freshness
The Working column SHALL list open tasks in `Working` state in the order
`listOpen` returns them (the adapter's deterministic creation order), each
row carrying the task id, title, the holder, and the claim-marker freshness
— the claim version's last-update instant, rendered as an age in text
output. When the claim marker is absent (`OpenTask.claimVersion` is null — a
`Working` task whose live marker went missing), the row SHALL show the
holder with freshness marked unknown instead of an age. The board SHALL NOT
judge staleness: no stale/healthy verdict is emitted. Working tasks the
adapter omits from `listOpen` because they carry no claim footprint at all
(e.g. a human-mislabeled issue with no holder to name) are absent from this
column by design; the board reports exactly what `listOpen` returns and
invents no holder.
<!-- implements FR4 of add-board-command -->

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

### Requirement: AwaitingHuman column with park reasons
The AwaitingHuman column SHALL list parked tasks in the order `listOpen`
returns them (the adapter's deterministic creation order), each row carrying
the task id, title, and the park reason (`escalation`, `infra`, or
`checkpoint`).
<!-- implements FR5 of add-board-command -->

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
one surface. Each ready entry's resolved eligibility (eligible, or the skip
reason: backoff, `finished`, or WIP-held), its backoff deadline, the
observed open-front count against the WIP limit, claim-version update
instants (or an explicit unknown when the marker is absent), and a
`truncated` flag for the ready window SHALL be materialized in the JSON so
consumers need no factory configuration to interpret the document. Rows in
every column SHALL appear in the adapter's list order, so the document is
byte-stable for a fixed tracker state and can be pinned by a reference
fixture.
<!-- implements FR6 of add-board-command -->
<!-- implements NFR-O1 of add-board-command -->
<!-- implements UX4 of add-board-command -->

#### Scenario: JSON is versioned and self-describing
- **WHEN** `gnomish board --json` runs against a queue with a backed-off
  task
- **THEN** the document carries `"version": 1`, `generatedAt`, and the
  task's backoff deadline as an ISO-8601 UTC instant

#### Scenario: Text and JSON agree
- **WHEN** the same tracker state is rendered as text and as JSON
- **THEN** every task id, title, annotation, and summary count present in
  one render is present in the other

### Requirement: Two list port calls, no per-row fetch
A board invocation SHALL perform exactly one `listReady` port call and one
`listOpen` port call, and SHALL NOT issue any per-row `fetchTask`,
regardless of queue size. Title enrichment SHALL add no tracker request
over each list operation's pre-enrichment shape. The board adds no read
cost beyond those two list operations; the HTTP-request count of the
operations themselves is the adapter's existing behavior (for GitHub,
internal per-task comment fetches absorbed by conditional `304`s) and is
not a board concern. The ready window SHALL default to 50 and be
overridable with `--limit`.
<!-- implements NFR-P1 of add-board-command -->

#### Scenario: No per-row fetch beyond the two list calls
- **WHEN** the board renders a tracker holding 30 ready and 5 open tasks
- **THEN** exactly one `listReady` and one `listOpen` are invoked and no
  `fetchTask` is called
- **AND** on the GitHub adapter fixture the recorded requests after title
  enrichment match those the two list operations already made before it,
  with no issue-detail request added

### Requirement: Tracker outage fails plainly
When the tracker is unreachable or persistently failing, the board SHALL
print a single clear error line and exit non-zero. Transient-failure
handling SHALL be whatever the tracker adapter already provides; the board
SHALL add no retry loop of its own.
<!-- implements NFR-R1 of add-board-command -->

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
<!-- implements UX1 of add-board-command -->
<!-- implements G3 of add-board-command -->

#### Scenario: Guide enables the external monitor
- **WHEN** an operator follows the guide's cron recipe
- **THEN** they can alert on queue growth and escalation age using only
  the documented JSON fields, without reading factory source
