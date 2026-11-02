# executor/conformance-kit — delta for migrate-verify-to-executor-contract

## Purpose

The executable definition of "speaks the executor contract": a command any executor author can
run against a candidate program, plus the reference executor and the misbehaving fake the
factory's own specs use.

## ADDED Requirements

### Requirement: Re-poll case pins the idempotent poll
The conformance kit SHALL run a re-poll case against a candidate whose describe declares `poll`,
driven by the `--pending-settings` option: `start` returns `pending`, the key is polled until a
terminal status, and the same key is polled once more. The case SHALL pass only when that poll
returns the same terminal status and answer; for a candidate without `poll` it SHALL be reported
as not applicable.
**Implements**: FR12, FR11 of migrate-verify-to-executor-contract
**Notes**: Layered after `define-executor-contract`, which adds this capability and its twelve cases.

#### Scenario: Reference executor passes the re-poll case
- **WHEN** the kit runs the re-poll case against the reference executor
- **THEN** the second poll of the ended run's key returns the terminal result of the first, and the case passes

#### Scenario: Fake that forgets an ended run fails the re-poll case
- **WHEN** the kit runs against the fake configured to break the re-poll case
- **THEN** exactly that case fails with a detail naming the key and the differing or refused second poll

#### Scenario: Re-poll case needs the poll capability
- **WHEN** the candidate's describe declares no `poll` capability
- **THEN** the kit reports the re-poll case as not applicable and sends no poll
