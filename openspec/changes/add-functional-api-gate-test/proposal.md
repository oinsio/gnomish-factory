# Proposal: add-functional-api-gate-test

## Why

The api-compatibility gate (`japicmpApiGate`, FR14/M5 of `add-plugin-architecture`) is
verified today by text-matching the build script: `ApiCompatibilityGateSpec` asserts that
`published-api-conventions.gradle` contains substrings like `failOnModification = true`.
This verifies that certain words exist, not that the gate works. It breaks on harmless
refactors of the build logic (asserting form, not behavior) and misses real disarming — an
`onlyIf { false }`, a dropped `check` wiring, or a broken baseline classpath all keep the
substrings intact while the gate silently stops biting. The gate's actual bite was verified
only once, manually, during task 7.3 of `add-plugin-architecture`; nothing re-verifies it
after future build-logic edits. `build-logic` currently has no tests at all.

## What Changes

- **ADDED**: a functional test suite (`functionalTest`, Gradle TestKit) in `build-logic`
  that builds a miniature project, applies the gate convention, and proves the gate's
  behavior: an incompatible api change fails the build, a compatible addition passes,
  a missing baseline fails with the arming error, and the gate really executes under
  `check` (outcome is not SKIPPED).
- **MODIFIED**: the api-compatibility gate logic is extracted from
  `published-api-conventions.gradle` (160 lines, over the project's 100–120 target) into
  its own convention plugin, applied by `published-api-conventions`. The gate's observable
  behavior for `:gnomish-plugin-api` is unchanged.
- **REMOVED**: the text-matching feature method in `ApiCompatibilityGateSpec` ("the
  published-api convention is armed to fail, not to report"). The two baseline-data
  feature methods (baseline jars exist, baseline carries the SPI surface) stay — they
  verify repository data the TestKit suite cannot see.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `build-conventions`: adds a requirement that the api-compatibility gate's arming is
  verified functionally — by executing the gate in an isolated TestKit build — rather
  than by matching the text of the convention script.

## Goals

- G1: the gate's bite is proven by an automated test on every `check` of `build-logic`,
  not by a one-off manual verification recorded in a javadoc comment.
- G2: disarming regressions (skipped task, dropped `check` dependency, report-only flags,
  empty baseline handling) are caught by tests that survive refactoring of the build script.
- G3: `published-api-conventions.gradle` returns within the project file-size target by
  extracting the gate into a single-purpose convention plugin.

## Non-Goals

- NG1: no change to the gate's behavior, flags, baseline workflow
  (`updateApiCompatibilityBaseline`), or the committed baseline itself.
- NG2: no TestKit coverage for the other convention plugins (`java-conventions`,
  `pitest-conventions`, ...) — this change establishes the suite and covers the gate only.
- NG3: no publication of the api artifact and no switch of the baseline source from
  committed jars to a repository (`-PapiBaselineVersion` stays as-is, untested territory
  until something is actually published).
- NG4: no mutation testing (PIT) of `build-logic` — its sources are Groovy DSL scripts,
  outside the Java-only mutation policy.

## Users & Scenarios

- U1: a maintainer refactors `build-logic` (renames a task, extracts a helper, reorders
  configuration). Today the textual spec fails on harmless edits and passes on harmful
  ones; after this change, `:build-logic:check` fails exactly when the gate stops biting.
- U2: a reviewer of a build-logic PR wants evidence the gate still bites without manually
  deleting `CheckClientFactory.provider()` and running the build; CI runs the functional
  suite for them.

## Requirements

### Functional

- FR1: `build-logic` SHALL have a `functionalTest` suite (Gradle TestKit + Spock) that
  executes the gate convention in an isolated temporary project with its own baseline.
- FR2: the suite SHALL prove the bite: after a binary-incompatible change to the mini
  project's public api relative to its baseline, the gate task fails the build and the
  failure output names a binary incompatibility.
- FR3: the suite SHALL prove compatible additions pass: adding a new public method
  relative to the baseline does not fail the gate (an addition is a MINOR bump, not a
  re-baseline event).
- FR4: the suite SHALL prove the arming precondition: with an empty baseline directory the
  gate fails with the "cannot be armed" error rather than silently passing.
- FR5: the suite SHALL prove participation in `check`: running `check` on the mini project
  executes the gate task with outcome SUCCESS — not SKIPPED, not absent — so an
  `onlyIf { false }` or a dropped `dependsOn` fails the test.
- FR6: the suite SHALL prove the baseline workflow: `updateApiCompatibilityBaseline`
  regenerates the baseline from the current surface, after which the previously failing
  gate passes.
- FR7: the gate configuration SHALL move to a dedicated convention plugin applied by
  `published-api-conventions`, so the TestKit mini project applies only the gate and its
  minimal prerequisites — not the full `library-conventions` chain (Spotless, Error Prone,
  PIT, toolchain).
- FR8: the text-matching feature method of `ApiCompatibilityGateSpec` SHALL be removed;
  the spec keeps the two baseline-data checks and MAY keep a single assertion that
  `:gnomish-plugin-api` applies the published-api convention (data of the repo, not
  behavior of the gate).
- FR9: `functionalTest` SHALL run as part of `:build-logic:check` and therefore in CI's
  root `check`.

### Non-Functional

- NFR-P1: the functional suite SHALL keep setup shared (one compiled fixture and baseline
  reused across feature methods where possible) and add no more than ~2 minutes to a cold
  root `check`; TestKit builds are whole-Gradle invocations and must not multiply.
- NFR-R1: the suite SHALL be hermetic: the mini project resolves plugins and japicmp from
  the TestKit plugin classpath and declares no remote repositories, so tests pass offline
  and cannot flake on network resolution.
- NFR-O1: on gate failure inside the mini build, the test failure message SHALL surface
  the underlying japicmp output (the named incompatible member), so a broken run is
  diagnosable from the CI log alone.

(Security and Cost NFRs considered: no credentials, sandboxes, or token spend are
involved — none apply.)

## Operator Experience Criteria

- UX1: a maintainer who disarms the gate by accident sees a failing
  `:build-logic:functionalTest` naming the disarmed aspect (test method name states the
  behavior, e.g. "an incompatible change fails the build"), instead of a green build.
- UX2: a maintainer refactoring build-logic wording/structure without behavior change sees
  green — no test asserts the script's text anymore.

## Success Metrics

- M1: reverting the arming (setting `failOnModification = false`, adding
  `onlyIf { false }` to the gate task, or removing the `check` wiring) makes
  `:build-logic:functionalTest` fail — verified once for each of the three mutations
  during implementation.
- M2: `grep` finds no assertion on the text of `*.gradle` files in
  `ApiCompatibilityGateSpec` after the change.
- M3: `published-api-conventions.gradle` and the extracted gate convention are each within
  the project file-size target (≤120 lines).

## Open Questions

- Q1: should the mini project's fixture sources live as committed resources or be written
  inline by the spec? (Leaning inline — the fixture is a handful of lines and versioning
  it separately adds indirection; decide in design.)

## Impact

- `build-logic/build.gradle`: new `functionalTest` suite wiring, `gradleTestKit()` and
  Spock dependencies for it.
- `build-logic/src/main/groovy/published-api-conventions.gradle`: gate block moves out;
  applies the new convention instead.
- `build-logic/src/main/groovy/<gate convention>.gradle`: new file (name decided in
  design).
- `build-logic/src/functionalTest/groovy/...`: new Spock spec(s).
- `bootstrap/src/test/groovy/.../ApiCompatibilityGateSpec.groovy`: textual feature method
  removed, javadoc updated to point at the functional suite.
- No production module behavior changes; `:gnomish-plugin-api` build result is identical.
