# Gnomish Factory

An external orchestrator where AI agents — the gnomes — pick tasks from a task tracker and drive them through a development pipeline autonomously. Humans are exception handlers, not participants: they step in only when a task is blocked or the gnomes cannot choose between alternatives.

> **Status: design phase.** Requirements and architecture are being shaped through [OpenSpec](openspec); no code exists yet.

## How it works

Factory instances are **stateless**. Several independent instances can serve the same project concurrently — everything they need lives in two shared systems:

- the **task tracker** holds coordination state: task statuses, claims, escalation reports, human decisions;
- the **task's git branch** holds working state: stage artifacts and a state file (pipeline position, attempt counters).

```mermaid
flowchart TB
    subgraph shared["Shared state"]
        Tracker["Task tracker<br/>(statuses, claims, escalations)"]
        Git["Git branch per task<br/>(artifacts, state file)"]
    end

    F1["Factory instance 1"] <--> Tracker
    F2["Factory instance N"] <--> Tracker
    F1 <--> Git
    F2 <--> Git

    Human["Human"] -->|resolves blocked tasks| Tracker
```

The factory core is a generic engine built on **ports and adapters**:

| Port        | Purpose                                                    | Adapters                                                                                        |
|-------------|------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| Tracker     | claim tasks, update statuses, post reports                 | GitHub, Jira, ...                                                                               |
| AI provider | call models from different vendors with per-stage settings | Claude, OpenAI, Gemini, Ollama, ...                                                             |
| Executor    | perform a stage                                            | `api` (direct model call), `agent-cli` (coding agent as subprocess in an isolated working copy) |

## Pipeline stages

A task travels through a pipeline of stages. Stages are **declarative** and live in the target project's repository under `.gnomish/` — adding or splitting a stage is a configuration change, not a factory release:

```
.gnomish/
  config.yaml          # autonomy limits (attempts, budgets) with defaults
  pipeline.yaml        # stage order
  stages/<name>/
    stage.yaml         # manifest: inputs, outputs, executor, model, verify checks
    instructions.md    # prompts, rules, best practices
    acceptance-criteria/  # criteria for LLM-judge checks (when the stage uses them)
```

Every stage follows the IDEF0/ICOM model extended with a Quality Control loop (ISO 9001:2015 process approach) — and every element is machine-verifiable:

```mermaid
flowchart LR
    Control["Control<br/>instructions, rules,<br/>best practices"]
    Mechanism["Mechanism<br/>executor, model,<br/>workspace"]
    QC["Quality Control<br/>verify checks: declarative,<br/>command, external, LLM judge"]

    Input["Input<br/>artifacts from<br/>previous stages"] --> Stage["Stage<br/>(transforms Input into Output)"]
    Stage --> Output["Output<br/>verified artifacts<br/>in the task branch"]
    Control --> Stage
    Mechanism --> Stage
    Stage <--> QC
```

Stage verification is an ordered list of checks in the manifest — engine built-ins (file/schema checks), `command` (any executable, exit-code contract), `external` (asynchronous third-party verification polled with a timeout: CI checks on the task branch, SonarQube quality gate), and `judge` (LLM-as-judge grading against acceptance criteria, returning a structured verdict). Cheap deterministic checks run first; any failure fails the stage. A **quality failure** (a non-pass verdict) feeds the check's findings back into a re-run of the stage — the gnome gets told what to fix — until the attempt limit is reached, at which point the task escalates with the findings history of all attempts. An **infrastructure failure** (the check itself cannot produce a verdict) is retried at the check level without burning attempts. Every attempt, including failed ones, is committed to the task branch, so any instance can resume mid-retry.

Every stage also declares an **advancement mode**: `auto` (proceed to the next stage once verification passes) or `manual` — a debug checkpoint where the factory commits the stage artifacts, pauses the task via a tracker status, and resumes when a human returns the task to work (the same protocol as escalation, so any instance can pick it up).

Full artifacts (specs, code, test reports, state) stay in the git branch; the tracker receives short human-readable progress summaries with links.

## Escalation

The factory never waits for a human in-band. When a stage exhausts its attempt limit or hits an undecidable choice, it escalates and moves on to other tasks:

```mermaid
sequenceDiagram
    participant F as Factory (any instance)
    participant T as Tracker
    participant H as Human

    F->>T: status → Blocked + report:<br/>problem, options considered
    H->>T: decision as a comment
    H->>T: status → In Progress
    F->>T: polls, sees the task is workable
    F->>F: reads decision + state file,<br/>resumes from recorded stage
    Note over F: resuming instance may differ<br/>from the one that blocked
```

## Tech stack

Java 25 LTS on virtual threads, built with Gradle 9.x. Minimal Spring Boot (`spring-boot-starter` only) provides dependency injection, configuration binding, and Logback logging — no web server, no database. Tracker and AI provider calls go through the async `java.net.http.HttpClient` guarded by Resilience4j; agent CLIs and `git` run as subprocesses. Tests are written in Spock 2 with WireMock for API contracts, JaCoCo + PIT for coverage and mutation testing, and Testcontainers for the E2E layer. Full rationale: [docs/adr/0001-tech-stack.md](docs/adr/0001-tech-stack.md).

## Development process

The project itself is developed AI-first with [OpenSpec](openspec): `/opsx:propose → /opsx:apply → /opsx:archive`, with `/opsx:explore` for complex topics. Process rules — traceability, proposal format, stage description format, diagram conventions — live in [.claude/rules](.claude/rules).

Documentation language is English. Diagrams are Mermaid.
