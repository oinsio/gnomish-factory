## REMOVED Requirements

### Requirement: The documented default-binding key binds
**Reason**: its "every property source form" clause and its command-line scenario contradict the sandbox-boundary level: `factory.bindings.default` is now read from the resolved project's own file only (FR6, NFR-S1 of add-project-registry). A MODIFIED block cannot drop the command-line scenario, so the requirement is replaced by "The default-binding key binds from the project file".
**Migration**: move `factory.bindings.default` from the command line or any other source into `GNOMISH_HOME/projects/<name>/project.yaml`; the startup violation report names the file and line.

## ADDED Requirements

### Requirement: The default-binding key binds from the project file
The operator's default stage binding SHALL be read from `factory.bindings.default` — the key the operator guides and the factory's own error messages name — set in the resolved project's `project.yaml`. It is a sandbox-boundary key of the operator-configuration capability: set in any other source it SHALL stop startup with the violation report, never bind silently. No other spelling of the key SHALL be required for the value to take effect.
<!-- implements FR6 of fix-operator-blockers -->
<!-- implements FR6, NFR-S1 of add-project-registry -->

#### Scenario: Project file default binds host
- **WHEN** the resolved project's `project.yaml` sets `factory.bindings.default: host` and no per-stage overrides
- **THEN** every stage resolves to the `host` binding and no container is created

#### Scenario: The error message's advice works as written
- **WHEN** startup refuses because the container binding needs `factory.sandbox.image`, and the operator follows the message by setting `factory.bindings.default: host` in the project file it names
- **THEN** the rerun starts in host mode

#### Scenario: Command-line default is refused
- **WHEN** the operator passes `--factory.bindings.default=host`
- **THEN** startup stops with a violation naming `projects/<name>/project.yaml` as the only place for the key, and no stage is bound
