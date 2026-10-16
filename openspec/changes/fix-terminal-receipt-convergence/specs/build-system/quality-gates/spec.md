# Spec Delta: build-system/quality-gates

## MODIFIED Requirements

Layered on "Scenario-to-test link" as introduced by `tighten-spec-contract` (archived
2026-10-14). This text lets a marker name a scenario of an ADDED or MODIFIED requirement in the
delta of an unarchived change, and retires the scenarios of a main-spec requirement that an
unarchived delta REMOVES: they need no link. Retirement only relaxes the gate: a marker or an
exemption line naming a retired scenario is accepted as before, and what is forgotten is
reported by the archive's own gate run, once the scenario has left the main spec. It refines the
scenarios "A scenario with no marker fails the gate" and "A dangling marker fails the gate"
accordingly, and adds "A marker naming an unarchived delta's scenario is not dangling", "A
marker naming a removed or archived scenario is dangling", "A scenario of a requirement removed
by an unarchived delta need not be linked" and "A marker or exemption naming a retired scenario
is accepted". Scenarios of unarchived deltas still need no link: only main-spec scenarios that
no unarchived delta retires must be linked.

### Requirement: Scenario-to-test link
For every scenario of every requirement of a main spec, except a requirement that the delta of
an unarchived change REMOVES, at least one test source SHALL contain the marker
`// Scenario: <spec-id> / <title>` with the title verbatim, and every such marker in the test
sources SHALL name a scenario that exists under a main-spec requirement or under an ADDED or
MODIFIED requirement of the delta of an unarchived change. A scenario verified by a mechanism
that is not a test SHALL be listed in the gate's exemption list with that mechanism, and the
list is asserted reached: an entry naming no scenario of a main spec fails the gate.
**Implements**: FR5 of tighten-spec-contract; FR6 of fix-terminal-receipt-convergence
**Notes**: `<spec-id>` is the capability path OpenSpec lists (`quality-gates`, `verification/verification-hardening`); the title is the text after `#### Scenario:` with surrounding whitespace trimmed. Test sources are every module's `src/test` (`*/src/test`, `*/*/src/test`), the test trees of the `build-checks` included build (`build-checks/*/src/test`) and `build-logic/src/functionalTest`.
**Notes**: A marker written with its test resolves through the change's delta until the archive merges the scenario into the main spec; the archive's own gate run then resolves it there, with no edit to the marker.
**Notes**: A REMOVED requirement retires the main-spec requirement of the same title in the same capability path (`delta-specs.md`). Retirement only waives the link: a marker or exemption line that still names a retired scenario is neither dangling nor stale until the archive moves the scenario out of the main spec, when the archive's own gate run reports it. A delta that removes a requirement whose tests still run (a change proposed but not yet implemented) therefore turns nothing red.

#### Scenario: A scenario with no marker fails the gate
- **WHEN** a main spec holds a scenario, under a requirement no unarchived delta removes, whose title appears in no marker of any test source and in no exemption entry
- **THEN** `checkSpecContract` fails, naming the spec id, the title and the marker to write above the feature that exercises it

#### Scenario: A dangling marker fails the gate
- **WHEN** a test source carries a marker whose spec id or title names no scenario of a main spec and no scenario of an ADDED or MODIFIED requirement in the delta of an unarchived change
- **THEN** `checkSpecContract` fails, naming the test file and line and the nearest existing title

#### Scenario: A marker naming an unarchived delta's scenario is not dangling
- **WHEN** a test source carries a marker whose spec id and title name a scenario that exists only under an ADDED or MODIFIED requirement in the delta of an unarchived change
- **THEN** `checkSpecContract` reports nothing about the marker, with or without `--changes`

#### Scenario: A marker naming a removed or archived scenario is dangling
- **WHEN** a test source carries a marker whose title exists only under a REMOVED requirement of an unarchived delta, or only in a delta under `openspec/changes/archive/`
- **THEN** `checkSpecContract` fails, reporting the marker as dangling

#### Scenario: A malformed marker fails the gate
- **WHEN** a test source carries a `// Scenario:` line that is not in the `<spec-id> / <title>` form
- **THEN** `checkSpecContract` fails, naming the test file and line and the form to write

#### Scenario: A renamed scenario breaks the link on both sides
- **WHEN** a scenario title is changed in the main spec and its marker is not
- **THEN** `checkSpecContract` reports the new title as unlinked and the old marker as dangling in the same run

#### Scenario: An exempt scenario passes with its reason
- **WHEN** a scenario is verified by a CI workflow and the exemption list names the scenario and that workflow
- **THEN** `checkSpecContract` reports nothing about it

#### Scenario: A stale exemption fails the gate
- **WHEN** the exemption list names a scenario that no main spec holds
- **THEN** `checkSpecContract` fails, naming the entry to remove

#### Scenario: A scenario of a requirement removed by an unarchived delta need not be linked
- **WHEN** a main spec holds a scenario with no marker and no exemption entry, and the delta of an unarchived change removes its requirement by title
- **THEN** `checkSpecContract` reports nothing about the scenario, with or without `--changes`
- **AND** a delta under `openspec/changes/archive/` that removes a requirement of the same title retires nothing

#### Scenario: A marker or exemption naming a retired scenario is accepted
- **WHEN** a test source carries a marker, or the exemption list carries an entry, naming a scenario that a main spec holds only under a requirement an unarchived delta removes
- **THEN** `checkSpecContract` reports nothing about the marker or the entry, with or without `--changes`

#### Scenario: Scenarios of unarchived deltas are not linked
- **WHEN** a delta of an unarchived change holds a scenario with no marker anywhere
- **THEN** `checkSpecContract` reports nothing about it, with or without `--changes`


