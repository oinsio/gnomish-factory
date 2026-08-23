# Proposal: redesign-dashboard

## Why

The dashboard is a wallboard for an orchestrator where humans are exception
handlers, not participants. It is watched two ways — parked on a second
monitor for hours, and opened in a tab for a few seconds — and both modes ask
the same question first: **is anything waiting for me right now?** The current
page cannot answer it at a glance: four sections carry equal visual weight
with `awaitingHuman` buried in a table column, timestamps are raw ISO-8601 UTC
the reader must subtract mentally, counts render as raw longs
(`4305175`), empty lists read as breakage, the staleness banner covers the
whole viewport and hides the last known state, and staleness is computed once
at load so a dead renderer can go unnoticed until the next reload.

## What Changes

- **MODIFIED**: the page's information architecture becomes four explicit
  priority layers — freshness strip, status line, "waiting for a human"
  (loudest block), "in progress" — followed by visually quieter reference
  blocks (outcomes by day, tokens, sandbox hygiene). The board section's
  columns are re-homed into the two board-fed blocks as compact rows (same
  composition and semantics, condensed annotations). Blocks never appear or
  disappear; empty blocks render explicit "all clear" states.
- **MODIFIED**: the full-viewport staleness banner is **REMOVED** and replaced
  by a persistent full-width freshness strip plus card dimming; staleness
  degrades the page, it never blocks it, and it is re-evaluated every second
  client-side instead of once at load.
- **ADDED**: relative time rendering ("N мин назад") with a 1-second client
  ticker; server-rendered absolute time remains the no-JS fallback and moves
  to `title` on hover.
- **ADDED**: compact server-side number formatting (`4.79M`, `25.6K`) with the
  exact value in `title`; tabular numerals so columns do not jitter across
  refreshes.
- **MODIFIED**: history table and abstract volume bar are replaced by per-day
  stacked outcome-mix bars (full-width, mix not magnitude) and per-model token
  bars with a cache-share caption.
- **MODIFIED**: the sandbox-hygiene section shrinks to the quietest
  reference block: the last tick's four-group breakdown only. The
  kept-environment inventory (ages, time-to-reap) and the recent
  stop/dispose actions table are dropped — per-object depth stays with the
  snapshot, the ledger, and `gnomish status <id>` — and hygiene alerts
  surface in the status card instead of this block.
- **MODIFIED**: page source is split — `dashboard.css` and `dashboard.js`
  become classpath resources inlined at render time; the output stays one
  self-contained HTML file.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `dashboard-page`: presentation requirements change — priority-layered
  layout with persistent blocks and explicit empty states; freshness strip +
  dimming replaces the full-page staleness banner, with a 1 Hz client-side
  re-check; relative time and compact numbers with no-JS absolute fallbacks;
  outcome-mix and token bars; sandbox-hygiene block reduced to the last
  tick's four-group breakdown with its alerts re-homed to the status card;
  CSS/JS extracted to resources but inlined into the single-file output.

## Goals

- G1: an operator answers "is anything waiting for me?" from the top two
  visual layers without scrolling or reading a table.
- G2: a dead renderer is visible on the open tab within 30 s without a page
  reload.
- G3: the page remains fully legible with JavaScript disabled and over
  `file://` with no network.

## Non-Goals

- NG1: cost estimation for token spend.
- NG2: `dashboard.json` + fetch/patch shell (needs an HTTP server; explicitly
  deferred). The markup must stay shaped for it: mutable values live in
  elements with stable classes/ids, the script reads inputs from data
  attributes, not templated literals.
- NG3: theme toggle or persisted theme preference — automatic
  `prefers-color-scheme` only.
- NG4: any external resource, webfont, chart library, or CSS build step.
- NG5: changes to data collection, cadences, ports, or the CLI contract of
  `gnomish dashboard`.

## Users & Scenarios

- U1: operator with the page parked on a wall monitor — glances up, reads the
  freshness strip and the "waiting for a human" block, goes back to work or
  intervenes.
- U2: operator opening the page in a tab for a few seconds — same question,
  same top-of-page answer; reference blocks below are consulted deliberately.
- U3: operator returning after the renderer died — sees the red strip with a
  climbing counter and the last known state still readable under dimming.

## Requirements

### Functional

- FR1: the page SHALL present four priority layers in fixed order — freshness
  strip, status line, waiting-for-a-human, in-progress — followed by quieter
  reference blocks (outcomes by day, tokens, sandbox hygiene). No later layer
  may be visually louder than an earlier one.
- FR2: blocks SHALL never appear or disappear with data; each block SHALL
  render an explicit empty-state sentence (empty escalation queue, empty
  board, no outcome days, no sweep data) instead of an empty list or a
  missing section.
