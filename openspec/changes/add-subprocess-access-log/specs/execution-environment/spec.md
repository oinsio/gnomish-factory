# execution-environment — delta for add-subprocess-access-log

## MODIFIED Requirements

### Requirement: All gnome-product processes go through the port
Agent-CLI rounds, CLI judge votes, command checks, and environment
self-check probes SHALL execute exclusively via `exec()` of the bound task
environment; no factory code path may spawn a process over a working copy
directly. The port's sole-seam claim SHALL be stated honestly, naming its
two disclosed factory-side bypasses: the container file channel, whose
`putFile`/`readFile` run their own docker executions outside `exec()`, and
the git `ext::` harvest transport, whose docker process is forked by git as
a grandchild of the supervised factory git invocation (killed and reaped
through that tree). The claim SHALL be enforced mechanically: a build gate
enumerates the allowed spawn sites, and any other production reference to
the process-launch API fails the build. The environment self-check SHALL
exec through the same decorated seam as every other port caller, so no
consumer of the port holds a raw, decorator-bypassing reference.
<!-- implements FR4 of add-sandbox-core -->
<!-- implements FR12, FR13 of add-subprocess-access-log -->

#### Scenario: Command check runs in the box
- **WHEN** a stage's command check executes in container mode
- **THEN** the process runs inside the task container against the volume
  working copy, and its exit code and output tail reach the engine unchanged

#### Scenario: An undeclared spawn site fails the build
- **WHEN** a production class outside the enumerated spawn sites references
  the process-launch API
- **THEN** the spawn-boundary gate fails the build naming the offending
  class

#### Scenario: Self-check probes cannot bypass the decorator chain
- **WHEN** a new environment runs its self-check probes
- **THEN** the probes execute through the decorated exec seam, and behavior
  added by an exec-seam decorator applies to them

### Requirement: Layered positive environment allowlist
The child environment of every `exec()` SHALL be composed of exactly three layers, with nothing inherited implicitly: (1) the adapter's base set — host: a fixed documented minimum (`PATH`, `HOME`, `TMPDIR`, locale variables, `TERM`, `USER`, `SHELL`; deliberately no agent sockets such as `SSH_AUTH_SOCK`); container: empty, the image's own `ENV` supplies the runtime environment; (2) operator-configured passthrough variables — exact names only, no patterns; values SHALL be read from the factory process environment at exec time, never stored in config; (3) factory-set protocol variables (the AI base-url/auth-token seam, findings/decision file paths). A passthrough name declared as a credential SHALL be a startup configuration error. The names (never the values) of the applied allowlist SHALL be logged at debug level per exec. The composed environment SHALL reach the child through environment channels only — never rendered as values into any spawn argv: the container adapter passes env entries as value-less flags whose values are delivered through the docker client's own process environment, so the wrapper argv is secret-free at the source and observable process argv on the host carries no environment value in either mode.
<!-- implements FR9 of add-sandbox-core -->
<!-- implements FR14 of add-subprocess-access-log -->

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
- **WHEN** a container exec runs with factory-set environment entries and
  the composed docker argv is inspected
- **THEN** env entries appear as value-less flags only, the values reach the
  in-box child through the docker client's environment, and no environment
  value is observable in any process argv on the host
