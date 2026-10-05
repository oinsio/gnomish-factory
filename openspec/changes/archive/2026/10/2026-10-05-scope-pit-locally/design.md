# Design: scope-pit-locally

## Context

See proposal.md — Why. What shapes the approach:

- `pitest-conventions.gradle` already owns the per-module narrowing: it reads the `pitScope`
  property at configuration time, intersects the globs with the module's own classes, subtracts
  `excludedClasses` in `afterEvaluate`, and skips the task when the result is empty
  (`scope-pit-to-changed-files` D1, D4). The diff itself is computed outside the build, in two
  shell steps of `.github/workflows/ci.yml` (that change's D2: "keep the diff logic in the
  workflow so local and CI share identical Gradle semantics"). This change inverts D2: the
  semantics stay identical precisely because the build computes the diff itself.
- The build runs with `org.gradle.configuration-cache=true`. A value read from the environment at
  configuration time must come through a `ValueSource`, or the cache will replay a stale scope;
  `HardwareSpec` is the in-repo precedent (`adapt-build-load-to-hardware` D3).
- The AI agent never commits (`process-invariants.md`), so on the development machine the branch's
  work is uncommitted for the whole change. A scope that reads only committed history would see
  nothing — the working tree, index and untracked files are the primary input, not an edge case.
- `MutationEngineLock` serializes module PIT runs; with a scoped default most runs are skipped, so
  the lock's cost disappears without touching it (NG3).
- `build-logic` is an included build with a TestKit `functionalTest` suite
  (`GradleRunnerSupport`, `TestTimeInjectionCheckFunctionalSpec`); `:bootstrap` holds the
  whole-tree architecture gates that read repository files, including workflow files
  (`DistributionTermsScriptSpec`, `RepoSourceTree`).

## Goals / Non-Goals

**Goals:** one scope owner for every environment (FR5); fail-safe toward the whole tree (NFR-R1);
configuration-cache correctness (NFR-R2); the two gap closures (FR2, FR3); a nightly whole-tree
run (FR6); evidence by TestKit scenarios rather than by reading the script.

**Non-Goals:** PIT history, line-level scope, lock relaxation, threshold or exemption changes
(proposal NG1–NG6); any change to how a module's `targetClasses` are intersected with
`excludedClasses` — that code stays.

## Decisions

**D1 — The scope is computed by one `ValueSource` in `build-logic`, `MutationScopeSource`,
and nowhere else.** It takes the repository root and the module→source-root map as parameters,
runs `git` through the injected `ExecOperations`, and returns a serializable `MutationScope`
value: the mode (`BRANCH` / `ALL` / `EXPLICIT`), the resolved base (or the fallback reason), and
per module the set of changed production class names plus a `widened` flag with its reason.
A new convention script, `pitest-scope-conventions.gradle`, obtains it once per build
(`providers.of(MutationScopeSource)`) and derives `targetClasses` and the skip predicate from it
— a separate script because `pitest-conventions.gradle` sits at the 200-line cap
(`ModuleBuildFileSpec`) and already split its verdict half out as `pitest-gate-conventions`;
the split follows the same responsibility line: engine configuration stays, *what to mutate
this build* moves. `pitest-conventions` applies the new script as it applies the gate script.
The CI workflows pass a mode, never a list. *Rationale:* FR5 and NFR-R2 — a `ValueSource` is the only
configuration-time read the configuration cache re-validates on every build, so an edit between
two invocations invalidates the cache instead of replaying yesterday's scope; and a single typed
value is what makes "CI and local agree" true by construction rather than by two scripts agreeing.
*Alternative rejected:* keep the shell computation in `ci.yml` and add a copy in a local script
— two implementations of one rule, the shape `manual-sync-pairs.md` exists to prevent, and the
local copy would still have to be invoked by hand (UX1).

**D2 — Changed files = `git diff --name-status --no-renames <base>` (working tree against the
base, so index and worktree edits are included) plus `git ls-files --others --exclude-standard`
(untracked).** Revised at apply time from `--name-only --diff-filter=d`: that form also dropped a
deleted *spec*, and deleting a killing spec is the weakening FR3 exists to catch. With the status
column a deleted production file still contributes nothing, a deleted test file widens its
module, and `--no-renames` makes a rename a deletion plus an addition whatever the operator's
`diff.renames` says. Both lists are mapped to modules by the `src/main/java/` marker exactly as the
current shell step does, and by the `src/test/` marker, which is new (D4); the module prefix is
matched by source-root segment, not by path depth, since modules nest (`sandbox/core`). Every
`git` process the source runs is insulated from the operator's environment (NFR-R4): argv
carries `-c core.quotePath=false` (stable path spelling) and `-c core.excludesFile=` (the
operator's global ignore list cannot hide an untracked class — that would narrow the scope,
the one direction NFR-R1 forbids; the repository's own `.gitignore` still applies), and the
environment carries `GIT_OPTIONAL_LOCKS=0`, so `git diff`'s index refresh takes no
`index.lock` and an agent's `git status` beside the running build is never refused.
*Rationale:* the agent's work is uncommitted (Context); a two-dot diff against the base
compares the worktree, not `HEAD`. On a clean CI checkout the same commands yield the committed
diff, so one code path serves both. *Alternative rejected:* `git status --porcelain` plus a
committed-range diff — two commands for the same answer with two parsers.

**D3 — Base resolution order: `merge-base(HEAD, main)`, else `merge-base(HEAD, origin/main)`;
if `HEAD` is the branch `refs/heads/main`, use `HEAD^`; if any step fails, mode `ALL` with the
failure as the reason.** *Rationale:* FR2 and NFR-R1. On a feature branch the merge base scopes
exactly the branch's additions; on `main` itself the merge base is `HEAD` and would scope nothing
— the first parent is what a squash merge or a merge commit changed. The trigger is the branch
*name*, not "merge base equals `HEAD`" (revised at apply time): a fresh branch with no commits of
its own — the agent's state for a whole change, since it never commits — also has merge base
`HEAD`, and the equality rule would re-mutate the last squash on `main` (0 to 102 production
classes over the six most recent ones) on every local run. Every surveyed tool that scopes to a
base (Nx, Turborepo, moon, Pants) leaves such a branch at merge base `HEAD`, i.e. scopes only the
uncommitted work; the one with an in-tool first-parent rule, moon, gates it on the current branch
name equalling the default branch, as this does. A push to `main` checks out the local branch
`main` (`actions/checkout`), so the CI path keeps the first parent; a pull request's merge commit
is detached and its merge base is not `HEAD`, so it needs no rule. A detached `HEAD` at `main`'s
tip scopes only the dirty tree, like a fresh branch. Every failure (no `main` ref in a fresh
clone with one branch, a root commit, `git` absent, not a checkout) resolves to the widest scope,
which is slow but never wrong. Two consequences of the order are deliberate. A developer's
local `main` that lags `origin/main` yields an older merge base, hence a wider scope — the safe
direction, so no freshness check is made. And the fork `pull_request` path the current `ci.yml`
step keeps apart (the event's base SHA) needs no counterpart: on that event the checkout is the
`refs/pull/N/merge` commit, whose first parent is the base branch tip, so
`merge-base(HEAD, origin/main)` is that tip and the diff is exactly the pull request's changes;
`fetch-depth: 0` stays so the history is there. Each candidate ref costs one `git` process:
`git rev-parse <ref>...HEAD HEAD^ --symbolic-full-name HEAD` prints `HEAD`, the ref, the merge
base (negated), the first parent and the current branch's full name together, so the "on `main`"
test needs no second call. No single command falls back
from `main` to `origin/main` (`--revs-only` and `--ignore-missing` drop the whole range), so a
checkout without a local `main` — every CI branch and pull-request checkout — spends a fourth
process; accepted at apply time over reordering the refs, which would narrow the scope. *Alternative rejected:* fail the build when
the base cannot be resolved, as the CI step does today — on an agent's machine that turns a
slow-but-correct run into a blocked task, and the agent's likely fix is to pass `-PpitScope` by
hand, which is the escape hatch this change removes.

**D4 — Widening rules are decided in the `ValueSource`, not in the module script.** A changed
file under `<module>/src/test/` marks that module `widened(test-change)`; a changed file under
`test-fixtures/src/` marks every module `widened(shared-fixture)`; a module marked widened gets
its whole production tree as its target list. *Rationale:* FR3 — the mapping from "what changed"
to "what to mutate" is the owner's whole job; splitting it would leave the module script holding
half the rule. The shared-fixture rule is deliberately the whole tree: `:test-fixtures` is
`api`-consumed by every module's test classpath, and a weakened fake can hide a survivor anywhere.
*Alternative rejected:* mapping `FooSpec` → `Foo` by name — the project's specs are named by
capability (`PushTerminationLoggingSpec` kills `GitProcessRunner` mutants), so the name rule would
miss exactly the specs that matter.

**D5 — Property contract: absent → `BRANCH`; `all` → `ALL`; anything else → `EXPLICIT` with
today's glob semantics; requesting the `pitestAll` task → `ALL` regardless.** The task-name check
reads `gradle.startParameter.taskNames`, which is part of the configuration-cache key, so a
`pitestAll` run and a `check` run cache separately. *Rationale:* FR4; the explicit list is kept
because the functional specs and the per-module `:module:check -PpitScope=…` developer workflow in
the guide use it. *Alternative rejected:* a second property (`pitScopeMode`) — two knobs for one
decision.

**D6 — The scope is announced once per build, at execution time, by one root task
`pitestScope` that every module's `pitest` and `pitestVerifyAllKilled` depend on.** The task is
registered once at the root (the `registerIfAbsent` shape `pitestAll` uses), carries the typed
scope as its input, has no `onlyIf`, and logs the line in `doLast`; it therefore runs — and the
line prints exactly once — whether the scope leaves one module to mutate or none. The line has
the shape `PIT scope: branch — base 3f2c1a7 (merge-base main) — adapters/git: 3 classes,
application: whole module (test change), 15 modules skipped`. *Rationale:* NFR-O1, UX2; a
configuration-time log line vanishes on a configuration-cache hit, as the budget's did before
D6 of `adapt-build-load-to-hardware`. *Alternatives rejected:* a `doFirst` on the first
`pitest` task that starts (the heavy-JVM budget's idiom) — on a clean tree or a docs-only
change every `pitest` is skipped by its `onlyIf`, no `doFirst` runs, and the one build the
reviewer most needs explained prints nothing; logging from inside the `ValueSource` — it runs
during cache validation too, so the line would print even for builds that run no PIT.

**D7 — Nightly = a separate workflow `pitest-nightly.yml`: `schedule` + `workflow_dispatch`,
`./gradlew check -PpitScope=all`, PIT reports uploaded, failure → issue via `gh`.** The Gradle
step runs under `set -o pipefail` and tees its output to `build/nightly.log`: a later step
cannot read an earlier step's console, so the file is what the failure step parses. Issue
handling: search open issues carrying the `nightly-mutation` label; comment on the first if one
exists, create otherwise; the body lists the modules whose `pitestVerifyAllKilled` or `pitest`
failed (the `:module:task FAILED` lines of `build/nightly.log`) and links the run. The parsing
and the comment-or-create decision live in `scripts/nightly-mutation-issue.sh`, driven by a
Spock spec over a canned log and a stubbed `gh` — the `check-distribution-terms.sh` /
`DistributionTermsScriptSpec` shape — so the only part verified by hand is the live dispatch.
Skip rule: a
restore-only `actions/cache` lookup keyed `nightly-verified-<HEAD sha>`; a hit means the
previous successful run verified this commit, and the job exits 0 before Gradle starts; on
success the job saves that key (an empty marker file). *Rationale:* FR6, NFR-C1, NFR-S1, UX4;
`check` rather than `pitestAll` answers proposal Q2 — one run, one complete artifact set, one
place to link. A separate workflow rather than a `schedule` trigger on `ci.yml` keeps
`ci.yml`'s permissions at `contents: read` (NFR-S1) and keeps the nightly's `issues: write`
from reaching branch pushes. The cache rather than an artifact for the skip marker: reading
another run's artifact is the Actions API and needs `actions: read`, which NFR-S1 withholds;
the cache API needs no token permission at all, and a cache evicted after seven idle days means
one redundant run, never a missed one. *Alternatives rejected:* a GitHub Marketplace "create
issue on failure" action — one more third-party action to pin and audit for a ten-line `gh`
script; the repository already uses `gh` in workflows. A `nightly-verified-sha` artifact
downloaded from the previous run — the permission above.

**D8 — Profile: `--profile` on the CI `check` invocation, `build/reports/profile/` uploaded
with the PIT reports' retention.** *Rationale:* FR7; the daemon-log archaeology that produced this
change should not be needed twice. *Alternative rejected:* Develocity build scans — an external
service and a terms-of-service acceptance for a report Gradle writes locally.

**Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.** It removes one: the shell computation in `ci.yml` and the Gradle-side intersection in
`pitest-conventions.gradle` were two halves of one rule kept in step by hand without a marker; D1
leaves one half.

**Single-owner mechanisms:**

| Owner                                     | Value (type)                                                     | Consumers                                                                                                                                                                                                                                                                                                                                                                                                    | Old way removed                                                                                                                                                                                                                                                                                                         | Enforced by                                                                                                                                                                                                                                                                                                                               |
|-------------------------------------------|------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MutationScopeSource` (`build-logic`, D1) | `MutationScope` (mode, base, per-module class sets and widening) | `pitest-scope-conventions.gradle` (`targetClasses`, the shared skip predicate, the `pitestScope` announcement task; reads `gradle.startParameter.taskNames` for the `pitestAll` implication, D5); `pitest-gate-conventions.gradle` (the skip predicate, taken from the scope script, on `pitestVerifyAllKilled` and `pitestReportLocation`); `.github/workflows/ci.yml` and `pitest-nightly.yml` (mode only) | `ci.yml` steps "Resolve diff base" and "Compute changed production classes" deleted; the computed `-PpitScope=${{ … }}` argument deleted; the raw `pitScopeRaw.split(',')` glob parse and the `pitScopeSet` boolean in `pitest-conventions.gradle` deleted, replaced by the typed value's accessors in the scope script | `MutationScopeFunctionalSpec` (TestKit, build-logic) drives the owner over a miniature git repository for every mode and fallback; `CiScopeOwnerSpec` (`:bootstrap`, beside `DistributionTermsScriptSpec`) asserts no workflow under `.github/workflows/` contains `git merge-base`, `git diff` or a `-PpitScope=` value other than `all` |

No identity claim is made ("the same" scope locally and in CI follows from there being one
computation, not from two values agreeing), so no identity spec is needed.

## Risks / Trade-offs

- **A red build on `main` is not re-judged by the next push** — the first-parent base scopes
  only the newest commit, so a survivor left by a red push is not in the next push's scope (the
  reason Nx's own CI recipe prefers the last green commit over `HEAD~1`) → the nightly run (D7)
  mutates the whole tree within a day; a last-green base would need the Actions API, which
  NFR-S1 withholds.
- **Cross-file mutation rot now also applies locally** (a mutant in an unchanged file survives
  because of a change elsewhere) → the nightly run (D7) catches it within a day; the issue it
  opens is the hand-off to a human. This is the trade-off `scope-pit-to-changed-files` accepted
  for CI, now extended to the local default, with the compensating control it declined.
- **A build-script change can weaken the gate without widening the scope** (proposal Q1) →
  visible in the review diff; the nightly run covers the result. Not widened, by decision.
- **`git` output differences across versions and operators** (rename detection, path quoting
  of non-ASCII names, a global ignore list) → `--name-status` with `-z`-free plain paths is stable
  across the floor version the project already requires (`gittransfer` pins git ≥ 2.45.1); the
  argv and environment of D2 neutralize `core.quotePath`, `core.excludesFile` and optional
  locks; the functional spec includes a non-ASCII path scenario and a global-ignore scenario.
- **A stale report in a skipped module** → today every local run mutates every module, so a
  `mutations.xml` under `build/reports/pitest/` is always this run's; with the scoped default a
  skipped module keeps the file an earlier run left, and `pitestVerifyAllKilled`'s
  `onlyIf(report exists)` would judge yesterday's survivors. The scope skip is therefore one
  predicate shared by `pitest`, `pitestVerifyAllKilled` and `pitestReportLocation`
  (`pitest-gate-conventions` takes it from `pitest-scope-conventions`), so a module the scope
  skips is skipped as a whole (NFR-R3).
- **The functional spec's git is the developer's git** → `build-logic`'s own build does not
  apply `adversarial-gitconfig-conventions`, so the mini repository would commit under
  `~/.gitconfig` (no identity on a CI runner; a global ignore or `diff.*` setting on a
  workstation). The spec therefore points `GIT_CONFIG_GLOBAL` at its own file — an identity and
  nothing else — for both the TestKit runner's environment and the fixture's `git` processes,
  the way `AdversarialGitConfig` does for the main build.
- **`ValueSource` re-runs on every build, including cache-hit builds** → three `git` invocations (a fourth when only `origin/main` exists, as on a CI checkout — D3),
  measured well under NFR-P1's two seconds on the reference machine; the spec asserts the count of
  invocations, not the time.
- **The nightly skip reads a cache entry from the previous run** → an evicted or missing entry
  simply means "run"; the failure direction is a redundant run, never a missed one.
- **`--profile` under the configuration cache** → supported in Gradle 9.x; if a future Gradle
  deprecates it, the upload step's path simply finds nothing and the step is
  `if: ${{ !cancelled() }}` (as `ci.yml`'s report uploads are) with `if-no-files-found: ignore`,
  so CI does not go red for a missing report.

## Migration Plan

Build and CI only; no data, no API. Rollback is a revert: the `ci.yml` steps return, the
`ValueSource` is unregistered, and the module script's previous property parse is restored. The
first run after the change on a fresh machine resolves `main` or `origin/main` like any other; no
operator action.

## Open Questions

None that affect specs or tasks. The nightly cron minute is chosen at implementation time to avoid
the OSV and Gitleaks weekly slots (03:32, 03:47 UTC Monday).
