# Spec Delta: executor/agent-executor

## MODIFIED Requirements

### Requirement: Fresh CLI round per attempt
The CLI `StageExecutor` adapter SHALL run one `execute()` call as one fresh CLI process spawned via `exec()` of the bound task environment over the task working copy, with stream-json output, never reusing a CLI session between attempts; work carries across retries through the task working copy, and prior findings reach the agent only through the rendered `Request.feedback`.
**Implements**: FR1, D2 of add-agent-executor; FR4 of add-sandbox-core

#### Scenario: Retry is a fresh process
- **WHEN** attempt 2 of a stage executes after a quality failure
- **THEN** a new CLI process starts via the task environment with no session-resume flag and its prompt contains the rendered findings of attempt 1

### Requirement: Round prompt composition
The round prompt SHALL be built from the shared briefing sections (task goal, input artifacts, prior-attempt feedback, decisions, control-file content) followed by the executor epilogue: the stage's full verify plan including judge acceptance-criteria content, the decision-file instruction, and — on attempts after the first — a rework preamble stating that the working copy already contains the prior attempt's result and must be reworked, not restarted.
**Implements**: FR2, D8, D9 of add-agent-executor

#### Scenario: Verify plan is visible to the agent
- **WHEN** a stage with a command check and a judge check executes
- **THEN** the prompt lists both checks, including the judge's acceptance criteria text

#### Scenario: Rework preamble on retry only
- **WHEN** attempt 1 and attempt 2 prompts of the same stage are compared
- **THEN** only the attempt 2 prompt contains the rework preamble

### Requirement: Decision-file protocol
In git modes the decision request SHALL live in the working copy at `.gnomish-task/decisions/<stage>-a<attempt>-<token>.json` — the single gnome-writable path under `.gnomish-task/` — where `<token>` is the *round token*, the task branch's tip at the moment the round opened. The adapter SHALL pass that exact path via `$GNOMISH_DECISION_FILE`, read it through the environment after process exit, and map: that path present → `DecisionNeeded`, absent → `Completed`.
**Implements**: FR13, FR15, NFR-R4 of make-checkpoint-gate-durable; FR3, NFR-R3, NFR-O2, D1 of add-agent-executor; FR23 of add-sandbox-core
**Notes**: The factory mints the round token and no later round of the task repeats it, so the token names one round; the request rides the round's commit, which is how a pending escalation survives factory death and is resumable by any instance from the branch alone.

#### Scenario: Agent asks for a decision
- **WHEN** the agent writes `{"question": "Refactor or patch?", "options": ["refactor", "patch"]}` to the path `$GNOMISH_DECISION_FILE` names and exits
- **THEN** the adapter returns `DecisionNeeded` with that question and both options

#### Scenario: No signal means Completed
- **WHEN** the process exits without creating a file at the path `$GNOMISH_DECISION_FILE` names
- **THEN** the adapter returns `Completed`

#### Scenario: Garbage decision file is not lost
- **WHEN** the decision file contains unparseable text
- **THEN** the adapter returns `DecisionNeeded` whose question is the raw content, with empty options, and logs the content at WARN

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying a decision request but before the escalation is recorded
- **THEN** a resuming instance reads the request from the snapshot's tree at the round's token path and escalates with that question, without replaying the round
- **AND** the resumed round carries the token and the snapshot commit that record names — no new token is minted, no tip is re-read — so the state commit it lands is checked against that token as a live round's would be

#### Scenario: An answered request is not asked again
- **WHEN** a container-mode task parked on a decision request is answered, the stage restarts at attempt 0, and the next round's box is cloned from a tip that still carries the earlier request file
- **THEN** the next round is given a path under its own token, reads only that path, and maps to `Completed` unless the gnome wrote a new request there

#### Scenario: Stale request from another round is ignored
- **WHEN** a decision file named for a previous round — another stage, another attempt number, or the same stage and attempt number under an earlier round's token — is present in the working copy
- **THEN** the current round maps to `Completed` and the stale file changes nothing

