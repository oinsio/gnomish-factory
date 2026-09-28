## ADDED Requirements

### Requirement: Unknown options are usage errors
Every `gnomish` subcommand SHALL reject a `--`-prefixed option it does not accept with a usage error that names the option, names the subcommand, and lists the options it accepts; where the rejected option matches a positional argument of the subcommand, the message SHALL say so. Options whose name contains a dot (Spring properties such as `--factory.*`, `--spring.*`, `--logging.*`) and Spring Boot's own `--debug` and `--trace` switches SHALL pass through to configuration and are not checked by this rule. The check SHALL run before the subcommand claims a task, creates a branch or worktree, or starts an environment.
<!-- implements FR8, NFR-R1, NFR-O1, UX2 of fix-operator-blockers -->

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
