# Tasks: adapt-build-load-to-hardware

## 1. Hardware detection and budget service (build-logic)

- [ ] 1.1 Add `HardwareSpec` `ValueSource` in
  `build-logic/src/main/groovy/com/github/oinsio/gnomish/build/` returning total RAM bytes and
  processor count via `com.sun.management.OperatingSystemMXBean` / `Runtime.availableProcessors()`
  (D3, NFR-R2)
- [ ] 1.2 Add `HeavyJvmBudget` `BuildService` beside `MutationEngineLock`: parameters carry the
  computed slot count and its inputs (RAM, cores, override source); the service logs the decision
  once when instantiated (D1, D6, NFR-O1, UX2)

## 2. Wiring in convention plugins

- [ ] 2.1 In `test-conventions`: define the formula constants next to `maxHeapSize = '3g'`
  (`TEST_HEAP_GB`, `DAEMON_GB`, `HEADROOM_GB`), compute
  `clamp(1, cores − 2, ⌊(ram − daemon − headroom) / testHeap⌋)`, honor the
  `gnomish.heavyJvmSlots` override property, register `HeavyJvmBudget` with that
  `maxParallelUsages`, and add `usesService` to every `Test` task (D1, D2, D5; FR1–FR4, NFR-R1)
- [ ] 2.2 In `pitest-conventions`: change `threads` to
  `Math.max(1, Runtime.runtime.availableProcessors() - 2)` and add `usesService(heavyJvmBudget)`
  to the `pitest` task alongside the existing `MutationEngineLock` usage (D4, FR5)
- [ ] 2.3 Remove `org.gradle.workers.max=6` and its tuning comment from `gradle.properties`;
  where the comment carried still-true knowledge (why test heap is 3g, what failures look like),
  move it to the constants' definition site in `test-conventions` (FR3, UX1)

## 3. Verification

- [ ] 3.1 Confirm `git grep org.gradle.workers.max` finds no committed occurrence and
  `./gradlew help` prints the budget log line with plausible values for this machine (M1, M3,
  budget ≥ 6 on the 36 GB / 14-core reference)
- [ ] 3.2 Confirm the override works: `./gradlew help -Pgnomish.heavyJvmSlots=2` logs the
  override as the budget source (FR4, UX2)
- [ ] 3.3 Confirm configuration-cache compatibility: two consecutive `./gradlew help` runs reuse
  the cached configuration with no `ValueSource`-related invalidation, and the budget line still
  appears on the cache-hit run (NFR-R2, D6)
- [ ] 3.4 Run a clean measured build: `./gradlew --stop && ./gradlew clean build` on the
  reference machine — green, with no minion deaths, `RUN_ERROR`s, worker-handshake timeouts, or
  WireMock connection failures in the output (M1, M2, G3); if minion memory deaths reappear,
  resolve proposal Q2 by widening `pitest`'s budget usage per D4's fallback