#### Scenario: A decision file beside the real one changes nothing
- **WHEN** the gnome writes a decision file under any name other than the path `$GNOMISH_DECISION_FILE` names, or a killed round left one behind, and the round's own path holds nothing
- **THEN** the round maps to `Completed`, whether or not the other file was removed

#### Scenario: A salvage commit carries the request
- **WHEN** a round that wrote a decision request ends in a salvage commit instead of a snapshot commit
- **THEN** the request is on the branch under its token, as it would be after a snapshot commit

#### Scenario: A snapshot without a request resumes as Completed
- **WHEN** an interrupted verification resumes from a snapshot commit whose tree holds nothing at the round's token path
- **THEN** the resumed round maps to `Completed`

#### Scenario: A garbage request in a snapshot is re-raised as raw text
- **WHEN** an interrupted verification resumes from a snapshot commit whose token path holds unparseable text
- **THEN** the resumed round returns `DecisionNeeded` whose question is the raw content, with empty options, read as tolerantly as a live round

#### Scenario: Empty decision file asks a fallback question
- **WHEN** the decision file at the round's path is empty
- **THEN** the adapter returns `DecisionNeeded` with a fallback question text and empty options, and logs the parse trouble at WARN

#### Scenario: A decision round keeps its telemetry
- **WHEN** a live round ends with a decision request
- **THEN** the `DecisionNeeded` result carries the round's usage and trace, as a `Completed` result would

#### Scenario: In-place mode uses a temp file outside the workspace
- **WHEN** a round runs in the git-less in-place mode
- **THEN** `$GNOMISH_DECISION_FILE` names a per-round temp file outside the workspace, and the file is deleted after the adapter reads it

### Requirement: Stdout is drained continuously from launch
Both CLI round executions (executor and judge) SHALL consume and parse the process's stream-json stdout concurrently with the running process, starting immediately after launch — never deferring the read until after process exit. The drain mechanism SHALL be one shared implementation used by both executions.
**Implements**: FR1, FR2, FR3, FR6, FR7, NFR-R1, NFR-R2, NFR-P1, UX3 of fix-round-stdout-drain
**Notes**: A `roundTimeout` kill closes the stream, so EOF is the drain's expected end signal there, not an error.

#### Scenario: Megabyte stream keeps its result event
- **WHEN** a round's CLI emits over 1 MB of stream-json noise followed by a valid result event and exits
- **THEN** the round completes normally with the result event, usage, and trace extracted

#### Scenario: Chatty synchronous writer does not hang
- **WHEN** the CLI writes more than the pipe buffer synchronously before exiting
- **THEN** the process is never blocked on a full pipe and the round completes well within `roundTimeout`

#### Scenario: Timeout kill still classifies as infrastructure
- **WHEN** the process outlives `roundTimeout` and is killed mid-stream
- **THEN** the drain ends without error and the round is an infrastructure failure, exactly as before this change

#### Scenario: An interrupted wait is not blamed on the grace
- **WHEN** the round thread is interrupted while waiting for its drain to finish
- **THEN** the round is an infrastructure failure of its own kind — reported as an interruption, never as an expired tail-drain grace an operator is advised to raise

#### Scenario: No reader outlives the round
- **WHEN** a round ends by any path — normal exit, timeout kill, or adapter exception
- **THEN** the drain thread is finished or terminated and the stdout stream is closed

#### Scenario: Tail-drain grace is operator-tunable with a safe default
- **WHEN** no tail-drain-grace property is configured
- **THEN** rounds use a 5-second grace
- **AND** a configured value overrides it, while a non-positive or malformed value fails startup before any dialog

#### Scenario: The round waits for its drain after exit
- **WHEN** the process exits while the tail of its stream is still in the pipe
- **THEN** the round completes only after the drain has consumed that tail, waiting at most the tail-drain grace

