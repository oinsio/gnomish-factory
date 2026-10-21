# Tasks

Verification per task follows `.claude/rules/verification-scope.md`: `build-logic` is an included
build, so its functional specs run by name with
`./gradlew -p build-logic functionalTest --tests '<Spec>'`; no production Java changes, so no
scoped PIT step. The whole root `check` runs once, in task 4.2, and is itself the measurement.

## 1. The scope value and its owner (D1; FR1, FR2, FR4, NFR-R2)

- [ ] 1.1 Red first: in `MutationScopeFunctionalSpec`, replace the feature "FR3, M3: a test change
  widens its module, a shared fixture change widens every module" (marker "A shared fixture
  change mutates everything", deleted with it) by a data-driven feature carrying the marker
  `// Scenario: build-system/quality-gates / A test or fixture change widens nothing` with the
  same three rows (a spec edited, a spec deleted, a fixture edited) plus a fourth where a
  production class in `mod-b` is edited beside the fixture; assert `changedClasses` is `[:]` for
  the first three and `[':mod-b': [...]]` for the fourth, and that the printed scope carries no
  `widened` key. Drop every `scope.widened == …` assertion and `widened` column elsewhere in the
  spec (the nested-module feature keeps its `changedClasses` rows; the nested-spec row asserts
  `[:]`). Keep "FR3: a deleted production file and a package-info contribute no class" and its
  marker "A deleted class contributes nothing" untouched (FR4). Verify: the spec compiles and the
  new feature fails against the current code.
- [ ] 1.2 Delete the widening from the owner: `ChangedSources.classify` drops the `touched` pass,
  `TEST_CHANGE`, `SHARED_FIXTURE`, `TEST` and `SHARED_FIXTURE_SOURCES`, and the `widened` field;
  its javadoc lists the two remaining rules (a present production `.java` is a class, a deleted
  one contributes nothing) and cites D1 of this change beside FR3 of `scope-pit-locally`.
  `MutationScope` loses `widened` and the `noWidened` locals; `MutationScope.branch` takes the
  class map; the class javadoc's "with any module widened … (D4)" sentence goes.
  `MutationScopeSource`'s javadoc (lines ~40–47) stops citing D4. `MiniScopeRepository` stops
  printing `widened` (line 171). Verify: `./gradlew -p build-logic functionalTest --tests
  'MutationScopeFunctionalSpec' --tests 'MutationScopeGitPathFunctionalSpec'` green;
  `grep -rn "widened\|SHARED_FIXTURE\|TEST_CHANGE" build-logic/src` empty except the markers task
  2.1 removes.

## 2. The module script and the scope line (D1; FR3, NFR-O1, UX1)

- [ ] 2.1 Red first: in `MutationScopeConventionsFunctionalSpec`, replace "M3, FR3: deleting an
  assertion from a killing spec widens the module, and the gate fails on the survivor" (lines
  80–91) by two features. (a) Marker "A test or fixture change widens nothing": the same
  weakened `SPEC` edit, run `GATE`, assert `:mod-a:pitest` is `SKIPPED`, the build succeeds and
  the line is `PIT scope: branch — base … (merge-base main) — every module skipped (1)` (the
  existing "every module skipped" form). (b) Marker "Surviving mutant fails the build", which the
  deleted feature carried: edit `FOO` so the existing spec no longer kills a mutant (a second
  method with no covering assertion), assert `:mod-a:pitest` `FAILED` with `below threshold`.
  Add the marker "A branch run explains itself" to (a) and keep it on "D1, NFR-O1: a changed
  class is mutated and the line counts it". Verify: (a) fails against the current script with
  the `whole module (test change)` line; (b) passes before and after.
