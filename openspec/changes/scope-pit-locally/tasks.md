# Tasks

Every task's sub-agent runs `./gradlew check` in the touched module(s) and, for `build-logic`,
`./gradlew check` from the ROOT: root `check` depends on
`gradle.includedBuild('build-logic').task(':check')` (`build.gradle`, FR9 of
add-functional-api-gate-test), which is what runs the included build's `functionalTest`;
`-p build-logic` does not.

## 1. The scope owner (D1–D5; FR1, FR2, FR3, FR4, NFR-P1, NFR-R1, NFR-R2, NFR-R4)

- [ ] 1.1 Add `MutationScope` (serializable value: mode `BRANCH|ALL|EXPLICIT`, base SHA or
      fallback reason, per-module changed-class sets, per-module widening reason) and
      `MutationScopeSource implements ValueSource<MutationScope, Parameters>` under
      `build-logic/src/main/groovy/com/github/oinsio/gnomish/build/`, with `ExecOperations`
      injected and the repository root plus module→source-root map as parameters. Every `git`
      argv carries `-c core.quotePath=false -c core.excludesFile=` and every process runs with
      `GIT_OPTIONAL_LOCKS=0` (D2, NFR-R4). Javadoc states the three `git` invocations (D2, D3),
      the widening rules (D4), the insulation and why a `ValueSource` (NFR-R2, `HardwareSpec`
      precedent). Verify: compiles; `ModuleBuildFileSpec`'s 200-line cap holds for every file
      touched (split the parser from the source if it does not).
