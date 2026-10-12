# operator-configuration

## Purpose

Operator configuration defines how the factory's `factory.*` keys are assembled from ordered sources (defaults, host file, project file, command line), where each key may legitimately be set, and how violations stop startup with one complete report. The target repository never supplies `factory.*` keys.

## Requirements

### Requirement: Configuration is assembled from four ordered sources
The factory's `factory.*` configuration SHALL be assembled from, in rising precedence: built-in defaults, the host file `GNOMISH_HOME/factory.yaml`, the resolved project's `project.yaml` (its `factory:` block), and the command line (including JVM system properties). Each configuration file is optional. Map-valued keys merge by key across sources; list-valued keys are replaced whole by the highest source that sets them. The target repository's `.gnomish/` files SHALL NOT be a source of `factory.*` keys.
<!-- implements FR5, NFR-P1 of add-project-registry -->

#### Scenario: Project overrides host
- **WHEN** `factory.yaml` sets `factory.git-network-timeout: 5m` and the project file sets `10m`
- **THEN** the effective value is `10m`

#### Scenario: Command line overrides the project for a permitted key
- **WHEN** the project file sets `factory.serve.slots: 2` and the command line passes `--factory.serve.slots=4`
- **THEN** the effective value is `4`

#### Scenario: Configuration assembly reads each file once
- **WHEN** any project-scoped command starts with a host file and three registered projects
- **THEN** the host file and each `project.yaml` are read exactly once, and no network call is made

### Requirement: Every key declares where it may be set
Every `factory.*` key SHALL declare exactly one level: `host` — the host file or the command line; `project` — the project file or the command line; `any` — every source; `sandbox-boundary` — the project file only. A key's level SHALL be declared once, beside the key's definition; a plugin-contributed subtree (`factory.check.<provider>`, `factory.connections.<name>`) takes the level declared for its root. The sandbox-boundary keys SHALL be `factory.bindings.*`, `factory.sandbox.image`, `factory.sandbox.egress-allowlist` and `factory.sandbox.env-passthrough`.
<!-- implements FR6, NFR-S1 of add-project-registry -->

#### Scenario: A boundary key in the host file
- **WHEN** `factory.yaml` sets `factory.sandbox.egress-allowlist`
- **THEN** startup stops with a violation naming the file and line and the project files the key belongs in

#### Scenario: A boundary key on the command line
- **WHEN** the operator passes `--factory.bindings.default=host`
- **THEN** startup stops with a violation saying the key is read only from `projects/<name>/project.yaml`

#### Scenario: A host key in a project file
- **WHEN** a project file sets `factory.docker-command-timeout`
- **THEN** startup stops with a violation naming `factory.yaml` or the command line as the places for it

### Requirement: Violations stop startup with one complete report
Before any tracker call, branch, worktree or box, the factory SHALL check the configuration sources and stop when it finds: a key set where its level forbids; a `factory.*` key no component defines (including removed keys such as `factory.agent-cli-env-passthrough`); an environment variable whose name starts with `FACTORY_`; or a configuration file writable by group or others. The report SHALL list every violation found in one run — never only the first — each with its location (file and line, or variable name), the key, the reason, and the concrete fix (which file to move it to, which command to run). The process SHALL exit with the usage-error exit code (2) and SHALL print the report once, without a stack trace.
<!-- implements FR7, FR12, NFR-S2, NFR-R1, NFR-O2, UX1 of add-project-registry -->

#### Scenario: Several violations reported together
- **WHEN** `factory.yaml` sets a boundary key and misspells another key, and `FACTORY_SERVE_SLOTS` is exported
- **THEN** one report lists all three, each with its location and fix, and the process exits before any side effect

#### Scenario: Environment variable is refused with the equivalent line
- **WHEN** `FACTORY_SERVE_SLOTS=4` is set in the environment
- **THEN** the violation suggests `factory.serve.slots: 4` in the project or host file, or `--factory.serve.slots=4`

#### Scenario: Group-writable project file
- **WHEN** `projects/widgets/project.yaml` is writable by its group
- **THEN** startup stops naming the file and the `chmod` that fixes it
