## MODIFIED Requirements

Layered on `Violations stop startup with one complete report` as modified by
`tighten-spec-contract` (sequenced before this change): that delta cut the body to the startup
check and moved the report's content and exit into the scenarios "A violation names its
location, key, reason and fix" and "The report exits with code 2 and no stack trace"; this text
adds the doctor's non-stopping listing as the scenario "Doctor lists violations instead of
stopping". All must survive the later sync.

### Requirement: Violations stop startup with one complete report
Before any tracker call, branch, worktree or box, the factory SHALL check the configuration sources and stop when it finds: a key set where its level forbids; a `factory.*` key no component defines (including removed keys such as `factory.agent-cli-env-passthrough`); an environment variable whose name starts with `FACTORY_`; or a configuration file writable by group or others.
**Implements**: FR7, FR12, NFR-S2, NFR-R1, NFR-O2, UX1 of add-project-registry; FR7 of add-doctor-command
**Notes**: `gnomish doctor` is the one command that does not stop on violations; it lists them as check results.

#### Scenario: Several violations reported together
- **WHEN** `factory.yaml` sets a boundary key and misspells another key, and `FACTORY_SERVE_SLOTS` is exported
- **THEN** one report lists all three, each with its location and fix, and the process exits before any side effect

#### Scenario: Environment variable is refused with the equivalent line
- **WHEN** `FACTORY_SERVE_SLOTS=4` is set in the environment
- **THEN** the violation suggests `factory.serve.slots: 4` in the project or host file, or `--factory.serve.slots=4`

#### Scenario: Group-writable project file
- **WHEN** `projects/widgets/project.yaml` is writable by its group
- **THEN** startup stops naming the file and the `chmod` that fixes it

#### Scenario: A violation names its location, key, reason and fix
- **WHEN** a project file sets a host key
- **THEN** the violation names the file and line, the key, the reason it is not allowed there, and the places to move it to

#### Scenario: The report exits with code 2 and no stack trace
- **WHEN** startup stops on configuration violations
- **THEN** the report is printed once, without a stack trace, and the process exits with the usage-error exit code 2

#### Scenario: Doctor lists violations instead of stopping
- **WHEN** `gnomish doctor` runs with a boundary key in `factory.yaml`
- **THEN** the violation appears as a `fail` line with its location and fix, and the remaining checks still run
