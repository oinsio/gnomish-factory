# Proposal: scope PIT to the branch's changes locally, with a scheduled whole-tree run

## Why

Development in this project is agent-driven: a change is applied as a sequence of tasks, each by a
fresh sub-agent that finishes by running `./gradlew check`. That command mutates the whole production
tree on every run, and the whole tree now costs about 57 minutes on the 14-core development machine
(measured 2026-10-03 over the Gradle daemon logs: `:adapters:git` 25 min, `:bootstrap` 16 min,
`:application` 5 min; everything that is not PIT finishes in the first few minutes). A change of ten
tasks therefore spends most of a working day mutating code none of its tasks touched. CI already
narrows the gate to the classes a branch changed (`scope-pit-to-changed-files`); the local build does
not, so the agent's inner loop is the slowest place in the pipeline.

Two gaps in the existing scoping surfaced in the same analysis. A diff that changes only test files
mutates nothing, so a weakened spec — an assertion deleted, a mutant now surviving — passes the CI
gate unobserved; for agent-written tests this is exactly the defect the mutation gate exists to catch.
And a push to `main` yields `merge-base(origin/main, HEAD) == HEAD`, an empty diff, so no mutation
gate runs on the default branch at all. Both are correctness defects of the gate with a green build.

## What Changes

- **MODIFIED** — the scoped-target mechanism moves from a CI-only shell step into the build itself.
  Without an explicit property, `./gradlew check` mutates the production classes the current branch
  changed (committed, staged, unstaged and untracked) relative to its merge base with `main`, in
  every environment — the agent's working copy and the CI runner compute the same scope from the
  same owner. `-PpitScope=all` requests the whole tree; `./gradlew pitestAll` implies it.
- **MODIFIED** — scope rules close the two gaps: a changed test source in a module widens the scope
  to that module's whole production tree; a changed shared test fixture (`:test-fixtures`) widens it
  to the whole build; a branch whose merge base is `HEAD` (the default branch itself) diffs against
  the first parent of `HEAD`; a base that cannot be resolved at all (no default-branch ref, a root
  commit, not a git checkout) falls back to the whole tree. The fallback direction is always *more*
  mutation, never less.
- **ADDED** — a scheduled CI workflow runs the whole-tree mutation gate nightly on `main` and opens
  (or updates) a tracker issue when it fails, so the one risk scoping accepts — a mutant in an
  unchanged file that stops dying because of a change elsewhere — is caught within a day instead of
  never.
- **ADDED** — the CI quality-gate run records a Gradle profile report and uploads it as a build
  artifact, so the next build-time question is answered from data rather than daemon logs.
- **REMOVED** — the two workflow shell steps that resolve the diff base and compute the changed-class
  list; CI passes no computed `-PpitScope` any more.

## Goals

- **G1** — A task that touches one or two modules runs `./gradlew check` locally in under 10 minutes
  on the reference development machine (14 cores, 36 GB), down from ~57.
- **G2** — The mutation gate's verdict on changed code is identical between the local run and the CI
  push run: one owner computes the scope, both consume it.
- **G3** — Whole-tree mutation coverage is asserted at least once a day on `main`, with a failure
  that reaches a human.
- **G4** — No change to the gate's strength on what it mutates: 100% threshold, `pitestVerifyAllKilled`,
  the documented exemptions, and the mutator set are untouched.

## Non-Goals

- **NG1** — No PIT incremental analysis (history files). Evaluated and rejected: PIT's own
  documentation calls the assumption that a dependency change cannot invalidate a recorded kill
  "unproven", and an agent reading a green gate cannot tell a reused verdict from a fresh one.
- **NG2** — No change to the mutator set, the 100% threshold, `excludedClasses`,
  `excludedTestClasses`, or `@DoNotMutate` policy.
- **NG3** — No relaxation of `MutationEngineLock` (two concurrent PIT runs). Deferred until the
  effect of scoping is measured; with a scoped default, concurrent PIT runs become rare.
- **NG4** — No line-level scoping (mutating only changed lines). Class-level scope is what the
  existing mechanism provides and what the gate's exemption model is written against.
