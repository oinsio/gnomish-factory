## MODIFIED Requirements

### Requirement: Runtime outage is an infrastructure failure
When the container runtime dies mid-task, the affected operation SHALL classify as an infrastructure failure: it is not retried, no stage attempt is burned, and the task escalates at once with a "cannot execute" report. When the runtime is unavailable at materialize, before any round of the claimed task, the run SHALL end with the typed precondition result instead: the claim is released, nothing is escalated, and under `serve` the container-runtime condition opens.
**Implements**: NFR-R1 of add-sandbox-core; FR14, FR15, NFR-R2 of add-sandbox-base-image; FR1, FR2 of split-long-spec-requirements
**Notes**: The mid-task class is the existing infrastructure-failure class, consistent with the other failure classes. Work in progress is the task's and is escalated; a box that never started is the operator's precondition and is a daemon condition (design D19).

#### Scenario: Docker daemon dies mid-task
- **WHEN** the daemon becomes unreachable between rounds
- **THEN** the round classifies as an infrastructure failure, the attempt counter is unchanged, and the task escalates as cannot-execute at once, the round not retried

#### Scenario: Runtime unreachable at materialize returns the task
- **WHEN** the container runtime is unreachable when a claimed task's first box would be created
- **THEN** no box is created, the run ends with the typed precondition result, the claim is released with no abort marker and no escalation report, and under `serve` the container-runtime condition opens

### Requirement: The box's git meets the floor before the seed clone
The container adapter SHALL, before every seed clone of a box, run the image's own `git --version` in a one-shot, network-less helper carrying the environment's ownership labels, and SHALL refuse the image when the reported version is below the factory's git floor or no version is reported. A claimed task whose box image is refused SHALL be released, not aborted. A helper the runtime could not start SHALL be reported as the runtime's failure, never as a version refusal.
**Implements**: NFR-S2 of tighten-spec-contract; FR10 of own-git-invocation-policy; FR11, FR14, NFR-R2 of add-sandbox-base-image; FR1, FR2 of split-long-spec-requirements
**Notes**: The seed clone reads the factory clone, on the reftable ref backend, with the image's git; the factory has one git floor (`GitVersion.FLOOR`), judged by one parser for the factory's own git and the box's, and the base image carries a git at or above it, so an image built `FROM` the base passes.
**Notes**: A seed clone happens at a fresh materialize and at a reattach that applies a commit pin.

#### Scenario: An image whose git is below the floor is refused before the seed clone
- **WHEN** a box is materialized on an image whose `git --version` reports 2.43.0
- **THEN** the run stops with a message naming the image, the floor, the reported version and the remedy; the check itself logs nothing and the layer that decides writes one coded line — `take` and `run` an ERROR beside the message they print, `serve` the box-git-floor condition's opening WARN — and no network, volume or container was created

#### Scenario: An image without git is refused with the runtime's complaint
- **WHEN** a box is materialized on an image in which `git` cannot be executed
- **THEN** the refusal quotes the runtime's answer instead of a version and nothing is created

#### Scenario: A runtime that cannot start the helper is not a version refusal
- **WHEN** the container runtime is unreachable when the floor probe would run
- **THEN** the failure classifies as the container runtime's, not the image's: no version refusal is logged and, under `serve`, the container-runtime condition opens rather than the box-git-floor one

#### Scenario: An image at or above the floor seeds as before
- **WHEN** a box is materialized on an image whose git reports the floor or newer
- **THEN** the probe logs nothing and the materialize continues with the seed clone

#### Scenario: A refused image releases the claimed task
- **WHEN** a claimed run stops because its box image's git is below the floor
- **THEN** the run ends with the typed precondition result, the claim is released, the task is not aborted, and under `serve` the box-git-floor condition opens so no further task is claimed until a probe passes
