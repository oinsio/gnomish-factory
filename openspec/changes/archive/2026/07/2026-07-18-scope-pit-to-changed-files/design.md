# Design: scope PIT to changed files in CI

## Context

Driven by the proposal for `scope-pit-to-changed-files`. `build.gradle`'s
`pitest { targetClasses = ['com.github.oinsio.gnomish.*'] }` mutates the whole
production tree; the CI job (`.github/workflows/ci.yml`, `timeout-minutes: 10`)
runs `./gradlew check` and no longer finishes in time (FR2, NFR-C1). The build
already has two guards that must keep working over any narrowed scope: the
`pitest.mutationThreshold = 100` gate and the fail-closed `pitestVerifyAllKilled`
task that parses `mutations.xml` and rejects any non-`KILLED` mutation.

## Goals / Non-Goals

**Goals:**
- CI mutates only production Java classes changed vs. the merge base with `main` (FR1–FR3).
- Local `./gradlew check` keeps full-project mutation, unchanged (FR5).
- An empty production diff is a clean pass, never a "No mutations found" failure (FR4).
- The 100% gate and `pitestVerifyAllKilled` apply unchanged to the scoped set.

**Non-Goals:**
- No scheduled/nightly full-project run (NG1). No threshold or exception changes (NG2).

## Decisions

### D1 — Opt-in Gradle property overrides `targetClasses`
Add a single project property, `pitScope`, read at configuration time. When set,
its comma-separated globs replace `pitest.targetClasses`; when unset or blank the
default full-tree list stands. Rationale: one narrow seam, zero behavior change
for local runs, and the property is the only thing CI needs to inject.
*Alternative rejected:* a community `pitest-git-plugin` / arcmutate SCM
integration — adds a dependency and hides the diff logic; the project already
treats `git` as a plain subprocess and prefers explicit control (ADR 0001).

### D2 — CI derives the class list from the diff, not the build
The workflow computes changed classes from git and passes them via `-PpitScope`.
Keeping the diff logic in the workflow (not `build.gradle`) preserves the rule
that local and CI builds share identical Gradle semantics — CI only supplies an
input. Mapping: `git diff --diff-filter=d --name-only <base>...HEAD` filtered to
`src/main/java/**/*.java`, each path stripped of the `src/main/java/` prefix and
`.java` suffix with `/`→`.`, yielding a fully-qualified class name used verbatim
as a PIT glob. `--diff-filter=d` drops deletions (a deleted class cannot be
mutated). Nested/inner classes need no special handling — mutating the top-level
class covers them.

### D3 — Base ref = merge base with `main`
For `push` runs, diff against `origin/main` (`git merge-base`), so only what the
branch adds on top of `main` is scoped — not unrelated commits already on `main`.
For fork `pull_request` runs, use the event's base SHA. Checkout must use
`fetch-depth: 0` (full history) so the merge base is reachable; the current
workflow uses the default shallow checkout and must change.

### D4 — Empty scope skips the mutation task cleanly
PIT hard-fails ("No mutations found", exit 1) on an empty target set. The
existing `onlyIf('no Java production classes to mutate yet')` guard already skips
`pitest` when there are no Java sources; extend the same predicate so that when
`pitScope` is *set but empty* (no changed production classes) the `pitest` task
is skipped instead of run. `pitestVerifyAllKilled` already mirrors pitest's skip
via its own `onlyIf` (no report file → skip), so it needs no change. Result: a
docs/tests/CI-only PR runs the full Spock + coverage + static-analysis gates and
simply has nothing to mutate.

### D5 — `targetTests` stays broad
Only `targetClasses` is narrowed. PIT's coverage analysis already selects the
tests that exercise the scoped classes, so a broad `targetTests` scan costs
little and avoids brittle test-name mapping. The speedup comes from mutating far
fewer classes, not from cutting the test scan.

## Risks / Trade-offs

- **Cross-file mutation rot** → a change in file A can make a mutation in an
  unchanged file B survive; the per-PR scoped gate will not catch it. *Accepted*
  per the proposal (no CI full run); local/manual `./gradlew check` remains the
  whole-project guarantee. Documented as the change's central trade-off.
- **Wrong/missing base ref** (shallow checkout, detached history) → the diff
  could be empty or over-broad. *Mitigation:* `fetch-depth: 0`; fail the step
  loudly if the merge base cannot be resolved rather than silently mutating
  nothing.
- **Renames** → `git diff` reports a rename as delete+add; the added path is
  included, the deleted one dropped by `--diff-filter=d`. Correct behavior, noted
  so it is not mistaken for a bug.
- **`pitScope` accidentally set in a local run** → developer sees a scoped run.
  *Mitigation:* the property is opt-in and documented as CI-only; unset by
  default, `./gradlew check` is full.

## Migration Plan

Build-only change, no data or API migration. Rollback = revert the workflow step
and the `build.gradle` property read; the default full-tree `targetClasses`
returns automatically since the property is opt-in.

## Open Questions

None — the safety-net question (nightly full run) was resolved as out of scope.
