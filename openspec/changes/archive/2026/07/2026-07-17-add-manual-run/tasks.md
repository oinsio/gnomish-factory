# Tasks: add-manual-run

TDD throughout (`.claude/rules/testing.md`): each task pairs a failing Spock spec with the code that passes it; spec descriptions carry FR references. FR/NFR/UX/M are proposal.md ids; D-references are design.md. add-stage-engine is archived: its tasks 2.11/2.12 (`AttemptRecord.result`, cumulative `TaskState` totals) are verified in code, the `startedAt` delta rides this change (section 1), and the per-port contract suites it never shipped are extracted here (section 2).

## 1. Stage-engine delta (`domain.engine`)

- [x] 1.1 `AttemptRecord.startedAt`: engine stamps the Clock reading when a round begins and carries it on the record; engine specs updated (recording, resume, telemetry, reference run) — FR15, D11

## 2. Port-contract suites

- [x] 2.1 Extract abstract per-port contract suites (Spock) for the stage ports — `StageExecutor`, `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient`, `JudgeVoter` — from the add-stage-engine specs; the scripted fakes pass them unchanged — FR14, M2
- [x] 2.2 Contract suites for the cross-cutting ports — `EngineEventListener`, `AttemptPersistence`; the recording/in-memory fakes pass them unchanged — FR14, M2

## 3. Console and workspace foundation (`adapter.console`)

