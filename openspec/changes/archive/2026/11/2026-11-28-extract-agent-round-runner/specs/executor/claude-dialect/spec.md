# executor/claude-dialect — delta for extract-agent-round-runner

## Purpose

The observable behavior of the Claude Code dialect: how the contract's request becomes a
`claude` invocation and how its event stream becomes a result.

## ADDED Requirements

### Requirement: Claude describe
The Claude dialect SHALL describe roles `transform`, `verdict`, `decision`; capabilities
`streams_progress`, `cancel`; enforcement level `TOOL_GATE`; its credentials, egress
host and config-directory isolation; and a settings schema that accepts exactly `allowedTools`,
`disallowedTools`, `maxTurns` and `roundTimeout`.
**Implements**: FR3, FR7, NFR-S1 of extract-agent-round-runner

#### Scenario: Describe names the credentials, the egress host and the config isolation
- **WHEN** the Claude dialect's describe is read
- **THEN** its credentials are `ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN`,
  `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` and `ANTHROPIC_MODEL`, its egress host is the host
  of the configured base URL, and its config-directory variable is `CLAUDE_CONFIG_DIR` together
  with the flags that disable repo-local policy sources

#### Scenario: Settings typo is a load error
- **WHEN** a judge check's settings contain `allowedTols`
- **THEN** loading reports a located error naming the stage, the check and the key

#### Scenario: No source names the credentials but the dialect
- **WHEN** production sources are grepped for `ANTHROPIC_`
- **THEN** hits exist only in the Claude dialect package

### Requirement: Claude's liveness cadence
While one tool call runs quietly for longer than the factory's silence bound, the Claude CLI,
started with the argv the dialect renders, SHALL write to stdout at gaps of at most a fifth of
that bound, on macOS and on Linux, whether the call runs in the main agent or inside a subagent.
**Implements**: FR17 of extract-agent-round-runner

#### Scenario: A quiet tool call in the main agent is reported within a fifth of the silence bound
- **WHEN** the real CLI runs one tool call that prints nothing for 420 seconds
- **THEN** the call runs to its end and the largest gap between two stdout lines is at most a
  fifth of the factory's silence bound, on macOS and in a Linux container

#### Scenario: A quiet tool call inside a subagent is reported within a fifth of the silence bound
- **WHEN** a subagent of the real CLI runs one tool call that prints nothing for 420 seconds
- **THEN** the call runs to its end and the largest gap between two stdout lines of the parent
  stream is at most a fifth of the factory's silence bound, on macOS and in a Linux container

### Requirement: Policy compilation
The dialect SHALL compile `workspaceWrite` to `--permission-mode acceptEdits`, `readOnly` to
`--permission-mode dontAsk` with a read-only tool set that settings may only narrow, and SHALL
always pass `--strict-mcp-config`; no rendered argv SHALL carry the mode that skips permission
checks.
**Implements**: FR3, FR5 of extract-agent-round-runner

#### Scenario: Verdict role is read-only
- **WHEN** a `verdict` request compiles
- **THEN** argv carries `dontAsk`, a read-only tool set and `--strict-mcp-config`, never
  `acceptEdits` or `bypassPermissions`

#### Scenario: Settings cannot widen a read-only run
- **WHEN** a `verdict` request's settings list a write-capable tool
- **THEN** the effective tool set stays read-only

### Requirement: Turn and budget bounds as hints
The dialect SHALL pass the request's turn and budget bounds to the CLI as hints (`--max-turns`
and the CLI's budget flag); the ledger SHALL enforce the bounds after the round regardless of
what the CLI honoured.
**Implements**: NFR-C1 of extract-agent-round-runner

#### Scenario: Turn and budget hints reach the argv
- **WHEN** a request carries a turn bound and a budget bound
- **THEN** the rendered argv carries `--max-turns` with the turn bound and the budget flag with the
  budget bound

### Requirement: Schema-enforced answers
For a request of every role the dialect SHALL pass the reply's JSON Schema to the CLI
(`--json-schema`), declaring the schema-enforced answer form; the validated final output SHALL
become the answer file's content; a final output that fails the schema SHALL be reported as a
malformed answer, never as a pass. Every reply schema SHALL have an object root, closed objects
with all properties required, and no combinator at the root.
**Implements**: FR3, FR4, FR19 of extract-agent-round-runner

#### Scenario: Judge verdict arrives as SARIF
- **WHEN** a `verdict` run completes
- **THEN** the answer file holds a SARIF document derived from the schema-validated output

#### Scenario: Malformed final output
- **WHEN** the CLI's final output does not match the schema
- **THEN** the result carries a malformed answer and the check resolves as cannot-verify

#### Scenario: A transform reply asks a decision through the schema
- **WHEN** a `transform` request is rendered and its round ends with a schema-valid reply that
  asks a decision
- **THEN** argv carries `--json-schema` with the transform reply schema, and the answer is the
  contract's decision request with the reply's question and options

#### Scenario: Every reply schema fits both schema families
- **WHEN** the reply schema of each role is read
- **THEN** its root is an object, every object in it sets `additionalProperties` to false and
  lists all its properties as required, and its root holds no `anyOf`, `oneOf` or `allOf`

### Requirement: Claude's schema-enforced answer holds on real rounds
The Claude CLI, started with the argv the dialect renders, SHALL end a `transform` round of many
tool calls that asks a decision, and a `verdict` on acceptance criteria that invite a long prose
reply, with a schema-valid structured output, on macOS and on Linux.
**Implements**: FR22 of extract-agent-round-runner

#### Scenario: A long transform round ends with a schema-valid decision request
- **WHEN** the real CLI runs a `transform` round of at least fifty tool calls whose instructions
  end by requiring a decision
- **THEN** its result event carries a structured output valid against the transform reply
  schema, on macOS and in a Linux container

#### Scenario: A verdict on prose-inviting criteria is schema-valid
- **WHEN** the real CLI judges acceptance criteria that ask for a detailed written review
- **THEN** its result event carries a structured output valid against the verdict reply schema,
  on macOS and in a Linux container

### Requirement: Stream parsing preserved
The dialect SHALL keep today's stream-json behavior: prompt on stdin, `init`, `assistant` and
`result` events, per-model token usage, top-level tool trace, the result event as essential
and other telemetry best-effort. No argv SHALL carry a session flag, and the session id SHALL
NOT leave the dialect package.
**Implements**: FR3, FR6, FR9, NFR-O1 of extract-agent-round-runner

#### Scenario: Reference transcripts still parse
- **WHEN** the committed reference stream-json fixtures are replayed through the dialect
- **THEN** usage, trace and final text equal the recorded expectations

#### Scenario: No argv carries a session flag
- **WHEN** a request of any role is rendered, with or without a continuation
- **THEN** argv carries none of `--resume`, `--continue` and `--session-id`, and the prompt is
  the role's input
