# execution-environment — delta for fix-docker-exec-env-argv

<!-- Sequencing: this change lands BEFORE add-subprocess-access-log, whose delta also MODIFIES
     "Layered positive environment allowlist" with an argv sentence. After this change archives,
     that delta is re-layered over the text below (its own sentence becomes redundant and is
     dropped; its remaining edits survive). See delta-specs.md, overlapping MODIFIED. -->

## MODIFIED Requirements

### Requirement: Layered positive environment allowlist
The child environment of every `exec()` SHALL be composed of exactly three layers, with nothing inherited implicitly: (1) the adapter's base set — host: a fixed documented minimum (`PATH`, `HOME`, `TMPDIR`, locale variables, `TERM`, `USER`, `SHELL`; deliberately no agent sockets such as `SSH_AUTH_SOCK`); container: empty, the image's own `ENV` supplies the runtime environment; (2) operator-configured passthrough variables — exact names only, no patterns; values SHALL be read from the factory process environment at exec time, never stored in config; (3) factory-set protocol variables (the AI base-url/auth-token seam, findings/decision file paths). A passthrough name declared as a credential SHALL be a startup configuration error. The names (never the values) of the applied allowlist SHALL be logged at debug level per exec. The composed environment SHALL reach the child through environment channels only — never rendered as values into any spawn argv on the host: the host adapter fills the child's own process environment; the container adapter passes each entry as a value-less environment flag naming the variable and delivers the value through the docker client process's own environment, from which the docker client resolves it into the box. A name absent from the client's environment SHALL simply be unset in the box, as it is today when absent from the factory environment.
<!-- implements FR9 of add-sandbox-core -->
<!-- implements FR1, FR2, FR6, NFR-R1, NFR-S1 of fix-docker-exec-env-argv -->

#### Scenario: Host secrets never reach the box
- **WHEN** the factory process holds a tracker token and unrelated cloud keys in its environment
- **THEN** the environment observed inside `exec()` contains only the three allowlist layers, and the unrelated cloud keys are absent

#### Scenario: Typical host project needs no env configuration
- **WHEN** a host-bound command check runs with an empty passthrough list
- **THEN** its environment contains exactly the host base set plus factory-set protocol variables, and toolchains resolvable via `PATH` work without operator configuration

#### Scenario: Passthrough carries live values by name
- **WHEN** the operator lists `JAVA_HOME` in passthrough and its value in the factory's environment later changes
- **THEN** the next `exec()` child observes the current value with no config change

#### Scenario: No environment value on any spawn argv
- **WHEN** a container exec runs with passthrough entries and factory-set entries, including a credential-shaped AI-seam value, and the composed docker argv is inspected
- **THEN** every environment flag on the argv names a variable and carries no `=` and no value, the same names appear with their values in the docker client's environment for that exec, and the in-box child observes the same names and values it observed before this change

#### Scenario: Process table during a round shows names only
- **WHEN** an operator lists host processes while an agent round or judge vote runs in container mode
- **THEN** the docker process's command line shows the environment variable names and no value

## ADDED Requirements

### Requirement: Container exec launch is one value
The container adapter SHALL produce the argv and the docker client environment of one exec together, from one composed child environment, as a single launch value: the environment flags on the argv SHALL be derived from the keys of that environment and nothing else, and the docker start seam SHALL accept only the launch value — no argv can be started without the environment that completes it. The docker client's environment for an exec SHALL be the factory's inherited process environment with every name the run's child-environment allowlist declares as a credential removed, and the composed child environment laid on top; nothing else SHALL be removed, so the docker client keeps the home, path, docker-configuration, certificate and proxy variables its own setup depends on. An exec composed with an empty child environment SHALL carry no environment flag and lay nothing on top.
<!-- implements FR3, FR4, FR5, NFR-S2, NFR-S4 of fix-docker-exec-env-argv -->

#### Scenario: Names and values cannot diverge
- **WHEN** an exec launch is built from a composed child environment
- **THEN** the set of variable names on its argv equals the key set of the environment it delivers, for every composed map

#### Scenario: Declared credential is subtracted from the client
- **WHEN** the factory process exports a declared tracker credential and a passthrough variable, and an exec is launched whose composed environment carries the passthrough variable only
- **THEN** the docker client process for that exec observes the passthrough variable with its live value and does not observe the declared credential, while an unrelated variable of the factory's environment (such as the docker daemon address) is still present

#### Scenario: Environment-free exec stays flag-free
- **WHEN** the container file channel writes or reads a file, or the materializer creates the scratch directory
- **THEN** the exec argv contains no environment flag and the docker client's environment is the inherited environment minus declared credentials, with nothing laid on top

#### Scenario: The bare form is unconstructible
- **WHEN** production code under the sandbox tree is scanned
- **THEN** exactly one source spells the environment flag, the only sources constructing a process are the docker client seam and the host adapter, and the docker start seam exposes no entry taking a bare argv
