# verification/verification-hardening — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: Pin-check guards external checks
When a stage declares external checks, each SHALL be guarded by a pin-check performed by a pin decorator wrapping the check's verdict executor, before its first `start`: the check's definition files — the union of pin paths declared in the stage law and the check's `checkId` when the executor's describe declares `checkIdIsPinnedPath` — SHALL be byte-identical to the base branch, compared as bare git objects at the attempt commit. Any difference SHALL yield a Fail verdict — a quality failure.
**Implements**: NFR-S1 of remove-interactive-console; FR6, FR16 of migrate-verify-to-executor-contract
**Notes**: The guard wraps the executor, so the engine's manifest-order verification chain is unchanged.

#### Scenario: Rewritten workflow is caught before the adapter is invoked
- **WHEN** the gnome branch modifies a definition file of the external check
- **THEN** the stage fails the pin-check with the diff as findings and the verdict executor is never started

#### Scenario: Early substitution is caught at the point of use
- **WHEN** the gnome modified a stage-3 check's definition file back at stage 1 and stages 1 and 2 passed
- **THEN** stage 3 fails the pin-check against the base branch before its verdict executor is started

#### Scenario: A check with no declared pin paths passes the pin vacuously
- **WHEN** an external check names a verdict executor whose describe does not declare `checkIdIsPinnedPath` and its declaration names no pin paths
- **THEN** the pin passes vacuously and the executor runs as usual
