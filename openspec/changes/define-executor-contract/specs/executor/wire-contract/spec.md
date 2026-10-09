# executor/wire-contract — delta for define-executor-contract

## Purpose

The one request/result contract every executor of the factory speaks — built-in or external,
in any role — including its status taxonomy, answer kinds, describe handshake and the rules
that let the contract evolve without breaking executors built against an older version.

## ADDED Requirements

### Requirement: Four executor roles over one request shape
An executor run SHALL be requested in exactly one role: `transform` (change the working copy),
`verdict` (judge the working copy), `decision` (choose among options), `effect` (act outside
the branch; vocabulary reserved, not executed in this change). The request SHALL carry role,
workspace, abstract policy, role inputs, settings, bounds, prior feedback and an optional
continuation; role inputs differ, everything else is common.
<!-- implements FR1, FR11 of define-executor-contract -->

#### Scenario: Role selects the inputs, not the shape
- **WHEN** a `verdict` request and a `transform` request are serialized for the same executor
- **THEN** both validate against the one request schema and differ only in `role` and the role
  inputs (criteria for `verdict`, instructions for `transform`)

#### Scenario: Effect role is refused by the engine in this version
- **WHEN** a law declares a stage or check in role `effect`
- **THEN** loading reports a located error naming the role as reserved

### Requirement: Closed status taxonomy
Every result SHALL carry one status: `completed`; `failed` with class `quality`,
`infrastructure` or `limit`; or `pending` with an opaque key. No other status token SHALL be
accepted, and an unknown token SHALL be read as `failed/infrastructure` naming the token.
<!-- implements FR2, NFR-R3 of define-executor-contract -->

#### Scenario: Status tokens round-trip
- **WHEN** every status and class constant is written to the wire and read back
- **THEN** each reads back as itself, and the spec iterates the full constant set

#### Scenario: Unknown status fails closed
- **WHEN** a result file carries `status: "done"`
- **THEN** the engine records `failed/infrastructure` with a reason naming `done`

#### Scenario: Pending from a role whose caller cannot poll
- **WHEN** a `transform` executor returns `pending` in this version
- **THEN** the round is `failed/infrastructure` with a reason naming the unsupported pending

### Requirement: Optional structured answer
A result MAY carry one answer: `verdict` (SARIF 2.1.0 `runs[].results[]`; pass/fail derived by
the engine from result levels against the check's threshold), `select` (`chosen`, optional
`ranking`, `rationale`), or `decision-request` (`question`, at least two `options`, optional
continuation key). A malformed answer SHALL never be read as a pass.
<!-- implements FR3, NFR-S2 of define-executor-contract -->

#### Scenario: Verdict pass is derived, not declared
- **WHEN** a `verdict` answer carries only `warning`-level results and the check's threshold is
  `error`
- **THEN** the engine records a pass with the findings attached as advisory

#### Scenario: Malformed verdict is cannot-verify
- **WHEN** the answer file is present but not valid SARIF
- **THEN** the check resolves as cannot-verify naming the parse error, never as pass

#### Scenario: Decision request with one option is malformed
- **WHEN** a `decision-request` answer carries a single option
- **THEN** the result is treated as a malformed answer and the round's feedback names the
  missing option

### Requirement: Describe handshake
Before first use an executor SHALL answer `describe` with: integer `protocolVersion`,
supported roles, capabilities (`poll`, `cancel`, `streams_progress`, `resumable`), a JSON
Schema for its settings, credential variable names, egress hosts, its config-directory
variable, the flags that disable repo-local policy sources, and its policy enforcement level
(`OS_SANDBOX`, `TOOL_GATE`, `AGENT_ASKS`, `PROMPT_ONLY`).
<!-- implements FR4, FR13 of define-executor-contract -->

#### Scenario: Version mismatch is a load error
- **WHEN** an executor describes `protocolVersion: 2` and the factory speaks only `1`
- **THEN** law loading reports a located error naming the executor and both versions

#### Scenario: Undeclared role is refused at load
- **WHEN** a stage names an executor in role `verdict` and its describe lists only `transform`
- **THEN** loading reports a located error naming the executor, the stage and the role

#### Scenario: Describe output is untrusted
- **WHEN** describe output exceeds the read cap or contains control characters in a text field
- **THEN** the executor is rejected at load with a located error; no field reaches a log unsanitized

### Requirement: Additive evolution
Both sides SHALL ignore unknown fields; a field SHALL never be renamed, retyped or removed
within a protocol version; every enum SHALL document its unknown-token arm.
<!-- implements FR4, NFR-R3 of define-executor-contract -->

#### Scenario: Older executor survives a new optional field
- **WHEN** the factory adds an optional request field and an executor built before it runs
- **THEN** the run proceeds; the executor's result is read unchanged

#### Scenario: Newer executor field is ignored
- **WHEN** a result carries a field the factory does not know
- **THEN** the result is read without error and the field is dropped

### Requirement: Abstract policy and post-hoc verification
The request policy SHALL be one of `readOnly`, `writeExactly(path)`, `workspaceWrite`; the
executor compiles it to its own mechanism. After a `verdict` or `decision` run the factory
SHALL verify the working copy is unchanged; a change SHALL resolve the run as
`failed/infrastructure` naming the violated policy.
<!-- implements FR11, NFR-S4 of define-executor-contract -->

#### Scenario: Judge that wrote a file is refused
- **WHEN** a `verdict` run leaves a modified file in the working copy
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path

#### Scenario: Policy is not a vendor tool name
- **WHEN** a request is rendered for any executor
- **THEN** it contains only the abstract policy token and never a vendor tool identifier

### Requirement: Usage provenance and bounds as hints
Usage SHALL carry provenance `REPORTED` or `ABSENT`; absent SHALL never render as zero. Bounds
(turns, tokens, money, three time bounds) SHALL be passed in the request as hints; the factory
enforces time bounds itself and the ledger enforces budget after the round.
<!-- implements NFR-O1, NFR-R2, NFR-C1 of define-executor-contract -->

#### Scenario: Absent usage under a money bound
- **WHEN** a stage with a money bound names an executor whose describe reports no usage
- **THEN** loading reports a located error naming the executor and the bound

#### Scenario: Silence bound expires
- **WHEN** an executor declaring `streams_progress` emits nothing for longer than the silence bound
- **THEN** the run is `failed/infrastructure` naming the silence bound

#### Scenario: Limit stop is its own class
- **WHEN** an executor returns `failed/limit` after reaching a turn bound
- **THEN** no attempt is burned and the stage ends with a budget-exhausted escalation
