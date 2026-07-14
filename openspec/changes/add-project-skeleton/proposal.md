# add-project-skeleton

## Why

The design phase is complete (architecture, stage contract, tech stack — ADR 0001), but no code exists. Every future change depends on a build that enforces the project's BDD/TDD discipline from day one: Spock specs, coverage, and mutation thresholds must be gates, not aspirations. The skeleton is a walking slice proving the whole toolchain works end to end.

## What Changes

- ADDED: Gradle 9.x build (Groovy DSL, wrapper) compiling Java 25 production code and Groovy Spock tests
- ADDED: minimal Spring Boot application (`spring-boot-starter` only) that boots, binds typed YAML config, logs via SLF4J/Logback, and exits cleanly
- ADDED: test infrastructure — Spock 2, `spock-spring`, JaCoCo, PIT (`pitest-junit5-plugin`, Java-only mutation) wired into `./gradlew check`
- ADDED: CI workflow (GitHub Actions) running build + tests + quality gates
- ADDED: repo hygiene — `.gitignore`, `.editorconfig`, `gradle.properties` (configuration/build cache, parallel), minimal `application.yaml`
- ADDED: code-format gate (Spotless) wired into `check`; JDK auto-provisioning via the foojay toolchain resolver
- ADDED: CI hardening (least-privilege permissions, concurrency cancellation, wrapper validation) and Dependabot for Gradle deps + Actions
- ADDED: compile-time static analysis — Error Prone + NullAway (JSpecify nullness), unused-code checks promoted to errors; dependency hygiene via the dependency-analysis plugin
- ADDED: security scanning in CI — CodeQL (SAST), OSV-Scanner (dependency CVEs as a gate), Gitleaks (secret scanning)

## Capabilities

### New Capabilities
- `factory-bootstrap`: the factory application starts, binds typed configuration from YAML, logs through SLF4J/Logback, and shuts down cleanly
- `quality-gates`: the build enforces the testing discipline — Spock suite, JaCoCo coverage report, PIT mutation thresholds — locally and in CI

### Modified Capabilities
_None (first change in the project)._

## Goals

- G1: a fresh clone with JDK 25 goes green on `./gradlew check` with zero manual setup
- G2: TDD loop is provably alive: at least one production class exists only because a Spock spec demanded it
- G3: quality gates fail the build when thresholds are violated (verified, not assumed)

## Non-Goals

- NG1: no ports/adapters (tracker, ai-provider, executor) — separate changes
- NG2: no pipeline engine, `.gnomish/` parsing, or stage execution
- NG3: no Testcontainers/E2E layer (per ADR 0001 it arrives with the first E2E need)
- NG4: no multi-module split — single Gradle module; module boundaries live at package level for now

## Users & Scenarios

- U1: a developer (or AI agent) clones the repo, runs `./gradlew check`, and gets a trustworthy verdict on their change
- U2: CI blocks a merge when tests, coverage, or mutation thresholds fail

## Requirements

### Functional
- FR1: Gradle wrapper builds the project on JDK 25 (toolchain-pinned); Groovy DSL
- FR2: the application boots a Spring context via `spring-boot-starter` (no web server) and exits with code 0
- FR3: configuration binds from `application.yaml` into an immutable typed properties object (`@ConfigurationProperties`), validated at startup
- FR4: logging goes through SLF4J with Logback backend; log level configurable without recompilation
- FR5: `./gradlew check` runs the Spock suite, JaCoCo report, and PIT mutation testing as one command
- FR6: PIT mutates Java production classes only (never Groovy test bytecode) and fails the build below thresholds (100% target; documented exceptions ≥95%)
- FR7: CI workflow runs `./gradlew check` on every push and pull request
- FR8: the build enforces consistent code formatting (Spotless check as part of `./gradlew check`)
- FR9: compilation fails on Error Prone bug patterns and NullAway null-safety violations; unused-code checks (`UnusedMethod`, `UnusedVariable`) are errors
- FR10: the build detects unused and misdeclared dependencies (dependency-analysis plugin) and fails on violations
- FR11: CI runs security scanning on push/PR — CodeQL for code vulnerabilities, OSV-Scanner failing on known-vulnerable dependency versions, Gitleaks failing on committed secrets

### Non-Functional
- NFR-R1 (reliability): the build is reproducible — Gradle version fixed by the wrapper, dependency versions pinned in one place
- NFR-O1 (observability): JaCoCo and PIT HTML/XML reports are published as CI artifacts
- NFR-S1 (security): no secrets in the repository; anything sensitive comes from environment variables
- NFR-S2 (security): the CI workflow runs with least-privilege token permissions; the Gradle wrapper is validated in CI
- NFR-R2 (reliability): dependency and workflow-action updates are proposed automatically (Dependabot)
- NFR-C1 (cost): CI run completes within 10 minutes on the default runner
- NFR-P1 (performance): clean local `./gradlew check` completes within 5 minutes on a developer machine

## Operator Experience Criteria

- UX1: one command (`./gradlew check`) answers "is my change OK?" — no multistep ritual
- UX2: a failed quality gate names the violated threshold and points to the HTML report
- UX3: formatting never requires human or AI attention — it is auto-applied on every agent edit (Claude Code hook) and on commit (pre-commit hook); the CI gate is a backstop, not the workflow

## Success Metrics

- M1: fresh-clone `./gradlew check` passes on JDK 25 (verified on at least one machine other than the author's environment)
- M2: PIT mutation score on skeleton production code = 100%
- M3: CI pipeline green on the change's final commit; red when a deliberately broken mutant-surviving commit is pushed (gate proof, may be verified locally)

## Open Questions

- Q1: ~~CI assumes GitHub hosting (GitHub Actions). Confirm the remote will be GitHub; otherwise the workflow file is a template to port.~~ **Resolved (2026-07-14): the remote will be GitHub — GitHub Actions confirmed.**

## Impact

- New files only: Gradle build scripts, wrapper, `src/main/java`, `src/test/groovy`, `.github/workflows/`, config files
- No existing code affected (there is none); documentation (README) gains build instructions
