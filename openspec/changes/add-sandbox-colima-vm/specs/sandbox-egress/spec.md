# sandbox-egress (delta)

## ADDED Requirements

### Requirement: Host-enforced layer 2 for VM-class environments
For VM-class environments, the no-route-out guarantee SHALL be enforced
by a packet filter on the host: default-drop all traffic from the VM —
to the internet and to the host itself — except the guard proxy and
registry-mirror endpoints. Filter rules SHALL be installed before the
first round, verified as part of the self-check, and removed at
dispose; a VM whose rules cannot be installed or verified SHALL NOT run
a task. Privileged filter management SHALL NOT require running the
factory with elevated privileges.
<!-- implements FR6 of add-sandbox-colima-vm -->

#### Scenario: Root in the guest cannot widen its reach
- **WHEN** a process with root privileges inside the VM disables in-guest proxy settings and attempts a direct connection
- **THEN** the packet on the host is dropped by the filter and the attempt is observable to the operator

#### Scenario: The host itself is not a target
- **WHEN** a process inside the VM attempts to reach a service listening on the host (SSH-class) outside the guard endpoints
- **THEN** the connection is dropped by the filter

### Requirement: Guard guarantees carry over to the VM path
The guard serving VM-class environments SHALL keep the change-A/B
guarantees unchanged: logs carry metadata only — request and response
bodies and injected credentials SHALL never be written; credential
injection SHALL happen only at the guard, so no real secret exists
inside the VM (sentinel or virtual key only).
<!-- implements NFR-S1, NFR-S2 of add-sandbox-colima-vm -->

#### Scenario: Guard logs leak nothing injectable
- **WHEN** the guard injects a credential into an allowed VM-originated request and logs the event
- **THEN** the log entry holds host, path, and verdict metadata only — no body, no credential value

### Requirement: VM isolation self-check probes
For VM-class environments the mandatory fail-closed self-check SHALL
additionally verify, before the first round: the virtualization backend
is the expected hardware one; the mount table contains no host-share
mounts; a host-filesystem probe fails; direct DNS to an external
resolver fails (host-resolver DNS bypass is a known exfiltration
channel); an SSH attempt from guest to host is dropped; the filter
rules are populated; and, when the stage requires in-box Docker, the
in-VM daemon responds. Any failed probe SHALL be an infrastructure
failure: the VM is destroyed and the task does not start.
<!-- implements FR8 of add-sandbox-colima-vm -->

#### Scenario: DNS bypass is caught before work starts
- **WHEN** the VM's resolver configuration would let a direct external DNS query succeed
- **THEN** the self-check fails on the DNS probe, the VM is destroyed, and an infrastructure failure is reported

#### Scenario: Missing filter rules block the task
- **WHEN** the filter anchor is empty because installation failed silently
- **THEN** the self-check fails on the filter probe and no round executes
