# manual-run

## Purpose

Provide `gnomish run`, an interactive console CLI that drives the pure stage engine through one ad-hoc task per process, with real (non-fake) interactive and command adapters standing in for the tracker- and agent-driven mechanisms — so an operator can exercise a pipeline end-to-end from the terminal without a tracker or CI integration.

## Requirements

### Requirement: Manifest-driven mechanism with interactive override
`gnomish run` SHALL bind mechanisms from the manifest alone: `agent-cli` stages to the CLI stage executor and judge checks to the CLI judge voter (models and settings from the manifest); `external` checks to the configured provider's client. No flag SHALL substitute a human for any of the three roles; `--interactive` in any form SHALL be a usage error like any unknown flag. A pipeline containing an `api` stage, or an `external` check whose provider has no `factory.check.<provider>` section, SHALL fail fast in the startup validation chain — pipeline-load exit code, before any dialog, naming the stage and, for a check, the check and the provider. No confirmation gate precedes the first paid round: the manifest-driven run is the tool's purpose and the operator is present.
<!-- implements FR1, FR2, FR3 of remove-interactive-console -->

#### Scenario: Flagless run uses real adapters
- **WHEN** `gnomish run` executes an `agent-cli` manifest with a judge check and no flags
- **THEN** the stage round and the judge vote both run through the CLI adapters with the manifest's pinned models

#### Scenario: Api stage rejected before any dialog
- **WHEN** the loaded pipeline contains a stage with `executor.type: api`
- **THEN** the process exits with the pipeline-load exit code naming the stage, without prompting

#### Scenario: Unconfigured check provider rejected before any dialog
- **WHEN** the loaded pipeline declares an `external` check whose provider is discovered but has no `factory.check.<provider>` section
- **THEN** the process exits with the pipeline-load exit code naming the stage, the check and the provider, and no branch, worktree or dialog is created

#### Scenario: Mixed run for judge calibration
- **WHEN** `gnomish run --interactive=judge` is invoked
- **THEN** the process exits with the usage-error code before loading the pipeline: no role is ever swapped for a human

#### Scenario: Full interactive override
- **WHEN** `gnomish run --interactive` is invoked
- **THEN** the process exits with the usage-error code before loading the pipeline

### Requirement: Agent-raised decisions reach the operator unchanged
A `DecisionNeeded` raised by the CLI executor through the decision-file protocol SHALL surface as the standard escalation stop — question and options rendered like any engine escalation, the process exiting 10 — and the operator's `--decision` on resume SHALL be recorded as a decision and fed back to the executor.
<!-- implements FR1, FR3 of make-run-headless -->

#### Scenario: Agent question round-trips
- **WHEN** the agent writes a decision file with a question and two options, the run exits 10, and the operator resumes with `--decision` naming one of them
- **THEN** the resumed run's executor prompt contains that decision verbatim

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

### Requirement: Read-only workspace with a definition snapshot
In git mode the runner SHALL NOT mutate the `--dir` clone: all work — gnome changes and `.gnomish-task/` state — happens in the task working copy owned by the bound task environment. Findings files SHALL live in the environment's scratch area — outside the working copy in every binding; host mode: a factory-private directory outside the worktree, as today; sandboxed mode: inside the task environment, never in factory-owned filesystem. Decision requests live in-branch under `.gnomish-task/decisions/` in git modes (FR23); only the in-place mode keeps the temp-file transport. In in-place mode the runner process SHALL write nothing inside the workspace: findings temp files and logs live outside it; the workspace changes only through the operator and the manifest's own commands, and the runner SHALL NOT require or inspect git. In both modes the pipeline definition SHALL be loaded once at startup; mid-dialog edits of `.gnomish/` take effect on the next invocation.
<!-- implements FR1, NFR-S1 of add-manual-run -->
<!-- implements FR10, NFR-S1, NFR-S2 of add-agent-executor -->
<!-- implements FR7, NFR-S2 of add-git-workflow -->
<!-- implements FR1, FR4 of add-sandbox-core -->

#### Scenario: No runner artifacts in the workspace
- **WHEN** an in-place run completes after executing command checks with findings files
- **THEN** every file the runner itself created resides outside the workspace

#### Scenario: Clone untouched in git mode
- **WHEN** a git-mode run executes stages and commits rounds
- **THEN** the clone's working copy, index, and current branch are exactly as before the run

#### Scenario: Sandboxed runner files stay in the environment
- **WHEN** a command check with findings runs in container mode
- **THEN** every runner-created temp file lives inside the task environment and none appears in factory-owned filesystem

