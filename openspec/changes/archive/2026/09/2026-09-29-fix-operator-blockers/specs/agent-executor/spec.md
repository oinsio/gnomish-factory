## MODIFIED Requirements

### Requirement: Hard-wired adapter policy
The following SHALL be adapter policy, not configuration: the judge runs strictly read-only (Read/Grep/Glob-class tools; a judge check's `allowedTools` may only narrow that set, never widen it); the executor round receives a pinpoint write allowance for exactly the decision-file path the adapter generated; transport flags (`-p`, `--output-format stream-json --verbose`) are protocol internals invisible to configuration; the model is not a setting — it is first-class manifest data (`executor.model`, the judge check's `model`) mapped to `--model`. The CLI permission mode SHALL be fixed per role: an executor round launches in the mode that auto-approves file edits inside its working directory (`acceptEdits`), a judge vote launches in the mode that denies every tool call outside its pre-approved set without waiting for an answer (`dontAsk`). No rendered command line SHALL ever carry the mode that skips all permission checks. Every executor round and judge vote SHALL launch with all MCP server configuration excluded (`--strict-mcp-config`), so neither the operator's own servers nor a `.mcp.json` in the working copy load into a round. Neither the permission mode nor the MCP exclusion SHALL be settable from the manifest or from operator configuration.
<!-- implements FR12, NFR-S1, NFR-S2, D7 of add-agent-executor -->
<!-- implements FR1, FR2, FR3, FR4, NFR-S1, NFR-S2, NFR-C1 of fix-operator-blockers -->

#### Scenario: Judge cannot widen its tools
- **WHEN** a judge check's settings request a write-capable tool in `allowedTools`
- **THEN** the effective tool set for the vote remains read-only

#### Scenario: Executor round can edit files with no wrapper
- **WHEN** an executor round launches with the stock `claude` binary and a stage whose `allowedTools` lists `Edit` and `Write`
- **THEN** its command line carries `--permission-mode acceptEdits` and `--strict-mcp-config`
- **AND** the gnome's edits inside the working directory are applied without any approval prompt

#### Scenario: Judge never auto-approves an edit
- **WHEN** a judge vote launches
- **THEN** its command line carries `--permission-mode dontAsk` and `--strict-mcp-config`
- **AND** never `acceptEdits` or `bypassPermissions`

#### Scenario: Manifest cannot set the permission mode
- **WHEN** a stage's `settings` contain `permissionMode`
- **THEN** startup fails before any round with the existing unknown-settings-key error naming the stage and the key

#### Scenario: Project MCP configuration starts nothing
- **WHEN** the working copy contains a `.mcp.json` declaring a server command
- **THEN** no round or vote starts that server

### Requirement: CLI child environment is allowlisted
The environment passed to CLI processes SHALL be the layered allowlist of the execution-environment capability: the adapter's base set, operator passthrough names, the AI base-url/auth-token seam variables, and required tool variables — nothing else. No factory-process variable SHALL be inherited implicitly. The AI seam SHALL carry the agent CLI's own credentials — `CLAUDE_CODE_OAUTH_TOKEN` (a subscription token minted with `claude setup-token`) and `ANTHROPIC_API_KEY` — beside `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` and `ANTHROPIC_MODEL`: each is read live from the factory environment and set on every agent round and judge vote when present, omitted when absent, in host and container mode alike. Seam variables SHALL be set on agent rounds and judge votes only, never on command checks or other execs.
<!-- implements FR9 of add-sandbox-core -->
<!-- implements FR5, NFR-S3 of fix-operator-blockers -->

#### Scenario: Tracker token is absent from the round
- **WHEN** an agent round starts while the factory holds the tracker token in its own environment
- **THEN** the CLI process environment contains no tracker token and no variable outside the allowlist

#### Scenario: Subscription token reaches the box with no passthrough
- **WHEN** the factory environment holds `CLAUDE_CODE_OAUTH_TOKEN`, the stage is container-bound, and `factory.sandbox.env-passthrough` is empty
- **THEN** the agent round's environment inside the box contains `CLAUDE_CODE_OAUTH_TOKEN` with the current value

#### Scenario: Agent credential stays out of command checks
- **WHEN** the factory environment holds `ANTHROPIC_API_KEY` and a stage runs an agent round followed by a `command` check, with no passthrough configured
- **THEN** the agent round's environment contains `ANTHROPIC_API_KEY`
- **AND** the command check's environment does not

### Requirement: Result event is essential, telemetry is best-effort
The adapter SHALL treat the stream-json result event as essential — a missing or unparseable result event is an infrastructure failure of the round — while telemetry parsing is best-effort: on telemetry parse trouble the round SHALL still complete with `ExecutorUsage.none()` and an empty trace. Unknown event types and unknown fields SHALL be ignored silently. The missing-result failure SHALL report how much of the stream was read (bytes and parsed-event count), and when the read volume is consistent with a filled OS pipe buffer the message SHALL name stream truncation as the likely cause — so a human can tell "the agent emitted no result" apart from "the stream was cut short" without reading adapter source. A result line whose subtype names an error or a limit (`error_max_turns`, `error_during_execution`, `error_max_budget_usd`, ...) SHALL be the round's result event even though, by the CLI's contract, it carries no `result` field: the event carries empty result text and the subtype verbatim. A `success` result line without a `result` field SHALL remain unparseable.
<!-- implements FR4, NFR-R1, NFR-R2, D3 of add-agent-executor -->
<!-- implements FR5, NFR-R2, UX2 of fix-round-stdout-drain -->
<!-- implements FR15 of fix-operator-blockers -->

#### Scenario: Telemetry failure does not fail the round
- **WHEN** usage fields in an otherwise valid stream cannot be parsed
- **THEN** the round completes normally with `ExecutorUsage.none()` and an empty trace

#### Scenario: Missing result event is infrastructure
- **WHEN** the process exits without emitting a parseable result event
- **THEN** the round is an infrastructure failure and no stage attempt is burned

#### Scenario: Diagnostics carry read volume
- **WHEN** a round fails for want of a result event
- **THEN** the failure message reports the bytes and events read from the stream

#### Scenario: Truncation is hinted at the buffer boundary
- **WHEN** a result-less stream's read volume sits at an OS pipe-buffer boundary
- **THEN** the failure message names probable stream truncation as the likely cause

#### Scenario: Limit-ended round carries its result event
- **WHEN** the CLI ends a round on its turn limit with a result line of subtype `error_max_turns` and no `result` field
- **THEN** the round has its result event with subtype `error_max_turns` and empty result text
- **AND** the round is not reported as a missing-result infrastructure failure
