# Tasks

Verification per task follows `.claude/rules/verification-scope.md`: build-logic functional
specs by name (`./gradlew -p build-logic functionalTest --tests '<Spec>'`), `:bootstrap`
architecture specs by name, no production Java so no scoped PIT. The one root `check` runs in
task 4.1; the live measurement is the first scheduled runs (task 4.3).

## 1. The module list owner (D1, D4; FR1, NFR-R3, NFR-S2)

- [ ] 1.1 Red first: `PitestModulesFunctionalSpec` in `build-logic/src/functionalTest` over the
  mini build of `MutationScopeConventionsSpecBase` (two modules applying the gate script, one
  nested): marker `// Scenario: build-system/quality-gates / The list is the modules that mutate`
  — `./gradlew -q pitestModules` stdout parses as a JSON array of `{path, label, dir}` objects
  equal to the applying modules in path order, with `label` `mod-a`, `mod-a-inner` and `dir`
  `mod-a`, `mod-a/inner`; marker `A new module joins without a workflow edit` — a third module
  written to `settings.gradle` with the conventions appears on the next run; marker `The output
  is stable under the configuration cache` — two runs with `--configuration-cache` print the same
  text and the second reports the cache reused. Verify: the spec compiles and all three fail on
  "task not found".
- [ ] 1.2 Register `pitestModules` once at the root from `pitest-gate-conventions.gradle` (the
  `names.contains(...) ? named : register` idiom of `pitestScope`); each applying module adds its
  `path`, `label` (leading colon dropped, colons to dashes) and `dir` (relative project directory)
  to a root-held `TreeMap` keyed by path at configuration time; the task takes the map as an
  input property and prints the array in `doLast` with one `println`, no logger prefix.
  `description` and `group = 'verification'`. Verify: task 1.1 green; `./gradlew -q
  pitestModules` on the real build prints 18 entries (M2) and `./gradlew pitestModules
  --configuration-cache` twice reports the cache reused.
