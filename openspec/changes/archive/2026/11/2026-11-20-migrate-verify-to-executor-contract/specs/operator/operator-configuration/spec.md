# operator/operator-configuration — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: Every key declares where it may be set
Every `factory.*` key SHALL declare exactly one level: `host` — the host file or the command line; `project` — the project file or the command line; `any` — every source; `sandbox-boundary` — the project file only. The sandbox-boundary keys SHALL be `factory.bindings.*`, `factory.sandbox.image`, `factory.egress.sandbox` and `factory.sandbox.env-passthrough`; `factory.egress.in-process` SHALL be a `project` key.
**Implements**: FR6, NFR-S1 of add-project-registry; FR17 of migrate-verify-to-executor-contract

#### Scenario: A boundary key in the host file
- **WHEN** `factory.yaml` sets `factory.egress.sandbox`
- **THEN** startup stops with a violation naming the file and line and the project files the key belongs in

#### Scenario: A boundary key on the command line
- **WHEN** the operator passes `--factory.bindings.default=host`
- **THEN** startup stops with a violation saying the key is read only from `projects/<name>/project.yaml`

#### Scenario: A host key in a project file
- **WHEN** a project file sets `factory.docker-command-timeout`
- **THEN** startup stops with a violation naming `factory.yaml` or the command line as the places for it

#### Scenario: A key's level is declared beside its definition
- **WHEN** a `factory.*` key is defined
- **THEN** its level is read from the key's own definition, declared nowhere else, and a definition carrying no level is refused naming the key

#### Scenario: A plugin subtree takes its root's level
- **WHEN** a key below a plugin-contributed subtree such as `factory.check.<provider>` or `factory.connections.<name>` is set
- **THEN** the key is checked against the level declared for that subtree's root

## ADDED Requirements

### Requirement: The operator egress block replaces the two allowlist keys
Operator configuration SHALL carry one egress block, `factory.egress`, with the scopes `sandbox`
(what a box may reach) and `in-process` (what the factory process may reach beyond the hosts its
connections grant), each a list in the one egress entry grammar. `factory.sandbox.egress-allowlist`
and `factory.check.http.allowlist` SHALL be removed keys, reported with the scope that replaces
each; `gnomish project show` SHALL print the block like any other key.
**Implements**: FR17 of migrate-verify-to-executor-contract
**Notes**: Design D16 as amended 2026-11-09; the consolidation follows GitLab's single outbound-request section and Gitea's migration onto one `ALLOWED_HOST_LIST`.

#### Scenario: The two scopes are read from the block
- **WHEN** a project file sets `factory.egress.sandbox` and `factory.egress.in-process`
- **THEN** the box guard admits the first list and the in-process guard the second, and neither list reaches the other guard

#### Scenario: A removed key names its scope
- **WHEN** a project file sets `factory.sandbox.egress-allowlist`
- **THEN** startup stops with a violation naming the line and `factory.egress.sandbox` as the key to move the list to