#### Scenario: A drain unfinished after the grace is infrastructure
- **WHEN** the drain has not finished when the tail-drain grace after process exit runs out
- **THEN** the round is an infrastructure failure, never a round completed on a partial stream

#### Scenario: The grace is not a manifest setting
- **WHEN** a stage's `settings` name a tail-drain grace
- **THEN** startup fails before any dialog with the unknown-settings-key error, since the grace is an installation-level application property

#### Scenario: The grace is documented for operators
- **WHEN** the operator guide's installation-properties table is read
- **THEN** it lists the tail-drain-grace property with its 5-second default and its startup validation

### Requirement: Result event is essential, telemetry is best-effort
The adapter SHALL treat the stream-json result event as essential — a missing or unparseable result event is an infrastructure failure of the round — while telemetry parsing is best-effort: on telemetry parse trouble the round SHALL still complete with no tool usage and no token usage — the wall time is still the adapter's own measurement — and an empty trace.
**Implements**: FR4, NFR-R1, NFR-R2, D3 of add-agent-executor; FR5, NFR-R2, UX2 of fix-round-stdout-drain; FR15 of fix-operator-blockers
**Notes**: The read volume in a missing-result failure lets a human tell "the agent emitted no result" apart from "the stream was cut short" without reading adapter source; by the CLI's contract an error or limit result line (`error_max_turns`, `error_during_execution`, `error_max_budget_usd`, ...) carries no `result` field.

#### Scenario: Telemetry failure does not fail the round
- **WHEN** usage fields in an otherwise valid stream cannot be parsed
- **THEN** the round completes normally with no tool usage and no token usage (the wall time is
  still the adapter's), and an empty trace

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

#### Scenario: Every error subtype keeps its result event
- **WHEN** a result line carries an `error_`-prefixed subtype other than `error_max_turns` (`error_during_execution`, `error_max_budget_usd`) and no `result` field
- **THEN** the round has its result event with empty result text and the subtype verbatim

#### Scenario: Unknown events and fields are ignored
- **WHEN** a stream carries an event type or a field the adapter does not know, beside a valid result event
- **THEN** the unknown event or field is skipped, the result event is still read, and no warning or failure reports it; an unknown event type leaves only a DEBUG skip line naming it

#### Scenario: A success line without a result field is still missing
- **WHEN** the stream's only result line has subtype `success` and no `result` field
- **THEN** the round is a missing-result infrastructure failure

### Requirement: Per-model token mapping
The adapter SHALL report tokens per resolved model id from the result event's `modelUsage`, each entry carrying input, output, cache-creation, and cache-read counts; when the CLI emits no `modelUsage`, all usage SHALL fall back under the main model id from the init event. An empty map means unreported — never fabricated zeros.
**Implements**: FR5, NFR-C1, D4 of add-agent-executor

#### Scenario: Multi-model round preserved
- **WHEN** the result event reports usage for two model ids
- **THEN** `tokensByModel` carries both entries with their four token counts intact

#### Scenario: Old CLI fallback
- **WHEN** the result event has no `modelUsage` but the init event named the main model
- **THEN** the round's usage appears under that model id

### Requirement: Top-level tool trace with adapter-side timing
The trace SHALL contain top-level tool calls only, with nested subagent calls excluded by the parent-id field (absent field → record everything seen). The adapter SHALL timestamp events at read time: a call starts when its tool_use block is read and ends when the matching tool_result (by id) is read; a call orphaned by process death ends at process exit.
**Implements**: FR6, NFR-O3, D3 of add-agent-executor
**Notes**: Timing is telemetry-grade: overlapping parallel calls may sum beyond wall time.

#### Scenario: Subagent internals excluded
- **WHEN** a Task tool call spawns nested tool calls marked with a parent id
- **THEN** the trace contains the Task call only and aggregates count it once

#### Scenario: Orphaned call gets a real duration
- **WHEN** the process dies after a tool_use with no matching tool_result
- **THEN** that call's duration spans from its start to process exit

#### Scenario: Wall time does not depend on the stream
- **WHEN** a round's stream yields no usable telemetry
- **THEN** the round's `wallTime` still spans process start to process exit, as measured by the adapter

### Requirement: Live progress listener SPI
The adapter's parse loop SHALL emit sealed progress events — round started (model, session id), top-level tool started (name, no input payload), round finished (result subtype, token summary, the agent's final-message summary) — to registered listeners synchronously as each line is read, while the process is still running — never as a post-exit burst.
**Implements**: FR7, NFR-O1, UX1, D9, D10 of add-agent-executor; FR4, NFR-O1, UX1 of fix-round-stdout-drain

