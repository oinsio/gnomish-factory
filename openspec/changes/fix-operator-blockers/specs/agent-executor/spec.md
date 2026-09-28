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
