## MODIFIED Requirements

### Requirement: Violations stop startup with one complete report
Before any tracker call, branch, worktree or box, the factory SHALL check the configuration sources and stop when it finds: a key set where its level forbids; a `factory.*` key no component defines (including removed keys such as `factory.agent-cli-env-passthrough`); an environment variable whose name starts with `FACTORY_`; or a configuration file writable by group or others. The report SHALL list every violation found in one run — never only the first — each with its location (file and line, or variable name), the key, the reason, and the concrete fix (which file to move it to, which command to run). `gnomish doctor` SHALL NOT stop on violations: it SHALL list the same violations as check results.
<!-- implements FR7, FR12, NFR-S2, NFR-R1, NFR-O2, UX1 of add-project-registry -->
<!-- implements FR7 of add-doctor-command -->

#### Scenario: Several violations reported together
- **WHEN** `factory.yaml` sets a boundary key and misspells another key, and `FACTORY_SERVE_SLOTS` is exported
- **THEN** one report lists all three, each with its location and fix, and the process exits before any side effect

#### Scenario: Environment variable is refused with the equivalent line
- **WHEN** `FACTORY_SERVE_SLOTS=4` is set in the environment
- **THEN** the violation suggests `factory.serve.slots: 4` in the project or host file, or `--factory.serve.slots=4`

#### Scenario: Group-writable project file
- **WHEN** `projects/widgets/project.yaml` is writable by its group
- **THEN** startup stops naming the file and the `chmod` that fixes it

#### Scenario: Doctor lists violations instead of stopping
- **WHEN** `gnomish doctor` runs with a boundary key in `factory.yaml`
- **THEN** the violation appears as a `fail` line with its location and fix, and the remaining checks still run
