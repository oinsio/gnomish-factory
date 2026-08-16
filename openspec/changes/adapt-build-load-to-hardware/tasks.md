# Tasks: adapt-build-load-to-hardware

## 1. Hardware detection and budget service (build-logic)

- [x] 1.1 Add `HardwareSpec` `ValueSource` in
  `build-logic/src/main/groovy/com/github/oinsio/gnomish/build/` returning total RAM bytes and
  processor count via `com.sun.management.OperatingSystemMXBean` / `Runtime.availableProcessors()`
  (D3, NFR-R2)
- [x] 1.2 Add `HeavyJvmBudget` `BuildService` beside `MutationEngineLock`: parameters carry the
  computed slot count and its inputs (RAM, cores, override source); the service logs the decision
  once when instantiated (D1, D6, NFR-O1, UX2)

## 2. Wiring in convention plugins

- [x] 2.1 In `test-conventions`: define the formula constants next to `maxHeapSize = '3g'`
  (`TEST_HEAP_GB`, `DAEMON_GB`, `HEADROOM_GB`), compute
  `clamp(1, cores − 2, ⌊(ram − daemon − headroom) / testHeap⌋)`, honor the
  `gnomish.heavyJvmSlots` override property, register `HeavyJvmBudget` with that
  `maxParallelUsages`, and add `usesService` to every `Test` task (D1, D2, D5; FR1–FR4, NFR-R1)
- [x] 2.2 In `pitest-conventions`: change `threads` to
  `Math.max(1, Runtime.runtime.availableProcessors() - 2)` and add `usesService(heavyJvmBudget)`
  to the `pitest` task alongside the existing `MutationEngineLock` usage (D4, FR5)
- [x] 2.3 Remove `org.gradle.workers.max=6` and its tuning comment from `gradle.properties`;
  where the comment carried still-true knowledge (why test heap is 3g, what failures look like),
  move it to the constants' definition site in `test-conventions` (FR3, UX1)

- [x] 2.4 Unblock committing the above: `.gitignore`'s `build/` matches a directory of that name at
  any depth, so it silently swallowed the `com.github.oinsio.gnomish.build` **source package** —
  `git ls-files` showed `MutationEngineLock`, `DockerDaemonLock` and `LayeringExtension` were never
  tracked, i.e. a fresh clone cannot even configure the build, and this change's own two new
  classes would have gone the same way. Added a negation for that one path (G1)

## 3. Verification

- [x] 3.1 Confirm `git grep org.gradle.workers.max` finds no committed *setting* and a heavy-JVM
  build prints the budget log line with plausible values for this machine (M1, M3, budget ≥ 6 on
  the 36 GB / 14-core reference). The command is `./gradlew :domain:test --rerun-tasks`, not
  `./gradlew help`: the service is instantiated on first use, and `help` runs no heavy task, so
  no budget line can exist there (D6). The only remaining occurrences of the property name are
  prose — the `gradle.properties` note documenting its deliberate absence and this change's own
  artifacts — so the grep is `git grep '^org\.gradle\.workers\.max'`
  *Result:* `Heavy-JVM budget: 9 concurrent test/mutation JVM(s) — detected 36 GB RAM, 14 cores;
  source: hardware formula min(cores-2, (ram-4-4)/3)`
- [x] 3.2 Confirm the override works: `./gradlew :domain:test --rerun-tasks
  -Pgnomish.heavyJvmSlots=2` logs the override as the budget source (FR4, UX2)
  *Result:* `... 2 concurrent test/mutation JVM(s) ... source: gnomish.heavyJvmSlots=2 (override)`
- [x] 3.3 Confirm configuration-cache compatibility: two consecutive runs reuse the cached
  configuration with no `ValueSource`-related invalidation, and the budget line still appears on
  the cache-hit run (NFR-R2, D6)
  *Result:* second run logs `Reusing configuration cache.` / `Configuration cache entry reused.`
  and still prints the budget line
- [x] 3.4 Run a clean measured build: `./gradlew --stop && ./gradlew clean build` on the
  reference machine — green, with no minion deaths, `RUN_ERROR`s, worker-handshake timeouts, or
  WireMock connection failures in the output (M1, M2, G3); if minion memory deaths reappear,
  resolve proposal Q2 by widening `pitest`'s budget usage per D4's fallback
  *Result:* green. Run with `--no-build-cache` added, since a cached `clean build` re-executes
  almost nothing and measures no load at all: 614 of 615 tasks executed, `BUILD SUCCESSFUL in
  14m 26s`, every module's PIT run all-KILLED with zero `RUN_ERROR`/`MEMORY_ERROR`/`TIMED_OUT`,
  no minion deaths, no handshake timeouts, no WireMock truncation. Q2 therefore stays open in
  favour of one slot per `pitest` — the fallback was not needed.
  *Observed but unrelated:* two earlier runs each failed one different spec on a Spock `@TempDir`
  `NoSuchFileException` raised inside `TempDirInterceptor.deleteTempDir`, i.e. the temp directory
  had already vanished at cleanup. Not a resource-oversubscription signature (no OOM, no minion
  or worker death), each spec passes on re-run, and no code in the tree wipes the temp root —
  flaky, and worth its own change if it recurs
