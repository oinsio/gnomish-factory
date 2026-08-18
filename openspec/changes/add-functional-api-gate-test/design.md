# Design: add-functional-api-gate-test

## Context

`ApiCompatibilityGateSpec` verifies the japicmp gate by matching substrings of
`published-api-conventions.gradle` — form, not behavior (FR1–FR6, FR8 of this
change; the gate itself is FR14/M5 of `add-plugin-architecture`). The gate's
bite was proven once, manually. Gradle's own guidance for verifying that build
logic behaves — as opposed to being configured a certain way — is functional
testing with TestKit's `GradleRunner`, run as a dedicated suite inside the
plugin-producing project. Several local choices follow.

## Decisions

**D1 — Extract the gate into `api-compatibility-gate-conventions.gradle`.**
The japicmp block (surface definition, committed-baseline wiring,
`apiBaselineVersion` override, `updateApiCompatibilityBaseline`,
`japicmpApiGate`, `check` wiring) moves to its own precompiled script plugin
whose only plugin prerequisite is `java-library` (it applies it directly, not
`library-conventions`); `published-api-conventions` applies it (FR7).
*Rationale:* the TestKit mini project must apply the gate without dragging the
full `library-conventions` chain — Spotless, Error Prone, PIT, the Java 25
toolchain — which would make each functional scenario slow and couple the gate
test to unrelated conventions (NFR-P1, NFR-R1). The extraction also returns
`published-api-conventions.gradle` (160 lines) under the file-size target
(G3, M3), and matches the existing one-concern-per-convention layout
(`pitest-gate-conventions` precedent).
*Alternative rejected:* testing `published-api-conventions` whole — realistic,
but every scenario would compile under the full static-analysis stack and the
gate test would fail on any unrelated convention change; isolation beats
end-to-end realism here because `:gnomish-plugin-api`'s own build already
exercises the full chain on every root `check`.

**D2 — A `functionalTest` JVM test suite in `build-logic`, Spock + TestKit.**
Declared via `testing.suites` with `gradleTestKit()` and the Groovy/Spock
dependencies, registered in `gradlePlugin.testSourceSets` so
`GradleRunner.withPluginClasspath()` injects the precompiled plugins, and wired
into `:build-logic:check` (FR1, FR9). Because `build-logic` is an included
build whose tasks the root never invokes on its own, the root `check` also
gains a `dependsOn` on the included build's `:check` — otherwise FR9's
"therefore in CI" clause would not hold. Spock, not JUnit, because the whole
project tests in Spock and data-driven feature methods fit the
scenario-per-arming-aspect shape.
*Rationale:* this is the layout Gradle's plugin-development plugin integrates
with out of the box — the classpath manifest generation only targets registered
test source sets.
*Alternative rejected:* placing the spec in `:bootstrap` next to
`ApiCompatibilityGateSpec` — bootstrap's test task would need the build-logic
plugin classpath manifest, which `groovy-gradle-plugin` does not produce for
other projects; the suite belongs to the project that owns the plugins.

**D3 — The mini project is written inline by the spec; the baseline is
generated, not committed (resolves Q1).** The fixture — `settings.gradle`,
`build.gradle` applying the gate convention, one public Java class — is a few
dozen lines written into a temp dir by the spec's setup. The baseline jars are
produced by running `updateApiCompatibilityBaseline` inside the fixture (which
also covers FR6), then the source is mutated per scenario: method removed
(FR2), method added (FR3), baseline dir emptied (FR4).
*Rationale:* committed fixture jars would be opaque binaries in the repo that
rot against the Java toolchain; generating them in-test keeps every input
readable in the spec and exercises the real re-arming workflow.
*Alternative rejected:* committed resource fixtures — indirection and binary
churn for no added confidence.

**D4 — One fixture, sequential scenarios, shared per-spec state.** A single
`@TempDir`-style fixture is built once per spec class (`setupSpec`), and
feature methods mutate the source/baseline and re-run the needed task, rather
than each scenario provisioning its own project (NFR-P1). TestKit runs reuse
the Gradle user home the runner provides by default; no per-scenario daemon
churn.
*Rationale:* each `GradleRunner` invocation is a whole Gradle build; five
isolated fixtures would dominate `check` wall-time for no isolation benefit —
the scenarios' mutations are deliberate and ordered.
*Trade-off:* accepted ordering coupling inside one spec class, documented in
the spec header.

**D5 — What remains textual, stays data-shaped.** `ApiCompatibilityGateSpec`
keeps its two baseline-data feature methods (jars exist; jars carry the SPI
surface) — they check repository *data* the TestKit fixture cannot see — and
gains a single line-level assertion that `gnomish-plugin-api/build.gradle`
applies `published-api-conventions` (FR8), in the style of
`ModuleBuildFileSpec`. The armed-to-fail textual method is deleted; its javadoc
paragraph about the manual task-7.3 verification is replaced by a pointer to
the functional suite.
*Rationale:* "module X applies convention Y" is a fact about a build file, best
checked as data; "convention Y bites" is behavior, now checked behaviorally.
*Alternative rejected:* deleting the spec entirely — the baseline-data checks
guard against a hollowed-out committed baseline, which the fixture-based suite
cannot detect.

**D6 — The mini project declares `mavenCentral()` and every fixture build runs
`--offline` against the outer build's Gradle user home.**
`JapicmpTask` resolves its worker's JAXB and Guava runtime at execution time
from the *consuming* project's repositories, from coordinates hardcoded in the
plugin (`resolveJaxb` / `resolveGuava`, 0.4.6) — no task property overrides
them, and TestKit's injected plugin classpath does not satisfy them. A fixture
with no repositories therefore fails the gate task on resolution, not on
compatibility (observed while applying task 3.2, which is why that task runs the
first fixture build `--offline`). So `build-logic` pre-resolves exactly those
coordinates in an unlocked `japicmpWorkerRuntime` configuration declared as an
input of `functionalTest`, and the runner passes `-g <outer gradle user home>`
plus `--offline`: the artifacts are in that cache before any scenario starts and
nothing is fetched while one runs (NFR-R1's outcome, not its original wording —
the requirement text was corrected).
*Alternative rejected:* materialising the worker runtime into a local
flat/metadata-less repository inside the fixture — Gradle drops transitives for
such repositories, so the worker classpath would silently lose modules and fail
in a way unrelated to the gate.
*Alternative rejected:* leaving the fixture online — the suite would then flake
on network resolution, which NFR-R1 exists to prevent.

## Risks / Trade-offs

- TestKit builds are slow (seconds each) → one shared fixture (D4), gate-only
  convention (D1), suite scoped to `:build-logic:check` which CI already runs;
  budget ~2 min cold (NFR-P1).
- The mini project must resolve nothing remotely or the suite flakes offline →
  fixture declares no repositories; japicmp and the plugins arrive via
  `withPluginClasspath()` (NFR-R1). Verified by running the suite with
  `--offline`.
- Sequential mutation of one fixture couples scenario order → order is explicit
  in one spec class with `@Stepwise`-style documentation; any scenario that
  needs pristine state re-runs `updateApiCompatibilityBaseline` first.
- Extraction could accidentally change `:gnomish-plugin-api` behavior → the
  existing baseline-data specs plus a full root `check` (gate runs against the
  real committed baseline) act as the regression net; the extraction moves the
  block verbatim.
