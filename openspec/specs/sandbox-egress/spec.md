# sandbox-egress

## Purpose

The sandbox egress guard is the sole route out of a task container: an internal-only network makes direct connections to the internet or the host impossible by construction, and the guard proxy enforces a default-deny allowlist owned by operator config, resolving DNS itself so no direct name resolution channel exists. Every materialized sandboxed environment passes a mandatory fail-closed self-check before its first gnome-product process, catching silent isolation degradation before any round can run. Denials are captured as structured, body-free findings readable back from the guard, and the reference sandbox image bakes in the CA certificate and proxy configuration tools need to cooperate with the guard even when they ignore proxy environment variables.

## Requirements

### Requirement: The guard is the only route out
A task container SHALL be attached only to its internal-only network; the guard proxy SHALL be the single reachable egress point. Direct connections from the box to the internet or to the host SHALL be impossible by construction, not by convention.
<!-- implements FR7 of add-sandbox-core -->

#### Scenario: Ignoring proxy settings goes nowhere
- **WHEN** a process inside the box strips `HTTP_PROXY` variables and dials an external address directly
- **THEN** the connection fails because no route exists

### Requirement: Default-deny allowlist owned by the operator
The guard SHALL deny every destination not present in the allowlist from factory installation config. The allowlist SHALL live outside the box and outside the target repo; a repo may request additions, only the operator grants them.
<!-- implements FR7 of add-sandbox-core -->

#### Scenario: Non-allowlisted host is denied
- **WHEN** a process requests a host absent from the allowlist via the guard
- **THEN** the guard refuses the connection and records a structured denial event

### Requirement: DNS resolves only through the guard
The box SHALL have no direct DNS access; name resolution SHALL happen at the guard. Open port-53 egress is a known exfiltration channel and SHALL NOT exist.
<!-- implements FR7 of add-sandbox-core -->

#### Scenario: Direct DNS query fails
- **WHEN** a process inside the box queries an external DNS server directly
- **THEN** the query gets no answer

### Requirement: Mandatory fail-closed self-check
Before the first gnome-product process in every materialized sandboxed environment — round environments and fresh-box verification/judge environments alike; the probes themselves run via `exec()` — the factory SHALL verify from inside the box: the in-box process user is non-root (the channel-write and commit identity D16 depends on), direct egress fails, a non-allowlisted destination is denied, an allowlisted destination succeeds, and the isolation mechanism in effect matches the adapter passport. Any failed probe SHALL be an infrastructure failure: the environment is rejected and no gnome-product process executes in it — at task start the task does not start; at verification time the affected check or judge vote classifies as an infrastructure failure.
<!-- implements FR8 of add-sandbox-core -->

#### Scenario: Silent protection degradation is caught
- **WHEN** the internal network was created without the internal flag and direct egress unexpectedly succeeds
- **THEN** the self-check fails, the environment is disposed, and the task reports an infrastructure failure — no round executes

#### Scenario: A root-running image is refused
- **WHEN** the sandbox image's default user is root (`id -u` is `0`), so channel writes and the in-box snapshot commit would run as root against root-owned cage surfaces (D16)
- **THEN** the self-check's non-root probe fails naming the probe, the environment is rejected, and no gnome-product process executes in it

#### Scenario: Fresh judge environment is self-checked too
- **WHEN** a fresh environment is materialized from the attempt commit for judge votes or a `verify-in: fresh-box` check and a self-check probe fails
- **THEN** no vote or check process executes in it, and the check classifies as an infrastructure failure — retried per existing policy, no stage attempt burned

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. Surfacing these denial findings in the task report is deferred to `add-sandbox-hardening`, which builds the verdict-independent findings slot its own NFR-O1 already requires — see `add-sandbox-core` Q6; this change covers capture and read-back only.
<!-- implements NFR-O1 of add-sandbox-core -->

#### Scenario: A denied request is captured as a structured finding
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round
- **THEN** the guard records a structured denial finding carrying the denied host, path, and method (never the request body), readable back from the guard

### Requirement: Guard outage is an infrastructure failure
When the guard is unreachable or down, in-flight checks SHALL classify as infrastructure failures (retried per existing policy, no stage attempt burned), and the factory SHALL restart the guard.
<!-- implements NFR-R1 of add-sandbox-core -->

#### Scenario: Guard crash burns no attempt
- **WHEN** the guard dies while a stage round is in progress
- **THEN** the round fails as an infrastructure failure, the attempt counter is unchanged, and the guard is restarted before retry

### Requirement: Image carries the egress plumbing
The reference image recipe SHALL bake in everything egress needs at build time: the factory CA certificate (seam for later TLS interception), proxy settings for tools that ignore proxy env vars (JVM: `gradle.properties`, `GRADLE_OPTS`, `settings.xml`), and registry endpoints as build parameters rather than hardcoded values.
<!-- implements FR7, UX4 of add-sandbox-core -->

#### Scenario: Gradle build flows through the guard
- **WHEN** a Gradle build inside the box resolves dependencies
- **THEN** the traffic goes through the guard to allowlisted registries despite JVM ignoring proxy environment variables