- [x] 3.1 `ConsoleIO`: readLine (EOF → `ConsoleClosedException`) + print; system implementation and scripted Spock fake — FR13, D1
- [x] 3.2 `DialogConsole`: meta-command interception (`status`, `status --json` → render, print, re-prompt), input-exhausted flag, unrecognized-answer re-prompt helper — FR10, FR13, UX1, D1
- [x] 3.3 Shared findings-entry dialog (one finding per line, empty line ends; maps to `Finding.message`) — FR4, FR5
- [x] 3.4 Directory-backed `Workspace` exposing the workspace root path (the engine's `Workspace` is a member-less marker; check runners and console adapters need the root; the runner creates it from `--project`) — FR1, FR6, FR7, D3

## 4. Real check runners (`adapter.check`)

- [x] 4.1 `files_exist` runner: literal workspace-relative paths, finding per missing path; malformed params / workspace escape → CannotVerify — FR6
- [x] 4.2 Command runner: `sh -c`, workspace cwd, inherited env, merged streams, bounded tail (~200 lines / 10 KB) — FR7, D6
- [x] 4.3 Verdict mapping: exit 0 Pass, 126/127 CannotVerify, other non-zero Fail with synthetic tail finding; sh start failure → CannotVerify — FR7, D6
- [x] 4.4 `GNOMISH_FINDINGS_FILE` lifecycle (temp path outside the workspace) + wire format `{"findings":[…]}`: valid file replaces synthetic finding, malformed degrades with warning, ignored-on-pass with warning — FR8, NFR-R2, NFR-S1, D6
- [x] 4.5 Both runners pass the extracted port-contract suites — FR14, M2

## 5. Interactive adapters (`adapter.console`)

- [x] 5.1 Stage briefing render: task goal, input artifacts, prior-attempt feedback, decisions, control files (read via the workspace root) — FR3
- [x] 5.2 Interactive `StageExecutor` prompt loop: empty Enter → Completed (measured wall time, no tokens, empty trace), `ask` → question/options dialog → DecisionNeeded — FR3
- [x] 5.3 Interactive `ExternalCheckClient`: pass/fail/running prompt, findings on fail — FR4
- [x] 5.4 Interactive `JudgeVoter`: prints acceptance criteria, one verdict per vote, findings on fail — FR5
- [x] 5.5 All three pass the port-contract suites through the scripted console; unproducible contract variants recorded as port-shape findings — FR14, M2

## 6. Status (`status`)

- [x] 6.1 `StatusReport` model + pure builder `(TaskContext, TaskState, activity) → StatusReport`, fields partitioned state-derivable vs live-only; `currentStage` null at `pipelineEnd` — FR11, D7
- [x] 6.2 Snapshot holder + event-listener adapter: state from `AttemptFinished`; `executing`/`verifying` activity from `AttemptStarted`/`ExecutionFinished`/`CheckStarted`; `lastEscalation` captured from `TaskFinished(Escalated)` — FR10, D7
- [x] 6.3 `awaitingInput` activity: `DialogConsole` marks prompt begin/end in the snapshot holder (no engine event exists for prompts) — FR10, D7
- [x] 6.4 Text renders: full report and one-line attempt summary; auto-summaries wired (per attempt, escalation render doubles, final full) — FR10, UX2
- [x] 6.5 JSON serializer (single configured ObjectMapper) + `status-report-v1.reference.json` byte-identity spec with injected clock — FR11, M3
- [x] 6.6 Attempt-boundary equivalence spec: report from events equals report from (context, state) — status-report capability
- [x] 6.7 Optional-usage spec: human-only run renders a contract-valid document (null tokens, empty `byTool`/`perVote`, wall time present) — NFR-C1

## 7. Runner (`app`)

- [x] 7.1 Argument parsing via `ApplicationArguments` (`--key=value`), fixed validation order (args → pipeline load), usage errors listing valid values — FR1, UX1, D5
- [x] 7.2 Startup pipeline load: once via `PipelineLoader` from `--project`/`.gnomish/`; `Invalid` → loader errors printed as-is, exit 3, before any dialog — FR1, FR12, D3
- [x] 7.3 Ad-hoc task synthesis: id generation / `--task-id`, title/body split, initial state incl. validated `--from-stage` — FR1, FR2, D4
- [x] 7.4 Outcome loop skeleton: exhaustive `TaskOutcome` switch, typed escalation renders per report kind; `PipelineMismatch` renders and exits as internal error — FR9, D8
- [x] 7.5 Escalation resume: decision prompt → reset `attemptsUsed`, append optional `Decision(author=operator)` (empty input = none) → run again — FR9, D8
- [x] 7.6 `Paused` checkpoint: confirmation prompt only — no reset, no decision — FR9, D8
- [x] 7.7 `Aborted`: cause + unpersisted-state summary on stderr, terminal (breaking-persist fake spec) — FR9, D8
- [x] 7.8 EOF paths: exhausted-input flag consulted before resume/checkpoint dialogs (exit 4 vs outcome codes); farewell line, no stack trace — FR13, NFR-R1, UX3, D2
- [x] 7.9 Exit codes via `ExitCodeGenerator` / `ExitCodeExceptionMapper` (0/1/2/3/4/10/11/12) — FR12, D10
- [x] 7.10 Production cross-cutting adapters: in-memory `AttemptPersistence` (main source set), system `Clock`, sleeping `Sleeper`; each passes its contract suite — D10, M2, NG3
- [x] 7.11 Port wiring `@Configuration`: interactive + real adapters, persistence, clock, sleeper, listeners (MDC, logging, snapshot) into `EnginePorts`; spock-spring context spec — D10
- [x] 7.12 No-args regression: factory-bootstrap behavior unchanged (clean boot, exit 0) — proposal "What Changes"

## 8. Logging

- [x] 8.1 Logback: single rolling file `~/.gnomish/logs/gnomish.log` (daily/size roll, ~7 days, total cap), pattern with `taskId`/`stage`/`attempt` MDC; console appender WARN+, ERROR to stderr; log location outside any workspace/git — NFR-O1, NFR-O2, NFR-S1, NFR-S2, D9
- [x] 8.2 MDC discipline: runner sets `taskId`; listener adapter maintains `stage`/`attempt` on the engine thread, cleared on `TaskFinished` — NFR-O1, D9
- [x] 8.3 Structured INFO line per engine event from the logging listener — NFR-O2

## 9. Gates and E2E

- [x] 9.1 E2E harness: fixture project with a pipeline covering all four check types; process launcher helper (real process, piped-stdin script, exit code + output capture) — M1
- [x] 9.2 Reference E2E session: quality retry → decision escalation + resume → manual pause → completion, exit 0; asserts the workspace holds no runner-created files — M1, FR12, NFR-S1
- [x] 9.3 E2E exit-code matrix: truncated script → 4; Ctrl-D at resume → 10; Ctrl-D at checkpoint → 11; usage error → 2; broken pipeline → 3 — M1, FR12, FR13
- [x] 9.4 `./gradlew check` green: PIT 100% on new Java production code (M4), Spotless, Error Prone/NullAway, buildHealth
- [x] 9.5 Traceability sweep: every FR/NFR/UX has an implementing spec or class reference
- [x] 9.6 README: document `gnomish run` usage and update the status sentence
