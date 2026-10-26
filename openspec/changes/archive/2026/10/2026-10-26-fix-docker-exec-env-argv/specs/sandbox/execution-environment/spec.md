# sandbox/execution-environment — delta for fix-docker-exec-env-argv

<!-- Sequencing: this change lands BEFORE add-subprocess-access-log, whose delta also MODIFIES
     "Layered positive environment allowlist" with an argv sentence. After this change archives,
     that delta is re-layered over the text below (its own sentence becomes redundant and is
     dropped; its remaining edits survive). See delta-specs.md, overlapping MODIFIED. -->

## MODIFIED Requirements

Layered on `Layered positive environment allowlist` as modified by `tighten-spec-contract`
(sequenced before this change). That delta cut the body to the three layers and moved the base
sets, exact names, the credential refusal and the debug line into scenarios, adding "The fixture
box inherits nothing from the test process" and the test-spawner scenario of
`make-checkpoint-gate-durable`; this text adds the environment-channel sentence to the body and
the scenarios "No environment value on any spawn argv", "Process table during a round shows
names only" and "An absent name stays unset in the box". `add-subprocess-access-log`, sequenced
after this change, MODIFIES the same requirement and carries these scenarios verbatim.

### Requirement: Layered positive environment allowlist
The child environment of every `exec()` SHALL be composed of exactly three layers, with nothing inherited implicitly: (1) the adapter's base set; (2) operator-configured passthrough variables, whose values are read from the factory process environment at exec time, never stored in config; (3) factory-set protocol variables (the AI base-url/auth-token seam, findings/decision file paths). It SHALL reach the child through environment channels only, never as a value on any spawn argv on the host.
**Implements**: FR9 of add-sandbox-core; FR22 of make-checkpoint-gate-durable; FR1, FR2, FR6, NFR-R1, NFR-S1 of fix-docker-exec-env-argv

#### Scenario: Host secrets never reach the box
- **WHEN** the factory process holds a tracker token and unrelated cloud keys in its environment
- **THEN** the environment observed inside `exec()` contains only the three allowlist layers, and the unrelated cloud keys are absent

#### Scenario: Typical host project needs no env configuration
- **WHEN** a host-bound command check runs with an empty passthrough list
- **THEN** its environment contains exactly the host base set plus factory-set protocol variables, and toolchains resolvable via `PATH` work without operator configuration

#### Scenario: Passthrough carries live values by name
- **WHEN** the operator lists `JAVA_HOME` in passthrough and its value in the factory's environment later changes
- **THEN** the next `exec()` child observes the current value with no config change

#### Scenario: The fixture box inherits nothing from the test process
- **WHEN** the test JVM's own environment carries a `GNOMISH_DECISION_FILE` naming a file, and a fake-agent round whose scenario carries a decision runs through the fixture box with no decision path set by the spec
- **THEN** the fake agent observes no such variable and the named file is left untouched

#### Scenario: The host base set is a fixed minimum without agent sockets
- **WHEN** the factory process environment carries `SSH_AUTH_SOCK` and a host-bound process is executed
- **THEN** the host base set it receives is the documented minimum (`PATH`, `HOME`, `TMPDIR`, locale variables, `TERM`, `USER`, `SHELL`), and `SSH_AUTH_SOCK` is absent

#### Scenario: The container base set is empty
- **WHEN** a process is executed in a container box with an empty passthrough list
- **THEN** the factory adds no base variables; the runtime environment comes from the image's own `ENV` plus the factory-set protocol variables

#### Scenario: Passthrough names are exact
- **WHEN** the operator lists a passthrough entry
- **THEN** it matches exactly one variable name; no pattern is expanded

#### Scenario: A credential passthrough name fails startup
- **WHEN** the operator lists a passthrough name declared as a credential
- **THEN** the factory refuses to start with a configuration error

#### Scenario: Applied allowlist names are logged without values
- **WHEN** a process is executed
- **THEN** the names of the applied allowlist are logged at debug level for that exec, and no value is logged

#### Scenario: Every test spawner composes over a cleared environment
- **WHEN** a test spawns a gnome-product process — through the fixture box that stands in for the host adapter, the fake-agent invocation, or the end-to-end harness
- **THEN** the child environment is composed over a cleared environment, through the production allowlist where one exists and from an explicit list otherwise, so the spawner is never laxer than the production owner and the child observes no `GNOMISH_*` variable the test itself did not set

#### Scenario: No environment value on any spawn argv
- **WHEN** a container exec runs with passthrough entries and factory-set entries, including a credential-shaped AI-seam value, and the composed docker argv is inspected
- **THEN** every environment flag on the argv names a variable and carries no `=` and no value, the same names appear with their values in the docker client's environment for that exec, and the in-box child observes the same names and values it observed before this change

#### Scenario: Process table during a round shows names only
- **WHEN** an operator lists host processes while an agent round or judge vote runs in container mode
- **THEN** the docker process's command line shows the environment variable names and no value

#### Scenario: An absent name stays unset in the box
- **WHEN** a container exec runs with a passthrough variable absent from the factory's environment
- **THEN** the variable is unset in the box and no error is raised

## ADDED Requirements

### Requirement: Container exec launch is one value
The container adapter SHALL produce the argv and the docker client environment of one exec together, from one composed child environment, as a single launch value, and the docker start seam SHALL accept only that value. The docker client's environment for an exec SHALL be the factory's inherited process environment with every name the run's child-environment allowlist declares as a credential removed, and the composed child environment laid on top.
**Implements**: FR3, FR4, FR5, NFR-S2, NFR-S4 of fix-docker-exec-env-argv
**Notes**: Only declared credentials are removed, so the docker client keeps the home, path, docker-configuration, certificate and proxy variables its own setup depends on.

#### Scenario: Names and values cannot diverge
- **WHEN** an exec launch is built from a composed child environment
- **THEN** the set of variable names on its argv equals the key set of the environment it delivers, for every composed map

#### Scenario: Declared credential is subtracted from the client
- **WHEN** the factory process exports a declared tracker credential and a passthrough variable, and an exec is launched whose composed environment carries the passthrough variable only
- **THEN** the docker client process for that exec observes the passthrough variable with its live value and does not observe the declared credential, while an unrelated variable of the factory's environment (such as the docker daemon address) is still present

#### Scenario: Environment-free exec stays flag-free
- **WHEN** the container file channel writes or reads a file
- **THEN** the exec argv contains no environment flag and the docker client's environment is the inherited environment minus declared credentials, with nothing laid on top

#### Scenario: Scratch directory creation carries no environment flag
- **WHEN** the materializer creates the scratch directory, a bounded management command
- **THEN** its exec argv contains no environment flag, and its docker client environment is the management commands' inherited environment, unchanged by this requirement

#### Scenario: The bare form is unconstructible
- **WHEN** production code under the sandbox tree is scanned
- **THEN** exactly one source spells the environment flag, the only sources constructing a process are the docker client seam and the host adapter, and the docker start seam exposes no entry taking a bare argv
