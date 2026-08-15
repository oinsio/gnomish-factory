# sandbox-provisioning (delta)

## ADDED Requirements

### Requirement: Images travel via a cluster-reachable registry
For cloud-bound projects, base images and provisioning snapshots SHALL
be published to and resolved from a registry reachable by the cluster;
registry endpoints remain parameterized configuration. Snapshot cache
semantics (fingerprint naming, TTL, rebuild triggers, no snapshot of a
gnome-touched environment) SHALL apply unchanged.
<!-- implements FR8 of add-sandbox-cloud-executor -->

#### Scenario: Snapshot reuse works in the cluster
- **WHEN** a second task of a cloud-bound project starts after provisioning published its snapshot
- **THEN** the task pod is created from the snapshot image pulled from the cluster-reachable registry, without re-running setup

#### Scenario: The invariant survives the move
- **WHEN** any cloud task completes
- **THEN** no image derived from a gnome-touched pod exists in the registry
