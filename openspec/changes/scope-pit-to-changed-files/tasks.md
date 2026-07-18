# Tasks: scope PIT to changed files in CI

## 1. Build: opt-in `pitScope` property (FR1, FR5)

- [ ] 1.1 In `build.gradle`, read the `pitScope` project property at configuration time (config-cache-safe capture); parse it as a comma-separated list of trimmed, non-blank class globs.
- [ ] 1.2 When `pitScope` is present, set `pitest.targetClasses` to the parsed globs; when absent or blank, keep the default `['com.github.oinsio.gnomish.*']`. Do not touch `targetTests`, `excludedClasses`, `excludedTestClasses`, the `@DoNotMutate` mechanism, or `mutationThreshold` (FR5, NG2).
- [ ] 1.3 Extend the `pitest` task `onlyIf` so that when `pitScope` is set but resolves to an empty glob list, the task is skipped (clean pass) instead of running into PIT's "No mutations found" hard failure (FR4). Confirm `pitestVerifyAllKilled`'s existing report-file `onlyIf` still mirrors the skip (no report → skip).

## 2. CI: derive changed classes and run scoped PIT (FR2, FR3, NFR-C1)

- [ ] 2.1 In `.github/workflows/ci.yml`, set the checkout to `fetch-depth: 0` so the merge base with `main` is reachable (D3).
- [ ] 2.2 Add a step that resolves the diff base: `origin/main` merge base for `push` runs, the event base SHA for fork `pull_request` runs. Fail the step loudly if the base cannot be resolved (do not silently mutate nothing) (D3, risk mitigation).
- [ ] 2.3 Compute the changed-class list: `git diff --diff-filter=d --name-only <base>...HEAD`, keep `src/main/java/**/*.java`, strip the `src/main/java/` prefix and `.java` suffix, map `/`→`.` to fully-qualified class names (D2, D4). Handle the empty result as an empty scope.
- [ ] 2.4 Pass the list to the build via `-PpitScope=<comma-separated globs>`; when the list is empty pass an empty/omitted scope so task 1.3's skip path triggers. Keep the single `./gradlew check` entry point (do not split PIT out of the check lifecycle).
- [ ] 2.5 Keep the JaCoCo and PIT report-upload steps working for scoped and skipped runs (report path unchanged; upload guarded by `!cancelled()` as today).

## 3. Verification (FR1–FR4)

- [ ] 3.1 Local: `./gradlew check` with no `pitScope` still runs full-project mutation and the 100% gate passes (regression check, FR5).
- [ ] 3.2 Local: `./gradlew check -PpitScope=com.github.oinsio.gnomish.<some.Class>` mutates only that class; a deliberately weakened test on it fails the gate; `pitestVerifyAllKilled` still enforced (FR1).
- [ ] 3.3 Local: `./gradlew check -PpitScope=` (empty) skips `pitest` and the build passes without a "No mutations found" error (FR4).
- [ ] 3.4 CI: push a branch touching one production class and confirm the run mutates only its class and completes within the 10-minute budget (FR2, FR3, NFR-C1).
- [ ] 3.5 CI: push a docs/tests/CI-only branch and confirm the run passes with no mutations (FR4).

## 4. Docs & handoff

- [ ] 4.1 Document `pitScope` (CI-only, opt-in; unset = full local run) in `build.gradle`'s pitest block comment and note the accepted cross-file trade-off (link the design's risk section).
- [ ] 4.2 Recommend a commit message summarizing the CI/build change and referencing `scope-pit-to-changed-files` (the human commits — never the agent).
