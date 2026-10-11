# executor/claude-dialect — delta for extract-agent-round-runner

## Purpose

The observable behavior of the Claude Code dialect: how the contract's request becomes a
`claude` invocation and how its event stream becomes a result.

## ADDED Requirements

### Requirement: Claude describe
The Claude dialect SHALL describe roles `transform`, `verdict`, `decision`; capabilities
`streams_progress`, `cancel`, `resumable`; enforcement level `TOOL_GATE`; credentials
`ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`,
`ANTHROPIC_MODEL`; egress host from the configured base URL; config-directory variable
`CLAUDE_CONFIG_DIR` and the flags that disable repo-local policy sources; a settings schema
with `allowedTools`, `disallowedTools`, `maxTurns`, `roundTimeout` and the three bounds.
<!-- implements FR3, FR7, NFR-S1 of extract-agent-round-runner -->

#### Scenario: Settings typo is a load error
- **WHEN** a judge check's settings contain `allowedTols`
- **THEN** loading reports a located error naming the stage, the check and the key

#### Scenario: No source names the credentials but the dialect
- **WHEN** production sources are grepped for `ANTHROPIC_`
- **THEN** hits exist only in the Claude dialect package

### Requirement: Policy compilation
The dialect SHALL compile `workspaceWrite` to `--permission-mode acceptEdits`, `readOnly` to
`--permission-mode dontAsk` with a read-only tool set that settings may only narrow, and SHALL
always pass `--strict-mcp-config`; no rendered argv SHALL carry the mode that skips permission
checks.
<!-- implements FR3, FR5 of extract-agent-round-runner -->

#### Scenario: Verdict role is read-only
- **WHEN** a `verdict` request compiles
- **THEN** argv carries `dontAsk`, a read-only tool set and `--strict-mcp-config`, never
  `acceptEdits` or `bypassPermissions`

#### Scenario: Settings cannot widen a read-only run
- **WHEN** a `verdict` request's settings list a write-capable tool
- **THEN** the effective tool set stays read-only

### Requirement: Schema-enforced answers
For `verdict` and `decision` requests the dialect SHALL pass the answer's JSON Schema to the
CLI (`--json-schema`) and SHALL write the validated final output to the answer file; a final
output that fails the schema SHALL be reported as a malformed answer, never as a pass.
<!-- implements FR3, FR4 of extract-agent-round-runner -->

#### Scenario: Judge verdict arrives as SARIF
- **WHEN** a `verdict` run completes
- **THEN** the answer file holds a SARIF document derived from the schema-validated output

#### Scenario: Malformed final output
- **WHEN** the CLI's final output does not match the schema
- **THEN** the result carries a malformed answer and the check resolves as cannot-verify

### Requirement: Stream parsing preserved
The dialect SHALL keep today's stream-json behavior: prompt on stdin, `init`, `assistant` and
`result` events, per-model token usage, top-level tool trace, the result event as essential
and other telemetry best-effort; the session id from `init` is the continuation key.
<!-- implements FR3, FR9, NFR-O1 of extract-agent-round-runner -->

#### Scenario: Reference transcripts still parse
- **WHEN** the committed reference stream-json fixtures are replayed through the dialect
- **THEN** usage, trace and final text equal the recorded expectations

#### Scenario: Resume flag on continuation
- **WHEN** a `start` carries a continuation key
- **THEN** argv carries `--resume <session id>`
