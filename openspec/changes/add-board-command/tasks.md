# Tasks: add-board-command

## 1. Titles in tracker list types (FR7, NFR-P1)

- [ ] 1.1 Extend the port contract suite with title-propagation properties
      for `listReady` and `listOpen` (red first, TDD) — FR7
- [ ] 1.2 Add the `title` component to `ReadyTask` and `OpenTask`;
      compiler-driven sweep of all construction sites (core, fixtures,
      existing specs) — FR7
- [ ] 1.3 In-memory adapter: map `title` from the stored snapshot in both
      list operations; contract suite green — FR7
- [ ] 1.4 GitHub adapter: extend the shared issue-feed parser to retain
      `number` + `title` pairs; consume in `GithubFeedQuery` and
      `GithubOpenQuery`; contract suite green — FR7
- [ ] 1.5 WireMock spec asserting the two list calls issue no additional
      issue-detail requests after enrichment — NFR-P1, M2

## 2. Board model and composition (FR2–FR5, NFR-P1)

- [ ] 2.1 Spock spec + immutable `BoardModel` (three columns, summary
      counts, `truncated` flag, `generatedAt`) built from one `listReady`
      and one `listOpen` result; rows in every column preserve the adapter's
      list order so the model is deterministic — FR2–FR5, NFR-P1 (design D5)
- [ ] 2.2 Eligibility annotation mirroring the feed predicate in precedence
      order — backoff (materialized deadline via `BackoffPolicy`, same
      base/cap resolution as the take feed) → `finished` → WIP-held (fresh
      task while `openFrontCount` from `listOpen` is at/above the resolved
      `factory.tracker.wip-limit`); data-driven spec over the reason
      combinations — FR2 (design D3, D7)
- [ ] 2.3 Summary counts (queued / eligible now / ineligible by reason, each
      task under exactly one reason so parts sum to queued) and
      returned-vs-fresh distinction; truncation marker when the window
      equals the limit — FR3 (design D4, D7)

## 3. CLI surface (FR1, NFR-R1)

- [ ] 3.1 Add `BOARD` to `Subcommand` and route it in
      `SubcommandDispatch`; parser spec for the token — FR1 (design D1)
- [ ] 3.2 `BoardCommand` + arguments parser (`--dir` default current
      directory, `--json`, `--limit` default 50); tracker resolution from
      `--dir`'s `.gnomish/config.yaml` reusing the existing take/serve
      assembly, minting a throwaway `InstanceId` for the read-only adapter
      construction (never written — NG3 holds) — FR1, NFR-S1 (design D8)
- [ ] 3.3 Tracker-outage handling: one clear error line, non-zero exit,
      no board-specific retry loop; spec with a failing tracker — NFR-R1

## 4. Rendering (FR6, NFR-O1, UX4)

- [ ] 4.1 Text renderer (sibling of `TaskListRenderer`): three columns,
      eligibility annotations (backoff / `finished` / WIP-held), claim ages
      (freshness "unknown" when `claimVersion` is null — display-only age per
      D6), park reasons, summary line — FR2, FR4, FR5, UX1
- [ ] 4.2 JSON mapper (sibling of `StatusReportJsonMapper`): `"version":
      1`, camelCase, ISO-8601 UTC, `generatedAt`, per-entry resolved
      eligibility (reason or eligible), materialized backoff deadlines,
      open-front count vs WIP limit, claim `updatedAt` (explicit unknown when
      absent), and a `truncated` flag for the ready window — FR6, NFR-O1
- [ ] 4.3 Reference JSON fixture (`board-v1.reference.json`) + spec
      pinning the document shape; text/JSON agreement spec (same model,
      same facts on both surfaces) — FR6, UX4, M1

## 5. Docs and verification

- [ ] 5.1 Operator guide (`docs/operator-guide.md`): board section (columns,
      backoff semantics, missing-marker/mislabel omission as known behavior)
      and the cron-monitor recipe over `--json`; explicitly disambiguate
      `gnomish board` (this CLI view) from the existing "Projects v2 Boards:
      Display Only" section (`board-bridge.yml`) so the two "board" terms do
      not collide — UX1, G3, U3
- [ ] 5.2 Full test run (`./gradlew check`), PIT on the new Java units,
      traceability grep: every FR/NFR/UX of this change maps to at least
      one spec or code reference — M1–M3
