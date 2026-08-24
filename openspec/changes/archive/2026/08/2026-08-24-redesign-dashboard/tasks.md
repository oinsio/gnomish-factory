# Tasks: redesign-dashboard

The prescribed stylesheet, client script, and markup skeleton are stored
verbatim in this change's `resources/` directory — copy them, do not
re-derive them. Design decisions D1–D7 in `design.md` govern the wiring.
Where implementation found a defect in a prescribed resource, the resource
here was corrected to match what ships, so "verbatim" stays a fact rather
than a stale claim; task 1.1 lists what changed and why.

## 1. Resources and renderer wiring

- [x] 1.1 Add `application/src/main/resources/dashboard/dashboard.css` and
      `dashboard.js`, copied verbatim from this change's `resources/`
      directory (D1). Five corrections were made during implementation and
      folded back into `resources/`, so the two copies are identical:
      - the attention-row border reads `color-mix(in srgb, var(--warn-dot)
        35%, transparent)` instead of a literal `rgba(...)`, which NFR-O1
        forbids outside `:root`
      - the rules the redesigned markup needs but the draft omitted:
        `.freshness__icon--*` state switching, `.status--stopped`,
        `.row__icon` / `.row__label` / `.row__dot--ready` / `.row__count`,
        and the shared `svg` sizing
      - the 640 px block gained `.row { flex-wrap: wrap }` and a full-width
        `.row__age`, without which a long title overflows at 375 px (UX4)
      - `.row:first-of-type` never matched — the block head is the card's
        first element of its type — so the first row's hairline is dropped
        by `.card__head + .row` instead (FR10)
      - the script drops the freshness strip's own `<time>` from the ticking
        list and guards the `sessionStorage` write in the `beforeunload`
        handler, which runs long after the draft's `try` block returned
        (UX5)
      - the script reads its stale threshold from `data-stale-after` (three
        times the cadence the page was actually rendered with) instead of a
        hardcoded 30 s, keeps the 30 s value only as a fallback pinned to
        the shipped cadence by a spec, and bails out early — leaving the
        server-rendered absolutes in place — when the strip or a parseable
        `data-generated-at` is missing (FR3, NFR-R2)
- [x] 1.2 TDD: spec first — renderer construction fails when either
      classpath resource is missing or blank, and rejects resource content
      containing `</` + `script` (case-insensitive); every refusal names the
      file and the remedy; then load both resources once at construction
      into final fields and inline them per render (FR10, NFR-R1, D1, D2)

## 2. Formatting helpers (TDD, PIT-gated)

- [x] 2.1 TDD: compact number formatter — below 1000 as-is; ≥ 1000 at
      three significant digits with trailing zeros dropped (`25.6K` /
      `4.79M` / `5M`); boundary specs for exactly 1000, exactly 1M, and a
      dropped `.0` (FR9, D5, M2)
- [x] 2.2 TDD: percentage arithmetic for outcome-mix and cache-share —
      integer percentages, zero total does not divide, a single-outcome day
      comes out at 100% (FR6, FR7, D5, M2)
- [x] 2.3 TDD: relative/absolute time rendering — every server-written
      timestamp emitted as `<time datetime="…" data-epoch="…">absolute</time>`
      with legible server-rendered absolute text (FR8, NFR-R2, UX2, D4)

## 3. Page skeleton and priority layers

- [x] 3.1 Rebuild `DashboardHtmlRenderer` output on the skeleton in
      `resources/markup-skeleton.html`: fixed block order — freshness strip,
      status line, waiting-for-a-human, in-progress, outcomes by day,
      tokens, sandbox-hygiene footnote; `<body data-generated-at
      data-mode="watch|oneshot">`, plus `data-stale-after` (three times the
      cadence) in watch mode; emit `<meta http-equiv="refresh"
      content="10">` in watch mode only (FR1, FR10, D3, D6)
- [x] 3.2 Status line card: state dot and text ("Daemon running" /
      "Snapshot not updating"), instance id, snapshot `writtenAt` as a
      `<time>`, right-aligned slots and consecutive-failures stats;
      failures in `--bad-fg` only when non-zero (existing daemon-section
      semantics, new presentation). Every triggered alert condition
      renders as a short `--bad-fg` line (`.status__alert`) inside this
      card — the daemon rules 1–5 (dead daemon, occupied slot with
      heartbeat not running, long idleBlocked, growing consecutive
      failures, stale reaper / growing restartCount) and the
      sandbox-hygiene alerts (sweep tick overdue, consecutive
      skipped-no-verdict, `tracked` stopped-orphan incident) — preserving
      the existing `AlertConditionEvaluator` /
      `SandboxHygieneAlertEvaluator` semantics
