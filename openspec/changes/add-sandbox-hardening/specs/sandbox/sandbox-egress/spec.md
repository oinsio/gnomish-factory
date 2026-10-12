# sandbox/sandbox-egress (delta)

## ADDED Requirements

### Requirement: TLS interception mode
The guard SHALL support a TLS interception mode using the factory CA
import seam baked into images, switchable per operator config without a
proxy tool swap. Images built with a populated `ca/` need no rebuild;
an image built with the (previously valid) empty `ca/` requires one
rebuild with the CA present before interception can be enabled.
Per-host passthrough exceptions SHALL exist for certificate-pinned
tools. Interception SHALL NOT buffer streaming (SSE) responses.
**Implements**: FR8, NFR-P1 of add-sandbox-hardening

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
**Implements**: FR9, NFR-S2 of add-sandbox-hardening

#### Scenario: Foreign account door is closed
- **WHEN** an injected prompt makes the gnome attach a foreign auth token to a request toward an allowlisted host
- **THEN** the upstream receives only the factory credential and the stripped header appears as a finding in the task report

### Requirement: L7 rules narrow allowed hosts
The guard SHALL support per-host L7 rules (path prefixes and methods)
for allowlisted hosts in interception mode; rules are operator-owned
config, and requests outside the rules SHALL be denied and recorded
like allowlist denials.
**Implements**: FR10 of add-sandbox-hardening

#### Scenario: Allowed host, disallowed path
- **WHEN** a process requests an allowlisted host with a path outside the operator's rules (e.g., a write method where only reads are permitted)
- **THEN** the guard denies the request and records a structured denial event

## MODIFIED Requirements

Layered on `Denials are captured as structured findings` and `Mandatory fail-closed self-check`
as modified by `tighten-spec-contract` (sequenced before this change): that delta cut both bodies
to one behavior, moved the rest into notes and scenarios, and carries the text of the archived
`fix-denial-attribution-durability` and `polish-sandbox-forensics` (the kept self-check box, the
hung-round denial), which the former rebase note waited for. This text adds the hardening-layer
denial sources (scenario "A hardening-layer denial reaches the report the same way") and the
hardening-layer self-check probes (scenarios "Enabled hardening layers are self-checked too" and
"Silent stripping degradation is caught"); all must survive the later sync.

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. Captured denials SHALL reach the task report for the round they occurred in, independently of the round's verdict and independently of whether that round ever closed.
**Implements**: NFR-O1 of add-sandbox-core; FR3, NFR-O1, NFR-S1 of fix-denial-report-attachment; FR1, FR5, NFR-O1 of fix-denial-attribution-durability; NFR-O1 of add-sandbox-hardening
**Notes**: The recorded path carries no query string because a denial finding is committed to the task branch, and a query is gnome-chosen request payload rather than metadata about the destination.
**Notes**: A round that dies before its close has no attempt record to hold its denials, which is why they are carried out on its escalation.

#### Scenario: A denial changes no verdict, outcome or retry feedback
- **WHEN** a denial is attached to a round's record
- **THEN** no check verdict, no stage outcome, and no feedback context of a retry changes because of it

#### Scenario: A denial is attributed to the round whose read collected it
- **WHEN** a crash leaves a denial unread and the next round's read collects it
- **THEN** the denial is reported under that next round — a declared relaxation of event-time attribution, not an accident

#### Scenario: A denied request is captured as a structured finding
- **WHEN** the gnome attempts a request to a non-allowlisted host during a round
- **THEN** the guard records a structured denial finding carrying the denied host, path, and method (never the request body), readable back from the guard

#### Scenario: A denied request's query string never enters the finding
- **WHEN** the denied request carries a query string (`GET /upload?token=…`)
- **THEN** the recorded finding names the destination and the path up to the query, and the query itself appears nowhere in the finding

