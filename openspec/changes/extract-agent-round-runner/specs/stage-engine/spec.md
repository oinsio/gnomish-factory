# stage-engine — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: Judge votes are verdict requests
A `judge` check SHALL resolve to a named executor in role `verdict`; the engine SHALL issue
`votes` independent `start` requests, derive each vote's verdict through the one verdict
derivation, and reduce with the existing majority rule; any vote with a malformed answer SHALL
make the whole check cannot-verify. `EnginePorts` SHALL expose no `JudgeVoter`.
<!-- implements FR4, M3 of extract-agent-round-runner -->

#### Scenario: Majority preserved
- **WHEN** three votes return two passing and one failing verdict
- **THEN** the check passes and the aggregated findings are recorded as before

#### Scenario: One malformed vote
- **WHEN** one of three votes returns a malformed answer
- **THEN** the check is cannot-verify, no attempt is burned

#### Scenario: Only one executor-facing port remains
- **WHEN** the engine ports record is inspected
- **THEN** it holds a `StageExecutor` and no judge or check port

## REMOVED Requirements

### Requirement: Judge majority voting
**Reason**: Superseded by "Judge votes are verdict requests": the vote count, odd-votes rule and
majority reduction are unchanged, the port is replaced by the executor contract.
**Migration**: `judge` checks name an executor supporting `verdict`; `model` and `criteriaFile`
keep their meaning.