- [ ] 2.2 In `pitest-scope-conventions.gradle`: `wholeModule = scope.mode == MutationScope.Mode.ALL`;
  the summary for the whole-module arm is the literal `'whole module'`; the `widened` reads at
  lines 106 and 111 are gone; the header comment's D1 citation gains D1 of this change. Verify:
  `./gradlew -p build-logic functionalTest --tests 'MutationScopeConventionsFunctionalSpec'`
  green, including the existing `ALL`-mode and one-module-run features whose lines must not
  change.
- [ ] 2.3 Old-way sweep: `grep -rn "widened\|SHARED_FIXTURE\|TEST_CHANGE\|whole module (" build-logic/src
  openspec/specs docs .claude/rules` — expected hits are only the docs lines tasks 3.1–3.2
  rewrite and the archived change folders; report the list (M4).

## 3. Documentation and the rule (D2, D3; FR5, UX2)

- [ ] 3.1 `docs/guides/developer-guide.md`, the "Branch (the default — no property)" paragraph:
  delete the sentence "Two changes widen the scope …"; add one sentence stating that a test or
  fixture change mutates nothing on the branch and that the nightly whole-tree run is where a
  weakened test or fixture is caught, within a day, as a `nightly-mutation` issue; update the
  example scope line (`application: whole module (test change)` goes). Add an `<!-- implements
  FR5, UX2 of narrow-branch-mutation-scope -->` line beside the existing `scope-pit-locally`
  one. Verify: `grep -n "whole module" docs/guides/developer-guide.md` shows only the `all`
  mode's sentence.
- [ ] 3.2 `.claude/rules/verification-scope.md`, "At the end: the whole build, once": qualify
  "the gate that proves cross-module effects" — the root `check` proves compile, specs and
  architecture gates across modules and the mutation gate on the changed classes; mutation rot
  in unchanged classes, a weakened test or fixture included, is the nightly whole-tree run's
  finding within a day; no `-PpitScope=all` is run by hand before a merge (D3, proposal Q1), and
  the pickup condition in NG1 of this change is cited. Verify: the section reads as one
  paragraph under the hard cap of the file-size rule; `grep -n "nightly" .claude/rules/verification-scope.md`
  shows the new sentence.
- [ ] 3.3 `docs/glossary.md`: if an entry defines the branch scope or the widening (grep
  `widen` and `mutation scope`), update it to the narrowed meaning; if none, record "no entry"
  in the task report. Verify: `grep -n -i "widen" docs/glossary.md` empty.

## 4. Gate and measurement (G1, G2, M1–M4, NFR-C1)

- [ ] 4.1 `./gradlew checkSpecContract --changes` green: the two deleted markers are gone, the
  moved scenario "A deleted class contributes nothing" resolves under "Branch-scoped mutation
  target" through the delta, and the new scenarios "A test or fixture change widens nothing" and
  "A weakened test or fixture is caught within a day" have markers (the latter in
  `NightlyMutationIssueScriptSpec`, beside "Cross-file mutation rot is caught within a day",
  since the same script path is what the scenario promises). Verify: the gate's output.
- [ ] 4.2 The one root `./gradlew check` of this change, on the change's own working tree
  (`test-fixtures` untouched by this change, so the tree is the control for M1: production
  changes in `build-logic` only mutate nothing in the modules). Record in the task report the
  `PIT scope:` line and the wall time; then, on the branch of the sibling change or any branch
  with production changes in two modules and `test-fixtures` edits, record the scope line and
  wall time again as M1's measurement. Verify: `check` green, both lines in the report.
- [ ] 4.3 After the push, record the CI `check` duration of the run as M2 (baseline 3 h 08 min,
  run of 2026-10-21) in the task report; a run above 40 min is attributed by its Gradle profile
  artifact before anything else is changed (`testing.md`, "Diagnosing a slow gate").

## Workflow follow-up

- Sequence at sync: this change's delta of `build-system/quality-gates` merges before the
  sibling `parallelize-nightly-mutation-run`'s, which MODIFIES "Scheduled whole-tree mutation
  run" over this delta's text.
- Recommend the commit message from the diff; no commit by the agent
  (`process-invariants.md`).
