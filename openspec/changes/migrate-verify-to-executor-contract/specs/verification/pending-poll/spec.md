# verification/pending-poll — delta for migrate-verify-to-executor-contract

## Purpose

The engine-owned lifecycle of a verdict that is not ready yet: the pending key, where it is
persisted, how any factory instance resumes the poll, and how the wait ends.

## ADDED Requirements

### Requirement: Pending key is persisted before the first poll
When a verdict executor returns `pending`, the engine SHALL persist the key with the check
identity, attempt and start time in the task-branch state in one commit, pushed, before the
first `poll`. The key SHALL be opaque to the engine and capped in size.
<!-- implements FR2, NFR-R1 of migrate-verify-to-executor-contract -->

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
<!-- implements FR2, FR4 of migrate-verify-to-executor-contract -->

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
<!-- implements FR3, NFR-R1, UX3 of migrate-verify-to-executor-contract -->

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

### Requirement: Poll observability
Each `poll` SHALL log executor, key prefix, elapsed and remaining time under the task's MDC; the
check's ledger line SHALL carry `executor=<name> status=<status>`.
<!-- implements NFR-O1, UX1 of migrate-verify-to-executor-contract -->

#### Scenario: Timeout names the missing contexts
- **WHEN** an `scm-status` check times out
- **THEN** its finding and log line name every expected context still absent