### Requirement: files_exist builtin runner
The `files_exist` runner SHALL check existence of the literal workspace-relative paths in its `files` param, producing one finding (message + path as location) per missing path. In sandboxed mode existence SHALL be evaluated against the harvested attempt commit via bare git object reads in the factory clone — no environment access; in host modes it SHALL check the workspace filesystem as today. Malformed params or a path resolving outside the workspace SHALL yield `CannotVerify`.
<!-- implements FR6 of add-manual-run -->
<!-- implements FR21 of add-sandbox-core -->

#### Scenario: Missing files enumerated
- **WHEN** two of three configured paths do not exist
- **THEN** the verdict is Fail with exactly two findings naming the missing paths

#### Scenario: Sandboxed check reads the commit, not the box
- **WHEN** `files_exist` runs in sandboxed mode
- **THEN** existence is answered from the attempt commit's tree in the factory clone, and content present only as uncommitted box residue does not count

#### Scenario: Workspace escape refused
- **WHEN** a configured path resolves outside the workspace root
- **THEN** the verdict is CannotVerify naming the offending path

### Requirement: Command check runner
The command runner SHALL execute the manifest command via `sh -c` through `exec()` of the bound task environment, with the working copy as working directory, merging stderr into stdout and retaining a bounded output tail (~200 lines / 10 KB). The child environment SHALL be the layered allowlist of the execution-environment capability (adapter base set + operator passthrough + factory-set variables) — no factory-process variable is inherited implicitly, so tracker credentials cannot reach a check by construction. Exit 0 → Pass; exit 126/127 → CannotVerify (shell convention for
not-executable / not-found); any other non-zero exit → Fail.
<!-- implements FR7 of add-manual-run -->
<!-- implements FR11, NFR-S1 of add-claim-heartbeat -->
<!-- implements FR4, FR9 of add-sandbox-core -->

#### Scenario: Red check carries feedback
- **WHEN** the command exits 1 without a findings file
- **THEN** the verdict is Fail with one synthetic finding whose details contain the output tail

#### Scenario: Missing binary is infrastructure
- **WHEN** the command exits 127
- **THEN** the verdict is CannotVerify, honoring the engine's classification table

#### Scenario: No factory variable leaks into a check
- **WHEN** a command check runs while `GNOMISH_GITHUB_TOKEN` and unrelated host variables are set in the factory environment
- **THEN** the check's process environment contains only the allowlisted variables

### Requirement: Findings-JSON wire format
Before starting the command, the runner SHALL allocate a findings-file path in the environment's scratch area — outside the working copy but inside the bound task environment — and pass it as `GNOMISH_FINDINGS_FILE`; after process exit the file SHALL be read back through the environment. After a non-zero exit (other than 126/127), a valid `{"findings":[{message, location?, details?}]}` file SHALL replace the synthetic finding; a malformed file SHALL degrade to the synthetic finding plus a logged warning — the exit-code verdict always stands; a findings file on exit 0 SHALL be ignored with a warning.
<!-- implements FR8, NFR-R2 of add-manual-run -->
<!-- implements FR1, FR4 of add-sandbox-core -->

#### Scenario: Structured findings win
- **WHEN** the command exits 1 and wrote two valid findings
- **THEN** the verdict is Fail with exactly those two findings

#### Scenario: Broken reporter cannot mask a red check
- **WHEN** the command exits 1 and the findings file is unparseable
- **THEN** the verdict is Fail (not CannotVerify) with the synthetic tail finding

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

### Requirement: Status command and auto-summaries
The runner SHALL print a one-line summary after every finished attempt and a full status at the end; the escalation report render serves as the escalation summary. `gnomish status <task>` and `gnomish status <task> --json` are the only status paths; there is no in-dialog meta-command because there is no dialog. Text and JSON SHALL be renders of one `StatusReport` built by a pure function of `(TaskContext, TaskState, live activity)`, where live activity is `executing` or `verifying`.
<!-- implements FR6 of make-run-headless -->

#### Scenario: Status mid-prompt
- **WHEN** a run is mid-flight and the operator runs `gnomish status <task>` in another process
- **THEN** the report is printed from the branch and the running process is unaffected; no prompt exists to type into

### Requirement: Exit codes by outcome family
Exit codes SHALL split into two families at 10: `< 10` — the tool itself could not do its job (0 reserved for success), `≥ 10` — the factory reached a legitimate non-completed outcome, so a scripted consumer can test `$? -ge 10`. All codes stay outside the shell signal zone (128+n); 1 and 2 keep their conventional roles. Code 4 is retired and SHALL NOT be returned; it stays listed as a gap so that no other code shifts.
<!-- implements FR5, FR8 of remove-interactive-console -->