#### Scenario: Progress observable without logs
- **WHEN** a recording listener subscribes to a round with three tool calls
- **THEN** it observes round-started, three tool-started events, and round-finished with the final summary

#### Scenario: Broken listener does not break the round
- **WHEN** a listener throws on every event
- **THEN** the round completes normally

#### Scenario: Progress is live, not post-mortem
- **WHEN** the CLI emits a tool-started line while the process is still running
- **THEN** subscribed listeners observe the event before the process exits

#### Scenario: Listeners are invoked from the drain thread
- **WHEN** a round's stream is parsed while the process runs
- **THEN** listeners are invoked synchronously on the round's drain thread as each line is read, and a listener that tolerates that thread observes every event

#### Scenario: The log renderer writes the feed under the attempt's keys
- **WHEN** a round runs with the shipped SLF4J renderer subscribed
- **THEN** each progress event is logged under the attempt's MDC keys
- **AND** the raw stream events are logged at DEBUG

#### Scenario: The log renderer is the one shipped listener
- **WHEN** the factory wires an executor round or a judge vote
- **THEN** it subscribes the shipped SLF4J renderer and no other listener

#### Scenario: Judge rounds feed the same listeners
- **WHEN** a judge vote runs
- **THEN** its progress events reach the same listeners as an executor round's

### Requirement: CLI judge vote
The CLI `JudgeVoter` adapter SHALL run one `vote()` as one CLI round via `exec()` of a task environment with the check's pinned model — in sandboxed mode a fresh environment materialized from the attempt commit, never the gnome-touched round environment (votes of one attempt may share it); in host mode the stage workspace, as today.
**Implements**: FR8, NFR-R1, NFR-O2, D5, D8 of add-agent-executor; FR4, FR15, FR19 of add-sandbox-core

#### Scenario: Fenced verdict accepted
- **WHEN** the judge's final message wraps `{"passed": false, "findings": [...]}` in a markdown code fence
- **THEN** the vote is Fail with the parsed findings

#### Scenario: No verdict is never a pass
- **WHEN** the judge process exits without a parseable verdict in its final message
- **THEN** the vote is `CannotVerify` and the raw message is logged at WARN

#### Scenario: Feedback is withheld from the judge
- **WHEN** a judge vote runs on attempt 3 after two quality failures
- **THEN** its prompt contains no prior-attempt findings

#### Scenario: Judge criteria come from the law, not the working copy
- **WHEN** the gnome has rewritten the acceptance-criteria file in the working copy before a vote
- **THEN** the vote's prompt carries the criteria content of the pipeline law bound at invocation start

#### Scenario: The judge prompt carries the task context and the verdict instruction
- **WHEN** a judge vote's prompt is built
- **THEN** it contains the task goal, the human decisions, the acceptance criteria and a structured-verdict instruction

#### Scenario: The first JSON object is the verdict
- **WHEN** the judge's final message carries prose around a JSON object, or more than one JSON object
- **THEN** the vote is read from the first JSON object in the message

#### Scenario: A dead judge process cannot verify
- **WHEN** the judge process dies, or exits without a result event
- **THEN** the vote is `CannotVerify`, never a pass, and the raw final message, if any, is logged at WARN