- FR3: the freshness strip SHALL replace the full-page staleness banner
  entirely (element, CSS, and inline script removed). Fresh state shows an
  upward-ticking age; stale state — age above three times the render cadence
  (30 s at the fixed 10 s cadence) — turns the strip red and dims — never
  hides — the cards. The check SHALL run every second client-side.
- FR4: the waiting-for-a-human block SHALL be the loudest element when
  non-empty (accent border and background, count, per-task rows with id,
  one-line reason, escalation age) and ordinary card chrome with an "all
  clear" sentence when empty. Unavailable per-task fields are dropped, not
  replaced with placeholders.
- FR5: the in-progress block SHALL show ready and working items as one
  compact row list built from the board model's own fields — status dot,
  task id, for working rows the holding instance and claim age, for ready
  rows a short eligibility note (backoff deadline, finished, WIP-held) —
  keeping
  Ready and Working distinguishable without separate headings or separate
  empty states.
- FR6: outcomes SHALL render as one full-width stacked mix bar per day
  (delivered / awaitingHuman / aborted / revoked, proportional within the
  day), the daily total as a number, one shared legend; bars SHALL NOT be
  scaled by daily volume.
- FR7: tokens SHALL render per model as a stacked bar (cacheRead /
  cacheCreation / input / output) with a caption leading with the integer
  cache share; zero cache renders "кэш не используется". Spend SHALL NOT use
  the status (alarm) palette.
- FR8: every server-written timestamp SHALL be a `<time>` element carrying
  full ISO in `datetime`, epoch millis in a data attribute, and
  server-rendered absolute text; the script SHALL rewrite it to a relative
  form every second and move the absolute value to `title`.
- FR9: counts ≥ 1000 SHALL be formatted compactly server-side in Java (one
  decimal, `.0` dropped), exact value in `title`; numeric cells use tabular
  numerals and the mono stack. Percentages are integers.
- FR10: `dashboard.css` and `dashboard.js` SHALL live as normal files under
  `src/main/resources/dashboard/` and be inlined into the output at render
  time; the rendered page SHALL remain one self-contained HTML file. A
  `--watch` page keeps `<meta http-equiv="refresh" content="10">`; a
  one-shot page carries no meta-refresh, and the page's mode is baked as a
  `data-mode` attribute so the static script can tell the two apart.

### Non-Functional

- NFR-R1: the renderer SHALL read both resources once at construction and
  fail fast at construction if either is missing; inlining SHALL be guarded
  against premature `</script>` termination.
- NFR-R2: with JavaScript disabled every number and timestamp SHALL still be
  present in absolute/exact form; no displayed value may be computed only in
  JS.
- NFR-O1: light and dark themes (via `prefers-color-scheme`) SHALL both be
  legible; every colour comes from the design-token block, no literal hex
  outside `:root`.
- NFR-S1: unchanged from the existing capability — the page exposes no
  credentials, prompts, or task content beyond the composed surfaces.
- NFR-P1: no new network requests, data sources, or cadence changes; the
  redesign is presentation-only.

## Operator Experience Criteria

- UX1: an empty escalation queue reads as a deliberate "all clear" (check
  glyph, calm sentence), not as an apology or a blank region.
- UX2: relative ages use short Russian forms that need no plural agreement
  (`N с назад`, `N мин назад`); hovering reveals the exact instant.
- UX3: numeric columns do not shift horizontally across the 10 s refresh.
- UX4: at 375 px width nothing overflows horizontally.
- UX5: with scripting enabled the reading position survives the 10 s
  meta-refresh reload; the refresh does not reset scroll.

## Success Metrics

- M1: all ten acceptance checks pass: `file://` offline fully styled render;
  both themes legible; strip turns red within 30 s without reload; no-JS
  completeness; explicit empty states everywhere; stable numeric columns;
  hover reveals ISO instant; 375 px fits; `./gradlew check` (incl. mutation
  gate) green; `#staleness-banner` absent from the codebase.
- M2: formatter and percentage arithmetic carry Spock specs covering the
  boundaries — exactly 1000, exactly 1M, dropped `.0`, zero total, single
  outcome day at 100% — and survive the 100% PIT gate.

## Open Questions

- Q1: which escalation fields the tracker port actually exposes for
  waiting-for-a-human rows. Known today: the board's `AwaitingHumanRow`
  carries the task id, title, and the park reason category (`ParkReason`:
  escalation / checkpoint / infra); a one-line reason text and the
  escalation timestamp are not exposed and are dropped per FR4 until the
  port grows them, noted in the PR description.
