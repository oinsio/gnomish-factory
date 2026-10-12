# executor/agent-round-runner — delta for extract-agent-round-runner

## Purpose

The one runner that executes an agent CLI round in any role, and the dialect contract that
makes a vendor's CLI one replaceable implementation behind it.

## ADDED Requirements

### Requirement: One runner serves every role
The agent round runner SHALL execute `transform`, `verdict` and `decision` requests through one
code path: argv from the dialect, launch through the environment seam, concurrent capped
drains, three bounds, result and answer through the shared result-file reader, denials from
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
bounds, answer schema, session resume), event parsing into progress and usage, final-answer
extraction, exit-code mapping to status with `limit` for turn or budget stops, and
continuation-key extraction. The runner SHALL depend on nothing else from the vendor.
**Implements**: FR2, FR8, G1 of extract-agent-round-runner

#### Scenario: Second dialect needs no runner change
- **WHEN** a fake dialect with a different event format is registered in test fixtures
- **THEN** all three roles complete through the unchanged runner

#### Scenario: Exit without result is infrastructure
- **WHEN** the CLI exits non-zero having emitted no result event
- **THEN** the status is `failed/infrastructure` naming the exit code and the stderr tail

#### Scenario: Turn limit is a limit stop
- **WHEN** the dialect reports a turn-limit stop
- **THEN** the status is `failed/limit` naming the bound and no attempt is burned

### Requirement: Bounded drains and three bounds
Stdout and stderr SHALL be drained concurrently from launch with byte and line caps; the
startup-read, silence and overall bounds SHALL be applied; expiry SHALL kill the process tree
and report `failed/infrastructure` naming the bound.
**Implements**: FR1, FR8, NFR-R1 of extract-agent-round-runner

#### Scenario: Oversized stdout does not grow the heap
- **WHEN** the CLI emits events past the byte cap
- **THEN** parsing continues on a capped buffer, truncation is logged, the round completes

#### Scenario: Silence bound on virtual time
- **WHEN** no event arrives for longer than the silence bound
- **THEN** the tree is killed and the result names the silence bound

### Requirement: Decision continuation through the session
A `decision-request` answer SHALL carry the dialect's session id as the continuation key; a
later `start` carrying the continuation and the answer SHALL resume that session; a session
the CLI no longer knows SHALL fall back to a fresh start with the answer in feedback, logged.
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: Answered question continues the session
- **WHEN** a round ends with a decision request and the answer arrives
- **THEN** the next round's argv carries the resume flag with the session id and the ledger
  shows `continued session=<prefix>`

#### Scenario: Unknown session falls back
- **WHEN** the CLI refuses the resume
- **THEN** a fresh round starts with the answer in feedback and a WARN line names the fallback

### Requirement: Vendor-neutral progress
Progress events SHALL carry model, tool name, final summary and usage only; no vendor field
(result subtype, session id) SHALL appear in an event type outside the dialect package.
**Implements**: FR6 of extract-agent-round-runner

#### Scenario: Listener sees no vendor field
- **WHEN** a recording listener subscribes to a Claude round
- **THEN** every observed event type is defined outside the dialect package and carries no
  session id or subtype
