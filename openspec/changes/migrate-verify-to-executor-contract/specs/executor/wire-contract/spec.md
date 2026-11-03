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
**Notes**: Layered after `define-executor-contract`, which adds this capability and archives first; no requirement of that change is modified here.
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
