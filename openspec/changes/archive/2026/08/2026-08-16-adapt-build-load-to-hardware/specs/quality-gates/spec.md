# quality-gates — delta for adapt-build-load-to-hardware

## ADDED Requirements

### Requirement: Hardware-derived heavy-JVM budget
The build SHALL limit how many memory-heavy forked JVMs (test JVMs and the mutation engine)
run concurrently to a budget computed from the host's total RAM and processor count, reserving
fixed allowances for the build daemon and OS headroom. On hardware where the formula yields
less than one slot, the budget SHALL clamp to 1 so heavy tasks degrade to serial execution
instead of failing. Tasks that do not fork heavy JVMs (compilation, static analysis,
formatting) SHALL NOT be constrained by this budget.
<!-- implements FR1, FR2, NFR-R1 of adapt-build-load-to-hardware -->

#### Scenario: Concurrency scales with the machine
- **WHEN** a clean `./gradlew build` runs on a machine whose RAM fits N test JVMs beside the
  daemon and headroom
- **THEN** at most N heavy JVMs execute concurrently
- **AND** the build completes green with no resource-induced failures (minion deaths,
  worker-handshake timeouts, truncated HTTP-stub connections)

#### Scenario: Small machine degrades to serial heavy tasks
- **WHEN** the build runs on a machine where the formula yields zero or negative slots
- **THEN** heavy JVMs run one at a time
- **AND** the build still configures and executes

#### Scenario: Light tasks keep full parallelism
- **WHEN** the heavy-JVM budget is saturated
- **THEN** compilation, static-analysis, and formatting tasks continue to run in parallel at
  Gradle's default worker count

### Requirement: No committed per-machine worker cap
The repository SHALL NOT commit a machine-specific global worker limit
(`org.gradle.workers.max`); a fresh clone SHALL build green with default Gradle settings on any
machine meeting the documented hardware minimum, with no edits to committed files.
<!-- implements FR3 of adapt-build-load-to-hardware -->

#### Scenario: Fresh clone needs no tuning
- **WHEN** a developer clones the repository onto a machine meeting the documented minimum and
  runs `./gradlew build`
- **THEN** the build passes without modifying any committed configuration
- **AND** `git grep org.gradle.workers.max` finds no committed occurrence

### Requirement: Heavy-JVM budget override
The build SHALL accept a Gradle property that overrides the computed heavy-JVM budget; when
set (via command line, user-level Gradle properties, or CI environment) it SHALL take
precedence over the hardware formula.
<!-- implements FR4 of adapt-build-load-to-hardware -->

#### Scenario: Override takes precedence
- **WHEN** the build runs with the override property set to K
- **THEN** at most K heavy JVMs execute concurrently regardless of detected hardware

### Requirement: Budget decision is logged
The build SHALL log, once per build, the effective heavy-JVM budget together with its inputs —
detected RAM, detected processor count, and the override property when one is set — so an
operator can see why a given concurrency was chosen and detect a stale override.
<!-- implements NFR-O1, UX2 of adapt-build-load-to-hardware -->

#### Scenario: Computed budget is visible
- **WHEN** the build runs without the override property
- **THEN** the log names the computed budget, the detected RAM, and the detected core count

#### Scenario: Override is discoverable
- **WHEN** the build runs with the override property set
- **THEN** the log names the override property and its value as the source of the budget
