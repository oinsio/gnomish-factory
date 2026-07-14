# Design: add-project-skeleton

## Context

First code in the repository. Driven by FR1–FR7 of the proposal: a Gradle build on Java 25 with Spock/JaCoCo/PIT gates and a walking-skeleton Spring application. The stack is fixed by ADR 0001; this design settles skeleton-local choices only.

## Goals / Non-Goals

**Goals:** prove the toolchain end to end (compile → Spock → JaCoCo → PIT → CI) with the smallest real production slice.

**Non-Goals:** any factory domain logic beyond configuration binding; multi-module layout; Testcontainers.

## Decisions

**D1 — Single Gradle module, hexagonal packages.** One module `gnomish-factory`; architecture boundaries live at package level under the base package `com.github.oinsio.gnomish` (`domain`, `application`, `adapter` packages arrive with later changes). Splitting into Gradle subprojects is deferred until a second deployable exists. *Alternative rejected:* multi-module now — ceremony without payoff, slows every build.

**D2 — Version catalog as the single version source.** `gradle/libs.versions.toml` declares all dependency and plugin versions (satisfies NFR-R1); build scripts reference catalog aliases only. *Alternative rejected:* versions inline in `build.gradle` — scatters the single source of truth.

**D3 — Spring Boot Gradle plugin + BOM.** Apply `org.springframework.boot`; dependency versions for the Spring family come from its BOM, everything else from the catalog. `bootJar` is the distribution format. *Alternative rejected:* plain `java` plugin with manual BOM import — more wiring for the same result.

**D4 — Config binding via a Java record with compact-constructor validation.** `FactoryProperties` is an immutable record bound with `@ConfigurationProperties` (constructor binding). Validation is plain Java in the compact constructor (throws on blank/invalid values) — no `spring-boot-starter-validation` dependency, and the validation logic is ordinary code that PIT can mutate and Spock can spec directly. First real property: `factory.instance-id` (needed later for task claiming). *Alternative rejected:* Jakarta Bean Validation annotations — extra starter, reflection-driven, weaker mutation-test signal.

**D5 — PIT scoped to Java, `main()` excluded.** `targetClasses = com.github.oinsio.gnomish.*`, JUnit 5 plugin (Spock 2 runs on the JUnit Platform). The application bootstrap class (`main()` + `SpringApplication.run`) is the one documented exclusion (per FR6: unreachable by unit tests) — excluded from PIT and JaCoCo class rules alike. Threshold: `mutationThreshold = 100`.

**D6 — CI on GitHub Actions.** One workflow: checkout → setup JDK 25 (Temurin) → Gradle build action → `./gradlew check` → upload JaCoCo + PIT reports as artifacts (always, even on failure). GitHub hosting confirmed (proposal Q1 resolved).

**D7 — Consistency and supply-chain hygiene.** `.editorconfig` + Spotless (palantir-java-format for Java — deterministic and non-configurable, so style is never a decision; Groovy specs formatted too) wired into `check`. Formatting is auto-applied in three layers: a Claude Code PostToolUse hook in `.claude/settings.json` formats each edited file via `spotlessApply -PspotlessIdeHook=<file>` (single-file, fast with the daemon); a git pre-commit hook (installed by a Gradle task on first build) runs `spotlessApply` as a safety net; CI only checks — no auto-commit bots (they break commit traceability). *Alternative rejected:* Checkstyle-style "check and complain" — forces attention to style instead of removing it. The `foojay-resolver-convention` settings plugin auto-provisions the pinned JDK 25 toolchain on machines that lack it. `gradle.properties` enables configuration cache, build cache, and parallel execution (configuration cache is Gradle 9's preferred mode). CI is hardened: least-privilege `permissions`, `concurrency` with cancellation of stale runs, Gradle wrapper validation. Dependabot watches Gradle dependencies and GitHub Actions versions.

**D8 — Static analysis: Error Prone + NullAway, dependency analysis.** Error Prone via the `net.ltgt.errorprone` Gradle plugin; NullAway configured with JSpecify annotations, `AnnotatedPackages = com.github.oinsio.gnomish`, severity ERROR. Dead-code detection is layered: Error Prone's `UnusedMethod`/`UnusedVariable` promoted to ERROR (reliable at private scope), the dependency-analysis plugin (`com.autonomousapps.dependency-analysis`) fails on unused/misdeclared dependencies, and the 100% mutation gate structurally kills unreachable-by-test code. *Alternatives rejected:* PMD unused-code rules — redundant with Error Prone's checks; whole-program public dead-code detectors (UCDetector, DCD) — unmaintained and unreliable under reflection/DI.

**D9 — Security scanning at CI level.** Three non-overlapping scanners as GitHub workflows (not part of local `check`, so the local loop stays fast): CodeQL for SAST, OSV-Scanner as a failing gate on known-vulnerable dependency versions, Gitleaks for committed secrets (the enforcement behind NFR-S1); GitHub push protection enabled in repo settings. *Alternatives rejected:* OWASP Dependency-Check — same job as OSV-Scanner but slow and NVD-API-key-bound; SpotBugs + FindSecBugs — overlaps Error Prone and CodeQL; SonarCloud — duplicates existing gates. *Deferred:* ArchUnit — architecture-boundary tests belong in the first change that introduces ports/adapters packages; nothing to constrain in a single-package skeleton.

**D10 — Startup test strategy.** FR2 ("boots and exits 0") is specified at context level: a `spock-spring` specification boots the real context (no web) and asserts the properties bean; process-level exit-code testing is deliberately out of scope for unit gates.

## Risks / Trade-offs

- [PIT vs Java 25 bytecode] → pin the latest pitest + gradle-pitest-plugin versions; task 1 verifies `check` passes before any production code is added
- [gradle-pitest-plugin vs configuration cache] → enable the cache globally; if the pitest task is incompatible, scope the exception to that task (documented in the build script) instead of disabling the cache build-wide
- [Error Prone / NullAway vs JDK 25 javac internals] → pin the latest Error Prone + NullAway versions and required `--add-exports` JVM args from the plugin docs; verified in task 2.5 before production code depends on it
- ~~[CodeQL is free only for public repositories]~~ resolved: the repository will be public
- [Gradle 9 / Groovy 4 / Spock 2 compatibility matrix] → use Spock `2.4-M6+` (Groovy 4 variant) aligned with the Gradle-embedded Groovy; verified in task 1
- [100% mutation score pressure on trivial code] → keep the skeleton slice small and meaningful (validation logic), so the threshold is honest rather than gamed
- [CI unverifiable until a GitHub remote exists] → workflow file is committed and validated locally with `act` or by inspection; M3 completes on first push

## Migration Plan

New files only; no rollback concerns. If the toolchain matrix fails on Java 25 (PIT), the documented fallback is pinning the exact last-known-good pitest version — not downgrading Java.

## Open Questions

None — proposal Q1 (GitHub hosting) is resolved: the remote will be GitHub.
