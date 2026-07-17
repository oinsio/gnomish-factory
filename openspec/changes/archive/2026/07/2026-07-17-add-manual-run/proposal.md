# Proposal: add-manual-run

## Why

The stage engine (add-stage-engine) runs only against test fakes; nothing has verified that its port shapes survive contact with real implementations. This change adds the first genuine adapters: a human plays the gnome through an interactive console dialog, making the whole QC cycle (verify, feedback, attempts, escalation, resume) observable live, while the already-written port-contract suites judge whether the ports were guessed right. It doubles as the pipeline author's tool: a dry-run of a project's `.gnomish/` without AI, tracker, or git.

## What Changes

- **ADDED** `manual-run`: single-task interactive CLI (`gnomish run`) — ad-hoc task, interactive executor/external/judge adapters, real `files_exist` and command check runners, in-process escalation → resume, exit codes, logging.
- **ADDED** `status-report`: the fixed StatusReport JSON contract (v1) — the cross-change anchor future `gnomish status` (git-workflow change) must reproduce.
- **MODIFIED** `stage-engine`: every recorded round gains a `startedAt` timestamp (stamped via the engine's Clock port) so the status contract's `attempts[].startedAt` is derivable from state alone; the capability spec's port enumeration is corrected to the nine `EnginePorts` members. The other model deltas this change relies on (`AttemptRecord.result`, cumulative totals) were folded into add-stage-engine before its archival.
- factory-bootstrap keeps its no-args behavior; pipeline-config schema is untouched.

## Capabilities

### New Capabilities
- `manual-run`: interactive single-task engine run against a human-prepared workspace
- `status-report`: versioned status JSON contract shared by this CLI and future consumers

### Modified Capabilities
- `stage-engine`: `AttemptRecord.startedAt` model delta; port-enumeration wording fix (add-stage-engine is archived, so the delta rides here)

## Goals

- **G1** — every engine port is exercised by a non-fake adapter (interactive or real) that passes its per-port contract suite (extracted in this change; the add-stage-engine fakes pass the same suites).
- **G2** — a pipeline author can dry-run their `.gnomish/` end to end (including from a chosen stage) with no AI, tracker, or git involvement.
- **G3** — the status JSON contract is fixed and machine-anchored so later changes cannot drift from it unnoticed.

## Non-Goals

- **NG1** — multi-task shell (start/tasks/attach); one process = one task = one dialog.
- **NG2** — scripted executor (gnome as shell script).
- **NG3** — persistence, git, cross-process resume (git-workflow change); persistence stays in-memory.
- **NG4** — tracker integration and AI adapters (api/agent-cli executors, ai-provider, real judge).
- **NG5** — sandboxing of command checks (commands run in the operator's own repo — accepted for manual mode).
- **NG6** — command-check timeout (config schema delta; required before the factory loop, noted in backlog).
- **NG7** — pipeline-config schema changes of any kind.

## Users & Scenarios

- **U1 — factory developer**: watches the QC cycle live, catches port-shape misfits within days of the engine landing.
- **U2 — pipeline author**: dry-runs `.gnomish/`, debugs stage N's verify block via `--from-stage` without replaying earlier stages' real command checks.
- **U3 — scripted harness (E2E, future changes)**: drives the dialog through piped stdin, asserts on exit codes, `--task-id`-stable log/JSON output, and the status-report contract.

## Requirements

### Functional

- **FR1** — `gnomish run` flags: `--project` (default: cwd; workspace root and `.gnomish/` location), exactly one of `--task` | `--task-file`, optional `--task-id` (validated charset), optional `--from-stage` (validated against the loaded definition). Validation order: arguments → pipeline load → run.
- **FR2** — ad-hoc task synthesis: generated id `manual-<yyyyMMdd-HHmmss>-<2 random chars>` unless `--task-id`; title = first non-empty line (markdown heading markers stripped), body = remainder; empty initial decisions; initial position = `--from-stage` or first stage.
- **FR3** — interactive `StageExecutor`: prints task goal, input artifacts, prior-attempt feedback, decisions, and the stage's control files; empty Enter → `Completed` (measured wall time, no token usage, empty trace), `ask` → question + options dialog → `DecisionNeeded`.
- **FR4** — interactive `ExternalCheckClient`: single poll prompt (`pass` / `fail` / `running`), findings dialog on `fail`; engine-side interval/timeout semantics untouched.
- **FR5** — interactive `JudgeVoter`: prints acceptance criteria file, asks one verdict per vote (majority voting stays in the engine), findings dialog on `fail`.
- **FR6** — real `files_exist` builtin runner: `files` param of literal workspace-relative paths; existence check; one finding per missing path; malformed params or paths escaping the workspace → `CannotVerify`.
- **FR7** — real command runner: `sh -c` in the workspace, inherited environment, merged stdout/stderr; exit 0 → `Pass`, 126/127 → `CannotVerify`, other non-zero → `Fail`; output tail (~200 lines / 10 KB) preserved for feedback.
- **FR8** — findings-JSON wire format: temp file path handed to the command as `GNOMISH_FINDINGS_FILE` (outside the workspace); `{"findings":[{message, location?, details?}]}`; exit-code verdict is primary — valid file replaces the synthetic finding, malformed file degrades to the synthetic finding with a warning, file on exit 0 is ignored with a warning.
- **FR9** — outcome loop: exhaustive handling of `TaskOutcome`; `Escalated` → typed report render → decision prompt (empty input = resume without decision) → reset `attemptsUsed`, append `Decision(author=operator)`, run again; `Paused` → confirmation prompt only (no reset, no decision); `Aborted` → cause + unpersisted-state summary on stderr, terminal; `PipelineMismatch` branch renders and exits as internal error (unreachable in-process).
- **FR10** — `status` / `status --json` accepted at any prompt (interception below the adapters, single input choke point); auto-summaries: one line per finished attempt, full report at the end; escalation render doubles as its summary.
- **FR11** — StatusReport: pure function `(TaskContext, TaskState, live activity) → StatusReport`; text and JSON rendered from the same model; JSON per the `status-report` capability contract.
- **FR12** — exit codes: 0 `Completed`; 1 internal error; 2 usage error; 3 pipeline load failure; 4 stdin exhausted mid-stage; 10 `Escalated`; 11 `Paused`; 12 `Aborted` (families split at 10: `>= 10` means the factory reached a legitimate outcome).
- **FR13** — EOF semantics: no TTY requirement (piped stdin is a first-class mode); EOF at a runner prompt = deliberate exit with the outcome's code; EOF inside an adapter mid-stage flows through the engine as an infrastructure failure, after which the runner skips the resume dialog and exits with code 4.
- **FR14** — interactive and real adapters pass the same port-contract spec suites as the add-stage-engine fakes.
- **FR15** — stage-engine model delta: the engine stamps `startedAt` on every recorded round from its Clock port, making `attempts[].startedAt` derivable from `TaskState` alone; the stage-engine spec's port enumeration is corrected to the nine `EnginePorts` members.

### Non-Functional — Reliability

- **NFR-R1** — exhausted input can never hang the process or re-enter an input-requiring dialog (no EOF → escalation → prompt → EOF loops).
- **NFR-R2** — a broken findings reporter never masks a red check: the exit-code verdict stands regardless of the findings file's state.

### Non-Functional — Observability

- **NFR-O1** — single rolling log file under `~/.gnomish/logs/` (daily/size roll, ~7 days history, total size cap) with `taskId`/`stage`/`attempt` MDC on every line; `stage`/`attempt` maintained by an event-listener adapter on the engine thread.
- **NFR-O2** — stdout belongs to the dialog: console log appender at WARN+, ERROR duplicated to stderr; engine event stream logged as structured INFO lines to the file.

### Non-Functional — Security

- **NFR-S1** — the runner process writes nothing inside the workspace (findings temp file and logs live outside); the workspace mutates only through the operator and the manifest's own commands.
- **NFR-S2** — logs are instance-local and never enter git (command output may carry environment secrets).

### Non-Functional — Cost

- **NFR-C1** — usage/token fields are optional throughout the status contract; human-executed runs report zero/absent usage without violating it.

## Operator Experience Criteria

- **UX1** — dialog language is English; invalid input re-prompts with the accepted answers; usage errors list valid values (e.g. known stage names).
- **UX2** — after every attempt the operator sees a one-line summary without asking; `status` works at any prompt, including resume and checkpoint dialogs.
- **UX3** — Ctrl-D is always a safe exit: farewell line, correct exit code, no stack trace.

## Success Metrics

- **M1** — reference E2E: a scripted stdin session drives a pipeline with all four check types through quality retry, decision escalation + resume, manual pause, and completion, asserting exit codes — as a real process with piped stdin.
- **M2** — every member of `EnginePorts` (seven behavioral ports plus the `Clock`/`Sleeper` environment ports) has at least one non-fake adapter passing its port-contract suite (G1 verified by test inventory).
- **M3** — `status-report-v1.reference.json` test: serialization of the reference StatusReport is byte-identical to the committed reference file.
- **M4** — PIT mutation score 100% on new Java production code (adapters, runner, render), justified exceptions only at integration boundaries.

## Open Questions

- **Q1** — when `ArtifactOutput` gains physical paths (deferred pipeline-config delta), should `--from-stage` pre-flight the input artifacts of the target stage? Out of scope here; noted so the flag's contract can grow.
