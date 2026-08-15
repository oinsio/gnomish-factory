# sandbox-egress (delta)

## ADDED Requirements

### Requirement: NetworkPolicy layer 2 with mandatory metadata block
For cloud environments, the no-route-out guarantee SHALL be enforced by
default-deny NetworkPolicy on the task namespace, permitting egress
only to the in-cluster guard (DNS included). The cloud metadata
endpoint SHALL be explicitly unreachable from task namespaces. The
startup self-check SHALL prove for cloud environments: direct egress
fails, the metadata endpoint is unreachable, and the guard path works;
any failure is an infrastructure failure preventing task start.
<!-- implements FR2, FR3 of add-sandbox-cloud-executor -->

#### Scenario: SSRF against the metadata service dies in policy
- **WHEN** a process inside a task pod requests the cloud metadata endpoint
- **THEN** the connection is denied by NetworkPolicy and the attempt is recorded as a denial finding

#### Scenario: Unenforced policy is caught before work starts
- **WHEN** the cluster's CNI does not actually enforce the default-deny policy and a direct egress probe succeeds
- **THEN** the self-check fails, the environment is disposed, and the task reports an infrastructure failure

### Requirement: Node-level image pulls under cluster registry policy
Because image pulls are performed by the node outside pod
NetworkPolicy, cloud deployments SHALL constrain them by cluster
registry policy (mirror or allowlist); the requirement and a
verification step SHALL ship as an operator checklist item.
<!-- implements FR8 of add-sandbox-cloud-executor -->

#### Scenario: The pull path is not a policy hole
- **WHEN** the operator completes the cloud checklist
- **THEN** node image pulls resolve only through the cluster's configured mirror/allowlist, and the verification step confirms an off-policy pull fails
