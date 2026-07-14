# Gnomish Factory

External orchestrator: AI agents ("gnomes") take tasks from a task tracker and complete them autonomously through a pipeline of verifiable stages; humans handle only escalations. See README.md for the architecture overview.

**Status: design phase — no code yet. Tech stack is chosen (see below and docs/adr/0001-tech-stack.md).**

## Tech stack (ADR 0001)

- Java 25 LTS (virtual threads), Gradle 9.x (Groovy DSL)
- Spring Boot minimal: `spring-boot-starter` only — DI, `@ConfigurationProperties`, Logback; no web/actuator/data starters
- `java.net.http.HttpClient` (async) + Resilience4j; Jackson (+ yaml); SLF4J + Logback
- `ProcessBuilder` for agent CLIs; `git` as a subprocess
- Tests: Spock 2 (+ spock-spring), WireMock, JaCoCo + PIT (Java-only mutation), Testcontainers for E2E — see `.claude/rules/testing.md`
- Static analysis: Error Prone + NullAway (JSpecify), unused-code checks as errors; dependency-analysis plugin; Spotless format gate
- Security scanning (CI only): CodeQL, OSV-Scanner (CVE gate), Gitleaks; ArchUnit planned with the first ports/adapters change

## Key decisions

- Factory instances are stateless; state = task tracker (coordination) + task git branch (artifacts, state file)
- Ports & adapters: tracker (GitHub/Jira), ai-provider, executor (`api` | `agent-cli`)
- Pipeline stages are declarative, defined in the target project repo under `.gnomish/`
- Stage contract = IDEF0/ICOM + Quality Control: Input / Output / Control / Mechanism / Verification, all machine-verifiable
- Escalation via tracker statuses; any instance can resume a returned task

## Conventions

- Documentation language: English (conversation with the user may be Russian)
- Mermaid diagrams wherever visuals help — no ASCII art, no images (.claude/rules/diagrams.md)
- **The AI agent NEVER commits.** No `git commit` — ever. After finishing work, recommend a commit message based on the diff since the last commit; the human commits (.claude/rules/process-invariants.md)

## Development Workflow (OpenSpec)

```
/opsx:propose <idea> → /opsx:apply → /opsx:archive
```

For complex features: `/opsx:explore` first, then `/opsx:propose`.

Active changes: `openspec/changes/`. Archived: `openspec/changes/archive/` (immutable). Stable specs: `openspec/specs/`.

## Process Rules (`.claude/rules/`)

| Rule file               | Scope                         | What it covers                                             |
|-------------------------|-------------------------------|------------------------------------------------------------|
| `traceability.md`       | global                        | Requirement IDs and traceability links in all artifacts    |
| `testing.md`            | global                        | Spock, TDD, JaCoCo + PIT, WireMock, Testcontainers usage   |
| `process-invariants.md` | global                        | Immutability, file size, module boundaries, change naming  |
| `diagrams.md`           | global                        | Mermaid diagram conventions                                |
| `stage-description.md`  | stage docs                    | IDEF0/ICOM + Quality Control format for pipeline stages    |
| `proposal-format.md`    | `openspec/**/proposal.md`     | Required sections and format for PRD                       |
| `delta-specs.md`        | `openspec/**/specs/**`        | Delta spec format with ADDED/MODIFIED/REMOVED              |
| `design-decisions.md`   | `openspec/**/design.md`       | When and how to write a local ADR                          |
| `archive-path.md`       | `openspec/changes/archive/**` | Year/month grouping for archived changes                   |
