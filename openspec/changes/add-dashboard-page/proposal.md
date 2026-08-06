# Proposal: add-dashboard-page

## Why

After `add-serve-observability` and `add-board-command`, the operator has
three healthy but separate surfaces: the daemon snapshot ("alive? busy?"),
the ledger ("what ran and what was spent"), and the tracker board ("queue,
work in progress, escalations"). A morning check or a wall monitor requires
composing them by hand — three commands, three mental merges. The factory
needs one glanceable page over all three, without violating the standing
constraints: no inbound HTTP (add-factory-serve NG3), no web stack
(ADR 0001), tight tracker read budget. An HTML *page* is not an HTTP
*server* — a self-contained file rendered by a CLI command composes the
three surfaces with zero new network surface area.

## What Changes

- **ADDED**: `gnomish dashboard` — a CLI command that renders a
  self-contained HTML page (all data, CSS, and JS inlined; zero external
  requests) composing three independently degrading sections: daemon
  (snapshot), history (ledger aggregate), and tracker board.
- **ADDED**: `--watch` mode — periodic re-render plus in-page
  `<meta http-equiv="refresh">`, turning a plain browser tab over a
  `file://` URL into a live wall display with no server involved.
- **ADDED**: two-layer staleness detection visible on the page: a stale
  *snapshot* flags the daemon section; a stale *page* (dead `--watch`
  renderer) triggers a full-page banner via inline JS.
- No daemon changes, no tracker port changes, no new tracker operations:
  the renderer is one more reader of the existing file and tracker-read
  contracts.

## Capabilities

### New Capabilities

- `dashboard-page`: the `gnomish dashboard` command — composition of the
  three observability surfaces into a self-contained HTML page: section
  content and degradation, staleness semantics, watch mode, refresh
  cadences, and output location.

### Modified Capabilities

_None._ The dashboard reuses the board composition and the snapshot/ledger
file formats as a reader; no requirement of `tracker-board`,
`serve-observability`, or any other existing capability changes.

## Goals

- **G1**: One page answers all three operator questions at a glance —
  daemon alive and busy with what, what ran and was spent recently, and
  what the tracker queue looks like — each section sourced from its owner.
- **G2**: Zero servers and zero inbound network surface: the page works
  opened from `file://`, and `--watch` provides a live view using only
  file regeneration and browser-side refresh.
- **G3**: The wall display never silently lies: both a dead daemon and a
  dead renderer are visibly flagged on the page itself.
- **G4**: A one-shot render is a single portable file, suitable for
  attaching to a ticket or escalation as a factory-state snapshot.

## Non-Goals

- **NG1**: No HTTP server, no sockets, no inbound endpoint of any kind —
  the page is a file; live behavior comes from re-rendering plus browser
  refresh.
- **NG2**: No external assets ever — no chart libraries, CDNs, webfonts,
  or images. History visuals stay at table + CSS-bar fidelity; wanting
  real graphs is the signal to move to an external collector + Grafana
  (add-serve-observability G3), not to grow the page.
- **NG3**: No fleet view: the page renders one instance's snapshot and
  ledger (the board's Working column naturally shows all holders, as on
  the CLI board). Fleet aggregation belongs to an external collector.
- **NG4**: No daemon involvement: the daemon neither knows about nor
  waits for the dashboard; the renderer only reads what the daemon and
  tracker already publish.
- **NG5**: No interactivity beyond viewing: the page performs no actions,
  no tracker writes, and no fetches; per-task depth stays with the
  existing canon (`gnomish status <id>`, the tracker UI).
- **NG6**: No streaming/push freshness guarantees: freshness is bounded
  by the documented cadences, not real-time.

## Users & Scenarios

- **U1 — wall display**: the operator runs `gnomish dashboard --watch`,
  opens the output file in a browser tab once, and glances at it through
  the day; sections redden on daemon death, stuck escalations, or tracker
  outage, and the whole page banners if the renderer itself dies.
- **U2 — snapshot for a ticket**: before escalating an incident, the
  operator runs one-shot `gnomish dashboard --out incident.html` and
  attaches the single file — reviewers see daemon state, recent history,
  and queue exactly as observed, with the observation time baked in.
- **U3 — morning after a drain**: the operator opens the page and reads
  the history section — outcomes by day and tokens by model for the last
  days — without running `jq` over ledger files.

## Requirements

### Functional

- **FR1**: `gnomish dashboard` SHALL be a new subcommand rendering a
  single HTML file; it SHALL resolve configuration (tracker, instance
  name, backoff parameters) from `--dir <clone>` exactly as
  `gnomish board` and `gnomish status` do; default output path is
  `dashboard.html` inside the instance's observability directory (keyed
  by instance name, per add-serve-observability FR9), overridable with
  `--out`.
- **FR2**: The page SHALL be fully self-contained: data, styles, and
  scripts inlined; rendering in a browser from `file://` SHALL trigger
  zero network requests.
- **FR3**: The page SHALL contain three sections — daemon (snapshot),
  history (ledger aggregate), tracker board — that degrade independently:
  a missing snapshot renders "daemon has not run here", an unreadable or
  absent ledger renders an empty history, an unreachable tracker renders
  the board section from the last cached board model (marked with its
  fetch time and a refresh-failure notice) or as unavailable with the
  failure summarized when no fetch has succeeded; a degraded section
  SHALL never fail the render of the others.
- **FR4**: The daemon section SHALL compute snapshot staleness from the
  file's own `writtenAt` + `intervalSeconds` (add-serve-observability
  FR2) and SHALL visually flag the operator-guide alert conditions
  observable from a single snapshot — rules 1–5 of the guide's six:
  stale snapshot in any non-`stopped` lifecycle state (dead daemon),
  occupied slots with heartbeat not `running`, long `idleBlocked`,
  growing tracker `consecutiveFailures`, stale reaper `lastRunAt` or
  growing `restartCount`. Rule 6 (`heldClaims` vs slot-count desync on
  two consecutive checks) needs check-to-check history and stays with
  the external dead-man's-switch monitor.
