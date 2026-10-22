# Proposal: narrow the branch mutation scope to the changed classes

## Why

`scope-pit-locally` (archived 2026-10-05) made the branch's changed production classes the
default mutation scope and added two widening rules (its design D4, `ChangedSources`): a changed
file under a module's `src/test/` mutates that module's whole production tree, and a changed file
under `test-fixtures/src/` mutates every module's whole tree. The premise behind the second rule —
that a shared-fixture edit is the exception — does not hold on this project. Every code-bearing
merge to `main` since October touched `test-fixtures/src/`:

| Merge      | `test-fixtures` files | Modules with test changes | Modules with production changes |
|------------|----------------------:|--------------------------:|--------------------------------:|
| `c6a4df18` |                     9 |                         6 |                               5 |
| `1a49a350` |                    52 |                        12 |                              15 |
| `87662b76` |                    32 |                        17 |                              14 |
| `60750be5` |                    63 |                        13 |                              14 |
| `acf06a2b` |                    12 |                         8 |                               9 |
| `001826d8` |                     3 |                         2 |                               1 |

So the branch scope is the whole tree in practice. The CI `check` of the pull-request branch of
2026-10-21 took 3 h 08 min on a 4-core runner against 3 h 51 min for the whole-tree nightly run of
2026-10-20; the local branch run of 2026-10-12 announced `PIT scope: branch … whole module (shared
fixture)` for all 19 modules and ran for the better part of an hour. The factory pushes the task
branch after every round, so the three-hour run restarts on each push and the branch is rarely
green when a human looks. The end-of-change root `check` that `verification-scope.md` asks for
costs the same hour locally. Development has slowed to the gate's pace.

The whole-tree guarantee the widening rules were meant to give already has a second owner: the
nightly whole-tree run on `main` (`pitest-nightly.yml`), which fails within a day on any mutant
that stops dying anywhere and reports it as a tracker issue. This change makes that owner the only
one, as a **temporary measure**: the branch scope becomes exactly the changed production classes,
and the whole-tree verdict moves from every push to once a day. The sibling change
`parallelize-nightly-mutation-run` makes the nightly's verdict arrive in about a quarter of the
time; together they define the condition under which the widening returns (NG1).

## What Changes

- **MODIFIED** — the branch mutation scope (`BRANCH` mode of `MutationScopeSource`) mutates only
  the production classes the branch changed, per module. A changed test file, a deleted test file
  and a changed shared fixture widen nothing. The `widened` field of `MutationScope`, the two
  widening reasons of `ChangedSources` and the `whole module (<reason>)` form of the scope line
  are removed: nothing varies per module in branch mode any more, so there is nothing to carry.
- **REMOVED** — the requirement "Test changes widen the mutation scope" of the
  `build-system/quality-gates` capability. Its scenario "A deleted class contributes nothing" is
  not about widening and moves under the branch-scope requirement unchanged.
- **MODIFIED** — the scope announcement no longer names a widening reason; a branch line reads
  `PIT scope: branch — base 3f2c1a7 (merge-base main) — adapters/git: 3 classes — 17 modules
  skipped`.
- **MODIFIED** — the documentation of the branch mode (`docs/guides/developer-guide.md`) and the
  end-of-change note of `.claude/rules/verification-scope.md` describe the narrowed scope and name
  the nightly run as the whole-tree owner.

Nothing changes in `ALL` and `EXPLICIT` modes, the 100 % threshold, `pitestVerifyAllKilled`, the
exemption model, `MutationEngineLock`, `ci.yml` or the nightly workflow.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `build-system/quality-gates`: the requirement "Test changes widen the mutation scope" is
  removed; "Branch-scoped mutation target" gains the deleted-class scenario and a scenario stating
  that a test or fixture change widens nothing; "Mutation scope is announced" drops the widening
  reason from the branch line; "Scheduled whole-tree mutation run" gains a scenario naming it the
  sole whole-tree owner for a weakened test.

## Goals

- **G1** — A branch that changes production classes in two modules and test files in six, with
  shared-fixture edits, runs its CI `check` on the 4-core runner in under 40 minutes, down from
  3 h 08 min.
- **G2** — The same branch's end-of-change local root `check` on the reference machine (14 cores,
  36 GB) finishes in under 20 minutes, with the mutation phase scoped to its changed classes only.
- **G3** — The whole-tree guarantee keeps one owner that fails visibly: a weakened test or fixture
  that lets a mutant in an unchanged class survive is reported by the nightly run as a
  `nightly-mutation` issue within a day of its merge.
- **G4** — The scope value and the scope line carry nothing that no longer varies: no `widened`
  field, no reason strings, no module-script branch for them.

## Non-Goals

- **NG1** — **Deferral: the widening rules of D4 of `scope-pit-locally` are not redesigned; they
  are removed as a temporary measure.** The pickup condition: the change that makes a whole-tree
  mutation run on the CI runner complete inside the bound the per-module matrix of
  `parallelize-nightly-mutation-run` achieves for the nightly (its M1), applied to `ci.yml`, or
  any change that makes a test change's blast radius computable (a coverage-based mapping from a
  spec to the classes it kills, PIT incremental analysis once its reuse rule is proven). That
  change restores a widening rule — or records why none is needed — and re-states the scenario
  "A weakened spec is caught" against the branch gate. Until then the nightly run is the owner,
  and NG1 of `scope-pit-locally` (no PIT incremental analysis) stands as written.
- **NG2** — No change to `ci.yml`: the push run still runs `check` with the branch scope; it only
  gets faster. The sibling change `parallelize-nightly-mutation-run` owns the nightly workflow;
  both MODIFY "Scheduled whole-tree mutation run", and this change is sequenced first — the
  sibling's delta is layered on this one's text.
