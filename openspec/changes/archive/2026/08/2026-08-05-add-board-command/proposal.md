# Proposal: add-board-command

## Why

The tracker is the factory's coordination plane, but the operator has no
first-class view of it. Today "what is in the queue, who is working, what
waits for a human" requires opening the tracker UI and mentally replaying
factory rules — most painfully backoff: a task sits in the ready queue yet
the daemon refuses to take it, and nothing explains why. `gnomish status`
cannot host this view: it belongs to the per-task canon (git branches read
from the `--dir` clone) and works without any tracker access, whereas the
board reads only the tracker (it still takes `--dir`, but to locate the
config root, not task branches); grafting a tracker mode onto `status` would
silently switch the data source behind one command.

## What Changes

- **ADDED**: `gnomish board` — a read-only CLI command that renders the
  tracker as a board with three columns mirroring the operator's mental
  model: **Ready** (queue order with backoff annotations), **Working**
  (holder + claim freshness), **AwaitingHuman** (park reason). Text and
  `--json` output, same dual-surface convention as `gnomish status`.
- **MODIFIED**: tracker port list types — `ReadyTask` and `OpenTask` gain a
  task `title`, populated by adapters from data already present in their
  list responses (no extra fetches). Contract suite extended accordingly.
- No daemon, snapshot, or ledger involvement: the board works with or
  without a running `serve` instance and reads only the tracker.

## Capabilities

### New Capabilities

- `tracker-board`: the `gnomish board` command — tracker-side operator
  board: data sourced from the two existing tracker list operations,
  backoff annotation reusing the core eligibility filter, text and JSON
  rendering.

### Modified Capabilities

- `tracker-port`: `ReadyTask` and `OpenTask` carry the task title so list
  surfaces can display human-readable rows without N+1 `fetchTask` calls;
  both adapters and the port contract suite are updated.

## Goals

- **G1** — the operator answers "what is the queue depth, who works on
  what, what waits for me" with one command, without a running daemon and
  without opening the tracker UI.
- **G2** — daemon eligibility becomes visible: for every ready-but-ineligible
  task the board shows why the feed would skip it — in backoff (until when),
  terminal (`finished`, reopened), or held by the WIP limit — answering "why
  is the daemon not taking task X" from the board alone, using only data the
  two list calls and the WIP-limit config already carry.
- **G3** — `--json` output gives external monitors (cron scripts) a stable
  machine-readable feed for tracker-side alert rules ("queue growing",
  "escalations sitting too long") — the tracker-owner complement to the
  snapshot-side rules of `add-serve-observability`.

## Non-Goals

- **NG1** — no daemon data on the board: snapshot/ledger content (slots,
  vitals, history) stays with `add-serve-observability`; composition of
  the three surfaces is the future dashboard change, not this one.
- **NG2** — no HTML rendering or `--watch` mode (deferred to
  `add-dashboard-page`).
- **NG3** — no tracker writes of any kind: the board never claims, parks,
  comments, or mutates markers.
- **NG4** — no extension of `gnomish status` and no CLI namespaces
  (`gnomish tracker status`); the flat-CLI convention stands.
- **NG5** — no new tracker port *operations*: the board is built entirely
  on `listReady` + `listOpen`; only the existing list types are enriched.
- **NG6** — no fleet aggregation logic: `listOpen` naturally shows every
  instance's claims project-wide; the board displays that as-is without
  grouping or per-instance statistics.
- **NG7** — the board does not predict *which* eligible task the feed claims
  first or *how deep* it reads: the feed's random head-zone pick
  (`FeedPolicy` D4) and its `FEED_LIMIT` read window are claim-ordering and
  read-depth concerns, not per-task eligibility. The board annotates whether
  a task is claimable, not its place in a future claim race.

## Users & Scenarios

- **U1 — morning check**: the operator runs `gnomish board` before the
  workday: sees 7 tasks queued (2 in backoff until 09:14), 3 in work across
  two instances, 1 escalation waiting — and starts with the escalation.
