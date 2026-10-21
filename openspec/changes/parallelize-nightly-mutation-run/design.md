# Design: parallelize-nightly-mutation-run

## Context

See proposal.md — Why. What shapes the approach:

- `pitest-nightly.yml` today: a `verified` job (a restore-only cache lookup keyed
  `nightly-verified-<sha>`, 30-min timeout) and one `mutation` job that builds git 2.56 from a
  cached tree, runs `./gradlew check -PpitScope=all 2>&1 | tee build/nightly.log`, writes the
  marker, uploads `**/build/reports/pitest/` as `pit-report`, and on failure runs
  `scripts/nightly-mutation-issue.sh build/nightly.log <run-url> pit-report`. The token is
  `contents: read` + `issues: write` at the top level; `actions: read` is deliberately withheld
  (D7 of `scope-pit-locally`), and `CiScopeOwnerSpec` fails on a job-level `permissions` block.
- The failure script parses one log for `Execution failed for task ':mod:task'.` followed by a
  `> reason` line, and for `> Task :mod:task FAILED`; it names each task once in first-seen order,
  comments on the oldest open `nightly-mutation` issue or creates one, and its body quotes the
  artifact name it was given. `NightlyMutationIssueScriptSpec` drives it against a stubbed `gh`
  over a canned log and pins two strings of the workflow (`if: failure()`, `run: bash
  scripts/nightly-mutation-issue.sh build/nightly.log`).
- The set of modules that mutate is the set that applies `test-conventions` (which applies
  `pitest-conventions`): 18 today, nested paths included (`:sandbox:core`,
  `:gnomish-plugin-api` but not `:gnomish-plugin-api:sample`). Root tasks shared by the
  conventions are registered with the `names.contains(...) ? named : register` idiom
  (`pitestScope` in `pitest-scope-conventions.gradle:131`); `pitestAll` is registered in the root
  `build.gradle` and each module's gate script adds itself to it.
- `MutationEngineLock` is a build service with `maxParallelUsages = 1`: it serializes modules
  within one Gradle invocation and has no effect across runners. The scope line and the skip
  predicate work per module already (`./gradlew :domain:check` is a documented form).
- `actions/download-artifact` inside the run that uploaded the artifact uses the runner's own
  artifact channel, not the REST API that `actions: read` guards; the nightly's own `restore`
  step relies on the same distinction for the cache. The spec-contract exemption file names the
  nightly's job `mutation` (no `timeout-minutes`) and `verified` (30 min), so job names are
  pinned by that file.
- Build-logic functional specs run on TestKit over a miniature build (`MiniScopeRepository`,
  `MutationScopeConventionsSpecBase`), which is where an enumeration task is driven with and
  without an extra module.

## Goals / Non-Goals

**Goals:** the largest module's time as the run's wall time (G1), one owner of the module list
(FR1, G2), one report per run with no issue race (FR4, FR5, G3), a marker only for an all-green
commit (FR4, G4), every pinned string and permission still pinned (FR7).

**Non-Goals:** `ci.yml` (proposal NG1), grouping (NG2), engine or lock settings (NG3), a remote
build cache (NG5).

## Decisions

**D1 — The module list is a root task `pitestModules`, registered once from
`pitest-gate-conventions.gradle` with the shared-root-task idiom, printing a JSON array of the
project paths that applied the script.** Each module's gate script adds its own `project.path`
to a root-held sorted set at configuration time (the same way each module adds its `pitest` to
`pitestAll`); the task carries that set as an input property and prints it in `doLast` with
`println`, so `./gradlew -q pitestModules` yields the array alone. *Rationale:* FR1, NFR-R3,
alternative zero — the set already exists as "the projects that applied the conventions"; a
YAML matrix would be a second copy of it kept by hand, the undeclared pair `manual-sync-pairs.md`
forbids, and it would need a `:bootstrap` gate comparing the two. Reading it from the build
leaves one owner and no gate to write. *Alternative rejected:* a hand-written `matrix.include`
in the workflow with a `:bootstrap` spec asserting it equals the applying modules — the gate
catches drift a day late and the list still has to be edited by hand. *Alternative rejected:*
`gradle projects` output parsed by shell — every project is listed, including the ones with no
mutation gate (`:gnomish-plugin-api:sample`), so the shell would re-implement the "applies the
conventions" predicate.