| Code | Family       | Meaning                                                                                                                                             |
|------|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| 0    | success      | `Completed`                                                                                                                                         |
| 1    | tool failure | internal error (unexpected exception; `PipelineMismatch`, unreachable in-process)                                                                   |
| 2    | tool failure | usage error (bad flags, unknown `--from-stage`)                                                                                                     |
| 3    | tool failure | pipeline load failure (`.gnomish/` invalid, `api` stage, unconfigured check provider; loader errors printed as-is)                                  |
| 4    | retired      | used in the MVP for stdin ending inside an interactive adapter; removed as unneeded; kept as a gap so other codes do not shift; may be filled by a future tool failure |
| 5    | tool failure | diverged branch on a claimless resume                                                                                                               |
| 6    | tool failure | task not found (`status` / `usage` only)                                                                                                            |
| 7    | tool failure | branch shape refused on pickup                                                                                                                      |
| 10   | outcome      | `Escalated` — operator left with the task in escalation                                                                                             |
| 11   | outcome      | `Paused` — operator left at a manual checkpoint                                                                                                     |
| 12   | outcome      | `Aborted` — persistence failed                                                                                                                      |

#### Scenario: Persistence failure is Aborted
- **WHEN** the persistence port fails (breaking fake)
- **THEN** the process prints the cause and unpersisted-state summary to stderr and exits 12

#### Scenario: Retired code is never returned
- **WHEN** any run terminates, by any path
- **THEN** the exit code is one of 0, 1, 2, 3, 5, 6, 7, 10, 11, 12

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

### Requirement: Port-contract compliance of new adapters
The CLI adapters SHALL pass the same port-level contract suites the add-stage-engine fakes pass, driven through the fake agent binary where a subprocess is needed. A contract variant an adapter cannot produce SHALL be recorded as a port-shape finding, not worked around.
<!-- implements FR4, FR6 of remove-interactive-console -->

#### Scenario: One suite, many adapters
- **WHEN** the port-contract suite runs against the CLI stage executor with the fake agent
- **THEN** every suite scenario passes without modification

### Requirement: Instance-local logging
Logging SHALL go to a single rolling file (daily/size roll, bounded history and total size) — `GNOMISH_HOME/projects/<name>/logs/<instance>.log` for a command resolved to a registered project, `GNOMISH_HOME/logs/factory.log` for a command that runs with no project — with `taskId`, `stage`, `attempt` MDC on every line — `taskId` set by the runner, `stage`/`attempt` maintained by an event-listener adapter on the engine thread. The file location SHALL be decided by the same owner that computes every other operator path; no separate log-directory variable exists. The console appender SHALL pass WARN and above only, with ERROR duplicated to stderr; engine events SHALL be logged as structured INFO lines. Logs SHALL never be committed to git.

A manual run SHALL end with the canonical per-task summary line — outcome,
stage, attempts used, wall time, token usage by model — assembled from the
engine's event stream and rendered by the same renderer the other modes use,
for every terminal outcome including aborts. Manual mode is the debugging
mode: the summary and the engine-event INFO lines together SHALL let the
operator see where a run stalled without raising verbosity.
<!-- implements NFR-O1, NFR-O2, NFR-S2 of add-manual-run -->
<!-- implements FR3 of harden-logging-observability -->
<!-- implements FR11, UX3 of add-project-registry -->

#### Scenario: Quiet dialog
- **WHEN** a stage executes and verifies successfully
- **THEN** stdout contains only dialog output while the event INFO lines appear in the log file with full MDC

#### Scenario: Manual run ends with the summary
- **WHEN** a manual run reaches any terminal outcome
- **THEN** the log's last line for that task is the canonical summary carrying
  outcome, stage, attempts, wall time, and token usage

#### Scenario: Two projects keep separate logs
- **WHEN** `take` runs for project `widgets` while `serve` runs for project `gateway` on one host
- **THEN** their lines land in `~/.gnomish/projects/widgets/logs/default.log` and `~/.gnomish/projects/gateway/logs/default.log` respectively — each path the operator uses daily fits on one line

### Requirement: English dialog with forgiving input
All renders and summaries SHALL be English. Flags are the only input: an unrecognized flag or combination SHALL exit 2 naming the accepted forms. The process SHALL exit cleanly on every path — farewell line, correct exit code, no stack trace — and SHALL never wait on stdin.
<!-- implements FR6, FR9 of make-run-headless -->

