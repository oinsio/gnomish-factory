# stage-engine — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Verify chain runs through the executor contract
The engine SHALL run every `builtin`, `command` and `external` check through the executor
contract in role `verdict`: the same registry, result shape and outcome classifier as stage
rounds. `EnginePorts` SHALL expose no `BuiltinCheckRunner`, `CommandCheckRunner` or
`ExternalCheckClient`; `JudgeVoter` remains until the agent dialect migrates.
<!-- implements FR1, FR2, G1 of migrate-verify-to-executor-contract -->

#### Scenario: Ordered fail-fast is preserved
- **WHEN** a stage declares three checks and the second fails
- **THEN** the third is not started and the round records the first two results in order

#### Scenario: Check infrastructure failure burns no attempt
- **WHEN** a verdict executor returns `failed/infrastructure`
- **THEN** the check is cannot-verify, the attempt counter is unchanged and the task escalates
  after the engine's retries

### Requirement: Verdict derivation is one component
The pass/fail of a check SHALL be derived in one engine component from the SARIF answer and the
check's threshold; no adapter and no built-in SHALL construct a verdict.
<!-- implements FR7, M2 of migrate-verify-to-executor-contract -->

#### Scenario: Threshold table verified
- **WHEN** each combination of result level and threshold is produced by a fake executor
- **THEN** the derived verdict matches the table (data-driven spec, one row per case)

## REMOVED Requirements

### Requirement: External check poll loop
**Reason**: The single-poll port and its delivery precondition are replaced by the engine-owned
pending/poll lifecycle over the executor contract (`verification/pending-poll`), which keeps
the delivery precondition, the interval, the timeout class and the run-URL preservation.
**Migration**: `external` checks map to the `github-actions` or `http` built-in; the attempt
commit delivery check runs before the first `start` of any verdict executor.
