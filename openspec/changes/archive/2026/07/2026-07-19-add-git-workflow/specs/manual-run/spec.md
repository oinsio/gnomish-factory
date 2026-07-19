# manual-run (delta)

## MODIFIED Requirements

### Requirement: Single-task dialog invocation
`gnomish run` SHALL run exactly one ad-hoc task as one interactive console dialog per process: `--dir` (default: current directory) names the target project — the clone in git mode, the workspace in in-place mode; exactly one of `--task` | `--task-file` supplies the description unless `--resume` names an existing task, `--task-id` (optional, filesystem/git-ref-safe charset) overrides the generated `manual-<yyyyMMdd-HHmmss>-<2 chars>` id, `--from-stage` (optional) starts at a named stage. Validation SHALL run in fixed order: arguments, then pipeline load, then the run. Task title = first non-empty description line (markdown heading markers stripped), body = remainder; decisions start empty.
<!-- implements FR7, FR8 of add-git-workflow -->

#### Scenario: Default invocation from the project directory
- **WHEN** `gnomish run --task="fix the flaky spec"` is invoked in a clone with a valid `.gnomish/`
- **THEN** the run starts in git mode at the first stage with a generated task id and title "fix the flaky spec"

#### Scenario: Unknown stage is a usage error
- **WHEN** `--from-stage=missing` names a stage absent from the loaded definition
- **THEN** the process exits with the usage exit code and the message lists the known stage names

#### Scenario: Broken pipeline reported before any dialog
- **WHEN** `.gnomish/` fails to load
- **THEN** the loader errors are printed as-is and the process exits with the pipeline-load exit code without prompting

### Requirement: Read-only workspace with a definition snapshot
In git mode the runner SHALL NOT mutate the `--dir` clone: all work — gnome changes and `.gnomish-task/` state — happens in the task worktree. In in-place mode the runner process SHALL write nothing inside the workspace: findings temp files and logs live outside it; the workspace changes only through the operator and the manifest's own commands, and the runner SHALL NOT require or inspect git. In both modes the pipeline definition SHALL be loaded once at startup; mid-dialog edits of `.gnomish/` take effect on the next invocation.
<!-- implements FR7, NFR-S2 of add-git-workflow -->

#### Scenario: No runner artifacts in the workspace
- **WHEN** an in-place run completes after executing command checks with findings files
- **THEN** every file the runner itself created resides outside the workspace

#### Scenario: Clone untouched in git mode
- **WHEN** a git-mode run executes stages and commits rounds
- **THEN** the clone's working copy, index, and current branch are exactly as before the run

## ADDED Requirements

### Requirement: Run modes
`gnomish run` SHALL accept `--mode git|in-place`, default `git`. Git mode: the factory creates the task branch, worktree, round commits, and pushes; the branch name and worktree path are printed upfront. In-place mode: the preserved legacy behavior — no git, in-memory state, no resume — with an honest reminder at start that exiting kills the task. Git-only flags (`--base`, `--resume`, `--discard-work`) combined with `--mode in-place` SHALL be a usage error (exit code 2).
<!-- implements FR7, UX1, UX4 of add-git-workflow -->

#### Scenario: Git mode is the default
- **WHEN** `gnomish run --task="t"` runs without `--mode`
- **THEN** the run operates in git mode and prints the task branch and worktree path before the first stage

#### Scenario: Git flag rejected in in-place mode
- **WHEN** `gnomish run --mode=in-place --resume T-1` is invoked
- **THEN** the process exits with code 2 naming the incompatible flags

#### Scenario: In-place reminder
- **WHEN** an in-place run starts
- **THEN** the dialog states that state is in memory only and the task dies with the process

### Requirement: Resume invocation
`gnomish run --dir <dir> --resume <task>` SHALL resume the named task from its branch; `--resume` is mutually exclusive with `--task`, `--task-file`, `--task-id`, and `--from-stage` (usage error). The resume dialogs SHALL mirror the in-process escalation dialogs: escalated → the report render and decision prompt; paused → the checkpoint confirmation; outcome null → continue silently from the recorded position; completed → report the task is done and exit with the success code.
<!-- implements FR8, UX2 of add-git-workflow -->

#### Scenario: Resume of an escalated task
- **WHEN** `--resume` opens a task parked as Escalated
- **THEN** the recorded report is rendered and the decision prompt behaves exactly as the in-process resume dialog

#### Scenario: Completed task resumes to a no-op
- **WHEN** `--resume` names a task whose outcome is completed
- **THEN** the process reports the task is done and exits 0
