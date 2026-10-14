# cli-arguments

## Purpose

How the `gnomish` command line is parsed and reported: which options each subcommand accepts, how the project directory is resolved, and how a failure already reported to the operator reaches them exactly once.

## Requirements

### Requirement: Unknown options are usage errors
Every `gnomish` subcommand SHALL reject a `--`-prefixed option it does not accept with a usage error that names the option, names the subcommand, and lists the options it accepts; where the rejected option matches a positional argument of the subcommand, the message SHALL say so. The check SHALL run before the subcommand claims a task, creates a branch or worktree, or starts an environment.
**Implements**: FR8, NFR-R1, NFR-O1, UX2 of fix-operator-blockers; FR6 of add-release-pipeline
**Notes**: The empty command line stays a no-op so that a Spring test context does not drive a run; the sole `--version` is owned by the `release-distribution` capability, "The factory reports its version".

#### Scenario: A mistyped task flag fails loudly
- **WHEN** the operator runs `gnomish status --dir=. --task=github:acme/widgets#7`
- **THEN** the command exits with a usage error naming `--task`, listing `--dir` and `--json`, and stating that the task id is positional
- **AND** no overview is printed

#### Scenario: Property options pass through
- **WHEN** the operator runs `gnomish take --dir=. --factory.serve.slots=2`
- **THEN** no unknown-option error is raised and the property reaches configuration

#### Scenario: Spring Boot's own switches pass through
- **WHEN** the operator runs `gnomish serve --dir=. --debug`
- **THEN** no unknown-option error is raised

#### Scenario: Every dotted option passes through
- **WHEN** a subcommand is given an option whose name contains a dot, such as `--spring.main.banner-mode=off` or `--logging.level.root=DEBUG`, or Spring Boot's `--trace`
- **THEN** no unknown-option error is raised and the option reaches configuration unchecked

#### Scenario: Rejected before side effects
- **WHEN** `gnomish take` is invoked with an unknown option
- **THEN** it exits with the usage error before any tracker call, branch, worktree or box is created

#### Scenario: A mistyped run flag is not a silent success
- **WHEN** the operator runs `gnomish run --tsk=fix the flaky spec` or a bare `gnomish --dirr=.`
- **THEN** the command exits with the usage error naming `--tsk` (or `--dirr`) for `'gnomish run'` and listing the options `run` accepts
- **AND** the exit code is 2, not 0

#### Scenario: A run with no task is a usage error, not a no-op
- **WHEN** the operator runs `gnomish run` or `gnomish run --debug` with neither `--task`, `--task-file` nor `--resume`
- **THEN** the command exits with the usage error saying exactly one of `--task` or `--task-file` is required

#### Scenario: An empty command line stays a no-op
- **WHEN** the application starts with no arguments at all, as a Spring test context does
- **THEN** no parser runs, nothing is printed, and the process exits 0

#### Scenario: A sole `--version` is the other command line that reaches no parser
- **WHEN** the operator runs `gnomish --version` with no other token
- **THEN** the product version is printed on one line and the process exits 0
- **AND** no subcommand parser runs and no usage error is raised

#### Scenario: `--version` with any other token is parsed as usual
- **WHEN** the operator runs `gnomish run --version` or `gnomish --version --dir=.`
- **THEN** the command line reaches the `run` parser and exits 2 with the usage error naming `--version` for `'gnomish run'`

### Requirement: The project directory is resolved once to an absolute path
Every subcommand that accepts `--dir` SHALL resolve its value — or, for the subcommands where the option is optional (`run`, `take`, `serve`, `board`, `dashboard`), the working directory when it is absent — to an absolute, normalized path when the command line is parsed, and every component SHALL receive that resolved path. A relative `--dir` SHALL behave exactly like the equivalent absolute path.
**Implements**: FR7 of fix-operator-blockers

#### Scenario: Relative directory starts git mode
- **WHEN** the operator runs `gnomish run --dir=. --task="fix the flaky spec"` from inside a clone
- **THEN** git mode starts a task branch exactly as with the clone's absolute path

#### Scenario: Parent-relative directory is normalized
- **WHEN** the operator passes `--dir=../widgets/./`
- **THEN** every path the factory prints and uses for that clone is the absolute, normalized path without `..` or `.` segments

#### Scenario: Status still requires the directory
- **WHEN** the operator runs `gnomish status` with no `--dir`
- **THEN** the command exits with the usage error saying `--dir` is required, as before

#### Scenario: Usage still requires the directory
- **WHEN** the operator runs `gnomish usage` with no `--dir`
- **THEN** the command exits with the usage error saying `--dir` is required, as `status` does

### Requirement: A reported failure is printed once
A failure the factory has already reported to the operator — a usage error or any other classified failure, and the exit-code carriers with which `take` and `serve` end — SHALL reach the operator once: its one line on stderr, or nothing more when the command already printed its outcome. The framework SHALL add no failure record of its own for it: no stack trace on stdout or stderr and no record in the log file. The exit code SHALL be the one the factory maps for the failure.
**Implements**: FR16, NFR-O1 of fix-operator-blockers

#### Scenario: A usage error is one line
- **WHEN** the operator runs `gnomish status --dir=. --task=x`
- **THEN** the process exits 2 and stderr holds exactly the usage error line
- **AND** stdout holds no `Application run failed` record and no stack trace
- **AND** the log file gains no record of the failure

#### Scenario: A completed take is not an unhandled failure
- **WHEN** `gnomish take` finishes and ends with its computed exit code
- **THEN** no WARN "unhandled exception", no `gnomish run failed:` line and no framework failure record is written

#### Scenario: An unclassified fault keeps its trace
- **WHEN** a command ends with a fault no classification names
- **THEN** stderr holds `gnomish run failed: <message>` and the log file holds the WARN with the stack trace

#### Scenario: A failure before any command keeps the framework's report
- **WHEN** a failure is raised before any `gnomish` command runs, such as a context that fails to start
- **THEN** the framework's own failure report is kept
