# pipeline/stage-engine — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: A judge check is one verdict request
A `judge` check SHALL be one `verdict` request to the executor its `executor:` block declares,
run as a check round and judged by the one verdict derivation; an answer that is no verdict SHALL
make the check cannot-verify. The checks of a stage SHALL run in declaration order and stop at
the first that does not pass. `EnginePorts` SHALL expose no `JudgeVoter`.
**Implements**: FR4, M3 of extract-agent-round-runner

#### Scenario: The judge's verdict is the check's verdict
- **WHEN** the executor of a `judge` check answers with a failing verdict carrying two findings
- **THEN** the check fails with those two findings and exactly one request was made

#### Scenario: A malformed judge answer is cannot-verify
- **WHEN** the executor of a `judge` check returns an answer that is no verdict
- **THEN** the check is cannot-verify, no attempt is burned

#### Scenario: A second judge runs only after the first passes
- **WHEN** a stage declares two `judge` checks and the first one fails
- **THEN** the second is never requested
- **AND** when the first passes, the second is requested and its verdict decides the stage

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
call `start`.
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

## MODIFIED Requirements

### Requirement: Failure classification
Verdicts SHALL be Pass, Fail (findings, possibly empty), or CannotVerify (reason, details). Fail increments the attempt counter and feeds the non-passing check results (every non-Pass result, including CannotVerify) of all prior attempts of the stage into the next executor request; CannotVerify escalates without burning an attempt. Each check situation SHALL classify as the scenarios below state.
**Implements**: FR4 of add-stage-engine; FR4 of extract-agent-round-runner
**Notes**: The scenario "A judge check classifies by its votes" keeps its title from before votes were removed; a judge check now has one verdict.

#### Scenario: Classification table verified
- **WHEN** each classification case this requirement's scenarios state is produced by a fake port
- **THEN** the engine classifies it as stated (data-driven spec, one row per case)

#### Scenario: A command check classifies by how it ended
- **WHEN** a `command` check's process exits with a non-zero code
- **THEN** the check is Fail
- **AND** a `command` check whose binary is not found or cannot start is CannotVerify

#### Scenario: An external check classifies by its poll
- **WHEN** an `external` check's poll returns failure
- **THEN** the check is Fail
- **AND** a poll timeout is Fail under the timeout class `quality` (the default) and CannotVerify under `infrastructure`
- **AND** a check id unknown to the service is CannotVerify

#### Scenario: A judge check classifies by its votes
- **WHEN** a `judge` check's verdict is negative
- **THEN** the check is Fail
- **AND** a model reply unparseable as a verdict is CannotVerify

#### Scenario: A throwing adapter keeps its stack trace
- **WHEN** a check adapter throws
- **THEN** the engine catches the exception and the check's CannotVerify carries the exception's stack trace

#### Scenario: Feedback carries the whole stage history
- **WHEN** attempt 3 starts after two quality failures
- **THEN** the executor request contains the non-passing check results of attempts 1 and 2

## REMOVED Requirements

### Requirement: Judge majority voting
**Reason**: Judge votes are removed (human decision 2026-11-22; proposal NG10): a `judge` check
is one request ("A judge check is one verdict request"), and a second opinion is a second
`judge` check in the stage's `verify` list.
**Migration**: remove `votes` from every `judge` check — a manifest that carries it is refused
at load; declare one `judge` check per opinion wanted.
