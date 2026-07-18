## 1. Scrubber unit specs (TDD, red first)

- [ ] 1.1 Create `ReferenceDumpScrubberSpec` in `com.github.oinsio.gnomish.adapter.agent` — NOT beside the scrubber in `e2e/paidsmoke`, which is excluded from the normal `test` task, so a spec there would never run in `check` (same placement rationale as the hygiene spec, design D2). Add a failing scenario: a captured result line with `total_cost_usd` and nested `costUSD` is scrubbed to a line containing neither, while every resolved model id and its four token/cache counts survive (FR1, FR4).
- [ ] 1.2 Add a failing scenario: the `permission_denials` array is removed (FR2).
- [ ] 1.3 Add a failing scenario: every per-event `uuid` and `request_id` is removed (FR3).
- [ ] 1.4 Add scenarios pinning existing behaviour: workspace path → `/workspace` (plain and JSON-escaped forms), real session id → `ref-session-<label>-1`, and the defense-in-depth collapse of stray `/Users/...`|`/home/...` prefixes → `/workspace-scrubbed` (FR5).
- [ ] 1.5 Add an idempotency scenario: scrubbing an already-scrubbed line a second time yields byte-identical output (NFR-R1).
- [ ] 1.6 Add a non-JSON-line scenario: a line that does not parse as JSON is returned with only the string scrubbing applied, no exception (design D1 fallback).

## 2. Harden the scrubber (green)

- [ ] 2.1 Extend `ReferenceDumpScrubber.scrub` to, after the existing path/session string replacement, parse the line with Jackson and recursively remove the deny-listed field names `total_cost_usd`, `costUSD`, `permission_denials`, `uuid`, `request_id`, then re-serialize compactly; fall back to the string-scrubbed line if it does not parse as JSON (FR1–FR5, design D1).
- [ ] 2.2 Rewrite the scrubber javadoc to enumerate exactly what is stripped (paths, session ids, money, `permission_denials`, `uuid`, `request_id`) and what is kept (model ids, token/cache counts, durations, message ids / `tool_use_id` linkage, `result` text, event shape), and note the deny-list is the single place to extend on future CLI fields (NFR-O1, design risk "deny-list drift").
- [ ] 2.3 Run `ReferenceDumpScrubberSpec` green.

## 3. Hygiene guard + deterministic refresh

- [ ] 3.1 In `build.gradle`, set the `referenceDumpDir` system property (pointing at `src/test/resources/stream-json-reference`) on the normal `test` task as well — under plain `test` the classpath copy in `build/resources/test` is not the committed file, so without this the hygiene spec cannot reach the real fixtures (FR6, design D2).
- [ ] 3.2 Add `ReferenceDumpHygieneSpec` in `com.github.oinsio.gnomish.adapter.agent` (NOT in `e2e/paidsmoke`, which is excluded from `test` — design D2), resolving all committed `*.reference.json` fixtures via `referenceDumpDir`.
- [ ] 3.3 Add always-on assertion features over all four fixtures (including the hand-authored `result-without-model-usage` placeholder): none contains `total_cost_usd`, `costUSD`, `permission_denials`, `"uuid"`, or `"request_id"` (M1/M2).
- [ ] 3.4 Add an always-on assertion feature: no fixture line matches `/(Users|home)/` — no absolute home path or username survives in any committed dump (NFR-S1).
- [ ] 3.5 Add always-on realism assertions: the three refreshed dumps still carry resolved model ids and the four token/cache counts under `modelUsage`; the placeholder still carries its top-level `usage` token fields (M3, G3).
- [ ] 3.6 Add the gated rewrite feature `@Requires({ System.getProperty('rescrubReferenceDumps') })`: re-scrub each committed fixture in place, line by line, with empty path/session arguments (FR6, design D2); skipped in normal `check`.
- [ ] 3.7 In the same gated feature, assert the rewrite is idempotent: a second in-memory pass over the just-written files produces byte-identical content (NFR-R1, M4).

## 4. Refresh the committed fixtures

- [ ] 4.1 Run the gated rewrite once to refresh the dumps: `./gradlew test --tests '*ReferenceDumpHygieneSpec' -DrescrubReferenceDumps=true` (zero CLI calls, zero tokens — NFR-C1).
- [ ] 4.2 Verify the metrics on the refreshed files: `grep -rE 'costUSD|total_cost_usd|permission_denials|"uuid"|"request_id"' src/test/resources/stream-json-reference` returns nothing (M1/M2) and `grep -rE '/(Users|home)/' src/test/resources/stream-json-reference` returns nothing (NFR-S1); model ids and token counts are still present (M3).
- [ ] 4.3 Re-run the 4.1 command a second time and confirm `git diff` over `src/test/resources/stream-json-reference` is empty (M4, NFR-R1).
- [ ] 4.4 Run `./gradlew test --tests '*StreamJsonReferenceDumpSpec' --tests '*ReferenceDumpHygieneSpec'` green — the parser still consumes the refreshed fixtures (G3, NG1).

## 5. Full verification

- [ ] 5.1 `./gradlew check` green, including the always-on hygiene assertions and the honest mutation gate (M5).
- [ ] 5.2 Recommend a commit message summarising the scrubber hardening and the refreshed fixtures, referencing this change and FR ids (no commit — project invariant).