- **U2 — "why is 42 stuck?"**: a task is visibly ready but the daemon
  ignores it; the board's Ready column shows `#42 … backoff until 14:02
  (3 aborts)` — no log archaeology needed.
- **U3 — external monitor**: a cron script polls `gnomish board --json`
  every few minutes and alerts when the ready count grows monotonically or
  an AwaitingHuman entry exceeds an age threshold — tracker-side rules
  that work even when no daemon is running.

## Requirements

### Functional

- **FR1** — `gnomish board` is a new subcommand; it takes `--dir` (default:
  current directory) naming the project config root and resolves the tracker
  section from that root's `.gnomish/config.yaml` exactly as `take`/`serve`
  do, then performs read-only tracker access.
- **FR2** — the **Ready** column lists ready tasks in the adapter's queue
  order, each row carrying: task id, title, the returned/fresh distinction,
  and — when the feed would not claim the task now — an eligibility
  annotation naming the reason, in the feed's own precedence order: in
  backoff (with the deadline, computed from the task's abort facts by the
  same core policy the feed uses, no reimplementation), then `finished`
  (terminal/reopened, defensively dropped by the feed), then WIP-held (a
  fresh task the feed skips while the open-front count is at or above the WIP
  limit). Returned tasks are never WIP-held.
- **FR3** — the Ready column is summarized as: total queued, eligible now
  (what the feed would actually claim), and the ineligible count broken down
  by reason in the same precedence order — in backoff, `finished`, WIP-held
  (e.g. "7 queued, 3 eligible, 2 in backoff, 1 finished, 1 WIP-held"). Each
  ready task counts under exactly one reason, so the parts sum to total
  queued. All counts are scoped to the fetched window (FR6's `--limit`): on a
  truncated window "queued" describes the shown entries, not the tracker's
  full queue, and the report flags the truncation.
- **FR4** — the **Working** column lists open tasks in `Working` state with
  holder and claim-marker freshness (the claim version's last-update time,
  rendered as age). When the claim marker is absent (a `Working` task whose
  marker went missing — `OpenTask.claimVersion` null), the row shows the
  holder with freshness marked unknown and no age. Working tasks the adapter
  omits for carrying no claim footprint at all (human mislabel) are absent
  from the board by design — it reports exactly what `listOpen` returns.
- **FR5** — the **AwaitingHuman** column lists parked tasks with their park
  reason (`escalation` / `infra` / `checkpoint`).
- **FR6** — output is human-readable text by default and a stable JSON
  document under `--json`, following the status-report v1 field
  conventions (camelCase, ISO-8601 UTC instants, `"version": 1`).
- **FR7** — `ReadyTask` and `OpenTask` carry the task title; both the
  GitHub and in-memory adapters populate it from their existing list-call
  responses, and the port contract suite verifies title propagation for
  every adapter.

### Non-Functional — Performance

- **NFR-P1** — one board invocation issues exactly one `listReady` and
  one `listOpen` port call and adds no per-row `fetchTask` fan-out of its
  own; title enrichment adds zero tracker requests over each list
  operation's pre-enrichment shape. The board contributes no read cost
  beyond those two list operations — whatever HTTP requests they make
  internally is the adapter's existing behavior (for GitHub, per-task
  comment fetches whose repeat cost is absorbed by conditional `304`s),
  not something the board adds.

### Non-Functional — Reliability

- **NFR-R1** — tracker unavailability produces a single clear error line
  and a non-zero exit code; transient-failure handling reuses the tracker
  adapter's existing retry policy, with no board-specific retry loop.

### Non-Functional — Observability

- **NFR-O1** — the `--json` document is self-describing enough for
  unattended consumers: versioned, with a generation timestamp, requiring
  no access to factory configuration to interpret — each ready entry carries
  its resolved eligibility (eligible, or the skip reason), backoff deadlines
  are materialized as instants, and the WIP gate is expressed as the
  observed open-front count against the limit, none left to be recomputed. A
  `truncated` flag distinguishes a capped ready window from a fully-shown
  one, so a consumer never mistakes a windowed "queued" count for the whole
  queue.

### Non-Functional — Security

- **NFR-S1** — the board reuses the existing tracker credential
  configuration; no new secrets, scopes, or write permissions are
  introduced or required.

*(Cost NFR considered: no LLM or paid-API usage beyond the two tracker
read calls — not applicable.)*

## Operator Experience Criteria

- **UX1** — the three columns read like a kanban board the operator
  already knows: Ready / Working / AwaitingHuman, in that order.
- **UX2** — every row leads with the task id and title; detail beyond the
  board's columns is reachable through the existing canon (`gnomish
  status <id> --dir …`, the tracker UI), which the board does not duplicate.
- **UX3** — the command works identically with zero daemons running; it
  never implies daemon state (no "slots", no "vitals").
- **UX4** — `--json` and text render the same underlying model; nothing is
  JSON-only or text-only.

## Success Metrics

- **M1** — the three scenarios U1–U3 are each satisfiable with a single
  board invocation (verified by specs covering each column and the JSON
  surface).
- **M2** — the board makes exactly one `listReady` and one `listOpen` port
  call and no `fetchTask`, and title enrichment adds no issue-detail request
  over the pre-enrichment shape — asserted by a port-call count and a GitHub
  WireMock spec comparing the recorded requests before and after enrichment.
- **M3** — both tracker adapters pass the extended port contract suite
  (title propagation) without adapter-specific exceptions.

## Open Questions

- **Q1** — the planned Jira adapter must be able to populate titles from
  its search/list responses (Jira search returns `summary`); confirm when
  that adapter is designed — the port change assumes list responses carry
  titles, which holds for GitHub and in-memory today.

## Impact

- **App layer**: new `BoardCommand` + arguments parser + renderers;
  `Subcommand` enum and `SubcommandDispatch` gain one entry.
- **Port types**: `ReadyTask`, `OpenTask` (new `title` component); no new
  `Tracker` operations.
- **Adapters**: GitHub feed/open query parsers retain the title they
  already receive; in-memory tracker maps title from its stored snapshot.
- **Tests**: port contract suite extended; new specs for board
  composition, rendering, and the JSON surface; WireMock request-count
  assertion.
- **Docs**: operator guide gains a board section (including the cron
  monitor recipe of U3).
