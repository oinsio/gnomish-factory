# stage-engine — delta for add-command-executor

## ADDED Requirements

### Requirement: Executor-reported quality failure
An executor SHALL be able to return a `Failed` result — findings plus the round's usage
and trace — instead of `Completed` or `DecisionNeeded`. The engine SHALL treat a
`Failed` round as a quality failure: the attempt is burned, the round is recorded with
its telemetry and persisted under the same strict-persistence ordering
(persist → AttemptFinished → next AttemptStarted) and the same engine events as any
round, and the verify chain is NOT invoked for that round. The `Failed` findings SHALL
feed the feedback of every subsequent attempt of the stage exactly as a failing check's
findings do, and attempt exhaustion SHALL escalate as `AttemptsExhausted` with the full
recorded history. All other executor results and the attempt-loop, persistence, and
escalation machinery SHALL be unchanged.
<!-- implements FR6, NFR-R1, NFR-R2, NFR-O1 of add-command-executor -->

#### Scenario: Failed round burns an attempt and feeds forward
- **WHEN** the executor returns `Failed` with one finding on attempt 1
- **THEN** `attemptsUsed` becomes 1, the round is recorded and persisted, and attempt
  2's executor request contains that finding in its feedback

#### Scenario: Verify chain is not invoked for a failed round
- **WHEN** the executor returns `Failed` for a stage declaring three verify checks
- **THEN** no check runs for that round and the round's record carries the quality
  failure

#### Scenario: Failed rounds count toward exhaustion
- **WHEN** every permitted attempt of a stage ends in an executor-reported `Failed`
- **THEN** the outcome is Escalated(AttemptsExhausted) and the final state lists every
  recorded round with its findings

#### Scenario: Failed round keeps its telemetry
- **WHEN** the executor returns `Failed` carrying usage and wall time
- **THEN** the recorded round carries that telemetry exactly as a `Completed` round's
  record would
