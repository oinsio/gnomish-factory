# verification/pending-poll Specification

## Purpose
The engine-owned lifecycle of a verdict that is not ready yet: the pending key, where it is
persisted, how any factory instance resumes the poll, and how the wait ends.

## Requirements

### Requirement: Pending key is persisted before the first poll
When a verdict executor returns `pending`, the engine SHALL persist the key with the check
identity, the attempt, the round's token and the start time as an entry of the open round's
state, in one pending commit on the round's last registered commit with its register, pushed,
before the first `poll`. The key SHALL be opaque to the engine and capped in size.
**Implements**: FR2, FR20, NFR-R1 of migrate-verify-to-executor-contract
**Notes**: The pending commit and its parent are stated in git-task-persistence, "Pending commits of an open round".

#### Scenario: Key lands before polling
- **WHEN** `start` returns `pending`
- **THEN** the registered commit carries the key before any `poll` call is made

#### Scenario: A pending entry names no outcome
- **WHEN** a pending commit is registered
- **THEN** its state's attempts, position and attempt counter equal those of the commit below it, and a pickup of it runs no stage executor

#### Scenario: An in-place run keeps the key in memory
- **WHEN** a check of a git-less in-place run returns `pending`
- **THEN** the key is held in the run's memory, no commit is written, and the poll proceeds

#### Scenario: Oversized key is infrastructure
- **WHEN** a key exceeds the cap
- **THEN** the check resolves as cannot-verify naming the cap, no poll is made

### Requirement: Poll loop with interval and timeout class
The engine SHALL call `poll(key)` no sooner than the larger of the check's `interval` and the
last pending answer's wait hint,
until a terminal status or the check's `timeout`; expiry SHALL classify per the declared
timeout class, and a timeout whose last pending answer was `unreported` or `unobserved` (an expected context absent, or a source not read) SHALL be
infrastructure regardless of class.
**Implements**: FR2, FR4 of migrate-verify-to-executor-contract

#### Scenario: Timeout with a quality class
- **WHEN** a `quality` check polls past its timeout with a running context
- **THEN** the check fails as a quality failure naming the timeout

#### Scenario: Expected context never appeared
- **WHEN** a `quality` check polls past its timeout and an expected context was never reported
- **THEN** the check resolves as cannot-verify naming the absent context, no attempt burned

### Requirement: Any instance resumes a persisted poll
A resuming instance SHALL read the persisted key and continue with `poll(key)`; it SHALL NOT
call `start` again. A persisted key for an executor that no longer declares `poll` SHALL be an
infrastructure failure. It SHALL continue every entry of the round, and only the entries whose
attempt and round token name the round it resumes.
**Implements**: FR3, FR20, NFR-R1, NFR-R4, UX3 of migrate-verify-to-executor-contract

#### Scenario: Kill between start and persist re-drives start
- **WHEN** the factory is killed after `start` returned `pending` and before the key commit
- **THEN** the resuming instance calls `start` again and persists the new key

#### Scenario: Kill after persist resumes the poll
- **WHEN** the factory is killed after the key commit
- **THEN** the resuming instance polls with the persisted key, makes no `start` call, and the
  ledger shows the resume

#### Scenario: Every pending check of the round is continued
- **WHEN** two checks of one round went `pending`, the first one ended, and the factory is killed while the second is polled
- **THEN** the resuming instance polls both recorded keys and calls `start` for neither

#### Scenario: An entry of another round is not continued
- **WHEN** the resumed state holds an entry whose attempt or round token differs from the round being verified
- **THEN** the entry is not polled and the check calls `start`

#### Scenario: Second recovery is a no-op
- **WHEN** recovery runs on a branch whose round's closing commit already landed
- **THEN** nothing is re-polled and the state is unchanged

#### Scenario: Kill after the terminal poll re-polls the persisted key
- **WHEN** the factory is killed after a `poll` returned a terminal status and before the attempt record carrying the verdict was pushed
- **THEN** the resuming instance polls the persisted key again, makes no `start` call, and resolves the check with the terminal result that poll returns

### Requirement: A changed pending key is persisted before the next poll
When a `poll` returns `pending` with a key different from the recorded one, the engine SHALL
persist the new key in the pending record, in one commit with its register, pushed, before the
next `poll`. This SHALL hold for every pending record the engine's one pending-poll owner holds,
whatever its subject. A pickup SHALL poll the key the registered commit records.
**Implements**: FR10, NFR-R4 of migrate-verify-to-executor-contract
**Notes**: `extract-agent-round-runner` reuses this requirement for a pending `transform` round (its FR12); it adds no second one.

#### Scenario: New key lands before the next poll
- **WHEN** a poll returns `pending` with a new key
- **THEN** the registered commit carries the new key before the next `poll` call is made

#### Scenario: Kill before the new key lands polls the previous key
- **WHEN** the factory is killed after a poll returned a new key and before its commit was pushed
- **THEN** the resuming instance polls the previous key, makes no `start` call, and a refusal of that key is `failed/infrastructure` naming the key, never a silent restart

### Requirement: Poll observability
Each `poll` SHALL log executor, key prefix, elapsed and remaining time under the task's MDC; the
check's ledger line SHALL carry `executor=<name> status=<status>`. A timeout's finding or
cannot-verify SHALL carry as its details the reason of the last pending answer whose
`pendingState` was not `unobserved`, followed by the last answer's reason when that answer was
`unobserved`; when every answer was `unobserved`, the last reason alone.
**Implements**: NFR-O1, UX1 of migrate-verify-to-executor-contract

#### Scenario: Timeout names the missing contexts
- **WHEN** an `scm-status` check times out
- **THEN** its finding and log line name every expected context still absent

#### Scenario: Timeout after a rate-limited read still names the missing contexts
- **WHEN** an `scm-status` check's answer names an absent expected context, its next read is rate-limited with a wait hint reaching past the timeout, and the check times out
- **THEN** the cannot-verify names the absent context, followed by the rate-limit reason
