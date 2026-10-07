# Spec Delta: manual-run

## MODIFIED Requirements

### Requirement: Outcome loop with in-process resume
The runner SHALL handle every `TaskOutcome` exhaustively and SHALL never read the console. `Escalated`: render the report by type and a return-path line naming the resume command in the form the CLI parses, ready to run as printed (`gnomish run --dir=<dir> --resume=<task>` with an optional `--decision="..."`), then terminate with exit code 10. `Paused`: print the checkpoint line naming the passed stage and the resume command, then terminate with exit code 11. `Completed`: print the final status and terminate with exit code 0. `Aborted`: print cause and an unpersisted-state summary to stderr and terminate with exit code 12. `PipelineMismatch` (unreachable in-process) renders and exits as an internal error. In git mode the terminal boundary that receives an `Escalated` or `Paused` outcome — host fresh run, host resume, container fresh run, container resume — SHALL record it on the task branch as a park (`outcome` with `lastEscalation` for an escalation) before the process exits, keeping the worktree or the stopped task environment for the resume and never running the `Completed` cleanup; in-place mode records nothing. The report, the checkpoint line and the return-path line SHALL be rendered by one component whichever path — in-process loop, host resume, container resume, or `take`'s park report for the checkpoint line — reaches the stop.
<!-- implements FR1, FR2, FR7, FR8, FR10, NFR-R3 of make-run-headless -->

#### Scenario: Decision-carrying resume
- **WHEN** a run ends with an `AttemptsExhausted` report and the operator runs `--resume <task> --decision="use approach A"`
- **THEN** the next run starts at the same stage with `attemptsUsed` 0 and the decision visible to the executor

#### Scenario: Empty decision retries after an environment fix
- **WHEN** a run ends with a `CannotVerify` report and the operator runs `--resume <task>` with no `--decision`
- **THEN** the run restarts with `attemptsUsed` reset and no decision appended

#### Scenario: Checkpoint continues without reset
- **WHEN** a run ends at a `Paused` checkpoint with exit 11 and the operator runs `--resume <task>`
- **THEN** the next run starts at the already-advanced position with counters untouched and no confirmation is asked

#### Scenario: Escalation ends the process with its return path on stdout
- **WHEN** the engine returns `Escalated` in a git-mode run
- **THEN** stdout carries the rendered report followed by a line naming `--resume=<task>` and the optional `--decision`, the process exits 10, stdin was never read, and that command, run as printed without the optional part, resumes the task

#### Scenario: Park is on the branch before the process exits
- **WHEN** a git-mode run — host or container, fresh or resumed — ends with `Escalated` or `Paused`
- **THEN** the task branch tip's `task.json` records that outcome (with `lastEscalation` for an escalation), the worktree or the stopped task environment is kept, no cleanup commit was made, and a following `--resume` switches on that recorded outcome

#### Scenario: Kill between the round commit and the park record of an exhausted stage
- **WHEN** the process is killed after the last round commit of a stage whose attempt limit is spent and before the park outcome commit
- **THEN** the branch is in the interrupted-run shape, `--resume` continues from the recorded position and the engine reproduces the `AttemptsExhausted` stop, recording the park it could not record before (the same window for a checkpoint or a `DecisionNeeded` is owned by `make-checkpoint-gate-durable`)

#### Scenario: A run park carries no pending tracker write
- **WHEN** a git-mode run records an `Escalated` or `Paused` park
- **THEN** the branch tip's `task.json` carries the outcome with no `trackerWritePending` marker, in one commit, since no tracker write follows a manual run

### Requirement: Resume invocation
`gnomish run --dir=<dir> --resume=<task>` SHALL resume the named task from its branch; `--resume` is mutually exclusive with `--task`, `--task-file`, `--task-id`, and `--from-stage` (usage error). `--decision="<text>"` SHALL be accepted only together with `--resume`, never with `--mode=in-place`, and only for a task whose recorded outcome is `escalated`; any other combination is a usage error naming the conflict. Resume SHALL behave by recorded outcome without any prompt: escalated with `--decision` → append the decision (author `operator`, the stage, now) and reset attempts in one commit, then continue; escalated without `--decision` → reset attempts and continue, unless the recorded report is `DecisionNeeded`, in which case the question is restated, no attempt is burned, and the process exits 10; paused → continue from the advanced position; outcome null → continue silently from the recorded position; completed → report the task is done and exit with the success code.
<!-- implements FR3, FR4, FR5, FR9 of make-run-headless -->

#### Scenario: Resume of an escalated task
- **WHEN** `--resume --decision="patch in place"` opens a task parked as Escalated with an `AttemptsExhausted` report
- **THEN** the branch gains one commit carrying the decision and the attempts reset, and the engine continues at the same stage

#### Scenario: Completed task resumes to a no-op
- **WHEN** `--resume` names a task whose outcome is completed
- **THEN** the process reports the task is done and exits 0

#### Scenario: Unanswered question is restated
- **WHEN** `--resume` without `--decision` opens a task whose recorded report is `DecisionNeeded`
- **THEN** the question and options are printed again with the return-path line, no round runs, and the process exits 10

#### Scenario: Decision without a question is a usage error
- **WHEN** `--resume --decision="x"` opens a task whose recorded outcome is paused or completed, or `--decision` is given without `--resume`
- **THEN** the process exits 2 naming the conflict and writes nothing to the branch

### Requirement: Agent-raised decisions reach the operator unchanged
A `DecisionNeeded` raised by the CLI executor through the decision-file protocol SHALL surface as the standard escalation stop — question and options rendered like any engine escalation, the process exiting 10 — and the operator's `--decision` on resume SHALL be recorded as a decision and fed back to the executor.
<!-- implements FR1, FR3 of make-run-headless -->

