# verification/pending-poll — delta for migrate-verify-to-executor-contract

## Purpose

The engine-owned lifecycle of a verdict that is not ready yet: the pending key, where it is
persisted, how any factory instance resumes the poll, and how the wait ends.

## ADDED Requirements

### Requirement: Pending key is persisted before the first poll
When a verdict executor returns `pending`, the engine SHALL persist the key with the check
identity, attempt and start time in the task-branch state in one commit, pushed, before the
first `poll`. The key SHALL be opaque to the engine and capped in size.
**Implements**: FR2, NFR-R1 of migrate-verify-to-executor-contract

#### Scenario: Key lands before polling
- **WHEN** `start` returns `pending`
- **THEN** the branch tip carries the key before any `poll` call is made

#### Scenario: Oversized key is infrastructure
- **WHEN** a key exceeds the cap
- **THEN** the check resolves as cannot-verify naming the cap, no poll is made

### Requirement: Poll loop with interval and timeout class
The engine SHALL call `poll(key)` at the check's `interval`, passing the interval as the
executor's in-poll wait bound, until a terminal status or the check's `timeout`; expiry SHALL
classify per the declared timeout class, and `absent after timeout` of an expected context
SHALL be infrastructure regardless of class.
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
infrastructure failure.
**Implements**: FR3, NFR-R1, NFR-R4, UX3 of migrate-verify-to-executor-contract

#### Scenario: Kill between start and persist re-drives start
- **WHEN** the factory is killed after `start` returned `pending` and before the key commit
- **THEN** the resuming instance calls `start` again and persists the new key

#### Scenario: Kill after persist resumes the poll
- **WHEN** the factory is killed after the key commit
- **THEN** the resuming instance polls with the persisted key, makes no `start` call, and the
  ledger shows the resume

#### Scenario: Second recovery is a no-op
- **WHEN** recovery runs on a branch whose poll already resolved
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
check's ledger line SHALL carry `executor=<name> status=<status>`.
**Implements**: NFR-O1, UX1 of migrate-verify-to-executor-contract

#### Scenario: Timeout names the missing contexts
- **WHEN** an `scm-status` check times out
- **THEN** its finding and log line name every expected context still absent
