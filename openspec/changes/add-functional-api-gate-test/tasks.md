# Tasks: add-functional-api-gate-test

## 1. Extract the gate convention (FR7, D1)

- [x] 1.1 Create `build-logic/src/main/groovy/api-compatibility-gate-conventions.gradle`: move the japicmp block from `published-api-conventions.gradle` verbatim — api surface definition, committed-baseline wiring, `apiBaselineVersion` override configuration, `updateApiCompatibilityBaseline`, `japicmpApiGate`, `check` wiring — with `plugins { id 'java-library'; id 'me.champeau.gradle.japicmp' }` as its only prerequisites
- [x] 1.2 Slim `published-api-conventions.gradle` to publication + semver verification, applying `api-compatibility-gate-conventions` and dropping the now-redundant `me.champeau.gradle.japicmp` id from its `plugins` block; confirm both files are ≤120 lines (M3)
- [x] 1.3 Run root `check` — `:gnomish-plugin-api:japicmpApiGate` still passes against the real committed baseline; `updateApiCompatibilityBaseline` produces an identical baseline (no diff). Precondition: `close-plugin-api-compilability-gap`'s re-baseline is landed, else "no diff" cannot hold

## 2. functionalTest suite wiring (FR1, FR9, D2)

- [x] 2.1 In `build-logic/build.gradle`, register a `functionalTest` JVM test suite (Spock + `gradleTestKit()`), add it to `gradlePlugin.testSourceSets`, and wire it into `check`
- [x] 2.2 Refresh `build-logic` dependency lockfiles for the new suite's configurations; verify `:build-logic:functionalTest` runs (empty) and root `check` still passes

## 3. Functional gate spec (FR2–FR6, D3, D4)

- [x] 3.1 Spec scaffolding in `build-logic/src/functionalTest/groovy/`: `setupSpec` writes the inline mini project (settings, build applying `api-compatibility-gate-conventions`, one public Java class, no repositories) into a temp dir; header documents the deliberate scenario ordering (D4)
- [x] 3.2 FR6: run `updateApiCompatibilityBaseline` in the fixture — baseline jars appear; a following `japicmpApiGate` run passes. Run this first fixture build with `--offline` so a hermeticity failure (NFR-R1) surfaces before the remaining scenarios are written
- [x] 3.3 FR2: remove a public method from the fixture source — `buildAndFail()` on the gate; output names a binary incompatibility; surface the japicmp detail by reading the fixture's `build/reports/japicmp/api-compatibility.txt` and including it in the assertion message (NFR-O1)
- [x] 3.4 FR3: restore the method and add a new public method — gate passes without re-baselining
- [x] 3.5 FR4: empty the fixture's baseline dir — gate fails with the arming error (the "No API compatibility baseline ... the gate cannot run" message); re-arm via `updateApiCompatibilityBaseline` afterwards
- [x] 3.6 FR5: invalidate the gate's outputs (delete the fixture's `build/reports/japicmp`), then run `check` on the fixture — `japicmpApiGate` outcome is SUCCESS (not SKIPPED, not UP-TO-DATE, not absent)
- [x] 3.7 NFR-R1: run the suite with `--offline` — green; NFR-P1: record suite wall-time, confirm ≤ ~2 min cold

## 4. Retire the textual assertions (FR8, D5)

- [x] 4.1 In `ApiCompatibilityGateSpec`, delete the "armed to fail, not to report" feature method and `conventionFile()`; add a single data-shaped assertion that `gnomish-plugin-api/build.gradle` applies `published-api-conventions`
- [x] 4.2 Rewrite the spec's javadoc: manual task-7.3 verification paragraph replaced by a pointer to the functional suite, keeping the FR14/M5 traceability references of `add-plugin-architecture` intact; confirm M2 (`grep` finds no assertion on `*.gradle` text in the spec)

## 5. Verification (M1–M3)

- [x] 5.1 M1 mutation drill — each of the three disarmings makes `:build-logic:functionalTest` fail, then revert: (a) `failOnModification = false`, (b) `onlyIf { false }` on `japicmpApiGate`, (c) remove the gate's `check` wiring
- [x] 5.2 Full root `check` green; run `openspec validate --strict` on this change; recommend a commit message
