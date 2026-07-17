# manual-run

## Purpose

Provide `gnomish run`, an interactive console CLI that drives the pure stage engine through one ad-hoc task per process, with real (non-fake) interactive and command adapters standing in for the tracker- and agent-driven mechanisms — so an operator can exercise a pipeline end-to-end from the terminal without a tracker or CI integration.

## Requirements

### Requirement: Single-task dialog invocation
`gnomish run` SHALL run exactly one ad-hoc task as one interactive console dialog per process: `--project` (default: current directory) names the workspace, exactly one of `--task` | `--task-file` supplies the description, `--task-id` (optional, filesystem/git-ref-safe charset) overrides the generated `manual-<yyyyMMdd-HHmmss>-<2 chars>` id, `--from-stage` (optional) starts at a named stage. Validation SHALL run in fixed order: arguments, then pipeline load, then the run. Task title = first non-empty description line (markdown heading markers stripped), body = remainder; decisions start empty.
<!-- implements FR1, FR2 of add-manual-run -->

#### Scenario: Dry-run from the project directory
- **WHEN** `gnomish run --task="fix the flaky spec"` is invoked in a directory with a valid `.gnomish/`
- **THEN** the run starts at the first stage with a generated task id and title "fix the flaky spec"

#### Scenario: Unknown stage is a usage error
- **WHEN** `--from-stage=missing` names a stage absent from the loaded definition
- **THEN** the process exits with the usage exit code and the message lists the known stage names

#### Scenario: Broken pipeline reported before any dialog
- **WHEN** `.gnomish/` fails to load
- **THEN** the loader errors are printed as-is and the process exits with the pipeline-load exit code without prompting

### Requirement: Read-only workspace with a definition snapshot
The runner process SHALL write nothing inside the workspace: findings temp files and logs live outside it; the workspace changes only through the operator and the manifest's own commands. The pipeline definition SHALL be loaded once at startup; mid-dialog edits of `.gnomish/` take effect on the next invocation. The runner SHALL NOT require or inspect git.
<!-- implements FR1, NFR-S1 of add-manual-run -->

#### Scenario: No runner artifacts in the workspace
- **WHEN** a run completes after executing command checks with findings files
- **THEN** every file the runner itself created resides outside the workspace

### Requirement: Interactive stage executor
The interactive `StageExecutor` SHALL print the task goal, input artifacts, prior-attempt feedback, decisions, and the stage's control files, then prompt: empty Enter returns `Completed` with the measured wall time, no token usage, and an empty trace; `ask` opens a question-and-options dialog and returns `DecisionNeeded`.
<!-- implements FR3 of add-manual-run -->

#### Scenario: Human requests a decision
- **WHEN** the operator answers `ask` and enters a question with two options
- **THEN** the executor returns `DecisionNeeded` with that question and both options and the engine escalates without burning an attempt

### Requirement: Interactive external poll
The interactive `ExternalCheckClient` SHALL prompt per poll — `pass` / `fail` / `running` — collecting findings on `fail`; interval and timeout semantics stay in the engine untouched.
<!-- implements FR4 of add-manual-run -->

#### Scenario: Simulated CI wait
- **WHEN** the operator answers `running` and then `pass` on the next poll
- **THEN** the engine sleeps the manifest interval between the prompts and the check passes

### Requirement: Interactive judge vote
The interactive `JudgeVoter` SHALL print the acceptance-criteria file and prompt one verdict per vote (majority voting and short-circuiting stay in the engine), collecting findings on a failing vote.
<!-- implements FR5 of add-manual-run -->

#### Scenario: Majority decided by one human
- **WHEN** a 3-vote judge check receives two matching `fail` verdicts
- **THEN** no third vote is prompted and the check fails with the collected findings

### Requirement: files_exist builtin runner
The `files_exist` runner SHALL check existence of the literal workspace-relative paths in its `files` param, producing one finding (message + path as location) per missing path. Malformed params or a path resolving outside the workspace SHALL yield `CannotVerify`.
<!-- implements FR6 of add-manual-run -->

