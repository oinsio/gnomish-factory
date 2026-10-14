# sandbox-egress

## Purpose

The sandbox egress guard is the sole route out of a task container: an internal-only network makes direct connections to the internet or the host impossible by construction, and the guard proxy enforces a default-deny allowlist owned by operator config, resolving DNS itself so no direct name resolution channel exists. Every materialized sandboxed environment passes a mandatory fail-closed self-check before its first gnome-product process, catching silent isolation degradation before any round can run. Denials are captured as structured, body-free findings readable back from the guard, and the reference sandbox image bakes in the CA certificate and proxy configuration tools need to cooperate with the guard even when they ignore proxy environment variables.

## Requirements

### Requirement: The guard is the only route out
A task container SHALL be attached only to its internal-only network; the guard proxy SHALL be the single reachable egress point. Direct connections from the box to the internet or to the host SHALL be impossible by construction, not by convention.
**Implements**: FR7 of add-sandbox-core

#### Scenario: Ignoring proxy settings goes nowhere
- **WHEN** a process inside the box strips `HTTP_PROXY` variables and dials an external address directly
- **THEN** the connection fails because no route exists

### Requirement: Default-deny allowlist owned by the operator
The guard SHALL deny every destination not present in the allowlist from factory installation config. The allowlist SHALL live outside the box and outside the target repo; a repo may request additions, only the operator grants them.
**Implements**: FR7 of add-sandbox-core

#### Scenario: Non-allowlisted host is denied
- **WHEN** a process requests a host absent from the allowlist via the guard
- **THEN** the guard refuses the connection and records a structured denial event

### Requirement: DNS resolves only through the guard
The box SHALL have no direct DNS access; name resolution SHALL happen at the guard. Open port-53 egress is a known exfiltration channel and SHALL NOT exist.
**Implements**: FR7 of add-sandbox-core

#### Scenario: Direct DNS query fails
- **WHEN** a process inside the box queries an external DNS server directly
- **THEN** the query gets no answer

### Requirement: Mandatory fail-closed self-check
Before the first gnome-product process in every materialized sandboxed environment — round environments and fresh-box verification/judge environments alike; the probes themselves run via `exec()` — the factory SHALL verify from inside the box: the in-box process user is non-root, direct egress fails, a non-allowlisted destination is denied, an allowlisted destination succeeds, and the isolation mechanism in effect matches the adapter passport.
**Implements**: FR8 of add-sandbox-core; FR3, NFR-R1, NFR-R2, NFR-O1, NFR-C1, UX3 of polish-sandbox-forensics
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

### Requirement: Denials are captured as structured findings
Every guard denial SHALL be recorded as structured metadata (host, path, method — never request bodies) and readable as findings from the guard, so a blocked attempt is a machine-readable signal rather than silence. Captured denials SHALL reach the task report for the round they occurred in, independently of the round's verdict and independently of whether that round ever closed.
**Implements**: NFR-O1 of add-sandbox-core; FR3, NFR-O1, NFR-S1 of fix-denial-report-attachment; FR1, FR5, NFR-O1 of fix-denial-attribution-durability
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

### Requirement: Denial read-back is durable across processes
The read position that makes each denial read a delta SHALL outlive the factory process that advanced it, for as long as the guard log it indexes exists, on every path that records denials: a factory process resuming a task over a surviving guard SHALL start its next read past every denial already recorded against an attempt or an escalation of that task.
**Implements**: FR3, FR4, FR5, NFR-R1, NFR-R2, NFR-R3, NFR-O2, NFR-C1 of fix-denial-attribution-durability
**Notes**: The fallback to reporting more is logged so that duplicates in a report are explainable.

#### Scenario: The position never advances ahead of its record
- **WHEN** the write of a record carrying denials is lost after the read that collected them
- **THEN** the durable position has not moved past those denials, so the next read re-reads them (a duplicated denial) and never skips them (a lost one)

#### Scenario: A position lives only as long as its guard log
- **WHEN** the guard and its log are gone, or the recorded position names a container that is not the live guard
- **THEN** the position is discarded and the read starts fresh, which is the correct start rather than a fallback

#### Scenario: The position and its record ride one commit
- **WHEN** a record carrying denials and the position that delimits them is written
- **THEN** both land in one commit, so a push delivers both or neither, and the position at the branch tip on origin after a successful push is the authoritative one — a local-only position is advisory

#### Scenario: The read stays bounded by the tail cap
- **WHEN** a denial read runs with a lost position, a stale one, or an intact one
- **THEN** it reads no more than the guard log tail cap