- [ ] 1.2 Write `MutationScopeFunctionalSpec` (TestKit, `build-logic/src/functionalTest`) over a
      miniature git repository built per scenario with real `git` (init, `main`, a feature branch,
      module layout `mod-a/src/main/java`, `mod-a/src/test/groovy`, `test-fixtures/src/main`).
      The spec owns its git configuration: it writes a global config file holding only a
      committer identity, points `GIT_CONFIG_GLOBAL` at it in the fixture's `git` processes
      and in the TestKit runner's environment (`build-logic` does not apply
      `adversarial-gitconfig-conventions`, so without this the mini repository commits under
      the developer's `~/.gitconfig` and has no identity on a CI runner — Risks). Scenarios:
      (a) a committed production change on the branch scopes that class only — FR1;
      (b) an UNCOMMITTED edit and an UNTRACKED new class are scoped — FR1, D2;
      (c) `main` moved after the fork: its classes are not scoped — FR2 merge-base scenario;
      (d) on `main` itself the first parent is the base — FR2 (M4);
      (e) no `main`/`origin/main`, and a root commit, and a non-git directory → mode ALL with the
          reason — FR2, NFR-R1;
      (f) a test-only change widens that module; a `test-fixtures` change widens every module — FR3
          (M3's precondition);
      (g) a deleted production file contributes nothing — FR3;
      (h) `-PpitScope=all` → ALL; `-PpitScope=com.x.Foo` → EXPLICIT; `pitestAll` in the task list →
          ALL — FR4, D5;
      (i) a non-ASCII path survives `core.quotePath` — NFR-R4, Risks;
      (j) configuration cache (NFR-R2): with `--configuration-cache`, two builds with no edit —
          the second output contains "Reusing configuration cache" and the same scope; a
          production file edited, a third build — the cache is invalidated and the edited class
          is in the scope;
      (k) the spec's global config additionally ignores `*.java` under the module's untracked
          directory: the untracked class is still scoped (`core.excludesFile` neutralized) —
          NFR-R4, NFR-R1;
      (l) the fixture holds `index.lock` open during the build: the scope resolves anyway
          (`GIT_OPTIONAL_LOCKS=0`) — NFR-R4.
      The mini build applies only the ValueSource (no convention plugin, `--offline`, as
      `TestTimeInjectionCheckFunctionalSpec` does) and prints the obtained scope for assertion.
      Verify: root `./gradlew check` runs the suite green; each scenario shown red first by
      disabling the rule it pins.
- [ ] 1.3 Assert NFR-P1 by shape, not time: the spec counts `git` invocations per build (≤ 3 in
      BRANCH mode, 0 in ALL mode via the property) through a `GIT_TRACE`-free wrapper script on the
      mini repository's PATH that appends each argv to a file. Verify: scenario green; a fourth
      invocation turns it red.

## 2. Consuming the owner in the build (D1, D5, D6; FR1, FR4, FR5, NFR-O1, NFR-R3, UX1, UX2)

- [ ] 2.1 Add `pitest-scope-conventions.gradle` (D1): obtain the scope via
      `providers.of(MutationScopeSource)` with this module's identity; set `targetClasses` from
      the typed value (ALL/widened → the module's package globs; BRANCH → the changed classes it
      owns; EXPLICIT → today's intersection); carry the `afterEvaluate` `excludedClasses`
      subtraction and the empty-scope resolution over from `pitest-conventions.gradle`; expose
      ONE skip predicate (a `Spec<Task>` over serializable data, as `pitScopeResolution` is
      today) that `pitest` takes here. The `pitestAll` implication reads
      `gradle.startParameter.taskNames` (D5). In `pitest-conventions.gradle` delete the
      `pitScope` block — `pitScopeRaw`, `pitScopeSet`, `globToPattern`, the `afterEvaluate`
      scope half and the scope `onlyIf` — and apply the new script beside
      `pitest-gate-conventions` (single-owner table, "Old way removed"). Verify:
      `./gradlew :domain:check` on a clean tree skips `:domain:pitest` with the skip reason;
      `./gradlew :domain:check -PpitScope=all` mutates it; both scripts stay ≤ 200 lines
      (`ModuleBuildFileSpec`), `pitest-conventions.gradle` shorter than before.
- [ ] 2.2 Share the skip with the verdict (NFR-R3, Risks "A stale report in a skipped module"):
      `pitest-gate-conventions.gradle` puts the same predicate on `pitestVerifyAllKilled` and
      `pitestReportLocation`, so a module the scope skips is skipped as a whole and a
      `mutations.xml` left by an earlier run is never judged. Document in the `pitestAll` task
      description that it mutates the whole tree regardless of `pitScope`. Verify: plant a
      stale `mutations.xml` with a SURVIVED entry under `domain/build/reports/pitest/`, run
      `./gradlew :domain:check` on a clean tree — green, `:domain:pitestVerifyAllKilled` SKIPPED;
      `./gradlew pitestAll --dry-run` lists every module's `pitest`; `./gradlew pitestAll` on a
      clean tree does not skip them (watch one small module, `:atomicfile`).
- [ ] 2.3 Announce the scope once per build (D6): `pitest-scope-conventions` registers the root
      task `pitestScope` once (`registerIfAbsent` shape, as `pitestAll`), with the typed scope
      as its input, no `onlyIf`, and a `doLast` logging the lifecycle line — mode, base (or
      fallback reason), per-module counts, widening reasons, skipped-module count; every
      module's `pitest` and `pitestVerifyAllKilled` depend on it. Verify: `grep -c "PIT scope:"`
      == 1 for a scoped `check` with changes, for a scoped `check` on a clean tree where every
      `pitest` is skipped, and on a configuration-cache hit (run twice).
- [ ] 2.4 Update `docs/guides/developer-guide.md` "Per-module verification": three modes in one
      paragraph each (branch default, `-PpitScope=all` / `pitestAll`, explicit list), the
      widening rules, the fallback direction, the nightly run as the whole-tree guarantee (UX3);
      one-line touch in `README.md`'s quality-gate sentence. Verify: `grep -n "pitScope" docs
      README.md` shows only the new wording; no mention of "leave it unset locally" survives.

## 3. CI consumes the mode only (D1, D8; FR5, FR7; M2)

- [ ] 3.1 Edit `.github/workflows/ci.yml`: delete the "Resolve diff base" and "Compute changed
      production classes" steps and the computed `-PpitScope=` argument; keep `fetch-depth: 0`
      (the build's own merge-base needs the history — say so in the comment, and that the fork
      `pull_request` case needs no separate base, D3); add `--profile` to the `check` invocation
      and an upload step for `build/reports/profile/` with `if: ${{ !cancelled() }}`,
      `if-no-files-found: ignore`, 14-day retention (D8). Verify: the workflow parses
      (`gh workflow view` or `actionlint` if available); the next push run's log carries the
      `PIT scope: branch — base …` line and a `profile` artifact.
- [ ] 3.2 Write `CiScopeOwnerSpec` in `bootstrap/src/test/groovy/.../architecture/` beside
      `DistributionTermsScriptSpec`: over every file in `.github/workflows/`, assert no
      `git merge-base`, no `git diff`, and no `-PpitScope=` value other than `all`; assert the scan
      reached `ci.yml` and `pitest-nightly.yml` by name (M2; single-owner "Enforced by"). Verify:
      green; red when a `-PpitScope=com.` literal is planted in a scratch copy of `ci.yml`.

## 4. The nightly whole-tree run (D7; FR6, NFR-S1, NFR-C1, UX4; M5)

- [ ] 4.1 Add `.github/workflows/pitest-nightly.yml`: `schedule` (a minute clear of the OSV and
      Gitleaks weekly slots) + `workflow_dispatch`; `permissions: contents: read, issues: write`;
      `timeout-minutes` unset on the build job (same reasoning as `ci.yml`'s); checkout
      `fetch-depth: 1` (ALL mode needs no history); skip step: `actions/cache/restore` with
      `lookup-only: true` and key `nightly-verified-${{ github.sha }}` — a hit ends the job
      with exit 0 before Gradle starts (NFR-C1, no other run's artifact is read, NFR-S1); the
      Gradle step runs `set -o pipefail; ./gradlew check -PpitScope=all 2>&1 | tee
      build/nightly.log`; on success `actions/cache/save` writes the key with an empty marker
      file; upload PIT reports with `if: ${{ !cancelled() }}`. Verify: `workflow_dispatch` run
      completes green (M5), its log shows `PIT scope: all`; a second dispatch on the same commit
      exits at the skip step.
- [ ] 4.2 Add the failure step: `if: failure()`, a short `gh` script that lists the failed Gradle
      task paths from `build/nightly.log` (`:module:pitest`, `:module:pitestVerifyAllKilled`,
      the `FAILED` lines), searches open issues labelled `nightly-mutation`, comments on the
      first or creates one whose body carries the module list, the run URL and the artifact
      name (UX4). Create the label if missing. The parsing and the comment-or-create decision
      live in `scripts/nightly-mutation-issue.sh`, driven by a Spock spec beside
      `DistributionTermsScriptSpec` over a canned log and a stubbed `gh` on PATH (two
      scenarios: no open issue → create with the module list; one open → comment). Verify: the
      spec is green; a `workflow_dispatch` run against a branch with a deliberately planted
      surviving mutant (a scratch branch, not committed to `main`) opens the issue; a second
      dispatch comments on it instead of opening another; the scratch branch is deleted
      afterwards and the issue closed by hand.
- [ ] 4.3 Extend `CiScopeOwnerSpec` (3.2) with the nightly's permission pins: the file grants
      exactly `contents: read` and `issues: write` and nothing else, and `ci.yml` still grants
      `contents: read` only (NFR-S1). Verify: green; red when `pull-requests: write` or
      `actions: read` is added to a scratch copy.
- [ ] 4.4 Document the nightly in `docs/guides/developer-guide.md` "CI" section: what it runs,
      where its issue lands, how to dispatch it by hand after fixing a survivor. Verify: section
      present; the guide's "There is no whole-tree mutation task: `./gradlew check` aggregates
      every module's run, which together cover the full production tree" sentence is replaced
      by the three-mode text of 2.4 and the nightly as the whole-tree guarantee.

## 5. Integration and measurement (G1, M1)

- [ ] 5.1 On the reference machine, with a change confined to `:application` (reuse the current
      working tree or a scratch edit), time `./gradlew check` end to end and record it against the
      57-minute baseline in the task report (M1 ≤ 10 min). Record also `:adapters:git`-only and
      clean-tree timings. Verify: numbers in the report; if M1 is missed, name the remaining
      cost by task from `--profile`.
- [ ] 5.2 Sweep (per `implementation.md`): `grep -rn "pitScope\|merge-base\|diff-filter"` over
      `.github/`, `build-logic/`, `*.gradle`, `docs/`, `README.md`; every hit is the owner, a
      consumer taking the typed value or the mode, or documentation of the new contract. Report
      the grep, the hits and the disposition of each.
