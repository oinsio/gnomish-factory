# pipeline/stage-engine — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: Judge votes are verdict requests
A `judge` check SHALL resolve to a named executor in role `verdict`; the engine SHALL issue
`votes` independent `start` requests, derive each vote's verdict through the one verdict
derivation, and reduce with the existing majority rule; any vote with a malformed answer SHALL
make the whole check cannot-verify. `EnginePorts` SHALL expose no `JudgeVoter`.
**Implements**: FR4, M3 of extract-agent-round-runner

#### Scenario: Majority preserved
- **WHEN** three votes return two passing and one failing verdict
- **THEN** the check passes and the aggregated findings are recorded as before

#### Scenario: One malformed vote
- **WHEN** one of three votes returns a malformed answer
- **THEN** the check is cannot-verify, no attempt is burned

#### Scenario: Only one executor-facing port remains
- **WHEN** the engine ports record is inspected
- **THEN** it holds a `StageExecutor` and no judge or check port

### Requirement: A pending transform round is persisted before it is polled
When a `transform` executor whose describe declares `poll` returns `pending`, the engine SHALL
land the round's working copy and a pending record (round token, stage, attempt, key, start time)
in one pending round commit with its register, pushed, before the first `poll`. That commit SHALL
close no attempt: the verify chain SHALL NOT run and the stage SHALL NOT advance on it.
**Implements**: FR10, FR11, NFR-R3 of extract-agent-round-runner

#### Scenario: Pending round commit lands before the first poll
- **WHEN** a `transform` executor declaring `poll` returns `pending` with a key
- **THEN** origin's registered commit carries the round's working copy and the pending record before any `poll` call is made

#### Scenario: A pending round is never verified
- **WHEN** a pickup reads a registered commit carrying a pending round record
- **THEN** no verify check starts, the position is unchanged, and the only action taken is a `poll` of the recorded key

#### Scenario: Pending without the poll capability stays refused
- **WHEN** a `transform` executor whose describe declares no `poll` returns `pending`
- **THEN** the round is `failed/infrastructure` naming the unsupported pending, and no pending record is written

### Requirement: A pending transform round is polled to its end
The engine SHALL poll a pending `transform` round at the factory's pending interval in the
round's environment until a terminal status, and SHALL close the round with that result as any
round, dropping the pending record in the closing commit. The wait SHALL end at the factory's
pending ceiling counted from the recorded start time, as `failed/infrastructure` naming it.
**Implements**: FR10, FR12, NFR-O2, UX3 of extract-agent-round-runner
**Notes**: A changed key follows "A changed pending key is persisted before the next poll" of `verification/pending-poll` (`migrate-verify-to-executor-contract`); for a round it lands in a pending round commit.

#### Scenario: Completed poll closes the round
- **WHEN** a poll of a pending round returns `completed`
- **THEN** the round closes with the working copy the poll left, the attempt record carries no pending record, the verify chain runs, and the ledger shows `pending key=<prefix>` for the round

#### Scenario: Restart does not reset the pending ceiling
- **WHEN** a pending round recorded its start time and is resumed after a restart
- **THEN** the remaining wait is the ceiling minus the time elapsed since the recorded start, and expiry is `failed/infrastructure` naming the ceiling

#### Scenario: A changed key of a pending round lands in a pending round commit
- **WHEN** a poll of a pending round returns `pending` with a key other than the recorded one
- **THEN** a pending round commit carrying the new key is pushed before the next `poll`, and the attempt stays open

### Requirement: Any instance resumes a pending transform round
A pickup that finds a pending round record at the registered commit SHALL continue that round
with `poll(key)` in an environment materialized at that commit, on any instance, and SHALL NOT
call `start`. The live path SHALL continue from the record read back from its own commit,
through the same parse as the resume.
**Implements**: FR11, NFR-R3, NFR-R4, G5 of extract-agent-round-runner; FR11 of migrate-verify-to-executor-contract

#### Scenario: Kill before the pending commit re-drives start
- **WHEN** the factory is killed after a `transform` `start` returned `pending` and before the pending round commit was pushed
- **THEN** the resuming instance re-runs the round with a new `start`, and the ledger names the re-driven start

#### Scenario: Kill after the pending commit resumes the poll
- **WHEN** the factory is killed after the pending round commit was pushed
- **THEN** a second instance polls the recorded key, makes no `start` call, and the ledger shows `resumed key=<prefix>`

#### Scenario: Kill after the terminal poll closes the round without a start
- **WHEN** the factory is killed after a poll of a pending round returned a terminal status and before the round close was pushed
- **THEN** a second instance polls the recorded key again, closes the round with the terminal result it returns, and makes no `start` call

#### Scenario: Resumed round keeps its identity
- **WHEN** a pending round is closed by a second instance
- **THEN** the closed attempt carries the round token the round went pending with, and the usage of its `start` and of every `poll` is recorded under that one round identity

#### Scenario: Second recovery of a pending round is a no-op
- **WHEN** recovery runs again on a branch whose pending round already closed
- **THEN** nothing is polled or started and the branch is unchanged

## REMOVED Requirements

### Requirement: Judge majority voting
**Reason**: Superseded by "Judge votes are verdict requests": the vote count, odd-votes rule and
majority reduction are unchanged, the port is replaced by the executor contract.
**Migration**: `judge` checks name an executor supporting `verdict`; `model` and `criteriaFile`
keep their meaning.
