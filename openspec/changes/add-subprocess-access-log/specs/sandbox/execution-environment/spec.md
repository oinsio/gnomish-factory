# sandbox/execution-environment — delta for add-subprocess-access-log

## MODIFIED Requirements

Layered on `All gnome-product processes go through the port` and `Layered positive environment
allowlist` as modified by `tighten-spec-contract` (sequenced before this change). That delta gives
the first block its `**Implements**:` line and changes nothing else; this text adds the
environment self-check probes to its body and the scenarios "An undeclared spawn site fails the
build", "Self-check probes cannot bypass the decorator chain" and "The sole-seam claim names its
two bypasses". That delta cut the second block's body to the three layers and moved its cases
into scenarios, adding "The fixture box inherits nothing from the test process" and the
test-spawner scenario of `make-checkpoint-gate-durable`. The second block is further layered
on `fix-docker-exec-env-argv` (sequenced before this change), whose argv rule is the same as
this change's: the block carries that change's text verbatim — the environment-channel sentence
and the scenarios "No environment value on any spawn argv", "Process table during a round shows
names only" and "An absent name stays unset in the box" — so this change's own argv sentence is
dropped (that change's proposal, "Queue effect") and only its traceability id is added. Both
blocks must survive the later sync.

### Requirement: All gnome-product processes go through the port
Agent-CLI rounds, CLI judge votes, command checks, and environment self-check probes SHALL execute exclusively via `exec()` of the bound task environment; no factory code path may spawn a process over a working copy directly.
**Implements**: FR4 of add-sandbox-core; FR12, FR13 of add-subprocess-access-log

#### Scenario: Command check runs in the box
- **WHEN** a stage's command check executes in container mode
- **THEN** the process runs inside the task container against the volume working copy, and its exit code and output tail reach the engine unchanged

#### Scenario: An undeclared spawn site fails the build
- **WHEN** a production class outside the enumerated spawn sites references
  the process-launch API
- **THEN** the spawn-boundary gate fails the build naming the offending
  class

#### Scenario: Self-check probes cannot bypass the decorator chain
- **WHEN** a new environment runs its self-check probes
- **THEN** the probes execute through the decorated exec seam, and behavior
  added by an exec-seam decorator applies to them, so no consumer of the port
  holds a raw, decorator-bypassing reference

#### Scenario: The sole-seam claim names its two bypasses
- **WHEN** the port's sole-seam claim is stated in its documentation
- **THEN** it names its two disclosed factory-side bypasses: the container file channel, whose
  `putFile`/`readFile` run their own docker executions outside `exec()`, and the git `ext::`
  harvest transport, whose docker process git forks as a grandchild of the supervised factory
  git invocation, killed and reaped through that tree

### Requirement: Layered positive environment allowlist
The child environment of every `exec()` SHALL be composed of exactly three layers, with nothing inherited implicitly: (1) the adapter's base set; (2) operator-configured passthrough variables, whose values are read from the factory process environment at exec time, never stored in config; (3) factory-set protocol variables (the AI base-url/auth-token seam, findings/decision file paths). It SHALL reach the child through environment channels only, never as a value on any spawn argv on the host.
**Implements**: FR9 of add-sandbox-core; FR22 of make-checkpoint-gate-durable; FR1, FR2, FR6, NFR-R1, NFR-S1 of fix-docker-exec-env-argv; FR14 of add-subprocess-access-log

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
- **WHEN** a container exec's composed environment names a passthrough variable absent from the docker client's environment
- **THEN** the variable is unset in the box, as it is when the name is absent from the factory environment, and no error is raised