### Requirement: Judge votes report per-model tokens
A CLI judge vote SHALL report its token usage per resolved model id in the same form as executor rounds (four token counts per model, empty map = unreported), preserving per-vote granularity in the recorded judge usage.
**Implements**: FR9, NFR-C1, D4 of add-agent-executor

#### Scenario: Vote tokens keyed by model
- **WHEN** a judge vote's result event reports `modelUsage`
- **THEN** the vote carries `tokensByModel` with those resolved model ids

### Requirement: Manifest settings with strict validation
The manifest `settings` map of an `agent-cli` executor and of a judge check SHALL accept exactly `allowedTools`, `disallowedTools`, `maxTurns`, and `roundTimeout`; any unknown key SHALL be a startup error raised before any dialog, naming the stage/check and the offending key.
**Implements**: FR11, UX2, D7 of add-agent-executor; NFR-S1 of add-tracker-port; FR9 of add-sandbox-core
**Notes**: Refusing the tracker's credential variables in the passthrough is what keeps tracker credentials from the gnome by construction.

#### Scenario: Typo fails fast
- **WHEN** a stage's settings contain `allowedTols`
- **THEN** startup fails before any dialog with a message naming the stage and the unknown key

#### Scenario: Binary path is installation config
- **WHEN** application properties point the CLI binary at a fake agent script
- **THEN** rounds execute that binary with no manifest change

#### Scenario: Credential variable cannot be allowlisted
- **WHEN** application properties allowlist a variable the active tracker adapter declares as a credential
- **THEN** startup fails before any dialog with an error naming the variable

#### Scenario: The binary defaults to claude on the PATH
- **WHEN** no application property names the CLI binary
- **THEN** rounds execute `claude` resolved from `PATH`

#### Scenario: The child-environment allowlist is installation config
- **WHEN** a stage's `settings` name an environment allowlist or a passthrough variable
- **THEN** startup fails before any dialog with the unknown-settings-key error, since the allowlist lives in application properties

#### Scenario: Passthrough names match exactly
- **WHEN** the operator passthrough names a variable, and the factory environment also holds variables whose names merely share a prefix with it
- **THEN** the round's environment receives the named variable only, beside the adapter base set and the factory-set variables, and inherits nothing else implicitly

### Requirement: Hard-wired adapter policy
The CLI adapters SHALL hard-wire, as adapter policy and never as configuration, the judge's read-only tool set, the executor round's write allowance for its own decision file, the transport flags, the per-role CLI permission mode, and the exclusion of all MCP server configuration; none of these SHALL be settable from the manifest or from operator configuration.
**Implements**: FR12, NFR-S1, NFR-S2, D7 of add-agent-executor; FR1, FR2, FR3, FR4, NFR-S1, NFR-S2, NFR-C1 of fix-operator-blockers
**Notes**: `acceptEdits` auto-approves file edits inside the working directory; `dontAsk` denies every tool call outside the pre-approved set without waiting for an answer; the MCP exclusion keeps both the operator's own servers and a working-copy `.mcp.json` out of a round.

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

#### Scenario: No command line skips all permission checks
- **WHEN** any executor round or judge vote command line is rendered
- **THEN** it never carries `bypassPermissions`

#### Scenario: A judge check may narrow its tools
- **WHEN** a judge check's settings list only `Read` in `allowedTools`
- **THEN** the effective tool set for the vote is `Read` alone, a subset of the Read/Grep/Glob-class set

#### Scenario: The executor may write exactly its decision file
- **WHEN** an executor round launches, whatever the stage's `allowedTools`
- **THEN** its allowed tools end with a `Write` allowance for exactly the decision-file path the adapter generated for that round, and for no other path

#### Scenario: Transport flags are not configurable
- **WHEN** an executor round or a judge vote launches
- **THEN** its command line carries `-p` and `--output-format stream-json --verbose`, whatever the manifest and the operator configuration say

