# Scope PIT mutation testing to changed files in CI

## Why

The `quality-gates` CI job runs `./gradlew check`, which mutates the entire
`com.github.oinsio.gnomish.*` tree (199 production classes and growing). PIT no
longer finishes inside the job's 10-minute budget: the run is cancelled, the
check never reports success, and pull requests cannot be merged. Full-project
mutation coverage does not scale with the codebase on a per-PR gate.

## What Changes

- CI runs PIT against **only the Java production classes touched by the branch**
  (the diff versus the merge base with `main`), not the whole project — so the
  mutation gate scales with change size, not codebase size.
- The build accepts an explicit, opt-in list of target classes (a Gradle
  property) that narrows PIT's `targetClasses`; when the property is absent the
  build behaves exactly as today (full-project mutation). Local `./gradlew
  check` is unchanged.
- The CI workflow computes the changed-class list from the git diff, maps
  changed `*.java` production files to fully-qualified class globs, and passes
  it to the scoped PIT run.
- When the branch changes **no** production Java files, the scoped PIT run is a
  no-op that passes cleanly (no "No mutations found" hard failure), so
  docs/test/CI-only PRs still go green.
- The 100% mutation-score gate and the fail-closed `pitestVerifyAllKilled`
  check continue to apply — now over the scoped set of mutations.
- **Trade-off (accepted):** whole-project mutation coverage is guaranteed only
  by local/manual `./gradlew check`, not by CI. A mutation that survives in an
  unchanged file because of a change elsewhere is not caught by the per-PR gate.
  No scheduled full run is added (out of scope, see Non-goals).

## Capabilities

### Modified Capabilities

- `quality-gates` — the "Continuous integration" and "Mutation testing gate"
  requirements change: CI mutates only changed production classes, with a
  build-level mechanism to scope `targetClasses` and a defined empty-diff
  behavior. `./gradlew check` semantics (full mutation, 100% gate) are
  preserved for local runs.

## Impact

- `.github/workflows/ci.yml` — add a step that derives the changed-class list
  from the diff and invokes the scoped mutation run; fetch depth must allow
  diffing against `main`.
- `build.gradle` — read the opt-in target-classes property and, when present,
  narrow `pitest.targetClasses`; keep the empty case a clean skip.
- No production code, ports, or adapters are affected — this is a build/CI-only
  change.

## Non-goals

- **NG1** — No scheduled or nightly full-project mutation run in CI.
- **NG2** — No change to the mutation threshold, the documented PIT exceptions,
  or `pitestVerifyAllKilled`.
- **NG3** — No change to local developer workflow: `./gradlew check` stays a
  full-project mutation run.
