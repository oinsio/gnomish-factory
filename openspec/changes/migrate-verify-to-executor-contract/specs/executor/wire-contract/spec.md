# executor/wire-contract — delta for migrate-verify-to-executor-contract

## Purpose

The one request/result contract every executor of the factory speaks — built-in or external,
in any role — including its status taxonomy, answer kinds, describe handshake and the rules
that let the contract evolve without breaking executors built against an older version.

## ADDED Requirements

### Requirement: A pending key stays pollable until its run ends
An executor whose describe declares `poll` SHALL answer a `poll` of every key it issued for a
run, from its `start` or from an earlier `poll`, until that run ends; a superseded key SHALL
reach the same run. A `poll` of a key whose run has ended SHALL return that run's terminal status
and answer again, unchanged, and SHALL NOT start the run anew.
**Implements**: FR11, NFR-R4 of migrate-verify-to-executor-contract
**Notes**: Layered after `define-executor-contract`, which adds this capability and archives first; the two requirements modified below are its text (design D14).
**Notes**: This is what lets a factory killed after a key rotation or after a terminal poll resume without a second `start` (design D7, D11).

#### Scenario: Re-poll of an ended run returns its result again
- **WHEN** a `poll` of a key returned a terminal status and the factory polls the same key again
- **THEN** the executor returns the same terminal status and answer, and no new run is started

#### Scenario: Superseded key still reaches its run
- **WHEN** a `poll` returned `pending` with a new key and the factory polls the previous key
- **THEN** the executor answers for the same run, `pending` or terminal, and does not refuse the key

#### Scenario: An executor that forgets an ended run is non-conforming
- **WHEN** a candidate refuses, or answers differently, a re-poll of a key whose run ended
- **THEN** the conformance kit's re-poll case fails naming the key, and the engine reads the refusal as `failed/infrastructure` naming the key, never as a new `start`

## MODIFIED Requirements

### Requirement: Poll of a program is refused without its preconditions
A poll of a program whose describe does not declare `poll`, or with a key that the polling
instance neither received from a `start` or `poll` of its own nor adopted from the task's
pending record on resume, SHALL be `failed/infrastructure` naming the reason, with no process
spawned. A key adopted from the pending record SHALL be polled as a key of the instance's own.
**Implements**: FR23 of define-executor-contract; FR3, FR11 of migrate-verify-to-executor-contract
**Notes**: Adoption happens on the resume path only, never from the request on the wire (design D14).

#### Scenario: Poll of a program without the capability
- **WHEN** a poll is asked of a program whose describe lists no `poll` capability
- **THEN** the result is `failed/infrastructure` naming the undeclared capability and no process is spawned

#### Scenario: Poll with a key this run did not issue
- **WHEN** a poll carries a key the executor instance neither received from a `start` or `poll` of its own nor adopted from the task's pending record
- **THEN** the result is `failed/infrastructure` naming the key as unknown and no process is spawned

#### Scenario: Recorded key is polled by a resuming instance
- **WHEN** an instance that did not start the run resumes a check whose pending record names a key of a `program` executor declaring `poll`
- **THEN** the program is spawned with `poll` and that key, and no `start` is called

### Requirement: A pending judge is judged when it ends
The working-copy check of a `verdict` or `decision` run SHALL be judged at the poll that ends a
pending run, against the view taken before its `start` or, for a key adopted from the pending
record on resume, against the view taken of the resuming instance's working copy when the key
was adopted; a `pending` answer itself SHALL not be judged.
**Implements**: FR23, FR11 of define-executor-contract; FR3 of migrate-verify-to-executor-contract

#### Scenario: Pending judge is judged when it ends
- **WHEN** a `verdict` run returned `pending`, a file of the working copy changed after its `start`, and its poll returns `completed`
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path

#### Scenario: Adopted key is judged against the view taken at adoption
- **WHEN** a resuming instance adopted a recorded key, a file of its working copy changed after the adoption, and the poll returns `completed`
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path
