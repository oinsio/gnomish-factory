# Gnomish Factory

![Gnomish Factory](docs/assets/gnomish-factory.png)

An external orchestrator where AI agents — the gnomes — pick tasks from a task tracker and drive them through a development pipeline autonomously. Humans are exception handlers, not participants: they step in only when a task is blocked or the gnomes cannot choose between alternatives.

> **Status: walking skeleton.** Requirements and architecture are shaped through [OpenSpec](openspec); the build, quality gates, and a minimal bootable application exist (see [Building](#building)). The domain core is in place — `.gnomish/` pipeline-config loading and the stage engine (a pure, reentrant orchestrator of the QC loop, driven entirely through ports). Its first non-fake adapters have landed as an interactive CLI: [`gnomish run`](#running-a-task-manually) drives a single task through the whole quality-control cycle with a human playing the gnome — no AI, tracker, or git. The autonomous adapters (tracker, AI executors, git) are not built yet.

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
  config.yaml          # schemaVersion + default autonomy limit (attempt limit; budgets are a later change)
  pipeline.yaml        # stage order — an explicit list of stage names
  stages/<name>/
    stage.yaml         # manifest: purpose, inputs, outputs, executor (type + model + settings), verify checks, advancement
    instructions.md    # prompts, rules, best practices (referenced by the manifest)
    acceptance.md      # acceptance criteria for an LLM-judge check, referenced by path per check (when the stage uses them)
```

Every stage follows the IDEF0/ICOM model extended with a Quality Control loop (ISO 9001:2015 process approach) — and every element is machine-verifiable:

![IDEF0 diagram of the Stage process](docs/assets/idef0-stage-diagram.svg)

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

## Running a task manually

`gnomish run` is the first way to drive the engine for real. It executes **one task through one pipeline**, no tracker and no git involved. By default it is manifest-driven: real `agent-cli` and judge adapters run each stage, with no AI provider outside them (see [Manifest-driven run and `--interactive` overrides](#manifest-driven-run-and---interactive-overrides) below). Passing `--interactive` swaps in a human standing in for the gnome instead: you read each stage briefing and press Enter to complete it, answer the verify checks, and resolve escalations at the prompt. It doubles as the pipeline author's dry-run tool for a project's `.gnomish/`, and as the harness that proves the engine's port shapes survive contact with real adapters.

There is no launcher script yet; run it through the boot jar (or `bootRun`) and pass the task flags. With **no** run flag present the application keeps its plain boot-and-exit behavior.

```bash
# via the boot jar (./gradlew build produces it under build/libs/)
java -jar build/libs/*.jar --task="fix the flaky login spec" --project=/path/to/target-repo

# or straight from Gradle
./gradlew bootRun --args='--task="fix the flaky login spec" --project=/path/to/target-repo'
```

Flags use Spring's `--key=value` form (quote values with spaces):

| Flag                  | Required          | Meaning                                                                  |
|-----------------------|-------------------|--------------------------------------------------------------------------|
| `--project=<dir>`     | no (default: cwd) | workspace root **and** the `.gnomish/` pipeline location                 |
| `--task="<text>"`     | one of these two  | task description inline (first line → title, rest → body)                |
| `--task-file=<path>`  | one of these two  | task description read from a file                                        |
| `--task-id=<id>`      | no                | override the generated id (`[A-Za-z0-9_-]+`); makes logs and JSON stable |
| `--from-stage=<name>` | no                | start partway through the pipeline, skipping earlier stages' checks      |

At **any** prompt you can type `status` or `status --json` to print the live task report (the same StatusReport contract a future `gnomish status` will reproduce), and **Ctrl-D** is always a safe exit. After every attempt the operator gets a one-line summary; a full report prints at the end. The runner writes nothing inside the workspace — the findings temp file and the rolling log under `~/.gnomish/logs/` both live outside it.

The process exit code reports the outcome — anything `>= 10` means the engine reached a legitimate terminal state:

| Code | Meaning               | | Code | Meaning                                      |
|------|-----------------------|-|------|----------------------------------------------|
| 0    | completed             | | 4    | stdin exhausted mid-stage                    |
| 1    | internal error        | | 10   | escalated (attempts exhausted / undecidable) |
| 2    | usage error           | | 11   | paused at a manual checkpoint                |
| 3    | pipeline load failure | | 12   | aborted                                      |

### Manifest-driven run and `--interactive` overrides

By default `gnomish run` is **manifest-driven, not interactive**: it reads the target project's `.gnomish/` pipeline and wires each stage's real adapter straight from the manifest — an `agent-cli` stage executor gets the CLI executor (a real `claude -p` subprocess per round), and every `judge` verify check gets the CLI judge, regardless of the stage's own executor type. This is the normal, paid mode, and starting a real agent round requires **no confirmation gate** by design — that is the tool's purpose, and the operator is present. `api` stages aren't supported yet and are rejected at startup (exit 3, before any dialog), naming the offending stage.

`--interactive` overrides the wiring, entirely or per role:

| Flag                     | Effect                                                                                                            |
|--------------------------|-------------------------------------------------------------------------------------------------------------------|
| *(absent)*               | manifest-driven: real CLI executor + real CLI judge (default, paid)                                               |
| `--interactive`          | full add-manual-run behavior: human plays both executor and judge                                                 |
| `--interactive=executor` | human plays the executor; judge stays the real CLI judge — verdict calibration                                    |
| `--interactive=judge`    | human plays the judge; executor stays the real CLI agent — judge-prompt debugging without paying for agent rounds |

`--interactive` may be given only once. External checks are always interactive regardless of this flag.

### Manifest settings vs. installation properties

A stage's `executor.settings` (and a `judge` check's own `settings`) accept exactly four keys — `allowedTools`, `disallowedTools`, `maxTurns`, `roundTimeout` — validated at startup before any dialog; an unrecognized key or malformed value is a startup error naming the stage/check and the key. These are portable, repo-level settings that travel with the pipeline definition.

Installation-level configuration — things that are true of *this machine*, not the repo — lives in `factory.*` application properties instead, never the manifest:

| Property                            | Meaning                                                              |
|-------------------------------------|----------------------------------------------------------------------|
| `factory.agent-cli-binary`          | path or name of the agent CLI binary (default: `claude` on `PATH`)   |
| `factory.agent-cli-env-passthrough` | environment variable names passed through to the spawned CLI process |

### Ollama E2E prerequisite

`./gradlew ollamaE2eTest` runs a local E2E suite that points the real `claude` CLI at a locally running Ollama instance (native Anthropic-compatible API since Ollama v0.14, via `ANTHROPIC_BASE_URL`) and drives a trivial stage through `gnomish run` end to end. It's excluded from `check`/`test`/`build` and is a native dev-machine prerequisite, not a Testcontainers layer — dockerized Ollama has no Metal access on macOS and is too slow. Individual specs skip cleanly with a clear message when Ollama or `claude` isn't available, so it's safe to run without any setup.

## Tech stack

Java 25 LTS on virtual threads, built with Gradle 9.x. Minimal Spring Boot (`spring-boot-starter` only) provides dependency injection, configuration binding, and Logback logging — no web server, no database. Tracker and AI provider calls go through the async `java.net.http.HttpClient` guarded by Resilience4j; agent CLIs and `git` run as subprocesses. Tests are written in Spock 2 with WireMock for API contracts, JaCoCo + PIT for coverage and mutation testing, and Testcontainers for the E2E layer. Compile-time quality is enforced by Error Prone + NullAway (JSpecify nullness, unused-code checks as errors), the dependency-analysis plugin, and a Spotless format gate. CI additionally runs CodeQL, OSV-Scanner, and Gitleaks for security scanning. Full rationale: [docs/adr/0001-tech-stack.md](docs/adr/0001-tech-stack.md).

## Building

<!-- implements UX1 of add-project-skeleton -->

The only prerequisite is a JDK capable of running the Gradle wrapper. Gradle itself (9.6.1) comes from the wrapper, and the Java 25 toolchain is auto-provisioned by the foojay resolver on first build — no local JDK 25 installation is needed. Docker is **not** required yet; it becomes a prerequisite when the Testcontainers E2E layer arrives (see ADR 0001).

One command answers "is my change OK?":

```bash
./gradlew check
```

It compiles with Error Prone + NullAway, runs the Spock suite, generates JaCoCo coverage reports, enforces the PIT mutation gate (100%), verifies Spotless formatting, and runs the dependency-analysis `buildHealth` check. Reports land in `build/reports/jacoco/test/html/index.html` and `build/reports/pitest/index.html`. `./gradlew build` additionally produces the boot jar.

Formatting is applied automatically: a Claude Code hook formats files as the agent edits them, and a git pre-commit hook (installed into `.git/hooks/` by any `./gradlew check` run) formats staged files as a safety net. Manual fallback: `./gradlew spotlessApply`.

Dependency locking is active — after changing dependencies, run `./gradlew check --write-locks` and commit the updated lockfiles (they keep builds reproducible and feed OSV-Scanner).

CI (GitHub Actions) runs `check`, CodeQL, OSV-Scanner, and Gitleaks on every push and pull request once the GitHub remote exists. After creating the remote, enable **Secret scanning** and **Push protection** in the repository settings.

## Development process

The project itself is developed AI-first with [OpenSpec](openspec): `/opsx:propose → /opsx:apply → /opsx:archive`, with `/opsx:explore` for complex topics. Process rules — traceability, proposal format, stage description format, diagram conventions — live in [.claude/rules](.claude/rules).

Documentation language is English. Diagrams are Mermaid.
