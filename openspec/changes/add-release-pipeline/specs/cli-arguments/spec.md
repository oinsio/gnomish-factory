## MODIFIED Requirements

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
