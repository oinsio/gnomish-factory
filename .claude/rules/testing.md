# Rule: testing

## Frameworks

- **Spock 2** (Groovy) for all unit and integration tests — BDD style (`given/when/then`), built-in mocks/stubs (no Mockito), data-driven tables for stage-verification matrices
- **`spock-spring`** when a Spring context is required
- **WireMock** (in-JVM) for tracker/AI API contract tests — no Docker needed
- **Local bare git repos** (`git init --bare` in a temp dir) for git-workflow tests (task branch, state file, resume by another instance)
- **Testcontainers + `testcontainers-spock`** only for the E2E layer: Gitea container as a real git remote with HTTP auth, sandbox containers for real agent-CLI runs. Docker is a dev/CI prerequisite

## TDD

Red-Green-Refactor: write a failing Spock spec first, make it pass, refactor. Every FR gets at least one spec referencing it (see `traceability.md`).

## Coverage and mutation testing

- **JaCoCo** for coverage reports (XML + HTML)
- **PIT** for mutation testing with `pitest-junit5-plugin` (Spock 2 runs on the JUnit Platform)
- PIT `targetClasses` MUST cover Java production code only — never Groovy test bytecode; mutating Groovy produces noisy false-positive survivors
- Mutation score target is **100%**; ≥95% is acceptable ONLY where behavior genuinely cannot be exercised by unit tests (integration boundaries, `main()` wiring) — each exception must be explicitly justified

## Rules

- Maximize automated verification in task plans — avoid manual testing steps
- One capability per spec file; descriptive method names in natural language (Spock convention)
- Contract tests for every port: each adapter must pass the same port-level spec suite
- Integration tests are the slowest — scope runs to what the change affects