#### Scenario: The model is manifest data, not a setting
- **WHEN** a stage names `executor.model`, or a judge check names its `model`
- **THEN** the round's command line carries that model as `--model`
- **AND** a `model` key in `settings` is rejected with the unknown-settings-key error

### Requirement: Round timeout and control-file preflight
`roundTimeout` expiry SHALL kill the CLI process and classify the round as an infrastructure failure (no verdict exists, no attempt burned). The adapter SHALL obtain the stage's control file from the pipeline law bound at invocation start — never from the gnome-writable working copy at use time; an unreadable control file SHALL be an infrastructure failure before any process starts (a "cannot execute" escalation), never a silently control-less prompt.
**Implements**: FR13, NFR-R1, D8 of add-agent-executor; FR19, NFR-S2 of add-sandbox-core

#### Scenario: Hung CLI cannot hang the engine
- **WHEN** the process outlives `roundTimeout`
- **THEN** it is killed and the round escalates as infrastructure, with `attemptsUsed` unchanged

#### Scenario: Unreadable control file stops before spawn
- **WHEN** the stage's instructions file cannot be read from the law source
- **THEN** no process starts and the failure is infrastructure, not a quality failure

#### Scenario: Unreadable criteria stop the vote before spawn
- **WHEN** a judge check's acceptance-criteria file cannot be read from the law source
- **THEN** no process starts and the vote is `CannotVerify`

#### Scenario: Unreadable criteria in the verify plan stop the round before spawn
- **WHEN** a stage's verify plan names a judge check whose acceptance-criteria file cannot be read from the law source
- **THEN** the executor round starts no process and the failure is infrastructure, not a quality failure

### Requirement: Shared briefing renderer
The briefing section renderer SHALL be a shared component with an explicit public API whose sections accept pre-read data; file reading SHALL remain with each adapter. The judge prompt SHALL use the section subset goal + decisions + criteria + verdict instruction; the stage round prompt SHALL use the full section set. The rendered text SHALL be the same whichever adapter requests a section.
**Implements**: FR4 of remove-interactive-console

#### Scenario: Extraction is invisible to the console
- **WHEN** the executor prompt and a spec render the same section over the same pre-read data
- **THEN** the rendered text is identical

### Requirement: Contract compliance via fake agent
Both CLI adapters SHALL pass the existing `StageExecutorContract` and `JudgeVoterContract` suites driven by a fake agent binary substituted through the configurable CLI path, covering at minimum: decision file present/absent/garbage, verdict clean/fenced/garbage, and a killed process.
**Implements**: FR15, G4 of add-agent-executor

#### Scenario: One suite, real machinery
- **WHEN** the contract suites run against the CLI adapters with the fake binary
- **THEN** every suite scenario passes through a real subprocess, pipes, and exit codes

### Requirement: Reference-dump fixtures carry realism without sensitive or per-run data
The paid-smoke recorder SHALL commit a real CLI stream-json transcript only after scrubbing each captured line so that no committed `*.reference.json` fixture contains machine-identifying data (home- or temp-directory absolute paths, with the usernames they embed), real session ids, cost figures, the `permission_denials` array, or any per-event `uuid` or `request_id`.
**Implements**: FR1, FR2, FR3, FR4, FR5, FR6, FR7, NFR-R1, NFR-S1, NFR-C1 of harden-reference-dump-scrubber
**Notes**: The `permission_denials` array carries raw tool inputs; what the scrub keeps is kept so the fixture stays representative of real CLI output.

#### Scenario: Money is stripped
- **WHEN** a captured result line carries `total_cost_usd` and per-model `costUSD` entries
- **THEN** the scrubbed line contains neither field
- **AND** every resolved model id and its four token/cache counts remain

