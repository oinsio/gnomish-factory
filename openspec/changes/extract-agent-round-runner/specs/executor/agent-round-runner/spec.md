# executor/agent-round-runner — delta for extract-agent-round-runner

## Purpose

The one runner that executes an agent CLI round in any role, and the dialect contract that
makes a vendor's CLI one replaceable implementation behind it.

## ADDED Requirements

### Requirement: One runner serves every role
The agent round runner SHALL execute `transform`, `verdict` and `decision` requests through one
code path: argv from the dialect, launch through the environment seam, a capped stdout drain
and the kept stderr file, three bounds, result and answer through the shared result-file reader, denials from
the environment, and the post-hoc working-copy check for `verdict` and `decision`.
**Implements**: FR1, NFR-S2 of extract-agent-round-runner

#### Scenario: Three roles, one runner
- **WHEN** a `transform`, a `verdict` and a `decision` request run with the fake dialect
- **THEN** each produces an `ExecutorResult` through the same launch, drain and read path, and
  only the dialect's argv and answer extraction differ

#### Scenario: Judge that modified the copy is refused
- **WHEN** a `verdict` run leaves a changed file in the working copy
- **THEN** the result is `failed/infrastructure` naming the path and no verdict is derived

### Requirement: Dialect contract
A dialect SHALL provide describe fields, argv rendering for a request (compiled policy, model,
bounds, answer schema), event parsing into progress and usage, final-answer extraction, and
exit-code mapping to status with `limit` for turn or budget stops. The runner SHALL depend on
nothing else from the vendor.
**Implements**: FR2, FR8, G1, UX2 of extract-agent-round-runner

#### Scenario: Second dialect needs no runner change
- **WHEN** a fake dialect with a different event format is registered in test fixtures
- **THEN** all three roles complete through the unchanged runner

#### Scenario: Exit without result is infrastructure
- **WHEN** the CLI exits non-zero having emitted no result event
- **THEN** the status is `failed/infrastructure` naming the exit code and the stderr tail

#### Scenario: Turn limit is a limit stop
- **WHEN** the dialect reports a turn-limit stop
- **THEN** the status is `failed/limit` naming the bound and no attempt is burned
- **AND** the escalation report reads `budget exhausted: <bound>`

### Requirement: Bounded drains and three bounds
Stdout SHALL be drained from launch through the one drain owner with byte and line caps; stderr
SHALL go to a file in the round scratch directory and be read, capped, after the exit
(`agent-executor`, "The round's stderr is kept"), never through a pipe of its own; the
startup-read, silence and overall bounds SHALL be applied; expiry SHALL kill the process tree
and report `failed/infrastructure` naming the bound.
**Implements**: FR1, FR8, NFR-R1 of extract-agent-round-runner

#### Scenario: Oversized stdout does not grow the heap
- **WHEN** the CLI emits events past the byte cap
- **THEN** parsing continues on a capped buffer, truncation is logged, the round completes

#### Scenario: Silence bound on virtual time
- **WHEN** no event arrives for longer than the silence bound
- **THEN** the tree is killed and the result names the silence bound

#### Scenario: A liveness line during a long tool call keeps the round alive
- **WHEN** the CLI writes nothing but a liveness line every 30 seconds for longer than the
  silence bound
- **THEN** the round is not killed on silence

### Requirement: The declared roundTimeout is the round's overall bound
The overall bound of a round SHALL be the `roundTimeout` of the request's settings when they
declare one and the factory's overall bound otherwise; the startup-read and silence bounds SHALL
be the factory's, and no settings key SHALL set them.
**Implements**: FR7, NFR-R1 of extract-agent-round-runner

#### Scenario: A declared roundTimeout is the round's overall bound
- **WHEN** a stage whose executor settings declare `roundTimeout: 28800s` runs a round that is
  still writing events after the factory's own overall bound has passed
- **THEN** the round is not killed at the factory's bound
- **AND** a round that outlives 28800 seconds is killed and reports `failed/infrastructure`
  naming the overall bound and that value

#### Scenario: Without a roundTimeout the factory's overall bound applies
- **WHEN** a stage whose executor settings declare no `roundTimeout` runs a round that outlives
  the factory's overall bound
- **THEN** the tree is killed and the result names the overall bound

#### Scenario: A judge vote is bounded by its check's roundTimeout
- **WHEN** a judge check's settings declare `roundTimeout` and one of its votes outlives it
- **THEN** that vote's process tree is killed and the vote is cannot-verify

### Requirement: An agent round keeps no state past its end
The `decision-request` answer of an agent round SHALL carry no continuation key, a key the agent
wrote included, and no agent executor SHALL declare `resumable`: the CLI's session lives on the
worker and is no record. The round after an answer SHALL be a fresh `start`
(`executor/agent-executor`, "A decision is rendered with the question it answers").
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: An agent-written continuation key is removed
- **WHEN** the agent writes a decision request carrying a `continuationKey` to the answer file
- **THEN** the result's answer carries the question and its options and no continuation key

#### Scenario: No agent executor declares resumable
- **WHEN** the describe of an agent executor built from the runner and a dialect is read
- **THEN** its capabilities do not contain `resumable`

### Requirement: Vendor-neutral progress
Progress events SHALL carry model, tool name, final summary and usage only; no vendor field
(result subtype, session id) SHALL appear in an event type outside the dialect package.
**Implements**: FR6 of extract-agent-round-runner

#### Scenario: Listener sees no vendor field
- **WHEN** a recording listener subscribes to a Claude round
- **THEN** every observed event type is defined outside the dialect package and carries no
  session id or subtype