#### Scenario: Resume by another instance does not re-report past denials
- **WHEN** a task whose denials are recorded against attempts and against a `cannotExecute` escalation is parked, and another factory instance resumes it over the surviving guard container
- **THEN** the first round after the resume reports only the denials recorded during that round — neither the attempts' nor the escalation's

#### Scenario: A lost read position prefers duplicates over silence
- **WHEN** the durable read position cannot be recovered for a task that has a guard log
- **THEN** the read returns the denials it can see, the report may repeat an already-recorded denial, and the fallback is logged

#### Scenario: A denial is reported once while the position holds
- **WHEN** a task runs several rounds across two factory processes with the read position intact
- **THEN** each denial appears under exactly one recorded round or escalation

### Requirement: Denial identity and idempotent merge
Every recorded denial SHALL carry a source-assigned identity — the denial source's own event timestamp paired with the source identity — kept from the log line it was parsed from. Attaching denials to a record SHALL merge idempotently by that identity against the denials already recorded at the branch tip. Identity SHALL come from the source's event coordinates, never from the finding's content.
**Implements**: FR7, NFR-R3 of fix-denial-attribution-durability
**Notes**: The identity is environment bookkeeping, not part of what a report shows.

#### Scenario: A merge that dropped denials says how many
- **WHEN** a fallback re-read's merge finds some of its denials already recorded at the tip
- **THEN** the merge outcome is logged as "N already present, M recovered"

#### Scenario: The identity stays out of status.json and the text render
- **WHEN** a recorded denial carrying an identity is written and reported
- **THEN** the task branch documents carry the identity additively under contract v1, while `status.json` and the text render show the denial without it

#### Scenario: A fallback re-read records no duplicates
- **WHEN** the durable read position is lost but the recorded denials carry identities, and the read falls back to the full tail
- **THEN** only the denials whose identities are not yet recorded are attached, and the report shows each denial exactly once

#### Scenario: Repeated denials to the same destination are counted, not collapsed
- **WHEN** the gnome triggers two denials to the same host, path, and method in one round
- **THEN** both are recorded as distinct events with distinct identities

#### Scenario: Records written before identities existed still merge
- **WHEN** a re-read encounters recorded denials with no identity field
- **THEN** they are kept as-is and the read degrades to the duplicate-tolerant fallback for them alone

### Requirement: Denial loss is visible in the report
When the factory can see that denials were lost — the tail cap saturated a read (lines older than the read window are gone), or a committed cursor names a source that no longer holds its log while recorded history says denials existed — it SHALL record a loss marker as a synthetic finding through the same findings channel the denials use, funnel-fenced like any finding, naming the loss window it can bound.
**Implements**: FR8, NFR-O3, UX3 of fix-denial-attribution-durability
**Notes**: The marker lets a report distinguish "no denials" from "no data".

#### Scenario: A loss marker gates nothing
- **WHEN** a record carries a loss marker
- **THEN** no check verdict, no stage outcome, and no feedback context of a retry changes because of it, exactly as for the denials it stands in for

#### Scenario: A source gone with recorded denials surfaces as loss
- **WHEN** a committed cursor names a denial source that no longer holds its log while the recorded history says denials existed
- **THEN** the next record carries a loss marker naming the recorded source and the live one

#### Scenario: A saturated tail cap surfaces as loss
- **WHEN** a read returns the tail cap's maximum and the cursor shows the window began after the last read position
- **THEN** the record carrying that read's denials also carries a loss marker naming the bounded gap, in both task documents and the text render

#### Scenario: A quiet task shows nothing
- **WHEN** a task records no denial and no loss condition occurs
- **THEN** no denial entry and no loss marker appear on any report surface

### Requirement: Guard outage is an infrastructure failure
When the guard is unreachable or down, in-flight checks SHALL classify as infrastructure failures (retried per existing policy, no stage attempt burned), and the factory SHALL restart the guard.
**Implements**: NFR-R1 of add-sandbox-core

#### Scenario: Guard crash burns no attempt
- **WHEN** the guard dies while a stage round is in progress
- **THEN** the round fails as an infrastructure failure, the attempt counter is unchanged, and the guard is restarted before retry

### Requirement: Image carries the egress plumbing
The reference image recipe SHALL bake in everything egress needs at build time: the factory CA certificate (seam for later TLS interception), proxy settings for tools that ignore proxy env vars (JVM: `gradle.properties`, `GRADLE_OPTS`, `settings.xml`), and registry endpoints as build parameters rather than hardcoded values.
**Implements**: FR7, UX4 of add-sandbox-core

#### Scenario: Gradle build flows through the guard
- **WHEN** a Gradle build inside the box resolves dependencies
- **THEN** the traffic goes through the guard to allowlisted registries despite JVM ignoring proxy environment variables
