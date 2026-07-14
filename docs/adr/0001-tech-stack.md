# ADR 0001: Tech Stack

Status: accepted (2026-07-14)

## Context

Gnomish Factory is a stateless orchestrator: it polls task trackers, calls AI provider APIs, launches coding-agent CLIs as subprocesses, runs verification scripts, and performs git operations. There is no inbound HTTP server and no database — all state lives in the tracker and in per-task git branches. The development process relies on BDD, mutation testing, and strict traceability.

## Decision

| Concern                   | Choice                                                                                                                                                                     |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Language / runtime        | Java 25 (current LTS); virtual threads for one-thread-per-task concurrency; no preview features                                                                            |
| Build                     | Gradle 9.x (latest), Groovy DSL                                                                                                                                            |
| Framework                 | Spring Boot, minimal: `spring-boot-starter` only (DI, `@ConfigurationProperties`, lifecycle). No web/webflux, no actuator, no data starters                                |
| HTTP client               | `java.net.http.HttpClient` — async (`sendAsync` / `CompletableFuture`), HTTP/2, Reactive-Streams-based bodies (`java.util.concurrent.Flow`)                                |
| Resilience                | Resilience4j (standalone): retries, timeouts, rate limiting, circuit breakers for tracker/AI calls                                                                         |
| Serialization / config    | Jackson + `jackson-dataformat-yaml` (stage manifests, factory config binding)                                                                                              |
| Logging                   | SLF4J + Logback (Spring Boot default)                                                                                                                                      |
| Subprocesses              | `ProcessBuilder` for agent CLIs; `git` invoked as a subprocess (no JGit)                                                                                                   |
| Testing                   | Spock 2 (BDD/TDD, built-in mocks) + `spock-spring`; WireMock for tracker/AI API contract tests; local bare git repos for git-workflow tests                                |
| Coverage / mutation       | JaCoCo + PIT with `pitest-junit5-plugin` (Spock 2 runs on JUnit Platform); mutate Java production code only — never Groovy test bytecode                                   |
| Integration / E2E (later) | Testcontainers + `testcontainers-spock`: Gitea container as a real git remote (HTTP auth), sandbox containers for real agent-CLI E2E runs. Docker is a dev/CI prerequisite |
| DI style                  | Spring container; no manual wiring, no Dagger                                                                                                                              |

## Consequences

Positive:
- Virtual threads give a simple blocking one-thread-per-task model with async-level scalability — no reactive framework complexity.
- Zero-dependency HTTP client that is still async and Reactive-Streams-based.
- Spock provides BDD, mocking, and data-driven tables in one tool; the mutation-testing process (PIT) carries over via the JUnit Platform.
- Minimal Spring keeps startup light while avoiding hand-rolled DI and config parsing.

Negative:
- JVM 25+ required on any machine running the factory.
- Two languages in the repo (Java production code, Groovy tests).
- PIT must be scoped to Java classes; misconfiguration produces noisy mutants from Groovy bytecode.
- Docker required for the integration/E2E test layer.

## Alternatives Considered

**TypeScript (Node)**: fastest iteration and the reference Anthropic SDK, but heavy stages run agent CLIs as subprocesses, which neutralizes the Agent-SDK advantage; weaker fit for this team's goals.

**Go**: best distribution (single binary) and concurrency, but a noticeably weaker BDD/mutation-testing ecosystem, which the development process depends on.

**Full reactive stack (WebFlux / Vert.x / RxJava)**: the workload is tens of concurrent operations (polling, API calls, subprocess waits), not tens of thousands; reactive chains would contaminate the codebase and hurt debuggability for no scalability need. Virtual threads + `CompletableFuture` cover the async requirements.

**No framework (manual DI)** and **Dagger 2**: viable for three port families, but rejected in favor of a minimal Spring container to avoid hand-rolled wiring and config binding.

**JGit**: full git implementation in-process, but shelling out to `git` is simpler, matches how agent CLIs operate on the working copy, and avoids a heavy dependency.

**MockServer (instead of WireMock)**: comparable stubbing features and a strong proxy/verification mode, but active development effectively stalled around 2023. WireMock is actively maintained and wins on stateful Scenarios (modeling tracker ticket lifecycles), fault injection (exercising Resilience4j), record-and-playback against real APIs, and out-of-the-box JUnit Platform / Testcontainers integration.
