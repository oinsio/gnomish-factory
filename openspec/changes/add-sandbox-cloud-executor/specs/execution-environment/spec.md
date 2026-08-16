# execution-environment

## ADDED Requirements

### Requirement: Neighbor-stack declaration in the stage Mechanism
A stage `Mechanism` MAY declare a static neighbor-service stack as a
narrow positive subset (service name, image, env, exposed ports). The
declaration SHALL be loaded as pipeline law from the factory clone of
the base branch and SHALL be validated fail-closed as untrusted input:
privileged mode, host mounts outside the working copy, published
ports, socket mounts, and capability additions SHALL be refused as
located configuration errors before any environment starts.
<!-- implements FR12 of add-sandbox-cloud-executor -->

#### Scenario: A weakening declaration is refused
- **WHEN** a repo's neighbor declaration requests privileged mode, a host mount outside the workspace, or a published port
- **THEN** the factory refuses fail-closed naming the forbidden mechanism, and no environment or service starts

### Requirement: Local neighbor realization in the container adapter
For container-bound stages the factory SHALL realize the declared
stack as factory-labeled containers joined to the task's internal
network: started before the stage, disposed with the environment
segment, subject to operator resource limits and startup orphan
cleanup; images SHALL resolve through the same registry parameters as
the environment image. The box SHALL reach services only by declared
service name; no Docker API SHALL be reachable from the box.
<!-- implements FR13 of add-sandbox-cloud-executor -->

#### Scenario: Integration check reaches its declared database
- **WHEN** a stage declares a `db` (Postgres-class) neighbor and runs on the container adapter
- **THEN** the check reaches `db` by name inside the task's internal network while direct egress from the box stays blocked

#### Scenario: Neighbors never outlive the segment
- **WHEN** the environment segment is disposed — normally or by orphan cleanup after a crash
- **THEN** the service containers are removed with it, and a restarted factory leaves no neighbor container behind
