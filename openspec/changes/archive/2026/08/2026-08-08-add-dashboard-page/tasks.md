# Tasks: add-dashboard-page

## 1. Data readers (FR3, FR4, FR6)

- [x] 1.1 Snapshot reader: parse `snapshot.json` into a view model;
      staleness from `writtenAt` + `intervalSeconds`; distinguish missing
      file / stale in any non-`stopped` state (`running`, `draining`,
      `stopping`) / stale-at-stopped; Spock spec with reference snapshot
      fixtures — FR3, FR4
- [x] 1.2 Alert-condition evaluation over the snapshot view model —
      operator-guide rules 1–5 (stale snapshot while not `stopped`,
      occupied slots with heartbeat not `running`, long `idleBlocked`,
      growing tracker `consecutiveFailures`, stale reaper `lastRunAt` /
      growing `restartCount`; rule 6 excluded — needs check-to-check
      history, the external monitor's job); data-driven spec per rule
      — FR4 (design D3)
- [x] 1.3 Ledger aggregator: read the last N daily files, sum
      `taskOutcome` lines into outcomes-per-day and tokens-by-model, skip
      torn/unparseable lines; spec incl. torn-last-line and missing-file
      cases — FR6 (design D5)

## 2. Board reuse (FR5)

- [x] 2.1 Extract the board fetch+build composition from `BoardCommand`
      (two list calls, backoff/WIP parameter resolution,
      `BoardModel.build` + `EligibilityPolicy`) into a reusable component
      both the command and the dashboard call in-process (refactor,
      behavior pinned by the existing board specs) — FR5 (design D7,
      proposal Q3)
- [x] 2.2 Spec: dashboard board data equals the board command's model for
      the same tracker state (same composition, same backoff deadlines)
      — FR5

## 3. HTML rendering (FR2, FR8, FR10, NFR-O1)

- [x] 3.1 `DashboardHtmlRenderer` (string-template sibling of the text
      renderers): three sections, degradation placeholders, inline CSS;
      section data timestamps and page `generatedAt` — FR2, FR3, FR10,
      NFR-O1 (design D6)
- [x] 3.2 Self-containment and content-boundary spec: scan rendered
      output for external references (`http://`, `https://`,
      protocol-relative); assert the page carries only composed-surface
      data (no credentials, prompts, or stage artifacts) — FR2, NFR-S1,
      M1
- [x] 3.3 Staleness banner (`--watch` pages only): bake `generatedAt` +
      cadence, inline JS compares against the browser clock, banner
      beyond `k ×` cadence; one-shot pages show the `generatedAt` age
      with no banner; spec on the baked values, script presence, and the
      bannerless one-shot case (JS logic unit-testable as a pure string
      with known inputs where feasible) — FR8, M3
- [x] 3.4 Alert-condition flags rendered visibly (section highlight);
      spec per flagged condition — FR4, UX3

## 4. CLI surface and watch loop (FR1, FR7, FR9, NFR-R1, NFR-R2)

- [x] 4.1 Add `DASHBOARD` to `Subcommand` and `SubcommandDispatch`;
      `DashboardCommand` + arguments parser (`--dir`, `--out`,
      `--watch`); config resolution from `--dir` as in board/status;
      default output path from the instance-directory resolution; parser
      spec — FR1
- [x] 4.2 Atomic write: temp + rename per render; spec observing only
      complete files at the target path — NFR-R2
- [x] 4.3 One-shot mode: single render, exit zero even with degraded
      sections; spec for fresh-install and tracker-outage renders — FR3,
      FR7
- [x] 4.4 Watch loop: render cadence with baked meta-refresh; board
      refreshed on its own cadence with cached model between refreshes
      (fetch time displayed); on board-refresh failure keep the cached
      model with a refresh-failure notice; loop survives source failures;
      specs with a controllable clock — FR7, FR9, NFR-R1, NFR-P1, M2

## 5. Docs and verification

- [x] 5.1 Operator guide: dashboard section — wall-display recipe
      (`--watch` + `file://` tab, both staleness layers explained),
      ticket-snapshot recipe (`--out`), and the documented constants
      (10 s render cadence, 60 s board cadence, `k = 3`, 7-day history
      window) — UX1–UX4, U1, U2
- [x] 5.2 Full test run (`./gradlew check`), PIT on the new Java units,
      traceability grep: every FR/NFR/UX of this change maps to at least
      one spec or code reference — M1–M4