- **NG5** — No speed-up of individual expensive mutants or slow specs; that is the follow-up change
  `kill-expensive-mutants`.
- **NG6** — No change to the heavy-JVM budget or the test tasks' parallelism.

## Users & Scenarios

- **U1 — the implementing sub-agent.** Finishes a task, runs `./gradlew check`, gets the full gate
  over the branch's changes in minutes; sees one log line saying what was scoped and why.
- **U2 — the human reviewer before committing.** Runs the same command; the verdict covers every
  class the change touched since `main`, including uncommitted work.
- **U3 — the CI push run.** Runs `./gradlew check` with no scope arguments and gets the same scope
  as U2 for the same tree.
- **U4 — the nightly run.** Mutates everything; on failure a tracker issue names the surviving
  mutations and links the report.
- **U5 — the developer asking "where does CI's time go".** Downloads the profile artifact.

## Requirements

### Functional

- **FR1** — When the `pitScope` property is absent, each module's `pitest` targets the production
  classes it owns whose source files differ between the working tree (index, worktree and untracked
  files included) and the scope base; a module with no such class skips its gate cleanly, as today.
- **FR2** — The scope base is the merge base of `HEAD` with the default branch (`main`, else
  `origin/main`). When the merge base equals `HEAD` the base is `HEAD`'s first parent. When no base
  can be resolved (no `main` ref, a root commit, not a git checkout) the scope is the whole tree.
- **FR3** — A changed file under a module's test source tree widens that module's scope to its whole
  production tree. A changed file under `:test-fixtures` sources widens the scope to the whole tree.
  A deleted or renamed-away production file contributes nothing (as today).
- **FR4** — `-PpitScope=all` selects the whole tree in every module. An explicit comma-separated glob
  list keeps today's meaning (the subset each module owns). Requesting the `pitestAll` task selects
  the whole tree regardless of the property.
- **FR5** — One component owns the scope computation; the module build logic, the `pitestAll`
  aggregate and the CI workflows consume its result and compute no diff of their own. The CI
  quality-gate workflow passes no `-PpitScope` value.
- **FR6** — A scheduled workflow runs the whole-tree gate (`check` with the whole-tree scope) on
  `main` nightly and on manual dispatch, uploads the PIT reports, and on failure creates a tracker
  issue — or comments on the still-open one from a previous night — naming the failing modules and
  linking the run.
- **FR7** — The CI quality-gate run produces a Gradle profile report and uploads it as a build
  artifact with the same retention as the PIT reports.

### Non-Functional — Performance

- **NFR-P1** — Scope computation adds under 2 seconds to configuration (two or three `git`
  invocations); it never runs the test suite or reads class files.

### Non-Functional — Reliability

- **NFR-R1** — Every failure of scope resolution degrades toward the whole tree, never toward an
  empty scope: a scope the build cannot justify is wider, not narrower.
- **NFR-R2** — The computation is configuration-cache compatible: the obtained scope is recorded
  with the cached configuration and re-obtained on every build, so editing a file between two
  invocations invalidates the cache rather than reusing yesterday's scope.
- **NFR-R3** — The scoped run keeps the existing fail-closed semantics: `pitestVerifyAllKilled`
  over the scoped set, the "No mutations found" skip for an empty module scope, and the
  `excludedClasses` subtraction before the emptiness test. A module whose mutation task is skipped
  by the scope is not judged on a report left in its build directory by an earlier run: the
  verdict task and the report-location finalizer skip with it.
- **NFR-R4** — The build's `git` invocations are insulated from the operator's git configuration
  and from concurrent git use of the same checkout: the operator's global excludes cannot hide an
  untracked class, path quoting is neutral, and no optional lock (`index.lock`) is taken, so an
  agent's own `git status` running beside the build is never refused.

### Non-Functional — Observability

