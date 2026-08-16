# Proposal: adapt-build-load-to-hardware

## Why

The committed `gradle.properties` pins `org.gradle.workers.max=6` — a value hand-measured on one
36 GB / 14-core machine after the module split made the default (one worker per core) fatal:
~42 GB of concurrent 3 GB test JVMs killed PIT minions, timed out worker handshakes, and broke
WireMock specs. The pinned constant fixes that machine but ships its hardware profile to every
other one: a 16 GB laptop still overcommits (5×3 GB + 4 GB daemon), a large CI runner is
throttled far below capacity, and the constant silently drifts when test heap or module count
changes. The real invariant is a memory budget, not a worker count — it should be computed from
the machine, not committed.

## What Changes

- **ADDED**: a hardware-derived concurrency budget for heavy JVM-forking tasks (`Test`, `pitest`),
  computed from the machine's total RAM and core count, enforced via a shared build service —
  replacing the global worker cap
- **ADDED**: a Gradle property override for the computed budget, for per-machine or CI tuning
  without touching the repository
- **MODIFIED**: PIT minion thread count derives from the machine's processor count instead of
  `maxWorkerCount` (under `MutationEngineLock` the mutating module owns the machine, so a reduced
  worker cap should not starve it)
- **REMOVED**: `org.gradle.workers.max=6` from the committed `gradle.properties`; light tasks
  (compile, Spotless, Error Prone) return to Gradle's default per-core parallelism

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `quality-gates`: adds a requirement that the build derives its heavy-JVM concurrency from the
  host hardware and stays green on a clean run without per-machine tuning of committed files

## Goals

- **G1**: a clean `./gradlew build` runs green with default settings on any machine meeting a
  documented RAM/core minimum — no per-machine edits of committed files
- **G2**: only memory-heavy forked JVMs are throttled; compilation, static analysis, and
  formatting keep full per-core parallelism
- **G3**: the mutation gate stays trustworthy under load — no minion deaths or `RUN_ERROR`s
  caused by resource oversubscription

## Non-Goals

- **NG1**: changing test JVM heap size (3 GB), PIT engine settings, or the `MutationEngineLock`
  single-mutator model — they stay as-is; this change only sizes how many such JVMs coexist
- **NG2**: remote/distributed test execution or CI runner sizing
- **NG3**: per-module heap tuning (all `test` tasks keep one shared heap convention)

## Users & Scenarios

- **U1** — developer on an arbitrary machine: clones the repo, runs `./gradlew build`, gets a
  green build sized to their RAM/cores without reading a tuning comment
- **U2** — CI runner: uses its full capacity instead of being capped at a laptop-derived constant;
  can pin the budget explicitly via the override property when the runner reports misleading
  hardware numbers
- **U3** — factory gnome executing `./gradlew check` inside a pipeline stage: the gate result
  reflects code quality, not host-dependent resource crashes

## Requirements

### Functional

- **FR1**: The build SHALL compute a heavy-JVM slot budget from host hardware as
  `clamp(1, cores − 2, ⌊(totalRam − daemonHeap − headroom) / testHeap⌋)`, with the constants
  (test heap, daemon heap, headroom) defined in one place next to where those heaps are configured
- **FR2**: All `Test` tasks and each module's `pitest` task SHALL be limited by that budget via a
  shared build service (`maxParallelUsages`); no other task types are constrained by it
- **FR3**: The committed `gradle.properties` SHALL NOT set `org.gradle.workers.max`; Gradle's
  default worker count applies to non-heavy tasks
- **FR4**: A Gradle property SHALL override the computed budget when set (project `-P`,
  `~/.gradle/gradle.properties`, or CI environment), taking precedence over the formula
- **FR5**: PIT's minion `threads` SHALL derive from the machine's available processor count
  (minus fixed headroom), not from `maxWorkerCount`

### Non-Functional — Reliability

- **NFR-R1**: On hardware where the formula yields zero or negative slots, the budget clamps to 1
  so the build degrades to serial heavy tasks instead of failing to configure
- **NFR-R2**: Hardware values are read through a configuration-cache-safe mechanism (`ValueSource`)
  so a cached configuration is reused or invalidated correctly when the machine changes

### Non-Functional — Performance

- **NFR-P1**: On the 36 GB / 14-core reference machine the computed budget is ≥ 6 (the previously
  pinned value), so clean-build wall time does not regress

### Non-Functional — Observability

- **NFR-O1**: The build logs the computed budget and its inputs (RAM, cores, override if any) once
  per build, so an operator can see why a given concurrency was chosen

## Operator Experience Criteria

- **UX1**: A fresh clone needs zero tuning to build green; the tuning comment in
  `gradle.properties` is replaced by the formula's documentation at its single definition site
- **UX2**: When the override property is set, the log line names it explicitly, so a stale
  override on a machine is discoverable rather than silently shadowing the formula

## Success Metrics

- **M1**: Clean `./gradlew build` (after `clean` + stopped daemons) passes on the reference
  machine with no `org.gradle.workers.max` anywhere in the repository (`git grep` finds none)
- **M2**: The same clean build shows no PIT minion deaths, `RUN_ERROR`s, or worker-handshake
  timeouts in the build scan/log
- **M3**: The budget log line (NFR-O1) appears in the build output with plausible values on the
  reference machine (budget ≥ 6)

## Open Questions

- **Q1**: Inside memory-limited containers (CI runners, factory sandboxes)
  `OperatingSystemMXBean.getTotalMemorySize()` may report host RAM, not the cgroup limit. Is the
  override property (FR4) an acceptable answer for containers, or should the formula read cgroup
  limits when present?
- **Q2**: `pitest` consumes one slot from the shared budget while its minions use several cores —
  is one slot enough of an approximation, given `MutationEngineLock` already serializes mutation,
  or should `pitest` runs also exclude concurrent `Test` tasks entirely?