- [x] 3.3 Waiting-for-a-human block: `card--attention` treatment when
      non-empty with per-task rows (icon by park reason —
      escalation / checkpoint / infra, id, one-line reason with ellipsis,
      escalation age); empty state "The queue is empty — the gnomes are
      managing on their own" with check glyph; drop unavailable fields, no
      placeholders — today that drops the reason text and escalation age
      (Q1); record dropped fields for the PR description
      (FR2, FR4, UX1, Q1)
- [x] 3.4 In-progress block: single compact row list for Ready + Working
      from the board model's own fields (dot, id; working rows: holder and
      claim age from `claimVersion`; ready rows: short eligibility note —
      backoff deadline, `finished`, WIP-held), ready items marked, a
      truncation indicator when the ready window was capped, one empty
      state "A slot is free, no ready tasks in the tracker" (FR2, FR5)
- [x] 3.5 Outcomes by day: full-width stacked mix bar per day (delivered /
      awaitingHuman / aborted / revoked, proportional within the day), date
      and total as numbers, one shared legend, `role="img"` aria-label;
      empty state "No finished tasks yet" (FR2, FR6)
- [x] 3.6 Tokens block: header with grand total and period; per model a
      stacked cacheRead / cacheCreation / input / output bar plus caption
      leading with integer cache share (`90% from cache · in 3.7K · out
      28.8K`); zero cache renders "cache not in use"; `--seg-*` palette
      only, never the status palette (FR7)
- [x] 3.7 Sandbox hygiene: dashed-border footnote "Sandbox sweep has not run
      yet" when no sweep data; normal card when data exists.
      Hygiene *alert* conditions do not render here — they surface as red
      lines in the status card (task 3.2); this block carries only the
      four-group breakdown of the last tick — the kept-environment
      inventory and the stop/dispose actions table are dropped, per the
      delta's modified "Sandbox hygiene section" requirement (FR2)
- [x] 3.8 Apply compact formatting + exact-value `title` to every count and
      `num` (mono, tabular-nums) styling to every numeric cell (FR9, UX3)
- [x] 3.9 Each block carries its data timestamp and degradation state
      (existing capability semantics, new placement): the board-fed blocks
      (waiting-for-a-human, in-progress) show the board fetch time in
      `card__meta`, plus a one-line refresh-failure notice when the last
      refresh failed (cached model kept), or an "unavailable + failure
      summary" empty-style state when no fetch ever succeeded; outcomes /
      tokens show the ledger day range; the status card shows snapshot
      `writtenAt` (task 3.2)

## 4. Freshness strip and banner removal

- [x] 4.1 Freshness strip markup: full-width strip above all blocks, two
      inline SVG icons switched per `data-state` by CSS, text span
      `#freshness-text` (FR3, D3)
- [x] 4.2 Delete `DashboardStalenessBannerRenderer`, the
      `#staleness-banner` element, its CSS, and its inline script; verify
      `staleness-banner` no longer occurs anywhere in the codebase. At
      sync/archive, reword the capability's Purpose paragraph in
      `openspec/specs/dashboard-page/spec.md` from the "inline
      self-staleness banner" to the freshness strip (FR3, M1)
- [x] 4.3 Keep the one-shot page free of stale degradation: the
      `data-mode` guard in `dashboard.js` shows the snapshot age as plain
      information ("one-shot snapshot · taken N min ago") instead of the
      stale strip and dimming, and no meta-refresh is emitted (task 3.1),
      per the modified staleness requirement

## 5. Verification

- [x] 5.1 Run `./gradlew check` — Spotless, Error Prone + NullAway, JaCoCo,
      and the 100% PIT mutation gate all green; any new exemption follows
      `.claude/rules/testing.md` (M1, M2)
- [x] 5.2 Walk the acceptance checklist against a rendered page: `file://`
      offline fully styled; light and dark legible (bar segments and strip
      specifically); strip turns red within 30 s without reload and cards
      stay readable through dimming; no-JS shows every number and timestamp
      absolute; all empty states present; numeric columns stable across
      refresh; hover reveals ISO instant; nothing overflows at 375 px
      (a manual browser check — no automated spec renders a viewport);
      scroll position survives the meta-refresh reload (M1, NFR-O1, UX4,
      UX5)
- [x] 5.3 Confirm the "do not" list: no full-viewport overlay, no hidden
      empty sections, no CDN/webfont/chart library/build step, no JS-only
      values, no volume-scaled outcome bars, no status palette on spend, no
      animation beyond the one opacity transition, no literal hex outside
      `:root`, no new network requests or data sources (NG3, NG4, NFR-O1,
      NFR-P1)
- [x] 5.4 Draft the PR description noting any escalation-row fields dropped
      because the tracker port does not expose them — `pr-description.md`
      in this change folder (Q1)
