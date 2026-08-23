# Tasks: redesign-dashboard

The prescribed stylesheet, client script, and markup skeleton are stored
verbatim in this change's `resources/` directory — copy them, do not
re-derive them. Design decisions D1–D6 in `design.md` govern the wiring.

## 1. Resources and renderer wiring

- [ ] 1.1 Add `application/src/main/resources/dashboard/dashboard.css` and
      `dashboard.js`, copied verbatim from this change's `resources/`
      directory (D1)
- [ ] 1.2 TDD: spec first — renderer construction fails when either
      classpath resource is missing, and rejects resource content containing
      `</` + `script` (case-insensitive); then load both resources once at
      construction into final fields and inline them per render (FR10,
      NFR-R1, D1, D2)

## 2. Formatting helpers (TDD, PIT-gated)

- [ ] 2.1 TDD: compact number formatter — below 1000 as-is; ≥ 1000 as
      `25.6K` / `4.79M` with one decimal and `.0` dropped; boundary specs
      for exactly 1000, exactly 1M, and a dropped `.0` (FR9, D5, M2)
- [ ] 2.2 TDD: percentage arithmetic for outcome-mix and cache-share —
      integer percentages, zero total does not divide, a single-outcome day
      comes out at 100% (FR6, FR7, D5, M2)
- [ ] 2.3 TDD: relative/absolute time rendering — every server-written
      timestamp emitted as `<time datetime="…" data-epoch="…">absolute</time>`
      with legible server-rendered absolute text (FR8, NFR-R2, UX2, D4)

## 3. Page skeleton and priority layers

- [ ] 3.1 Rebuild `DashboardHtmlRenderer` output on the skeleton in
      `resources/markup-skeleton.html`: fixed block order — freshness strip,
      status line, waiting-for-a-human, in-progress, outcomes by day,
      tokens, sandbox-hygiene footnote; `<body data-generated-at
      data-mode="watch|oneshot">`; emit `<meta http-equiv="refresh"
      content="10">` in watch mode only (FR1, FR10, D3, D6)
- [ ] 3.2 Status line card: state dot and text ("Демон работает" /
      "Снимок не обновляется"), instance id, snapshot `writtenAt` as a
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
- [ ] 3.3 Waiting-for-a-human block: `card--attention` treatment when
      non-empty with per-task rows (icon by park reason —
      escalation / checkpoint / infra, id, one-line reason with ellipsis,
      escalation age); empty state "Очередь пуста — гномы справляются
      сами" with check glyph; drop unavailable fields, no placeholders —
      today that drops the reason text and escalation age (Q1); record
      dropped fields for the PR description (FR2, FR4, UX1, Q1)
- [ ] 3.4 In-progress block: single compact row list for Ready + Working
      from the board model's own fields (dot, id; working rows: holder and
      claim age from `claimVersion`; ready rows: short eligibility note —
      backoff deadline, `finished`, WIP-held), ready items marked, a
      truncation indicator when the ready window was capped, one empty
      state "Слот свободен, готовых задач в трекере нет" (FR2, FR5)
- [ ] 3.5 Outcomes by day: full-width stacked mix bar per day (delivered /
      awaitingHuman / aborted / revoked, proportional within the day), date
      and total as numbers, one shared legend, `role="img"` aria-label;
      empty state "Пока нет завершённых задач" (FR2, FR6)
- [ ] 3.6 Tokens block: header with grand total and period; per model a
      stacked cacheRead / cacheCreation / input / output bar plus caption
      leading with integer cache share (`90% из кэша · in 3.7K · out
      28.8K`); zero cache renders "кэш не используется"; `--seg-*` palette
      only, never the status palette (FR7)
- [ ] 3.7 Sandbox hygiene: dashed-border footnote "Уборка песочницы ещё не
      запускалась" when no sweep data; normal card when data exists.
      Hygiene *alert* conditions do not render here — they surface as red
      lines in the status card (task 3.2); this block carries only the
      four-group breakdown of the last tick — the kept-environment
      inventory and the stop/dispose actions table are dropped, per the
      delta's modified "Sandbox hygiene section" requirement (FR2)
- [ ] 3.8 Apply compact formatting + exact-value `title` to every count and
      `num` (mono, tabular-nums) styling to every numeric cell (FR9, UX3)
- [ ] 3.9 Each block carries its data timestamp and degradation state
      (existing capability semantics, new placement): the board-fed blocks
      (waiting-for-a-human, in-progress) show the board fetch time in
      `card__meta`, plus a one-line refresh-failure notice when the last
      refresh failed (cached model kept), or an "unavailable + failure
      summary" empty-style state when no fetch ever succeeded; outcomes /
      tokens show the ledger day range; the status card shows snapshot
      `writtenAt` (task 3.2)

## 4. Freshness strip and banner removal

- [ ] 4.1 Freshness strip markup: full-width strip above all blocks, two
      inline SVG icons switched per `data-state` by CSS, text span
      `#freshness-text` (FR3, D3)
- [ ] 4.2 Delete `DashboardStalenessBannerRenderer`, the
      `#staleness-banner` element, its CSS, and its inline script; verify
      `staleness-banner` no longer occurs anywhere in the codebase. At
      sync/archive, reword the capability's Purpose paragraph in
      `openspec/specs/dashboard-page/spec.md` from the "inline
      self-staleness banner" to the freshness strip (FR3, M1)
- [ ] 4.3 Keep the one-shot page free of stale degradation: the
      `data-mode` guard in `dashboard.js` shows the snapshot age as plain
      information ("разовый снимок · сделан N мин назад") instead of the
      stale strip and dimming, and no meta-refresh is emitted (task 3.1),
      per the modified staleness requirement

## 5. Verification

- [ ] 5.1 Run `./gradlew check` — Spotless, Error Prone + NullAway, JaCoCo,
      and the 100% PIT mutation gate all green; any new exemption follows
      `.claude/rules/testing.md` (M1, M2)
- [ ] 5.2 Walk the acceptance checklist against a rendered page: `file://`
      offline fully styled; light and dark legible (bar segments and strip
      specifically); strip turns red within 30 s without reload and cards
      stay readable through dimming; no-JS shows every number and timestamp
      absolute; all empty states present; numeric columns stable across
      refresh; hover reveals ISO instant; nothing overflows at 375 px;
      scroll position survives the meta-refresh reload (M1, NFR-O1, UX4,
      UX5)
- [ ] 5.3 Confirm the "do not" list: no full-viewport overlay, no hidden
      empty sections, no CDN/webfont/chart library/build step, no JS-only
      values, no volume-scaled outcome bars, no status palette on spend, no
      animation beyond the one opacity transition, no literal hex outside
      `:root`, no new network requests or data sources (NG3, NG4, NFR-O1,
      NFR-P1)
- [ ] 5.4 Draft the PR description noting any escalation-row fields dropped
      because the tracker port does not expose them (Q1)