#### Scenario: Typo re-prompts
- **WHEN** the operator passes `--decison="x"` (misspelled) with `--resume`
- **THEN** the process exits 2 naming the accepted flags, and no branch write happens

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

### Requirement: Manual ownership mode
Container environments created by `gnomish run` SHALL be labelled with ownership mode `manual`. Manual objects are governed by the age-only policy of `sandbox-lifecycle` — no claim oracle — so a live manual session is never disturbed by a coexisting daemon, and a forgotten manual zombie is still reclaimed after the configured thresholds.
<!-- implements FR2, FR7 of add-serve-sandbox-lifecycle -->

#### Scenario: Manual session beside a daemon
- **WHEN** a container `gnomish run` session works on a host where `gnomish serve` also runs
- **THEN** the daemon's sweep classifies the manual objects by their mode label and applies only the age policy — the running manual box under the threshold is untouched

### Requirement: Run startup sweep degrades without a tracker
The `run` startup sweep pass SHALL evaluate the shared `sandbox-lifecycle` policy. When no tracker is configured, tracked objects of other tasks SHALL receive skipped-no-verdict (untouched, logged); manual objects follow the age policy, which needs no tracker; the run's own task key keeps its existing `--discard-work` and reattach semantics. Verdicts SHALL be logged in the uniform vocabulary.
<!-- implements FR6, FR9, NFR-O4, NFR-R1 of add-serve-sandbox-lifecycle -->

#### Scenario: Trackerless run touches no tracked object
- **WHEN** a `gnomish run` without tracker configuration starts on a host holding another task's tracked objects
- **THEN** those objects are reported skipped-no-verdict and untouched, and the run proceeds normally

### Requirement: Resume invocation
`gnomish run --dir=<dir> --resume=<task>` SHALL resume the named task from its branch; `--resume` is mutually exclusive with `--task`, `--task-file`, `--task-id`, and `--from-stage` (usage error). `--decision="<text>"` SHALL be accepted only together with `--resume`, never with `--mode=in-place`, and only for a task whose recorded outcome is `escalated`; any other combination is a usage error naming the conflict. Resume SHALL behave by recorded branch state without any prompt, and every continuation SHALL land its one lifecycle commit before the engine runs: escalated with `--decision` → the decision commit (decision appended, attempts reset, outcome cleared), then continue; escalated without `--decision` → the resumed commit (attempts reset, outcome cleared), then continue — unless the recorded report is `DecisionNeeded`, in which case the question is restated, nothing is written, and the process exits 10; at a gate (`AwaitingApproval(stage)`, whether or not the `paused` park was recorded) → the approval commit (position past the gate, outcome cleared), then continue; outcome null at a stage → continue silently from the recorded position; completed → report the task is done and exit with the success code.
<!-- implements FR4, FR7 of make-checkpoint-gate-durable -->
<!-- implements FR3, FR4, FR5, FR9 of make-run-headless -->

#### Scenario: Resume of an escalated task
- **WHEN** `--resume --decision="patch in place"` opens a task parked as Escalated with an `AttemptsExhausted` report
- **THEN** the branch gains one commit carrying the decision and the attempts reset, and the engine continues at the same stage

#### Scenario: Resume without a decision writes the reset
- **WHEN** `--resume` without `--decision` opens a task parked as Escalated with an `AttemptsExhausted` or `CannotVerify` report
- **THEN** the branch gains one commit with `outcome` null and the reset attempt counter before the first round, and a kill after it does not grant a second reset

#### Scenario: Resume of a checkpoint approves it
- **WHEN** `--resume` opens a task whose branch is at `AwaitingApproval(stage)`
- **THEN** the branch gains the approval commit — position past the gate, `outcome` null — and the engine starts the following stage; no prompt, no decision

#### Scenario: Resume of a gate whose park was lost
- **WHEN** `--resume` opens a task at `AwaitingApproval(stage)` whose `task.json` outcome is null
- **THEN** the run behaves exactly as for a recorded `paused` park: it approves and continues

#### Scenario: Completed task resumes to a no-op
- **WHEN** `--resume` names a task whose outcome is completed
- **THEN** the process reports the task is done and exits 0

#### Scenario: Unanswered question is restated
- **WHEN** `--resume` without `--decision` opens a task whose recorded report is `DecisionNeeded`
- **THEN** the question and options are printed again with the return-path line, no round runs, nothing is written, and the process exits 10

#### Scenario: Decision without a question is a usage error
- **WHEN** `--resume --decision="x"` opens a task whose branch is at a gate or whose outcome is completed, or `--decision` is given without `--resume`
- **THEN** the process exits 2 naming the conflict and writes nothing to the branch