#### Scenario: Denied exfiltration attempt reaches the report on a passing attempt
- **WHEN** a round records a guard denial and every check of the attempt passes
- **THEN** the attempt's report entry carries the denial finding while the attempt result stays passed

#### Scenario: Denied exfiltration attempt of a hung round reaches the report
- **WHEN** a round that recorded a guard denial is killed before it could close
- **THEN** the denial is read from the environment and reported under the task's escalation rather than only in the factory log

#### Scenario: A hardening-layer denial reaches the report the same way
- **WHEN** a round triggers an L7 rule denial, a stripped credential header, a stripped server-side tool, or a budget event
- **THEN** the guard records it as a structured finding, the task report carries it for that round, and the round's verdicts are unchanged

### Requirement: Mandatory fail-closed self-check
Before the first gnome-product process in every materialized sandboxed environment — round environments and fresh-box verification/judge environments alike; the probes themselves run via `exec()` — the factory SHALL verify from inside the box: the in-box process user is non-root, direct egress fails, a non-allowlisted destination is denied, an allowlisted destination succeeds, and the isolation mechanism in effect matches the adapter passport.
**Implements**: FR8 of add-sandbox-core; FR3, NFR-R1, NFR-R2, NFR-O1, NFR-C1, UX3 of polish-sandbox-forensics; FR11 of add-sandbox-hardening
**Notes**: The non-root user is the channel-write and commit identity design D16 depends on; a rejected box is kept so the operator can inspect why the self-check failed, and the keep-stop is best-effort forensics.
**Notes**: Retention semantics of execution media other than the container adapter are owned by their own changes.

#### Scenario: Silent protection degradation is caught
- **WHEN** the internal network was created without the internal flag and direct egress unexpectedly succeeds
- **THEN** the self-check fails, the environment is rejected, and the task reports an infrastructure failure — no round executes

#### Scenario: A root-running image is refused
- **WHEN** the sandbox image's default user is root (`id -u` is `0`), so channel writes and the in-box snapshot commit would run as root against root-owned cage surfaces (D16)
- **THEN** the self-check's non-root probe fails naming the probe, the environment is rejected, and no gnome-product process executes in it

#### Scenario: Fresh judge environment is self-checked too
- **WHEN** a fresh environment is materialized from the attempt commit for judge votes or a `verify-in: fresh-box` check and a self-check probe fails
- **THEN** no vote or check process executes in it, and the check classifies as an infrastructure failure — retried per existing policy, no stage attempt burned

#### Scenario: The failed box is kept for inspection
- **WHEN** a self-check probe fails in a materialized box
- **THEN** the box's container is stopped, its container, volume, and network remain, the keep notice in the factory log names the kept container, and the environment is still rejected as an infrastructure failure

#### Scenario: A failed keep-stop does not mask the self-check failure
- **WHEN** the stop of a rejected box itself fails (runtime outage mid-rejection)
- **THEN** the reported failure is still the self-check failure, naming the failed probe

#### Scenario: Kept self-check box is governed by the existing sweep
- **WHEN** a box kept after a failed self-check is later evaluated by the `sandbox-lifecycle` sweep
- **THEN** it is enumerated and classified exactly like any other kept environment of its role — labels stamped at creation place it in the sweep universe — and no self-check-specific sweep logic exists

#### Scenario: Enabled hardening layers are self-checked too
- **WHEN** the gateway, TLS interception, or the server-side tool policy is enabled for a sandboxed environment
- **THEN** the self-check additionally verifies, per enabled layer: the gateway is reachable and the virtual key is valid; interception is active on intercepted hosts and a foreign auth header does not survive to the upstream; a disallowed server-side tool is stripped from a probe request — and a failed probe rejects the environment as an infrastructure failure

#### Scenario: Silent stripping degradation is caught
- **WHEN** interception is enabled but a probe request's foreign auth header reaches the upstream echo unmodified
- **THEN** the self-check fails and the task does not start