**D2 — Three jobs: `verified` (unchanged), `modules` (runs `pitestModules`, sets an output),
`mutation` (matrix over `fromJSON(needs.modules.outputs.list)`, `fail-fast: false`, one module
each), and `report` (`needs: [modules, mutation]`, `if: always()`).** `mutation` runs
`./gradlew ${module}:pitest ${module}:pitestVerifyAllKilled -PpitScope=all 2>&1 | tee
build/nightly.log` under `set -o pipefail`, uploads `<module dir>/build/reports/pitest/` as
`pit-report-<label>` and `build/nightly.log` as `nightly-log-<label>` with `if: ${{ !cancelled()
}}`, where `label` is the path without its leading colon and with `:` replaced by `-`
(`adapters-git`), computed by the `modules` job beside the path so no shell in the matrix job
derives it. `report` writes the marker when `needs.mutation.result == 'success'`; otherwise it
downloads `nightly-log-*` with `pattern` + `merge-multiple: false`, concatenates them into
`build/nightly.log` in label order, and runs the script once. The job named `mutation` keeps no
`timeout-minutes` and `verified` keeps 30 min, so the exemption file's lines stay true; `report`
gets a 15-min timeout and joins the exemption line listing bounded jobs. *Rationale:* FR2–FR6;
`fail-fast: false` is what makes a red module not cancel the others (NFR-R1's "cancelled" case
otherwise appears on every red night); one `report` job is what makes two red modules one
comment — two failing matrix jobs each running the script would race on `gh issue list` and
create two issues. The marker written by `report` rather than by each job is G4 by
construction: `needs.mutation.result` is `success` only if every matrix leg succeeded.
*Alternative rejected:* each matrix job runs the script on its own failure (no aggregate job) —
the issue race above, and a cancelled leg would report nothing. *Alternative rejected:* the
matrix job runs `<module>:check` — repeats the module's tests the merge commit's push run
already ran, and `:bootstrap:test` alone is the Docker and Gitea suites; the mutation tasks are
the nightly's whole purpose, and the proposal records the revisit of Q2 of `scope-pit-locally`.

**D3 — The script takes one log that may hold several Gradle runs and an artifact *prefix*
rather than an artifact name.** The awk already keys by task and keeps the first reason, so a
concatenation of per-module logs needs no parser change; the third argument becomes the prefix
(`pit-report-`), and the body lists, for each failed task, `pit-report-<label>` derived from the
task's project path by the same colon-to-dash rule (in the script, in `awk`, since the script is
the body's owner), plus the run link; the body's first line names the per-module invocation.
Bash 3 compatible, as today. *Rationale:* FR5, NFR-O2, UX2; the script stays the one owner of
the body and its spec stays the one place the body is tested. *Alternative rejected:* the
workflow composes the body and the script only posts it — moves untested text into YAML, the
shape D7 of `scope-pit-locally` put the script there to avoid.

**D4 — The label rule (`:adapters:git` → `adapters-git`) lives in two places by necessity,
and the two are a declared pair.** The `modules` job needs the label for artifact names
(workflow expressions cannot run a replace over a matrix value at upload time without a shell
step, which NFR-S2 wants to avoid for interpolated data), so `pitestModules` prints objects
`{path, label, dir}` and the workflow uses them verbatim; the script derives the same label from
a task path when it names a failed task's artifact. One is Groovy in `build-logic`, the other
`awk` in a shell script; neither can `{@link}` the other, so the pair is registered in the
"Declared pairs with no shared classpath" table of `manual-sync-pairs.md`, both ends carry the
`Kept in sync with` sentence, and `NightlyMutationIssueScriptSpec` plus the enumeration
functional spec each pin their end on the same three example paths (a top-level, a nested, a
two-level path). *Rationale:* the preference order of `manual-sync-pairs.md` — a shared
abstraction is impossible across a Gradle build and a POSIX script, so the pair is declared
rather than left in memory. *Alternative rejected:* the script reads the label from the log
(each job could `echo` a `label=` line into its log) — a third spelling of the same rule, in
YAML.

**Sync surfaces: the declared pair of D4 (the module-label rule in `pitestModules` and in
`scripts/nightly-mutation-issue.sh`), added by this change with both markers and the registry
row in the same change (tasks 1.3, 2.3). No other surface: the change touches no existing
declared pair (`grep -rn "Kept in sync with" build-logic/src/main scripts` lists
`TestTimeInjectionCheck`, `AdversarialGitConfig`, `TestDockerEnvironment` and
`scripts/linux-specs.sh`, none of which it edits).**