#### Scenario: Missing files enumerated
- **WHEN** two of three configured paths do not exist
- **THEN** the verdict is Fail with exactly two findings naming the missing paths

#### Scenario: Workspace escape refused
- **WHEN** a configured path resolves outside the workspace root
- **THEN** the verdict is CannotVerify naming the offending path

### Requirement: Command check runner
The command runner SHALL execute the manifest command via `sh -c` with the workspace as working directory and inherited environment, merging stderr into stdout and retaining a bounded output tail (~200 lines / 10 KB). Exit 0 → Pass; exit 126/127 → CannotVerify (shell convention for not-executable / not-found); any other non-zero exit → Fail.
<!-- implements FR7 of add-manual-run -->

#### Scenario: Red check carries feedback
- **WHEN** the command exits 1 without a findings file
- **THEN** the verdict is Fail with one synthetic finding whose details contain the output tail

#### Scenario: Missing binary is infrastructure
- **WHEN** the command exits 127
- **THEN** the verdict is CannotVerify, honoring the engine's classification table

### Requirement: Findings-JSON wire format
Before starting the command, the runner SHALL create a temp file path outside the workspace and pass it as `GNOMISH_FINDINGS_FILE`. After a non-zero exit (other than 126/127), a valid `{"findings":[{message, location?, details?}]}` file SHALL replace the synthetic finding; a malformed file SHALL degrade to the synthetic finding plus a logged warning — the exit-code verdict always stands; a findings file on exit 0 SHALL be ignored with a warning.
<!-- implements FR8, NFR-R2 of add-manual-run -->

#### Scenario: Structured findings win
- **WHEN** the command exits 1 and wrote two valid findings
- **THEN** the verdict is Fail with exactly those two findings

#### Scenario: Broken reporter cannot mask a red check
- **WHEN** the command exits 1 and the findings file is unparseable
- **THEN** the verdict is Fail (not CannotVerify) with the synthetic tail finding

### Requirement: Outcome loop with in-process resume
The runner SHALL handle every `TaskOutcome` exhaustively. `Escalated`: render the report by type, prompt for a decision — non-empty input appends `Decision(author=operator, stage, time)`, empty input appends nothing — reset `attemptsUsed`, and run again. `Paused`: confirmation prompt only, no reset, no decision. `Aborted`: print cause and an unpersisted-state summary to stderr and terminate. `PipelineMismatch` (unreachable in-process) renders and exits as an internal error.
<!-- implements FR9 of add-manual-run -->

#### Scenario: Decision-carrying resume
- **WHEN** the operator answers an `AttemptsExhausted` report with a decision text
- **THEN** the next run starts at the same stage with `attemptsUsed` 0 and the decision visible to the executor

#### Scenario: Empty decision retries after an environment fix
- **WHEN** the operator answers a `CannotVerify` report with an empty line
- **THEN** the run restarts with `attemptsUsed` reset and no decision appended

#### Scenario: Checkpoint continues without reset
- **WHEN** the operator presses Enter at a `Paused` checkpoint
- **THEN** the next run starts at the already-advanced position with counters untouched

### Requirement: Status command and auto-summaries
`status` and `status --json` SHALL be accepted at any prompt of the dialog, intercepted below the adapters at the single input choke point, re-prompting afterwards. The runner SHALL print a one-line summary after every finished attempt and a full status at the end; the escalation report render serves as the escalation summary. Text and JSON SHALL be renders of one `StatusReport` built by a pure function of `(TaskContext, TaskState, live activity)`.
<!-- implements FR10, FR11 of add-manual-run -->

#### Scenario: Status mid-prompt
- **WHEN** the operator types `status` at the executor prompt
- **THEN** the report is printed and the same executor prompt is asked again

