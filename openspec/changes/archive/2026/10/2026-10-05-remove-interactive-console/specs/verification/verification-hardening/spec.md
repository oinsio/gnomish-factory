# Spec Delta: verification/verification-hardening

## MODIFIED Requirements

### Requirement: Pin-check guards external checks
When a stage declares external checks, each SHALL be guarded by a pin-check performed by a guard component wrapping any `ExternalCheckClient`, before the adapter's first poll: the check's definition files — the union of pin paths declared in the stage law and paths contributed by the adapter — SHALL be byte-identical to the base branch, compared as bare git objects at the attempt commit. Any difference SHALL yield a Fail verdict with the diff as findings — a quality failure — and the adapter is never invoked. When the union is empty (the provider contributes no paths and the declaration names none), the pin SHALL pass vacuously. The engine's manifest-order verification chain is unchanged.
<!-- implements NFR-S1 of remove-interactive-console -->

#### Scenario: Rewritten workflow is caught before the adapter is invoked
- **WHEN** the gnome branch modifies a definition file of the external check
- **THEN** the stage fails the pin-check with the diff as findings and the adapter is never invoked

#### Scenario: Early substitution is caught at the point of use
- **WHEN** the gnome modified a stage-3 check's definition file back at stage 1 and stages 1 and 2 passed
- **THEN** stage 3 fails the pin-check against the base branch before its adapter is invoked

#### Scenario: Interactive client with nothing declared passes the pin
- **WHEN** an external check is served by a configured provider that contributes no pin paths and its declaration names none
- **THEN** the pin passes vacuously and the provider's client is polled as usual
