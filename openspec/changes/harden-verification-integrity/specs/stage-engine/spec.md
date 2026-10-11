# stage-engine — delta for harden-verification-integrity

## ADDED Requirements

### Requirement: Advisory verdicts do not gate
The verify chain SHALL record an advisory check's verdict and findings in the attempt but SHALL
treat it as passed for control flow: fail-fast ordering is not triggered, no attempt is burned,
and the attempt's recorded result lists it with its severity.
<!-- implements FR6 of harden-verification-integrity -->

#### Scenario: Advisory failure in the middle of the chain
- **WHEN** the second of three checks is advisory and fails
- **THEN** the third check runs, the stage's verdict depends on checks one and three only, and
  the attempt record lists all three results with severities
