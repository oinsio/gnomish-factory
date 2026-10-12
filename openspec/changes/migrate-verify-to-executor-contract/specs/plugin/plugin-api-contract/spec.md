# plugin/plugin-api-contract — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Sample verdict program proves the executor authoring path
The stand-in third-party module SHALL include a sample verdict program — a script with no
factory dependency — that passes the executor conformance kit in role `verdict` with `poll`.
Check authoring is proven by the kit, not by compiling a factory against `gnomish-plugin-api`.
**Implements**: FR8 of migrate-verify-to-executor-contract

#### Scenario: Sample verdict program passes the kit
- **WHEN** the conformance kit runs against the sample verdict program
- **THEN** every verdict and poll case passes and the program declares no factory dependency

## REMOVED Requirements

### Requirement: Sample plugin proves the check authoring path
**Reason**: The check SPI leaves the api; there is no Java check factory to compile.
**Migration**: the sample verdict program and `gnomish executor verify` replace it.

### Requirement: Attempt-commit workspace readable through the contract
**Reason**: The attempt commit travels in the executor request; no Java workspace type is
needed by a program.
**Migration**: read `workspace.attemptCommit` from the request on stdin.
