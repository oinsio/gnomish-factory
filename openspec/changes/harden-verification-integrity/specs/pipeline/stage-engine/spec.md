# pipeline/stage-engine — delta for harden-verification-integrity

## MODIFIED Requirements

### Requirement: Ordered fail-fast verification
Verify checks SHALL run strictly in manifest order; the first non-Pass verdict of a blocking
check stops the chain. A check declaring `severity: advisory` SHALL NOT stop the chain.
**Implements**: FR2 of add-stage-engine; FR6 of harden-verification-integrity

#### Scenario: Later checks not reached
- **WHEN** the second of four checks returns Fail
- **THEN** checks three and four are never invoked and the attempt is a quality failure

#### Scenario: An advisory failure does not stop the chain
- **WHEN** the first of two checks is advisory and returns Fail, and the second is blocking
- **THEN** the second check is invoked

## ADDED Requirements

### Requirement: Advisory verdicts do not gate
The verify chain SHALL record an advisory check's verdict and findings in the attempt but SHALL
treat it as passed for control flow: no attempt is burned, and the attempt's recorded result
lists it with its severity.
**Implements**: FR6 of harden-verification-integrity

#### Scenario: Advisory failure in the middle of the chain
- **WHEN** the second of three checks is advisory and fails
- **THEN** the third check runs, the stage's verdict depends on checks one and three only, and
  the attempt record lists all three results with severities