- **NG3** — No change to the 100 % threshold, the mutator set, `excludedClasses`,
  `excludedTestClasses`, `@DoNotMutate`, `pitestVerifyAllKilled` or the cost report (NG2 of
  `scope-pit-locally`, NG1 and NG4 of `kill-expensive-mutants` stand).
- **NG4** — No relaxation of `MutationEngineLock` and no change to the heavy-JVM budget (NG3 and
  NG6 of `scope-pit-locally` stand; with the scope really narrow, concurrent PIT runs stay rare).
- **NG5** — No line-level scoping (NG4 of `scope-pit-locally` stands).
- **NG6** — No change to `ALL` and `EXPLICIT` modes, to the base resolution (D3 of
  `scope-pit-locally`) or to the fallback-to-whole-tree direction when no base resolves.

## Users & Scenarios

- **U1 — The implementing agent.** Runs `./gradlew check` at the end of a change whose tasks
  touched production classes in two modules and specs in six; the run mutates the changed classes
  of the two modules and skips the rest, and the scope line says so.
- **U2 — The human reviewing a pull request.** The push run's `check` finishes in minutes rather
  than hours, so the branch's status is current when the review starts.
- **U3 — The human on the morning after a merge.** A merged change weakened a fixture and a mutant
  in an unchanged class now survives; the nightly run fails on that module, the `nightly-mutation`
  issue names it, and the fix is a separate, small branch whose own scope is that class.
- **U4 — The reader of the scope line.** Sees per-module class counts and skipped modules only;
  no `whole module (test change)` form exists to decode.

## Requirements

### Functional

- **FR1** — In `BRANCH` mode each module's mutation target is exactly the production classes whose
  source files under its `src/main/java/` differ from the scope base (committed, staged, unstaged
  and untracked alike), with their nested classes. A changed, added or deleted file under any
  module's `src/test/` and a changed, added or deleted file under `test-fixtures/src/` contribute
  nothing to any module's target.
- **FR2** — `MutationScope` carries no per-module widening: the `widened` field, the two widening
  reasons of `ChangedSources` and every reader of them (`pitest-scope-conventions.gradle`, the
  scope announcement, `MiniScopeRepository`) are removed, not left empty; so is the deleted-path
  input of `ChangedSources`, which only the widening read.
- **FR3** — The branch scope line names the base, its origin, each mutated module with its class
  count, and the number of skipped modules; it contains no widening reason. The `ALL` and
  `EXPLICIT` forms of the line are unchanged.
- **FR4** — A deleted or renamed-away production file still contributes no class (the scenario
  moves; its behaviour and its test do not change).
- **FR5** — `docs/guides/developer-guide.md` (the branch-mode paragraph) and
  `.claude/rules/verification-scope.md` describe the narrowed scope and name the nightly run as the
  whole-tree owner, with the pickup condition of NG1 cited.

### Non-Functional Performance

- **NFR-P1** — The scope computation runs the same number of `git` processes as before (no new
  read); removing the widening removes work, never adds it.

### Non-Functional Reliability

- **NFR-R1** — The fail-safe direction of `scope-pit-locally` (NFR-R1 there) is unchanged: an
  unresolvable base still mutates the whole tree. Narrowing applies only to a resolved branch
  scope.
- **NFR-R2** — The configuration-cache contract is unchanged: `MutationScope` stays a serializable
  value obtained through the `ValueSource`, so an edit between two builds still re-resolves the
  scope.

### Non-Functional Observability

- **NFR-O1** — The scope line is still logged once per build, at execution time, in every mode,
  including a branch run that mutates nothing.

### Non-Functional Security

- **NFR-S1** — No workflow changes; the token permissions pinned by `CiScopeOwnerSpec` are
  untouched. (Considered; nothing applies beyond this.)

### Non-Functional Cost

- **NFR-C1** — A push run and the end-of-change `check` mutate only the changed classes; the
  whole-tree cost is paid once a day by the nightly run instead of on every push.

## Operator Experience Criteria

- **UX1** — The branch scope line is shorter and has one form: modules with class counts, then
  the skipped count. A reader who sees a module mutated knows a production file of that module
  changed.
- **UX2** — The developer guide states in one sentence what a test or fixture change does to the
  branch gate now (nothing) and where that case is covered (the nightly run), so the agent does
  not pass `-PpitScope=all` by hand to be safe.

## Success Metrics

- **M1** — On the reference development machine, `./gradlew check` on a branch with production
  changes in `:adapters:git` (two classes) and `:application` (three classes), test changes in six
  modules and nine `test-fixtures` files finishes in ≤ 20 min; the scope line lists two modules.
- **M2** — The CI `check` of such a branch finishes in ≤ 40 min on `ubuntu-latest` (baseline
  3 h 08 min, run of 2026-10-21).
- **M3** — `MutationScopeFunctionalSpec`'s data-driven feature for a spec edited, a spec deleted
  and a fixture edited asserts an empty widening — the field is gone — and every module's
  `pitest` is skipped in the conventions spec for a test-only change.
- **M4** — `grep -rnE '\.widened|\bwidened( =|$|[:,).[])|SHARED_FIXTURE|TEST_CHANGE|whole module \(' build-logic/src`
  returns no hit. The pattern matches the identifiers, not the English word: `widened` in its
  ordinary sense at `TestTimeInjectionCheckFunctionalSpec.groovy:19` and
  `test-conventions.gradle:169` is not part of this change.

## Open Questions

- **Q1** — Whether the end-of-change root `check` of `verification-scope.md` should additionally
  recommend one whole-tree run (`-PpitScope=all`) before a merge of a change that edits
  `test-fixtures` heavily. Proposed answer: no — that is the widening by hand, and the nightly's
  one-day bound is the accepted cost; the rule text says so explicitly.