### Requirement: Exit codes by outcome family
Exit codes SHALL split into two families at 10: `< 10` — the tool itself could not do its job (0 reserved for success), `≥ 10` — the factory reached a legitimate non-completed outcome, so a scripted consumer can test `$? -ge 10`. All codes stay outside the shell signal zone (128+n); 1 and 2 keep their conventional roles.
<!-- implements FR12 of add-manual-run -->

| Code | Family       | Meaning                                                                              |
|------|--------------|--------------------------------------------------------------------------------------|
| 0    | success      | `Completed`                                                                          |
| 1    | tool failure | internal error (unexpected exception; `PipelineMismatch`, unreachable in-process)    |
| 2    | tool failure | usage error (bad flags, unknown `--from-stage`)                                      |
| 3    | tool failure | pipeline load failure (`.gnomish/` invalid; loader errors printed as-is)             |
| 4    | tool failure | stdin exhausted mid-stage (input script too short)                                   |
| 10   | outcome      | `Escalated` — operator left with the task in escalation                              |
| 11   | outcome      | `Paused` — operator left at a manual checkpoint                                      |
| 12   | outcome      | `Aborted` — persistence failed                                                       |

#### Scenario: Persistence failure is Aborted
- **WHEN** the persistence port fails (breaking fake)
- **THEN** the process prints the cause and unpersisted-state summary to stderr and exits 12

### Requirement: EOF semantics without a TTY
Piped stdin SHALL be a first-class mode: no TTY check. EOF at a runner prompt (resume, checkpoint) SHALL exit with that outcome's code; EOF inside an adapter mid-stage SHALL flow through the engine as an infrastructure failure, after which the runner — seeing the input-exhausted flag — skips the resume dialog and exits with code 4. Exhausted input SHALL never hang the process or re-enter an input-requiring dialog.
<!-- implements FR13, NFR-R1 of add-manual-run -->

#### Scenario: Script too short
- **WHEN** piped stdin ends while the executor prompt is waiting
- **THEN** the process exits 4 without prompting for a resume decision

#### Scenario: Deliberate exit at an escalation
- **WHEN** the operator presses Ctrl-D at the resume prompt
- **THEN** the process exits 10

### Requirement: Port-contract compliance of new adapters
Interactive and real adapters SHALL pass the same port-level contract suites the add-stage-engine fakes pass, driven through a scripted console where input is needed. A contract variant an interactive adapter cannot produce SHALL be recorded as a port-shape finding, not worked around.
<!-- implements FR14 of add-manual-run -->

#### Scenario: One suite, many adapters
- **WHEN** the port-contract suite runs against the interactive executor with a scripted console
- **THEN** every suite scenario passes without modification

### Requirement: Instance-local logging
Logging SHALL go to a single rolling file under `~/.gnomish/logs/` (daily/size roll, bounded history and total size) with `taskId`, `stage`, `attempt` MDC on every line — `taskId` set by the runner, `stage`/`attempt` maintained by an event-listener adapter on the engine thread. The console appender SHALL pass WARN and above only, with ERROR duplicated to stderr; engine events SHALL be logged as structured INFO lines. Logs SHALL never be committed to git.
<!-- implements NFR-O1, NFR-O2, NFR-S2 of add-manual-run -->

#### Scenario: Quiet dialog
- **WHEN** a stage executes and verifies successfully
- **THEN** stdout contains only dialog output while the event INFO lines appear in the log file with full MDC

### Requirement: English dialog with forgiving input
All prompts, renders, and summaries SHALL be English; unrecognized input SHALL re-prompt listing the accepted answers; Ctrl-D SHALL always exit cleanly — farewell line, correct exit code, no stack trace.
<!-- implements UX1, UX3 of add-manual-run -->

#### Scenario: Typo re-prompts
- **WHEN** the operator answers `pss` to a pass/fail/running prompt
- **THEN** the prompt repeats naming the three accepted answers