#### Scenario: Agent question round-trips
- **WHEN** the agent writes a decision file with a question and two options, the run exits 10, and the operator resumes with `--decision` naming one of them
- **THEN** the resumed run's executor prompt contains that decision verbatim

### Requirement: Status command and auto-summaries
The runner SHALL print a one-line summary after every finished attempt and a full status at the end; the escalation report render serves as the escalation summary. `gnomish status <task>` and `gnomish status <task> --json` are the only status paths; there is no in-dialog meta-command because there is no dialog. Text and JSON SHALL be renders of one `StatusReport` built by a pure function of `(TaskContext, TaskState, live activity)`, where live activity is `executing` or `verifying`.
<!-- implements FR6 of make-run-headless -->

#### Scenario: Status mid-prompt
- **WHEN** a run is mid-flight and the operator runs `gnomish status <task>` in another process
- **THEN** the report is printed from the branch and the running process is unaffected; no prompt exists to type into

### Requirement: English dialog with forgiving input
All renders and summaries SHALL be English. Flags are the only input: an unrecognized flag or combination SHALL exit 2 naming the accepted forms. The process SHALL exit cleanly on every path — farewell line, correct exit code, no stack trace — and SHALL never wait on stdin.
<!-- implements FR6, FR9 of make-run-headless -->

#### Scenario: Typo re-prompts
- **WHEN** the operator passes `--decison="x"` (misspelled) with `--resume`
- **THEN** the process exits 2 naming the accepted flags, and no branch write happens

### Requirement: EOF semantics without a TTY
Layered on "EOF semantics without a TTY" as modified by `remove-interactive-console` (sequenced before this change). Stdin SHALL be irrelevant to `gnomish run`: no engine port and no runner path reads it, so a closed, empty or absent stdin changes nothing. Exit codes 10 and 11 SHALL be produced from the terminal outcome, never from an EOF.
<!-- implements FR7 of make-run-headless -->

#### Scenario: Deliberate exit at an escalation
- **WHEN** a run escalates with stdin closed from the start
- **THEN** the process exits 10 with the report and the return-path line, identically to a run with an open terminal

#### Scenario: Deliberate exit at a checkpoint
- **WHEN** a run reaches a manual checkpoint with stdin closed from the start
- **THEN** the process exits 11 with the checkpoint line, identically to a run with an open terminal

#### Scenario: Script too short
- **WHEN** stdin is closed before the run starts and the pipeline completes
- **THEN** the process exits 0 and no code in the retired or EOF family is returned

### Requirement: Run modes
`gnomish run` SHALL accept `--mode git|in-place`, default `git`. Git mode: the factory creates the task branch and the task working copy through the bound task environment, closes rounds per the bound adapter's round protocol, and pushes; the branch name is printed upfront — together with the worktree path in host mode, or the task-environment identifier in sandboxed mode, whose working-copy location is a private adapter detail and is never printed as a host path. In-place mode: the preserved legacy behavior — no git, in-memory state, no resume — with an honest reminder at start that exiting kills the task and that an escalation or a manual checkpoint ends the task for good, since there is no branch to resume from. Git-only flags (`--base`, `--resume`, `--discard-work`, `--decision`) combined with `--mode in-place` SHALL be a usage error (exit code 2).
<!-- implements FR9 of make-run-headless -->

#### Scenario: Git mode is the default
- **WHEN** `gnomish run --task="t"` runs without `--mode`
- **THEN** the run operates in git mode and prints the task branch before the first stage — with the worktree path when the binding is host

#### Scenario: Sandboxed run prints the environment, not a host path
- **WHEN** a git-mode run starts with a container binding
- **THEN** the upfront output names the task branch and the task environment, and no host filesystem path of the working copy is printed

#### Scenario: Git flag rejected in in-place mode
- **WHEN** `gnomish run --mode=in-place --resume T-1` or `gnomish run --mode=in-place --decision="x"` is invoked
- **THEN** the process exits with code 2 naming the incompatible flags

#### Scenario: In-place reminder
- **WHEN** an in-place run starts
- **THEN** the output states that state is in memory only, the task dies with the process, and an escalation or checkpoint ends it for good

### Requirement: Single-task dialog invocation
`gnomish run` SHALL run exactly one ad-hoc task per process, with no console dialog: `--dir` (default: current directory) names the target project — the clone in git mode, the workspace in in-place mode; exactly one of `--task` | `--task-file` supplies the description unless `--resume` names an existing task, `--task-id` (optional, filesystem/git-ref-safe charset) overrides the generated `manual-<yyyyMMdd-HHmmss>-<2 chars>` id, `--from-stage` (optional) starts at a named stage, `--decision` (optional, `--resume` only) supplies the operator's answer to a recorded escalation. Validation SHALL run in fixed order: arguments, then pipeline load, then the run. Task title = first non-empty description line (markdown heading markers stripped), body = remainder; decisions start empty.
<!-- implements FR3, FR9 of make-run-headless -->

#### Scenario: Default invocation from the project directory
- **WHEN** `gnomish run --task="fix the flaky spec"` is invoked in a clone with a valid `.gnomish/`
- **THEN** the run starts in git mode at the first stage with a generated task id and title "fix the flaky spec"

#### Scenario: Unknown stage is a usage error
- **WHEN** `--from-stage=missing` names a stage absent from the loaded definition
- **THEN** the process exits with the usage exit code and the message lists the known stage names

#### Scenario: Broken pipeline reported before any dialog
- **WHEN** `.gnomish/` fails to load
- **THEN** the loader errors are printed as-is and the process exits with the pipeline-load exit code
