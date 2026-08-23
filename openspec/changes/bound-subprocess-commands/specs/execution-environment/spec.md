## ADDED Requirements

### Requirement: Docker management commands are bounded
Every docker management command the factory issues (create, run, inspect, remove, list, and the
rest of the non-streaming CLI surface) SHALL be bounded by a configured deadline and have its
output drained concurrently with the running process — including `docker run` when the image is
absent locally and the CLI reaches a registry over the network. Expiry SHALL be reported as a
distinct timed-out outcome, never as an ordinary non-zero exit; the existing classification of an
unreachable daemon as an infrastructure failure is unchanged.
<!-- implements FR10, NFR-R1, G1 of bound-subprocess-commands -->

#### Scenario: A wedged registry does not hang the take
- **WHEN** a box's image is absent and the registry accepts the connection but never answers
- **THEN** the management command returns within the configured docker deadline and the failure is
  classified as infrastructure, not quality

### Requirement: Environment process termination is tree-wide and interruption is named
When an execution-environment process is killed on a timeout, the kill SHALL terminate the process
and every descendant it spawned, cooperatively first and forcibly after a short grace, and reap
them — an agent CLI's own children do not outlive the round that launched them. An interrupted
wait SHALL be a named outcome of the environment's wait contract, distinct from any exit code, on
every wait path the environment exposes.
<!-- implements FR11, NFR-R2, G2, G5 of bound-subprocess-commands -->

#### Scenario: A timed-out round leaves no orphaned agent children
- **WHEN** an agent round expires on its round timeout and the agent CLI had spawned subprocesses
- **THEN** the CLI and all its descendants are terminated and reaped

#### Scenario: Interruption is not an exit code
- **WHEN** a wait on an environment process is interrupted by shutdown
- **THEN** the caller observes a named interrupted outcome, not a sentinel exit code
