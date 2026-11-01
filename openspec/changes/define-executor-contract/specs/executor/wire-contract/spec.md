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
**Implements**: FR1, FR11 of define-executor-contract

#### Scenario: Role selects the inputs, not the shape
- **WHEN** a `verdict` request and a `transform` request are serialized for the same executor
- **THEN** both validate against the one request schema and differ only in `role` and the role
  inputs (criteria for `verdict`, instructions for `transform`)

#### Scenario: Effect role is refused by the engine in this version
- **WHEN** a stage or check names an executor whose describe lists only `effect` for the role the use site needs (`transform` for a stage, `verdict` for a check)
- **THEN** the describe preflight reports a located error naming the executor, the use site and `effect` as reserved; the law itself has no role field

### Requirement: Closed status taxonomy
Every result SHALL carry one status: `completed`; `failed` with class `quality`,
`infrastructure` or `limit`; or `pending` with an opaque key. No other status token SHALL be
accepted, and an unknown token SHALL be read as `failed/infrastructure` naming the token.
**Implements**: FR2, NFR-R3 of define-executor-contract

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
**Implements**: FR3, NFR-S2 of define-executor-contract

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
**Implements**: FR4, FR13 of define-executor-contract

#### Scenario: Version mismatch is refused before the first round
- **WHEN** an executor describes `protocolVersion: 2` and the factory speaks only `1`
- **THEN** the describe preflight reports a located error naming the executor and both versions, the task parks as it does for an invalid law, and no round runs

#### Scenario: Undeclared role is refused before the first round
- **WHEN** a stage names an executor in role `verdict` and its describe lists only `transform`
- **THEN** the describe preflight reports a located error naming the executor, the stage and the role, and no round runs

#### Scenario: Describe output is untrusted
- **WHEN** describe output exceeds the read cap or contains control characters in a text field
- **THEN** the preflight parks the task as a located configuration error (the program cannot be described) and no field reaches a log unsanitized

### Requirement: Poll of a program executor
A `program` executor whose describe declares `poll` SHALL be polled as it is started: the
declared argv with the word `poll` appended, the same request on standard input, the pending key
in `GNOMISH_PENDING_KEY`, the result in `GNOMISH_RESULT_FILE` read under the result reader's
rules, under the same bounds.
**Implements**: FR23 of define-executor-contract

#### Scenario: Poll is addressed like start
- **WHEN** a program that declared `poll` returned `pending` with a key and the factory polls it
- **THEN** the program is spawned with its declared argv and `poll` as the last argument, the same request on standard input and `GNOMISH_PENDING_KEY` carrying the key, and its result is read from the file `GNOMISH_RESULT_FILE` names

### Requirement: Poll of a program is refused without its preconditions
A poll of a program whose describe does not declare `poll`, or with a key the polling run did
not receive from its own `start`, SHALL be `failed/infrastructure` naming the reason, with no
process spawned.
**Implements**: FR23 of define-executor-contract

#### Scenario: Poll of a program without the capability
- **WHEN** a poll is asked of a program whose describe lists no `poll` capability
- **THEN** the result is `failed/infrastructure` naming the undeclared capability and no process is spawned

#### Scenario: Poll with a key this run did not issue
- **WHEN** a poll carries a key the executor instance did not receive from a `start` of its own
- **THEN** the result is `failed/infrastructure` naming the key as unknown and no process is spawned

### Requirement: A pending judge is judged when it ends
The working-copy check of a `verdict` or `decision` run SHALL be judged at the poll that ends a
pending run, against the view taken before its `start`; a `pending` answer itself SHALL not be
judged.
**Implements**: FR23, FR11 of define-executor-contract

#### Scenario: Pending judge is judged when it ends
- **WHEN** a `verdict` run returned `pending`, a file of the working copy changed after its `start`, and its poll returns `completed`
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path

### Requirement: Additive evolution
Both sides SHALL ignore unknown fields; a field SHALL never be renamed, retyped or removed
within a protocol version; every enum SHALL document its unknown-token arm.
**Implements**: FR4, NFR-R3 of define-executor-contract

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
**Implements**: FR11, NFR-S4 of define-executor-contract

#### Scenario: Judge that wrote a file is refused
- **WHEN** a `verdict` run leaves a modified file in the working copy
- **THEN** the verdict is discarded and the check resolves as cannot-verify naming the path

#### Scenario: Policy is not a vendor tool name
- **WHEN** a request is rendered for any executor
- **THEN** it contains only the abstract policy token and never a vendor tool identifier

### Requirement: Usage provenance and bounds as hints
Usage SHALL carry provenance `REPORTED` or `ABSENT`; absent SHALL never render as zero. Bounds
(turns, tokens, money, three time bounds) SHALL be passed in the request as hints; the factory
enforces the time bounds itself; the law declares no budget bound in this version (proposal NG12).
**Implements**: NFR-O1, NFR-R2, NFR-C1 of define-executor-contract

#### Scenario: Silence bound expires
- **WHEN** an executor declaring `streams_progress` emits nothing for longer than the silence bound
- **THEN** the run is `failed/infrastructure` naming the silence bound

#### Scenario: Limit stop is its own class
- **WHEN** an executor returns `failed/limit` after reaching a turn bound
- **THEN** no attempt is burned and the stage ends with a budget-exhausted escalation
