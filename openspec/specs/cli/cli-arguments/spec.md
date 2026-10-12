# cli-arguments

## Purpose

How the `gnomish` command line is parsed and reported: which options each subcommand accepts, how the project directory is resolved, and how a failure already reported to the operator reaches them exactly once.

## Requirements

### Requirement: Unknown options are usage errors
Every `gnomish` subcommand SHALL reject a `--`-prefixed option it does not accept with a usage error that names the option, names the subcommand, and lists the options it accepts; where the rejected option matches a positional argument of the subcommand, the message SHALL say so. Options whose name contains a dot (Spring properties such as `--factory.*`, `--spring.*`, `--logging.*`) and Spring Boot's own `--debug` and `--trace` switches SHALL pass through to configuration and are not checked by this rule. The check SHALL run before the subcommand claims a task, creates a branch or worktree, or starts an environment. The entrypoint SHALL hand every non-empty command line to its subcommand's parser, so the check cannot be skipped by a command line that names no known option. Exactly two command lines reach no parser: the empty one (no subcommand token and no option), which is the no-op that keeps a Spring test context from driving a run, and the one consisting of the sole token `--version`, which prints the product version and exits 0 before Spring starts (the `release-distribution` capability, "The factory reports its version"). A `--version` accompanied by any other token SHALL reach the parser like any other command line and is rejected as an unknown option.
<!-- implements FR8, NFR-R1, NFR-O1, UX2 of fix-operator-blockers -->
<!-- implements FR6 of add-release-pipeline -->

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
Every subcommand that accepts `--dir` SHALL resolve its value — or, for the subcommands where the option is optional (`run`, `take`, `serve`, `board`, `dashboard`), the working directory when it is absent — to an absolute, normalized path when the command line is parsed, and every component SHALL receive that resolved path. Whether `--dir` is required does not change: `status` and `usage` SHALL keep refusing an absent `--dir` with their usage error. A relative `--dir` SHALL behave exactly like the equivalent absolute path.
<!-- implements FR7 of fix-operator-blockers -->

#### Scenario: Relative directory starts git mode
- **WHEN** the operator runs `gnomish run --dir=. --task="fix the flaky spec"` from inside a clone
- **THEN** git mode starts a task branch exactly as with the clone's absolute path

#### Scenario: Parent-relative directory is normalized
- **WHEN** the operator passes `--dir=../widgets/./`
- **THEN** every path the factory prints and uses for that clone is the absolute, normalized path without `..` or `.` segments

#### Scenario: Status still requires the directory
- **WHEN** the operator runs `gnomish status` with no `--dir`
- **THEN** the command exits with the usage error saying `--dir` is required, as before

### Requirement: A reported failure is printed once
A failure the factory has already reported to the operator — a usage error or any other classified failure, and the exit-code carriers with which `take` and `serve` end — SHALL reach the operator once: its one line on stderr, or nothing more when the command already printed its outcome. The framework SHALL add no failure record of its own for it: no stack trace on stdout or stderr and no record in the log file. The exit code SHALL be the one the factory maps for the failure. An unclassified fault SHALL keep its WARN with the stack trace in the log file, and a failure raised before any command runs SHALL keep the framework's own report.
<!-- implements FR16, NFR-O1 of fix-operator-blockers -->

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
