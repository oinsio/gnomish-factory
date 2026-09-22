## MODIFIED Requirements

### Requirement: One recovery owner per shape
<!-- implements G1, G2, NFR-R1 of harden-task-branch-contract; FR3, FR5, FR8, NFR-R1 of fix-envelope-medium -->
Each named shape SHALL have exactly one recovery owner — the component responsible for converging that shape to a clean expected state. Recovery SHALL be idempotent and convergent: running a recovery on an already-recovered state changes nothing, running any recovery twice equals running it once, and a kill during recovery lands in a shape whose own recovery completes the remaining work. Convergence SHALL NOT depend on which instance picks the task up: a recovery owner decides from the durable medium — the branch tip and the tracker — never from a local working copy, so the instance whose own working copy froze the state converges it exactly as any other instance does. A working copy the factory keeps between pickups is a staging area for the next write, not an input to any recovery decision, and the build SHALL reject a recovery-path read of a factory-owned envelope file from the working copy.

#### Scenario: Recovering a recovered state is a no-op
- **WHEN** a shape's recovery runs and then runs again on the resulting state
- **THEN** the second run changes no branch content, no tracker state, and reports nothing to repair

#### Scenario: Kill mid-recovery converges on the next pickup
- **WHEN** a recovery is killed after any of its durable steps
- **THEN** the next pickup classifies the frozen state to a named shape whose recovery completes the work

#### Scenario: The instance that froze the state converges it
- **WHEN** an instance dies with its working copy ahead of or behind the branch tip and the same
  instance picks the task up again with that working copy still in place
- **THEN** classification and recovery read the tip, the working copy's state does not change the
  route taken, and the branch converges to the same shape another instance would have produced

#### Scenario: A working-copy read on a recovery path fails the build
- **WHEN** a recovery-path component reads a factory-owned envelope file from the working copy
  instead of through the tip reader
- **THEN** the architecture gate fails the build naming the file and the owner to route through
