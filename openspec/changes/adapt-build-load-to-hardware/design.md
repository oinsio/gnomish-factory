# Design: adapt-build-load-to-hardware

## Context

See proposal.md — Why. The build already has the pattern this change generalizes:
`MutationEngineLock` is a shared `BuildService` with `maxParallelUsages = 1` that serializes
`pitest` tasks. The constraint to encode now is a memory budget over concurrent heavy JVMs
(FR1/FR2): every `test` task forks a 3 GB JVM (`test-conventions`), the daemon holds 4 GB
(`gradle.properties` jvmargs), and PIT minions run with no `-Xmx` by deliberate decision
(documented in `pitest-conventions`). The build must stay configuration-cache safe
(`org.gradle.configuration-cache=true` is on) — NFR-R2.

## Goals / Non-Goals

**Goals:**
- One mechanism that throttles exactly the heavy-JVM tasks, sized from the host at build time
- Constants of the formula live next to the heaps they describe, not in a prose comment

**Non-Goals:**
- Per-task heap right-sizing, or accounting for Docker containers Testcontainers spawns
  outside the JVM (same exposure as today; the OS headroom allowance is the buffer)
- Changing the `MutationEngineLock` single-mutator model

## Decisions

**D1 — Throttle via a shared `BuildService` with computed `maxParallelUsages` (FR1, FR2).**
A `HeavyJvmBudget` build service, registered once from `test-conventions`, with every `Test`
task and each module's `pitest` task declaring `usesService(...)`. This is the same mechanism
already proven by `MutationEngineLock`, and it scopes the limit to exactly the tasks that fork
heavy JVMs — Gradle's scheduler keeps light tasks running in the freed workers (G2).
*Alternative rejected:* keeping `org.gradle.workers.max` (even computed) — it throttles the
whole graph including compilation and static analysis, and cannot be set from build logic
(`maxWorkerCount` is fixed before settings evaluation). *Alternative rejected:* `Test`
`maxParallelForks` — it limits forks within one task, not concurrency across modules' tasks.

**D2 — Formula and constants in one place (FR1, UX1).** The budget is
`clamp(1, cores − 2, ⌊(totalRamGb − DAEMON_GB − HEADROOM_GB) / TEST_HEAP_GB⌋)` with
`TEST_HEAP_GB = 3` defined beside `maxHeapSize = '3g'` in `test-conventions`, `DAEMON_GB = 4`
matching `org.gradle.jvmargs`, and `HEADROOM_GB = 4` covering OS, IDE, and unbounded PIT
minions. On the 36 GB / 14-core reference machine this yields `min(12, ⌊28/3⌋) = 9 ≥ 6`,
satisfying NFR-P1. The clamp floor of 1 implements NFR-R1.
*Alternative rejected:* keeping a measured constant with a comment — the very problem this
change removes.

**D3 — Hardware read through a `ValueSource` (NFR-R2).** A `HardwareSpec` `ValueSource` in
`build-logic` returns `{totalRamBytes, processors}` using
`com.sun.management.OperatingSystemMXBean.getTotalMemorySize()` and
`Runtime.availableProcessors()`. `ValueSource` is the configuration-cache-sanctioned way to
read external state: the cached configuration records the value and re-checks it, so moving
the repo to different hardware invalidates the cache instead of silently reusing a stale
budget. `com.sun.management` is non-standard but present on every JDK this project supports
(Temurin/HotSpot per ADR 0001).
*Alternative rejected:* parsing `/proc/meminfo` / `sysctl hw.memsize` — platform-specific
subprocesses for a value the JVM already exposes.

**D4 — `pitest` takes one budget slot; its `threads` derive from processors (FR5).**
`threads = max(1, availableProcessors − 2)` replaces `maxWorkerCount − 2`: under
`MutationEngineLock` the single mutating module is entitled to the machine, and with
`workers.max` gone `maxWorkerCount` no longer encodes anything about memory. The `pitest`
task also declares `usesService(heavyJvmBudget)` — a deliberate under-approximation (one slot
for a minion pool), accepted because mutation is already serialized and the `HEADROOM_GB`
allowance absorbs the minions; proposal Q2 stays open until a full clean build is measured.
*Alternative rejected:* having `pitest` drain the whole budget (mutual exclusion with all
`Test` tasks) — reintroduces the serialization cost the lock was designed to avoid, and the
measured failures were N×3g test JVMs, not tests-beside-minions.

**D5 — Override property `gnomish.heavyJvmSlots` (FR4).** Read via
`providers.gradleProperty('gnomish.heavyJvmSlots')`; when present its integer value replaces
the formula entirely. Works from `-P`, `~/.gradle/gradle.properties`, or
`ORG_GRADLE_PROJECT_gnomish_heavyJvmSlots` in CI — all standard Gradle property channels, no
new mechanism. This is also the containers answer for now (proposal Q1): runners whose
reported RAM is misleading pin the budget explicitly.
*Alternative rejected:* auto-detecting cgroup limits — worth doing only if a real runner
misreports; deferred behind Q1.

**D6 — Log the decision from the service, not at configuration time (NFR-O1, UX2).**
`HeavyJvmBudget` logs budget + inputs + override source when Gradle instantiates it (first
use, execution phase). Configuration-time logging would vanish on every configuration-cache
hit — precisely the runs where a stale override is easiest to forget.
*Alternative rejected:* logging in the convention script body — silent on cache hits.

## Risks / Trade-offs

- [Containerized runners may see host RAM, overcommitting the cgroup] → override property
  (D5); revisit cgroup detection under Q1 if observed on a real runner
- [Removing `workers.max` restores per-core parallelism for light tasks, raising baseline
  memory slightly] → light tasks run in-daemon or in small workers; `HEADROOM_GB` covers it,
  and the clean-build measurement in tasks gates the change
- [One slot under-counts a `pitest` minion pool (D4)] → `MutationEngineLock` keeps it to one
  pool machine-wide; if the measured clean build still shows minion memory deaths, resolve Q2
  by drain-the-budget instead
- [`ValueSource` re-read per build adds a trivial cost and its value participates in cache
  invalidation: RAM upgrades invalidate the config cache] → correct behavior, cost is one
  MXBean call

## Migration Plan

Single-commit build-logic change; no production code affected. Rollback = revert the commit
(the removed `workers.max=6` line is restorable verbatim from history). Verified by a clean
`./gradlew build` with stopped daemons on the reference machine (M1–M3).
