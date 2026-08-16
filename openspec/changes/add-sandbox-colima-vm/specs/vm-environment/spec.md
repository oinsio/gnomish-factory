# vm-environment

## ADDED Requirements

### Requirement: Colima VM adapter of the environment port
A `colima-vm` adapter SHALL implement the `TaskExecutionEnvironment`
port with one virtual machine per environment. `dispose()` SHALL destroy
the VM together with its host-side filter rules as one idempotent
operation; VM destruction is a mandatory protocol step of the
environment lifecycle, never deferred housekeeping.
<!-- implements FR1 of add-sandbox-colima-vm -->

#### Scenario: One environment, one VM
- **WHEN** an environment is materialized with the `colima-vm` binding
- **THEN** a dedicated factory-named VM exists for it, and dispose removes the VM and empties its filter rules

#### Scenario: Dispose is idempotent
- **WHEN** dispose is called twice, or after a partially failed teardown
- **THEN** the second call succeeds and neither a VM nor a filter rule of the environment remains

### Requirement: VM isolation invariants
The adapter SHALL create VMs with no host filesystem mounts, SSH agent
forwarding disabled, guest→host port forwarding disabled, DNS pointed
only at the guard, and the platform's hardware virtualization backend;
operator-configured CPU, memory, and disk limits SHALL apply per VM. A
VM whose effective configuration deviates — including a silent fallback
to a software backend — SHALL be refused and destroyed.
<!-- implements FR2 of add-sandbox-colima-vm -->

#### Scenario: Silent backend fallback is refused
- **WHEN** the VM starts on a software-emulation backend because hardware virtualization was unavailable
- **THEN** the adapter detects the mismatch, destroys the VM, and reports an infrastructure failure — no round executes

#### Scenario: Host home directory is not reachable
- **WHEN** a process inside the VM probes for host filesystem shares
- **THEN** no mount exposes any host path, and host-credential locations (`~/.ssh`-class) do not exist in the guest

### Requirement: Exec over multiplexed SSH
`exec` SHALL run commands inside the VM over SSH with streamed
stdout/stderr and exit codes delivered through the existing port
contract; connections SHALL be multiplexed so rounds and polling do not
pay per-call handshakes; connection parameters SHALL come from the VM
runtime and survive VM recreation. All sessions are host-initiated: the
guest SHALL hold no credential that reaches the host or any other
system. <!-- implements FR3 of add-sandbox-colima-vm -->

#### Scenario: Round runs unchanged above the port
- **WHEN** an agent round or command check executes in a VM environment
- **THEN** its consumers receive the same streamed output and exit-code contract as with the host and container adapters

### Requirement: Seeding without network or credentials
Materialization SHALL bring the repository into the VM using only the
factory's own host→guest transport, sourced from the factory's local
clone; the working clone inside the VM SHALL carry agent-only identity
with background maintenance disabled, and no remote address or
credential SHALL exist anywhere in the guest.
<!-- implements FR4 of add-sandbox-colima-vm -->

#### Scenario: The box cannot reach the real remote
- **WHEN** a process inside the VM inspects the working clone's configuration and environment
- **THEN** it finds no server address and no credential, and the only history present is what the factory seeded

### Requirement: Harvest and tip observation over SSH
Harvest SHALL fetch the task branch from the VM over SSH with the
factory-fixed refspec, fast-forward-only, without recursing submodules.
Branch-tip observation SHALL poll from the factory side under
factory-owned rate limiting; an event source inside the VM MAY wake the
factory early but SHALL NOT increase the fetch rate beyond the
factory's limit. <!-- implements FR5 of add-sandbox-colima-vm -->

#### Scenario: Rewritten history is refused at the boundary
- **WHEN** the branch history inside the VM has been rewritten and the factory harvests
- **THEN** the fast-forward-only fetch refuses the update and the anomaly surfaces as a failure, not a silent overwrite

#### Scenario: A hostile guest cannot command a fetch storm
- **WHEN** something inside the VM generates rapid tip-change signals
- **THEN** the factory's fetch frequency stays within its own rate limit

### Requirement: In-VM Docker under the egress policy
The VM SHALL run a Docker daemon usable by gnome processes without
socket passthrough from the host and without privileged containers on a
shared kernel. The daemon's egress SHALL go through the guard via
daemon-level proxy configuration, and image pulls SHALL go through the
pull-through registry mirror — the only non-proxy endpoint the filter
allows. <!-- implements FR7 of add-sandbox-colima-vm -->

#### Scenario: Testcontainers works with egress blocked
- **WHEN** a check inside the VM starts a Testcontainers-managed service while direct egress is blocked by the host filter
- **THEN** the image arrives via the mirror, the container starts, and the check completes

#### Scenario: Daemon cannot bypass the guard
- **WHEN** the in-VM daemon pulls from a registry not served by the mirror or proxy policy
- **THEN** the pull fails at the host filter and the denial is recorded

### Requirement: Adapter passport
The `colima-vm` passport SHALL declare: hardware task↔task boundary,
in-VM Docker supported, egress control enforced on the host, and its
cost profile (startup latency, disk per VM). Needs/passport
reconciliation SHALL route stage needs requiring in-box Docker to
adapters whose passport carries it.
<!-- implements FR9 of add-sandbox-colima-vm -->

#### Scenario: Docker-needing stage binds correctly
- **WHEN** a stage declares it needs Docker inside the box and the operator has bound it to `colima-vm`
- **THEN** reconciliation passes; bound to the container adapter instead, the factory refuses fail-closed naming the unmet need

### Requirement: VM provisioning
Provisioning SHALL prepare each VM before any gnome process: the factory
CA baked into every trust store the toolchain consults (system, JVM,
Node, Python), proxy configuration for proxy-ignoring tools (JVM/Gradle,
wrapper, Maven), and Docker daemon proxy and mirror configuration.
`.gnomish/setup.sh`, when the project declares one, SHALL run inside the
VM during provisioning under the same egress policy.
<!-- implements FR10 of add-sandbox-colima-vm -->

#### Scenario: JVM builds trust the guard
- **WHEN** a Gradle build inside the VM fetches a dependency through the guard with interception enabled
- **THEN** the JVM trust store already contains the factory CA and the build proceeds without trust errors

### Requirement: Orphan cleanup covers VMs and filter state
Factory startup SHALL find and destroy orphaned factory-named VMs and
stale filter rules left by crashed instances; cleanup SHALL be
idempotent and safe to run while other instances hold live
environments. <!-- implements FR11 of add-sandbox-colima-vm -->

#### Scenario: Crash leaves nothing permanent
- **WHEN** a factory instance dies mid-task and another instance starts
- **THEN** the orphaned VM and its filter rules are reclaimed, and the task itself remains resumable from its branch
