# sandbox-egress (delta)

## ADDED Requirements

### Requirement: TLS interception mode
The guard SHALL support a TLS interception mode using the factory CA
import seam baked into images, switchable per operator config without a
proxy tool swap. Images built with a populated `ca/` need no rebuild;
an image built with the (previously valid) empty `ca/` requires one
rebuild with the CA present before interception can be enabled.
Per-host passthrough exceptions SHALL exist for certificate-pinned
tools. Interception SHALL NOT buffer streaming (SSE) responses.
<!-- implements FR8, NFR-P1 of add-sandbox-hardening -->

#### Scenario: Interception is a mode switch
- **WHEN** the operator enables interception on an installation whose image was built with the factory CA in `ca/`
- **THEN** boxes trust the intercepted connections without an image rebuild, and pinned hosts on the exception list keep working via passthrough

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

<!-- Rebase note: fix-denial-attribution-durability (active, lands first)
     modifies this same requirement; rebase this block onto the merged text
     when that change archives. -->

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. This extends to every denial source this change adds: L7 rule denials, stripped credential headers, stripped server-side tools, and budget events SHALL be recorded and attached the same way. The recorded path SHALL carry no query string: a denial finding is committed to the task branch, and a query is gnome-chosen request payload rather than metadata about the destination. Captured denials SHALL reach the task report for the round they occurred in, independently of the round's verdict: attaching a denial SHALL NOT change any check verdict, the stage outcome, or the feedback context of a retry. Folding a denial into a check `Verdict.Fail` (which would flip the stage outcome) SHALL NOT be the attachment mechanism — NFR-O1 is observability, not a gate.
<!-- implements NFR-O1 of add-sandbox-core -->
<!-- implements FR3, NFR-O1, NFR-S1 of fix-denial-report-attachment -->
<!-- implements NFR-O1 of add-sandbox-hardening -->

#### Scenario: A denied request is captured as a structured finding
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round
- **THEN** the guard records a structured denial finding carrying the denied host, path, and method (never the request body), readable back from the guard

#### Scenario: A denied request's query string never enters the finding
- **WHEN** the denied request carries a query string (`GET /upload?token=…`)
- **THEN** the recorded finding names the destination and the path up to the query, and the query itself appears nowhere in the finding

#### Scenario: Denied exfiltration attempt reaches the report on a passing attempt
- **WHEN** a round records a guard denial and every check of the attempt passes
- **THEN** the attempt's report entry carries the denial finding while the attempt result stays passed

#### Scenario: A hardening-layer denial reaches the report the same way
- **WHEN** a round triggers an L7 rule denial or a server-side tool strip
- **THEN** the task report carries it as a structured finding for that round, and the round's verdicts are unchanged

### Requirement: Mandatory fail-closed self-check
Before the first gnome-product process in every materialized sandboxed
environment — round environments and fresh-box verification/judge
environments alike; the probes themselves run via `exec()` — the factory
SHALL verify from inside the box: the in-box process user is non-root
(the channel-write and commit identity D16 depends on), direct egress
fails, a non-allowlisted destination is denied, an allowlisted
destination succeeds, and the isolation mechanism in effect matches the
adapter passport. When the respective layers are enabled, the
self-check SHALL additionally verify: the gateway is reachable and the
virtual key is valid; interception is active on intercepted hosts and a
foreign auth header does not survive to the upstream; a disallowed
server-side tool is stripped from a probe request. Any failed probe
SHALL be an infrastructure failure: the environment is rejected and no
gnome-product process executes in it — at task start the task does not
start; at verification time the affected check or judge vote classifies
as an infrastructure failure.
<!-- implements FR8 of add-sandbox-core -->
<!-- implements FR11 of add-sandbox-hardening -->

#### Scenario: Silent protection degradation is caught
- **WHEN** the internal network was created without the internal flag and direct egress unexpectedly succeeds
- **THEN** the self-check fails, the environment is disposed, and the task reports an infrastructure failure — no round executes

#### Scenario: A root-running image is refused
- **WHEN** the sandbox image's default user is root (`id -u` is `0`), so channel writes and the in-box snapshot commit would run as root against root-owned cage surfaces (D16)
- **THEN** the self-check's non-root probe fails naming the probe, the environment is rejected, and no gnome-product process executes in it

#### Scenario: Silent stripping degradation is caught
- **WHEN** interception is enabled but a probe request's foreign auth header reaches the upstream echo unmodified
- **THEN** the self-check fails and the task does not start

#### Scenario: Fresh judge environment is self-checked too
- **WHEN** a fresh environment is materialized from the attempt commit for judge votes or a `verify-in: fresh-box` check and a self-check probe fails
- **THEN** no vote or check process executes in it, and the check classifies as an infrastructure failure — retried per existing policy, no stage attempt burned