- [ ] 1.3 Declare the label rule as a pair: the `Kept in sync with` sentence in the task's
  comment in `pitest-gate-conventions.gradle` naming `scripts/nightly-mutation-issue.sh`, the
  invariant stated (path → label: drop the leading colon, replace `:` with `-`); add the row to
  the "Declared pairs with no shared classpath" table of `.claude/rules/manual-sync-pairs.md`
  with the three example paths both specs pin (`:domain`, `:adapters:git`,
  `:gnomish-plugin-api`). Verify: `grep -n "Kept in sync with" build-logic/src/main/groovy/pitest-gate-conventions.gradle
  scripts/nightly-mutation-issue.sh` shows both ends (the script's end lands in 2.3).

## 2. The failure report over several logs (D3, D4; FR5, NFR-O2, UX2)

- [ ] 2.1 Red first, in `NightlyMutationIssueScriptSpec`: marker `// Scenario:
  build-system/quality-gates / Two red modules make one report` — a canned log that is two
  Gradle runs concatenated (`:adapters:git:pitestVerifyAllKilled` with its reason, then
  `:domain:pitest FAILED` with `below threshold`), the third argument `pit-report-`; assert one
  `gh issue create` (or one comment with an open issue) whose body lists both tasks once, each
  followed by its artifact `pit-report-adapters-git` / `pit-report-domain`, the run link, and a
  first line naming the per-module invocation; a row for `:gnomish-plugin-api:pitest` asserts
  `pit-report-gnomish-plugin-api`. Keep the existing features (no log, no failed task, three
  arguments) green with the prefix argument. Verify: the new feature fails on the old body.
- [ ] 2.2 Rewrite the script's `body()` and the awk: the third argument is the artifact prefix;
  each failed task line carries `(report: <prefix><label>)` where label is derived in awk from
  the task's project path (`:adapters:git:pitest` → `adapters-git`); the header line reads "The
  nightly whole-tree mutation run (one job per module, `<module>:pitest
  <module>:pitestVerifyAllKilled -PpitScope=all` on `main`) failed."; "PIT reports:" names the
  prefix and the per-module artifacts of the run. Bash 3 compatible (no `${var,,}`, no
  associative arrays outside awk). Verify: `./gradlew :bootstrap:test --tests
  'NightlyMutationIssueScriptSpec'` green on macOS and through `scripts/linux-specs.sh` (a shell
  script is a platform subject, `verification-scope.md` step 5).
- [ ] 2.3 The script's header comment gains the `Kept in sync with` sentence naming the
  `pitestModules` task in `pitest-gate-conventions.gradle` and the same invariant (closes 1.3).
  Verify: the grep of 1.3 shows both ends.

## 3. The workflow (D2; FR2, FR3, FR4, FR6, FR7, NFR-R1, NFR-R2, NFR-S1)

- [ ] 3.1 Red first, in `CiScopeOwnerSpec`: a feature asserting that no workflow under
  `.github/workflows/` holds, outside a comment, a literal module path of the build — the
  patterns `:adapters:git`, `adapters/git`, `:bootstrap`, `sandbox/docker` and, generally, any
  `:<name>:pitest` with a name from `./gradlew -q pitestModules`'s output read through
  `RepoSourceTree` (the settings file's `include` lines are the fixture-free source: parse
  `include '<path>'` lines and ban each as `:<path with : for :>` and `<path>/`); the planted-copy
  twin asserts a copy of the nightly with `- module: ':adapters:git'` is reported. Keep the
  NFR-S1 features: top-level permissions exactly `contents: read`, `issues: write`; a job-level
  `permissions` block reported. Verify: the new feature is green on the current workflow (no
  list exists) and red on the planted copy; it is the enforcement row of D1.
- [ ] 3.2 Rewrite `pitest-nightly.yml` per D2: `verified` unchanged; `modules` (needs
  `verified`, same `if`, `timeout-minutes: 30`): checkout `fetch-depth: 1`, the git-from-source
  steps are NOT needed here (no factory git runs) — but Gradle's own configuration does not run
  git either, so the job runs JDK + `setup-gradle` + `./gradlew -q pitestModules` into
  `$GITHUB_OUTPUT` as `list`, failing if the array is empty; `mutation` (needs `modules`,
  `strategy: fail-fast: false, matrix: module: ${{ fromJSON(needs.modules.outputs.list) }}`,
  `name: mutation (${{ matrix.module.path }})`, no `timeout-minutes`): the existing git-build
  steps, JDK, Gradle, then `./gradlew "${{ matrix.module.path }}:pitest"
  "${{ matrix.module.path }}:pitestVerifyAllKilled" -PpitScope=all 2>&1 | tee build/nightly.log`
  under `set -o pipefail`, upload `${{ matrix.module.dir }}/build/reports/pitest/` as
  `pit-report-${{ matrix.module.label }}` and `build/nightly.log` as
  `nightly-log-${{ matrix.module.label }}`, both `if: ${{ !cancelled() }}`, 14-day retention;
  `report` (needs `[modules, mutation]`, `if: always()`, `timeout-minutes: 15`): checkout
  (`fetch-depth: 1`, for the script), if `needs.mutation.result == 'success'` write and save the
  marker as today; else download `pattern: nightly-log-*` into `logs/`, concatenate `logs/*/
  nightly.log` in name order into `build/nightly.log` (an empty set leaves no file, which the
  script already handles), and `run: bash scripts/nightly-mutation-issue.sh build/nightly.log
  <run-url> pit-report-`. Top-level `permissions` and `concurrency` unchanged; no job-level
  permissions; comments explain each job in the file's existing voice and cite this change.
  Verify: `./gradlew :bootstrap:test --tests 'CiScopeOwnerSpec' --tests
  'NightlyMutationIssueScriptSpec'` green — update the latter's workflow feature to the new
  strings (`if: always()` on the report job and the `run:` line with the prefix); `actionlint`
  if present on the machine, else `yq '.jobs | keys'` lists `verified, modules, mutation,
  report`.
- [ ] 3.3 `docs/guides/developer-guide.md`, the nightly paragraph: one job per module, the
  matrix from `./gradlew -q pitestModules`, per-module `pit-report-<label>` artifacts, the
  marker written by the final job; the "add a module" guidance says the nightly needs no edit
  (UX3). Add `<!-- implements FR1, FR2, FR3, UX1, UX3 of parallelize-nightly-mutation-run -->`.
  Verify: `grep -n "pit-report" docs/guides/developer-guide.md` shows the per-module name only.
- [ ] 3.4 `config/spec-contract-exemptions/build-system-quality-gates.txt`: "A long build run is
  not cancelled" names `pitest-nightly.yml (job mutation, every matrix leg)`; "A hung job is
  bounded by the timeout" adds `pitest-nightly.yml (jobs verified, modules) — 30 — and (job
  report) — 15`; "An unchanged main is not re-mutated" names job `report` as the marker writer
  beside job `verified` as the reader. Markers for the new scenarios: `Two red modules make one
  report` (task 2.1), `A red module does not stop the others` and `A cancelled or skipped module
  writes no marker` and `A module with nothing to mutate is green` — the first two are
  workflow-structure facts, so they go in this exemption file naming `pitest-nightly.yml
  (strategy fail-fast: false; job report's if on needs.mutation.result)`; the last is the
  existing scope-skip behaviour, marker in `MutationScopeConventionsFunctionalSpec`'s empty-scope
  feature. Verify: `./gradlew checkSpecContract --changes` green.

## 4. Gate and measurement (G1–G4, M1–M3, NFR-P1)

- [ ] 4.1 The one root `./gradlew check` of this change, green. Verify: the output and the
  `PIT scope:` line (branch; build-logic changes mutate nothing).
- [ ] 4.2 M3 by the script spec's two-module log (task 2.1) and, after the push to the change
  branch, one manual `workflow_dispatch` of the nightly from that branch is not possible (a
  schedule and a dispatch run on the default branch only), so M3's live half is the first red
  night after the merge, if any; record that in the task report rather than planting a survivor
  on `main`.
- [ ] 4.3 After the merge, record the first three scheduled runs' wall times and the longest
  leg of each in the task report (M1, NFR-P1, baseline 3 h 51 min); a run above 90 min is
  attributed by leg before anything is changed (`testing.md`, "Diagnosing a slow gate"), and a
  leg whose compile share exceeds a third of its time meets NG5's condition and is recorded as
  such.

## Workflow follow-up

- Sync order: `narrow-branch-mutation-scope`'s delta of `build-system/quality-gates` merges
  first; this change's MODIFIED "Scheduled whole-tree mutation run" is layered on it.
- Recommend the commit message from the diff; no commit by the agent
  (`process-invariants.md`).