**Single-owner mechanisms:**

| Owner | Value (type) | Requires | Consumers | Old way removed | Enforced by |
|-------|--------------|----------|-----------|-----------------|-------------|
| `pitestModules` root task (`pitest-gate-conventions.gradle`, D1) | the mutation-module list: a JSON array of `{path, label, dir}` objects, one per project that applied the gate script | a configured build; no git, no network | `.github/workflows/pitest-nightly.yml` (job `modules` → `matrix` through `fromJSON`; job `mutation` reads `path`, `label`, `dir`) | the single `./gradlew check -PpitScope=all` step, which enumerated nothing; no YAML list ever existed, and none is added | `CiScopeOwnerSpec` gains a feature: no workflow under `.github/workflows/` holds a literal module path of the build (`:adapters:git`, `adapters/git`) outside a comment; `PitestModulesFunctionalSpec` (build-logic, TestKit) asserts the array equals the applying modules of a mini build with and without an extra module |

No identity claim is made (the matrix *is* the printed list; nothing is computed twice), so no
identity spec is needed beyond the functional spec.

**Deferrals of archived proposals (constraint 7 of `/opsx:propose-checked`):**

- `scope-pit-locally` Q2 answered "`check` rather than `pitestAll`: one run, one complete
  artifact set, one place to link" — revisited in D2; the one place to link is the run, and the
  issue body names each failed module's artifact.
- `scope-pit-locally` NG3 (no `MutationEngineLock` relaxation) — not touched: the lock still
  serializes inside each job (NG3 here).
- `remove-ci-build-timeout` NG3 ("not optimising the build's wall time — separate work") — this
  is that work for the nightly; its NG2 (which gates run) is kept: the same gate, per module.
  The exemption lines it left ("A long build run is not cancelled", "A hung job is bounded by
  the timeout") are updated for the `report` job (task 3.4).
- `adapt-build-load-to-hardware` NG2 ("remote/distributed test execution or CI runner sizing")
  — met in the narrow sense: PIT runs are distributed across runners, one build per runner; its
  stated invariant ("a memory budget, not a worker count") holds unchanged inside each job. No
  runner sizing (standard runners only).
- `narrow-branch-mutation-scope` NG1 — the condition it names is measured by this change's M1;
  NG1 here re-defers applying the matrix to `ci.yml` with that condition restated.

## Risks / Trade-offs

- **Concurrent-job limit: 18 matrix legs plus two small jobs on a plan with 20 concurrent jobs,
  while a CI push run may be running** → the legs queue, not fail; the wall time grows by a
  leg's duration at worst. If the limit ever binds, NG2's pickup condition is met.
- **A cold Gradle user-home cache makes every leg compile the module's dependency chain** →
  `setup-gradle` restores the previous day's cache per job; measured in the first three runs
  (M1); NG5 names the condition for a remote cache.
- **`download-artifact` fails in `report` because no log was uploaded (every leg cancelled
  before Gradle)** → `report` treats a missing or empty download as "no log" and the script
  already yields an issue that says the run failed before Gradle wrote a log (its existing
  feature); the step uses `if-no-files-found: ignore` semantics via a guard.
- **The exemption file's job names go stale** → task 3.4 updates both lines in the same change,
  and `checkSpecContract` fails on a line naming nothing.
- **Docker-backed PIT coverage passes (`:bootstrap`, `:sandbox:docker`) now run on separate
  runners at once** → each runner has its own daemon; the suites name their objects per test id,
  not per host, and ran on one runner before.
- **`pitestModules` prints under `-q` but a plugin logs a lifecycle line to stdout anyway** →
  the functional spec asserts the whole stdout parses as the array; the workflow fails on a
  parse error (`fromJSON`) rather than running an empty matrix, and the `modules` job also
  asserts the array is non-empty.

## Migration Plan

Workflow, script and build-logic only. The first scheduled run after the merge is the
measurement (M1); a red first run is attributed by job before anything is changed. Rollback is a
revert. Sequenced after `narrow-branch-mutation-scope` at sync; it can land alone, in which case
the layered preamble of its delta names what the hand merge must keep.
