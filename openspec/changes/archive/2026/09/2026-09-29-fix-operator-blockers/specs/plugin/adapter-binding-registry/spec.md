## ADDED Requirements

### Requirement: The documented default-binding key binds
The operator's default stage binding SHALL be read from `factory.bindings.default` — the key the operator guides and the factory's own error messages name — in every property source form the factory reads (command line, configuration file, system property). No other spelling of the key SHALL be required for the value to take effect.
<!-- implements FR6 of fix-operator-blockers -->

#### Scenario: Command-line default binds host
- **WHEN** the factory starts with `--factory.bindings.default=host` and no per-stage overrides
- **THEN** every stage resolves to the `host` binding and no container is created

#### Scenario: The error message's advice works as written
- **WHEN** startup refuses because the container binding needs `factory.sandbox.image`, and the operator reruns with the `factory.bindings.default=host` the message suggests
- **THEN** the rerun starts in host mode