#### Scenario: Machine temp paths are collapsed but opaque per-run tokens survive
- **WHEN** a captured line embeds a macOS `var/folders` temp hash, a per-uid `/tmp/claude-<uid>` dir, or their dashed project-dir encoding, alongside an `agentId`/`task_id`
- **THEN** every such path is collapsed to `/workspace-scrubbed`, leaving no `var/folders`, `var-folders`, or `/tmp/claude-<uid>` substring
- **AND** the opaque `agentId`/`task_id` tokens remain unchanged

#### Scenario: Raw tool inputs and identifiers are stripped
- **WHEN** a captured line carries a `permission_denials` array, a `uuid`, and a `request_id`
- **THEN** the scrubbed line contains none of the `permission_denials` array, the `uuid`, or the `request_id`

#### Scenario: Paths and session ids stay scrubbed
- **WHEN** a captured line embeds the workspace absolute path and the real session id
- **THEN** the scrubbed line shows `/workspace` and the synthetic `ref-session-<label>-1` in their place

#### Scenario: Deterministic zero-cost refresh
- **WHEN** the committed fixtures are refreshed from disk
- **THEN** no `claude` process is launched and no tokens are spent
- **AND** applying the refresh a second time leaves the files byte-identical

#### Scenario: API message and tool-use ids survive
- **WHEN** a captured line carries API `msg_` and `toolu_` ids
- **THEN** the scrubbed line carries them unchanged

#### Scenario: Home-directory paths lose their username
- **WHEN** a captured line embeds a home-directory path outside the workspace (`/Users/<name>/…`, `/home/<name>/…`)
- **THEN** the path's prefix is collapsed to `/workspace-scrubbed` and the username does not survive

### Requirement: CLI processes run through the task environment
Agent-CLI rounds and CLI judge votes SHALL be spawned via `exec()` of the bound `TaskExecutionEnvironment`, never as direct host subprocesses. The stream-json contract, decision-file semantics, timeouts, and telemetry SHALL be unchanged — the adapter consumes the same stdout stream and exit code regardless of where the process runs.
**Implements**: FR4 of add-sandbox-core

#### Scenario: Round in a container speaks the same protocol
- **WHEN** an agent round executes in container mode
- **THEN** stream-json events, the decision file, and the exit code are observed by the executor adapter exactly as in host mode

### Requirement: CLI child environment is allowlisted
The environment passed to CLI processes SHALL be the layered allowlist of the execution-environment capability: the adapter's base set, operator passthrough names, the AI base-url/auth-token seam variables, and required tool variables — nothing else. No factory-process variable SHALL be inherited implicitly.
**Implements**: FR9 of add-sandbox-core; FR5, NFR-S3 of fix-operator-blockers
**Notes**: `CLAUDE_CODE_OAUTH_TOKEN` is a subscription token minted with `claude setup-token`.

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

#### Scenario: The AI seam carries five variables
- **WHEN** the factory environment holds `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` and `ANTHROPIC_MODEL`, with no passthrough configured
- **THEN** every agent round and judge vote receives all five with their current values, in host and container mode alike

#### Scenario: An absent seam variable is omitted
- **WHEN** the factory environment does not hold one of the seam variables
- **THEN** the round's environment does not contain that variable at all, rather than an empty value

### Requirement: Prompts are delivered via stdin
Round and judge prompts SHALL be passed to the agent CLI via stdin, never as an argv argument, in all modes and both adapters — argv caps a single argument (Linux `MAX_ARG_STRLEN`) while prompts accumulate the findings history of all prior attempts, and argv content is visible to any host user via process listings.
**Implements**: FR24 of add-sandbox-core

#### Scenario: Late attempt with a large feedback history starts
- **WHEN** a round prompt exceeds the platform's single-argument size limit
- **THEN** the process starts and receives the full prompt on stdin, with no truncation

#### Scenario: Prompt content is absent from the command line
- **WHEN** a round or judge process is inspected while running
- **THEN** its command line contains transport flags only — no prompt text, no findings