Amended by design D14 of fix-terminal-receipt-convergence: the Docker client configuration
variables are replaced by the build's own (the ADDED requirement "Forked test JVMs use the
build's own Docker client configuration"), so "Only the factory's own namespace is removed" now
excepts `DOCKER_CONFIG` and `DOCKER_CONTEXT`; every other scenario is unchanged.

### Requirement: Forked test JVMs carry no operator-process variables
The test build SHALL remove every `GNOMISH_*` environment variable it did not set itself from the environment of every forked test JVM — each `Test` task and the `pitest` task, whose minions inherit that JVM's environment. Apart from the Docker client configuration, which the test build replaces with its own, the strip SHALL leave every other inherited variable as it is.
**Implements**: FR22 of make-checkpoint-gate-durable; FR13 of fix-terminal-receipt-convergence
**Notes**: A test thereby observes only the factory variables its own spawner sets on a child, never those of the process that launched the build. The strip is subtractive because the test JVM legitimately depends on the machine's tooling variables; its owner is applied beside the pinned test git configuration.

#### Scenario: A variable of the launching process does not reach the test JVM
- **WHEN** a build is launched from a process whose environment holds `GNOMISH_DECISION_FILE` and runs a module's `test` task
- **THEN** `System.getenv()` inside the forked test JVM holds no `GNOMISH_*` key

#### Scenario: PIT minions are stripped the same way
- **WHEN** the same build runs a module's `pitest` task
- **THEN** no mutation minion observes the variable

#### Scenario: The machine's tooling variables survive
- **WHEN** the launching process holds `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED`
- **THEN** the forked test JVM still observes both

#### Scenario: Only the factory's own namespace is removed
- **WHEN** the launching process holds variables outside the `GNOMISH_` namespace — the machine's Gradle and Testcontainers variables and `DOCKER_HOST` among them — other than `DOCKER_CONFIG` and `DOCKER_CONTEXT`
- **THEN** the forked test JVM observes each of them unchanged

#### Scenario: A variable the build sets itself survives
- **WHEN** a build script sets a `GNOMISH_*` variable on a test task on purpose
- **THEN** the forked test JVM observes that variable

#### Scenario: Every module gets the strip from its test conventions
- **WHEN** a module applies the shared test conventions plugin
- **THEN** its `Test` tasks and its `pitest` task strip the variables through the one build-logic owner, with no configuration in the module's own build file

## ADDED Requirements

### Requirement: Forked test JVMs use the build's own Docker client configuration
Every forked test JVM — each `Test` task and the `pitest` task, whose minions inherit its environment — SHALL run with `DOCKER_CONFIG` naming a per-task copy of one committed Docker client configuration that names no credential helper, no plugin hooks and no proxies, and with the operator's daemon endpoint handed on as `DOCKER_HOST`.
**Implements**: FR13, FR14, FR15, NFR-C3, UX4 of fix-terminal-receipt-convergence
**Notes**: The endpoint is the operator's `DOCKER_HOST` when set, else the current context's endpoint from `docker context inspect`; on a macOS host with a `unix://` endpoint `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` is `/var/run/docker.sock` unless the operator set it.

#### Scenario: The operator's credential helper is not consulted
- **WHEN** the operator's Docker configuration names a `credsStore` and the build runs a module's `test` task
- **THEN** the test JVM's `DOCKER_CONFIG` names a configuration with no `credsStore`, `credHelpers`, `proxies` or plugin hooks, and the operator's configuration directory is not it

#### Scenario: PIT minions run under the same configuration
- **WHEN** the same build runs a module's `pitest` task
- **THEN** no mutation minion observes the operator's `DOCKER_CONFIG`

#### Scenario: An operator's DOCKER_HOST is handed on unchanged
- **WHEN** the launching process holds `DOCKER_HOST`
- **THEN** the test JVM observes the same value and the build runs no `docker` process to resolve it

#### Scenario: Without DOCKER_HOST the current context's endpoint is handed on
- **WHEN** the launching process holds no `DOCKER_HOST` and the operator's current context names an endpoint
- **THEN** the test JVM observes that endpoint as `DOCKER_HOST` and observes no `DOCKER_CONTEXT`

#### Scenario: On macOS Testcontainers mounts the daemon's own socket path
- **WHEN** the host is macOS, the endpoint is a `unix://` socket and the operator set no `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
- **THEN** the test JVM observes `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` as `/var/run/docker.sock`

#### Scenario: On Linux no socket override is set
- **WHEN** the host is Linux and the operator set no `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
- **THEN** the test JVM observes no `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`

#### Scenario: The operator's CLI plugins are still found
- **WHEN** the operator's Docker configuration directory holds a `cli-plugins` directory
- **THEN** the per-task copy's `cliPluginsExtraDirs` names it

#### Scenario: An endpoint the build cannot hand on fails with the remedy
- **WHEN** the operator set no `DOCKER_HOST` and the current context's endpoint is `ssh://` or carries TLS material
- **THEN** the build fails naming `DOCKER_HOST`, `DOCKER_CERT_PATH` and `DOCKER_TLS_VERIFY`

#### Scenario: A machine without Docker still builds
- **WHEN** no `docker` binary is on the path or the current context cannot be inspected
- **THEN** the build sets no `DOCKER_HOST`, the test task runs, and the Docker specs skip through their availability checks

#### Scenario: Machine-specific values do not split the cache
- **WHEN** two machines differ only in their Docker endpoint and plugin directory
- **THEN** a test task's cache key is the same on both

#### Scenario: Every module gets the Docker configuration from its test conventions
- **WHEN** a module applies the shared test conventions plugin
- **THEN** its `Test` tasks and its `pitest` task carry the configuration through the one build-logic owner, with no configuration in the module's own build file