- **NFR-O1** — The build logs one lifecycle line per build naming the scope mode (`branch`,
  `all`, `explicit`), the resolved base commit, and the count of changed classes per module —
  including the widening reason when FR3 applied and the fallback reason when FR2's fallback did.
  The line is logged even when the scope leaves every module's mutation task skipped: a run that
  mutates nothing says so and why.

### Non-Functional — Security

- **NFR-S1** — The nightly workflow's token carries only `contents: read` and `issues: write`; no
  other workflow gains a permission.

### Non-Functional — Cost

- **NFR-C1** — The nightly run executes once per day plus manual dispatches, on the standard
  runner; it is skipped when `main` has not changed since the previous successful nightly run.
  The skip needs no permission beyond NFR-S1's pair: it must not read another run's artifacts.

## Operator Experience Criteria

- **UX1** — `./gradlew check` with no arguments is the fast, correct default; nothing has to be
  remembered to get the scoped run, and nothing has to be remembered to stay safe.
- **UX2** — The scope line reads as a sentence a reviewer can check against `git status`:
  which base, which classes, why widened.
- **UX3** — The developer guide's "Per-module verification" section describes the three modes in
  one paragraph each and names the nightly run as the whole-tree guarantee.
- **UX4** — The nightly failure issue is actionable from its body alone: module, surviving
  mutation count, link to the run and the report artifact.

## Success Metrics

- **M1** — Local `./gradlew check` after a change confined to `:application` or `:domain` completes
  in ≤ 10 minutes on the reference machine (baseline ~57 min).
- **M2** — `.github/workflows/ci.yml` contains no `git diff`, `git merge-base` or computed
  `-PpitScope=`; a build-wide spec asserts it.
- **M3** — A test-only diff that deletes an assertion from a killing spec fails the scoped gate
  (demonstrated by a TestKit scenario).
- **M4** — A push to `main` runs a non-empty mutation gate (demonstrated by the same suite's
  first-parent scenario).
- **M5** — The nightly workflow has run green at least once before the change is archived.

## Open Questions

- **Q1** — Should a changed build script (`build-logic/**`, a module `build.gradle`) widen the scope
  to the whole tree, since a change to `excludedClasses` or `pitest-conventions` can weaken the gate
  itself? Proposed answer: no — it is visible in the review diff and the nightly run covers it;
  revisit if a nightly failure is ever traced to one.
- **Q2** — Should the nightly run use `check` (the whole gate) or `pitestAll` (mutations only)?
  Proposed answer: `check` with the whole-tree scope, so one run produces every report and the
  issue body can cite one artifact set.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `quality-gates`: the "Scoped mutation target" requirement changes — the default scope is the
  branch's changes computed by the build, with explicit whole-tree and explicit-list modes and the
  widening/fallback rules; the "Continuous integration" requirement changes — CI passes no computed
  scope, uploads a profile report, and a scheduled whole-tree run with issue creation is added.
  No other active change MODIFIES either requirement; the delta restates the stable text as
  `add-release-pipeline` (archived 2026-10-04) left it, changing only the scoping sentences.

## Impact

- `build-logic/src/main/groovy/` — a new scope owner (a `ValueSource` over `git`) and a new
  convention script consuming it (`pitest-conventions.gradle` is at the file-size cap and keeps
  the engine configuration only); the whole-tree implication of `pitestAll` lives in that
  consumer. `pitest-gate-conventions.gradle` shares the scope skip with the verdict task.
- `build-logic/src/functionalTest/` — a TestKit suite driving the scope owner over a miniature git
  repository (branch, test-only, fixture, first-parent, no-base, configuration-cache scenarios).
- `bootstrap/src/test/groovy/.../architecture/` — a build-wide spec over the workflow files (M2),
  beside `DistributionTermsScriptSpec`.
- `.github/workflows/ci.yml` — remove two steps, add `--profile` and one upload;
  `.github/workflows/pitest-nightly.yml` — new; `scripts/nightly-mutation-issue.sh` — new, with
  a `:bootstrap` spec beside `DistributionTermsScriptSpec`.
- `docs/guides/developer-guide.md` — the per-module verification section; `README.md` one line.
- No production Java, ports or adapters are affected.
