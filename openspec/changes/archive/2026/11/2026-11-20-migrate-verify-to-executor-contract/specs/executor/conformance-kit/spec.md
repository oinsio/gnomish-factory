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

### Requirement: Pending cases poll from a new working copy
The conformance kit's pending-then-poll and re-poll cases SHALL send every `poll` with a request
naming a working copy other than the one `start` ran in, whose content follows the case's role:
for `verdict` or `decision` the content before `start`, for `transform` the files of `start`'s
working copy at the poll without its `.git`. A candidate passes them only when what a poll of its
key needs lives outside its working copy.
**Implements**: FR12, FR11 of migrate-verify-to-executor-contract
**Notes**: Design D11 as amended 2026-11-14; the rule Concourse states for its reused `check` containers — correctness holds from a cold container — made a case of the kit, so reusing a box later (proposal NG16) is only a cache. The role split mirrors the engine: a fresh-box verdict poll runs in a clean box at the attempt commit, a transform poll over the pending round commit, which carries `start`'s writes (FR10 of `extract-agent-round-runner`).
**Notes**: One poll copy per case serves all of its polls, and the poll is answered from the same pending table `start` filled, as one run of the engine holds one; the case's verdict joins what both copies observed. Known limits: a re-poll is not sent from a second new copy, and a candidate declaring several live roles is run in the first, as every pending case is.

#### Scenario: Reference executor passes the pending cases from a new working copy
- **WHEN** the kit runs the pending-then-poll and re-poll cases against the reference executor
- **THEN** each poll names a working copy other than `start`'s, holding the content its role prescribes, and both cases pass

#### Scenario: A verdict candidate polls from the content before start
- **WHEN** the kit runs the pending-then-poll case against a candidate whose first live role is `verdict`
- **THEN** the poll's working copy holds the content before `start`, none of the files `start` wrote

#### Scenario: A candidate that keeps its run in its working copy fails
- **WHEN** the kit runs against the fake preset that records its pending run in its working copy's `.git` at `start` and reads it back at `poll`
- **THEN** the pending-then-poll case fails with a detail naming the key and the refused poll

### Requirement: An in-process candidate carries the settings of its ordinary cases
An in-process candidate SHALL carry the settings the kit sends in its ordinary cases, and the
settings-schema case SHALL judge those same settings, so a built-in whose settings schema
requires a key is run, not reported not applicable. The kit SHALL drive such a candidate bound
to each case's own working copy. A program candidate SHALL keep empty ordinary settings, and the
command's options SHALL be unchanged.
**Implements**: FR1, FR13 of migrate-verify-to-executor-contract
**Notes**: `files-exist` requires `files` and `http` requires `url`; empty settings would fail their schemas (design D18).

#### Scenario: files-exist with its declared files passes the verdict case
- **WHEN** the kit runs the `files-exist` built-in in process with ordinary settings declaring `files` and a case working copy that holds every listed path
- **THEN** the settings-schema case passes, the verdict case runs against that case's working copy and passes, and no case is reported not applicable for its settings
