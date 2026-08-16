# sandbox-egress (delta)

## ADDED Requirements

### Requirement: TLS interception mode
The guard SHALL support a TLS interception mode using the factory CA
already baked into images, switchable per operator config without an
image rebuild or proxy tool swap. Per-host passthrough exceptions SHALL
exist for certificate-pinned tools. Interception SHALL NOT buffer
streaming (SSE) responses.
<!-- implements FR8 of add-sandbox-hardening -->

#### Scenario: Interception is a mode switch
- **WHEN** the operator enables interception on an existing installation
- **THEN** boxes built from change-A images trust the intercepted connections without rebuild, and pinned hosts on the exception list keep working via passthrough

#### Scenario: Model streaming stays live
- **WHEN** an intercepted host streams a response
- **THEN** the box receives it incrementally, not after buffering completes

### Requirement: Foreign credentials are stripped, factory credentials injected
In interception mode the guard SHALL strip any auth credential not
issued by the factory from requests to policy-designated hosts and MAY
inject the factory-owned credential outside the box, so the box holds
only a sentinel. Stripped headers SHALL be recorded as findings.
Injected credentials SHALL never appear in guard logs.
<!-- implements FR9, NFR-S2 of add-sandbox-hardening -->

#### Scenario: Foreign account door is closed
- **WHEN** an injected prompt makes the gnome attach a foreign auth token to a request toward an allowlisted host
- **THEN** the upstream receives only the factory credential and the stripped header appears as a finding in the task report

### Requirement: L7 rules narrow allowed hosts
The guard SHALL support per-host L7 rules (path prefixes and methods)
for allowlisted hosts in interception mode; rules are operator-owned
config, and requests outside the rules SHALL be denied and recorded
like allowlist denials.
<!-- implements FR10 of add-sandbox-hardening -->

#### Scenario: Allowed host, disallowed path
- **WHEN** a process requests an allowlisted host with a path outside the operator's rules (e.g., a write method where only reads are permitted)
- **THEN** the guard denies the request and records a structured denial event

## MODIFIED Requirements

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path,
method — never request bodies), readable as findings from the guard, AND
attached to the task report through a verdict-independent findings slot,
so a blocked attempt is a visible signal regardless of the round verdict.
Folding a denial into a check `Verdict.Fail` (which would flip the stage
outcome) SHALL NOT be the attachment mechanism — NFR-O1 is observability,
not a gate.
<!-- implements NFR-O1 of add-sandbox-core; NFR-O1 of add-sandbox-hardening -->

#### Scenario: A denied request is captured as a structured finding
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round
- **THEN** the guard records a structured denial finding carrying the denied host, path, and method (never the request body), readable back from the guard

#### Scenario: Denied exfiltration attempt reaches the report
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round and the round's checks otherwise pass
- **THEN** the task report contains a finding with the denied host and path, and the passing round verdict is unchanged

### Requirement: Mandatory fail-closed self-check
Before the first gnome-product process in every materialized sandboxed
environment — round environments and fresh-box verification/judge
environments alike; the probes themselves run via `exec()` — the factory
SHALL verify from inside the box: direct egress fails, a non-allowlisted
destination is denied, an allowlisted destination succeeds, and the
isolation mechanism in effect matches the adapter passport. When the
respective layers are enabled, the self-check SHALL additionally verify:
the gateway is reachable and the virtual key is valid; interception is
active on intercepted hosts and a foreign auth header does not survive
to the upstream; a disallowed server-side tool is stripped from a probe
request. Any failed probe SHALL be an infrastructure failure: the
environment is rejected and no gnome-product process executes in it —
at task start the task does not start; at verification time the
affected check or judge vote classifies as an infrastructure failure.
<!-- implements FR8 of add-sandbox-core; FR11 of add-sandbox-hardening -->

#### Scenario: Silent protection degradation is caught
- **WHEN** the internal network was created without the internal flag and direct egress unexpectedly succeeds
- **THEN** the self-check fails, the environment is disposed, and the task reports an infrastructure failure — no round executes

#### Scenario: Silent stripping degradation is caught
- **WHEN** interception is enabled but a probe request's foreign auth header reaches the upstream echo unmodified
- **THEN** the self-check fails and the task does not start

#### Scenario: Fresh judge environment is self-checked too
- **WHEN** a fresh environment is materialized from the attempt commit for judge votes or a `verify-in: fresh-box` check and a self-check probe fails
- **THEN** no vote or check process executes in it, and the check classifies as an infrastructure failure — retried per existing policy, no stage attempt burned
