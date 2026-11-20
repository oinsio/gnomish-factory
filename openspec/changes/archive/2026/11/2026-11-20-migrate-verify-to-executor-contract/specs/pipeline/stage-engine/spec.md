# pipeline/stage-engine — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Verify chain runs through the executor contract
The engine SHALL run every `builtin`, `command` and `external` check through the executor
contract in role `verdict`: the same registry, result shape and outcome classifier as stage
rounds. `EnginePorts` SHALL expose no `BuiltinCheckRunner`, `CommandCheckRunner` or
`ExternalCheckClient`; `JudgeVoter` remains until the agent dialect migrates.
**Implements**: FR1, FR2, G1 of migrate-verify-to-executor-contract

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
**Implements**: FR7, M2 of migrate-verify-to-executor-contract

#### Scenario: Threshold table verified
- **WHEN** each combination of result level and threshold is produced by a fake executor
- **THEN** the derived verdict matches the table (data-driven spec, one row per case)

### Requirement: Pending check events
The engine SHALL emit one CheckPolled for each poll of a pending check, none for the check's start, and SHALL emit CheckTimedOut once when a pending check's wait reaches its timeout, as the wait's last event, carrying the failure the wait resolved to.
**Implements**: NFR-O1, UX1 of migrate-verify-to-executor-contract

#### Scenario: Each poll of a pending check is one event
- **WHEN** a started check answers pending on its start and on its first poll, then passes on its second poll
- **THEN** the run emits two CheckPolled events, one per poll, none for the start, and no CheckTimedOut

#### Scenario: A pending check's timeout is its last event
- **WHEN** a pending check's wait reaches its timeout
- **THEN** CheckTimedOut is emitted once, after the last CheckPolled, and carries the failure the wait resolved to

## MODIFIED Requirements

### Requirement: Engine events
The engine SHALL emit sealed events — RunStarted, AttemptStarted, ExecutionFinished, CheckStarted, CheckPolled, CheckTimedOut, CheckFinished, AttemptFinished (new state + trace), TaskFinished — each self-contained with the (taskId, stage, attempt) key shared with logs and telemetry, delivered synchronously; every run, including pre-flight escalations, emits at least RunStarted and TaskFinished; listener exceptions SHALL be logged and swallowed.
**Implements**: FR12, NFR-O2, UX2 of add-stage-engine; NFR-O1, UX1 of migrate-verify-to-executor-contract

#### Scenario: Status reconstructed from events alone
- **WHEN** a recording listener observes a run with a retry
- **THEN** position, attempt counters, check verdicts, and metrics rebuilt from events match the final state

#### Scenario: Broken listener does not break the run
- **WHEN** the listener throws on every event
- **THEN** the run completes normally and each failure is logged

## REMOVED Requirements

### Requirement: External check poll loop
**Reason**: The single-poll port and its delivery precondition are replaced by the engine-owned
pending/poll lifecycle over the executor contract (`verification/pending-poll`), which keeps
the delivery precondition, the interval, the timeout class and the run-URL preservation (the run
URL on the SARIF run, `executor/wire-contract`, design D23).
**Migration**: `external` checks map to the `github-actions` or `http` built-in; the attempt
commit delivery check runs before the first `start` of any verdict executor.
