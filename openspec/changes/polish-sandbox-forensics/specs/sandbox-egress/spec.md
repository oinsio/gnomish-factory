## MODIFIED Requirements

### Requirement: Mandatory fail-closed self-check
Before the first gnome-product process in every materialized sandboxed environment — round environments and fresh-box verification/judge environments alike; the probes themselves run via `exec()` — the factory SHALL verify from inside the box: the in-box process user is non-root (the channel-write and commit identity D16 depends on), direct egress fails, a non-allowlisted destination is denied, an allowlisted destination succeeds, and the isolation mechanism in effect matches the adapter passport. Any failed probe SHALL be an infrastructure failure: the environment is rejected and no gnome-product process executes in it — at task start the task does not start; at verification time the affected check or judge vote classifies as an infrastructure failure. A rejected box SHALL be stopped and kept — container, volume, and network retained — rather than disposed, so the operator can inspect why the self-check failed; the failure report SHALL name the kept container. The keep-stop is best-effort forensics and SHALL never mask or reclassify the self-check failure itself. Retention of a kept box is governed entirely by the existing `sandbox-lifecycle` sweep policy — no self-check-specific retention exists.
<!-- implements FR8 of add-sandbox-core -->
<!-- implements FR3, NFR-R1, NFR-C1, UX3 of polish-sandbox-forensics -->

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
- **THEN** the box's container is stopped, its container, volume, and network remain, the failure report names the kept container, and the environment is still rejected as an infrastructure failure

#### Scenario: A failed keep-stop does not mask the self-check failure
- **WHEN** the stop of a rejected box itself fails (runtime outage mid-rejection)
- **THEN** the reported failure is still the self-check failure, naming the failed probe

#### Scenario: Kept self-check box is governed by the existing sweep
- **WHEN** a box kept after a failed self-check is later evaluated by the `sandbox-lifecycle` sweep
- **THEN** it is enumerated and classified exactly like any other kept environment of its role — labels stamped at creation place it in the sweep universe — and no self-check-specific sweep logic exists