- **FR5**: The board section SHALL present the same three columns with
  the same semantics as `gnomish board` (Ready with the full eligibility
  annotation — backoff with deadline, `finished`, WIP-held — and
  truncation marker, Working with holder and claim age, AwaitingHuman
  with park reason), produced by the same composition and eligibility
  logic in-process — not by shelling out and not by a reimplementation.
- **FR6**: The history section SHALL aggregate the ledger files of the
  last N days into outcomes per day and tokens by model, rendered as
  tables with CSS bars; the reader SHALL tolerate a torn last ledger line
  (per the add-serve-observability NFR-R2 reader contract).
- **FR7**: `--watch` SHALL re-render the file on a fixed cadence and bake
  a matching `<meta http-equiv="refresh">` into the page; without
  `--watch` the command renders once and exits (one-shot is the default).
- **FR8**: A `--watch`-rendered page SHALL carry its `generatedAt` and
  refresh cadence plus an inline script comparing `generatedAt` against
  the browser clock; when the page's age exceeds a multiple of the
  refresh cadence, a full-page banner SHALL declare the view stale —
  meta-refresh alone SHALL NOT be relied on for this. A one-shot page
  SHALL show its `generatedAt` age as plain information and SHALL NOT
  banner: it is a point-in-time snapshot, reviewable long after capture.
- **FR9**: In `--watch` mode the snapshot and ledger SHALL be re-read on
  every render cycle, while the tracker board SHALL be refreshed on its
  own slower cadence with the last result cached between board refreshes;
  the board section SHALL display when its data was fetched.
- **FR10**: Task rows on the page SHALL stay pointers — id, title, and
  the columns' own fields — with no embedded per-task reports; the page
  SHALL target a single screen without scrolling at typical queue sizes.

### Non-Functional — Performance

- **NFR-P1**: A one-shot render SHALL cost exactly the board's tracker
  read budget (one `listReady` + one `listOpen`); in `--watch` mode
  tracker reads SHALL be bounded by the board cadence regardless of the
  render cadence — a browser tab open all day SHALL NOT burn the GitHub
  rate limit.

### Non-Functional — Reliability

- **NFR-R1**: The `--watch` loop SHALL survive any data-source failure —
  tracker outage, missing or torn files — by rendering degraded sections
  and continuing; it SHALL NOT exit on transient failures.
- **NFR-R2**: Each render SHALL replace the output file atomically
  (temp + rename), so a browser refresh never observes a half-written
  page.

### Non-Functional — Observability

- **NFR-O1**: Every section SHALL carry the timestamp of its underlying
  data (snapshot `writtenAt`, board fetch time, ledger day range), and
  the page its `generatedAt` — the observation time of everything shown
  is explicit on the page itself.

### Non-Functional — Security

- **NFR-S1**: The page SHALL contain no credentials, prompts, or task
  content beyond what the composed surfaces already expose (ids, titles,
  states, counters, timestamps, token counts); with zero external
  requests, opening the file leaks nothing to any network.

*(Cost NFR considered: no LLM usage and no paid-API calls beyond the
board's tracker reads — not applicable.)*

## Operator Experience Criteria

- **UX1**: Double-clicking the output file shows the full dashboard —
  no server, no install, no network required.
- **UX2**: The page reads top-to-bottom on one screen; meta-refresh
  resetting scroll position is a non-issue because there is nothing to
  scroll to.
- **UX3**: Staleness is never ambiguous: a red daemon section means the
  daemon is stale; a full-page banner means the view itself is stale —
  the operator never mistakes one for the other.
- **UX4**: The one-shot file is portable: attached to a ticket, it renders
  identically for the reviewer.

## Success Metrics

- **M1**: The generated HTML contains no external references — verified
  by a spec scanning the output for `http(s)://`, protocol-relative, and
  other non-inline resource URLs.
- **M2**: In `--watch` mode with default cadences, tracker read calls
  over one hour stay within the board-cadence budget (one
  `listReady` + `listOpen` pair per board refresh interval), asserted by
  a test over the cadence logic.
- **M3**: Kill the daemon → the daemon section is flagged within
  `k × intervalSeconds`; kill the renderer → the browser banner appears
  within the documented multiple of the refresh cadence (inline-JS logic
  verified by spec; timings by the cadence math, not wall-clock tests).
- **M4**: One-shot output is exactly one file; deleting everything else
  leaves the page fully renderable.

## Open Questions

- **Q1**: Default `--watch` render cadence (~10 s per the explore
  session) and the staleness multiple for the page banner — fix in
  design.
- **Q2**: Default history window N days and the board refresh cadence
  (~60 s per the explore session) — fix in design.
- **Q3**: Whether the board composition from `add-board-command` needs
  extraction into a reusable component or is already consumable as-is —
  resolve in design against that change's implementation.

## Impact

- **App layer**: new `DashboardCommand`, arguments parser, HTML renderer
  (string-template sibling of the existing text renderers), ledger
  aggregator, watch loop; `Subcommand` / `SubcommandDispatch` gain one
  entry.
- **Reused, read-only**: snapshot + ledger file formats
  (add-serve-observability), board model composition + eligibility
  annotation (add-board-command), instance-directory resolution.
- **Dependencies**: implementation ordered after `add-serve-observability`
  and `add-board-command` — it consumes their file formats and board
  query logic.
- **Docs**: operator guide gains a dashboard section (wall-display and
  ticket-snapshot recipes).
- No new libraries, no daemon or port changes.
