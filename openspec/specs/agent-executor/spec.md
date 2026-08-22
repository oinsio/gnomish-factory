# agent-executor

## Purpose

Real agent-cli execution of stages and judge votes through the Claude Code CLI: process lifecycle, wire protocols (decision file, structured verdict), stream-json telemetry, live progress, settings, and wiring.

## Requirements

### Requirement: Fresh CLI round per attempt
The CLI `StageExecutor` adapter SHALL run one `execute()` call as one fresh CLI process spawned via `exec()` of the bound task environment over the task working copy, with stream-json output, never reusing a CLI session between attempts; work carries across retries through the task working copy, and prior findings reach the agent only through the rendered `Request.feedback`.
<!-- implements FR1, D2 of add-agent-executor -->
<!-- implements FR4 of add-sandbox-core -->

#### Scenario: Retry is a fresh process
- **WHEN** attempt 2 of a stage executes after a quality failure
- **THEN** a new CLI process starts via the task environment with no session-resume flag and its prompt contains the rendered findings of attempt 1

### Requirement: Round prompt composition
The round prompt SHALL be built from the shared briefing sections (task goal, input artifacts, prior-attempt feedback, decisions, control-file content) followed by the executor epilogue: the stage's full verify plan including judge acceptance-criteria content, the decision-file instruction, and — on attempts after the first — a rework preamble stating that the working copy already contains the prior attempt's result and must be reworked, not restarted.
<!-- implements FR2, D8, D9 of add-agent-executor -->

#### Scenario: Verify plan is visible to the agent
- **WHEN** a stage with a command check and a judge check executes
- **THEN** the prompt lists both checks, including the judge's acceptance criteria text

#### Scenario: Rework preamble on retry only
- **WHEN** attempt 1 and attempt 2 prompts of the same stage are compared
- **THEN** only the attempt 2 prompt contains the rework preamble

### Requirement: Decision-file protocol
In git modes the decision request SHALL live in the working copy at `.gnomish-task/decisions/<stage>-a<attempt>.json` — the single gnome-writable path under `.gnomish-task/`. The adapter SHALL pass that path via `$GNOMISH_DECISION_FILE`, read it through the environment after process exit, and map: file matching the current stage and attempt present → `DecisionNeeded`, absent → `Completed`; files not matching the current stage and attempt SHALL be ignored. The request rides the snapshot (or salvage) commit, so a pending escalation survives factory death and is resumable by any instance from the branch alone. Reading SHALL be tolerant: invalid JSON → the entire file content becomes the question with empty options; empty file → a fallback question text; the raw content SHALL be logged at WARN on any parse trouble. A `DecisionNeeded` result SHALL carry the same telemetry (usage and trace) as `Completed`. The git-less in-place mode SHALL keep the per-round temp-file transport outside the workspace.
<!-- implements FR3, NFR-R3, NFR-O2, D1 of add-agent-executor -->
<!-- implements FR23 of add-sandbox-core -->

#### Scenario: Agent asks for a decision
- **WHEN** the agent writes `{"question": "Refactor or patch?", "options": ["refactor", "patch"]}` to the decision file for the current stage and attempt and exits
- **THEN** the adapter returns `DecisionNeeded` with that question and both options

#### Scenario: No signal means Completed
- **WHEN** the process exits without creating a decision file for the current stage and attempt
- **THEN** the adapter returns `Completed` and verification proceeds

#### Scenario: Garbage decision file is not lost
- **WHEN** the decision file contains unparseable text
- **THEN** the adapter returns `DecisionNeeded` with the raw text as the question and logs the content at WARN

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying a decision request but before the escalation is recorded
- **THEN** a resuming instance finds the request in the branch and escalates with that question, without replaying the round

#### Scenario: Stale request from another round is ignored
- **WHEN** a decision file named for a previous stage or attempt is still present in the working copy
- **THEN** the current round maps to `Completed` and the stale file changes nothing

### Requirement: Stdout is drained continuously from launch
Both CLI round executions (executor and judge) SHALL consume and parse the process's stream-json stdout concurrently with the running process, starting immediately after launch — never deferring the read until after process exit. A round's output size SHALL be bounded by neither the OS pipe buffer nor any adapter-side ceiling: a stream larger than any pipe buffer completes normally with its result event intact, and a writer that blocks on a full pipe cannot occur because the pipe is being drained. Round completion SHALL wait for both process exit (within `roundTimeout`, unchanged) and drain completion, with a bounded tail-drain grace after exit; a drain still incomplete after the grace SHALL be an infrastructure failure of the round, never a silently partial stream. The grace SHALL be an installation-level application property (like the CLI binary path — never a manifest setting), defaulting to 5 seconds; a non-positive or malformed value SHALL be a startup error before any dialog, and the property SHALL be documented in the operator guide's installation-properties table. A `roundTimeout` kill SHALL end the drain cleanly (stream close/EOF is the expected signal, not an error) and keep today's timeout classification. The drain mechanism SHALL be one shared implementation used by both executions.
<!-- implements FR1, FR2, FR3, FR6, FR7, NFR-R1, NFR-R2, NFR-P1, UX3 of fix-round-stdout-drain -->

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

### Requirement: Result event is essential, telemetry is best-effort
The adapter SHALL treat the stream-json result event as essential — a missing or unparseable result event is an infrastructure failure of the round — while telemetry parsing is best-effort: on telemetry parse trouble the round SHALL still complete with `ExecutorUsage.none()` and an empty trace. Unknown event types and unknown fields SHALL be ignored silently. The missing-result failure SHALL report how much of the stream was read (bytes and parsed-event count), and when the read volume is consistent with a filled OS pipe buffer the message SHALL name stream truncation as the likely cause — so a human can tell "the agent emitted no result" apart from "the stream was cut short" without reading adapter source.
<!-- implements FR4, NFR-R1, NFR-R2, D3 of add-agent-executor -->
<!-- implements FR5, NFR-R2, UX2 of fix-round-stdout-drain -->

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

### Requirement: Per-model token mapping
The adapter SHALL report tokens per resolved model id from the result event's `modelUsage`, each entry carrying input, output, cache-creation, and cache-read counts; when the CLI emits no `modelUsage`, all usage SHALL fall back under the main model id from the init event. An empty map means unreported — never fabricated zeros.
<!-- implements FR5, NFR-C1, D4 of add-agent-executor -->

#### Scenario: Multi-model round preserved
- **WHEN** the result event reports usage for two model ids
- **THEN** `tokensByModel` carries both entries with their four token counts intact

#### Scenario: Old CLI fallback
- **WHEN** the result event has no `modelUsage` but the init event named the main model
- **THEN** the round's usage appears under that model id

### Requirement: Top-level tool trace with adapter-side timing
The trace SHALL contain top-level tool calls only, with nested subagent calls excluded by the parent-id field (absent field → record everything seen). The adapter SHALL timestamp events at read time: a call starts when its tool_use block is read and ends when the matching tool_result (by id) is read; a call orphaned by process death ends at process exit. `ExecutorUsage.tools` SHALL be derived from the trace, and `wallTime` SHALL be measured by the adapter from process start to exit independently of stream parsing. Timing is telemetry-grade: overlapping parallel calls may sum beyond wall time.
<!-- implements FR6, NFR-O3, D3 of add-agent-executor -->

#### Scenario: Subagent internals excluded
- **WHEN** a Task tool call spawns nested tool calls marked with a parent id
- **THEN** the trace contains the Task call only and aggregates count it once

#### Scenario: Orphaned call gets a real duration
- **WHEN** the process dies after a tool_use with no matching tool_result
- **THEN** that call's duration spans from its start to process exit

### Requirement: Live progress listener SPI
The adapter's parse loop SHALL emit sealed progress events — round started (model, session id), top-level tool started (name, no input payload), round finished (result subtype, token summary, the agent's final-message summary) — to registered listeners synchronously as each line is read, while the process is still running — never as a post-exit burst; listeners SHALL therefore tolerate being invoked from the round's drain thread. Listener exceptions are swallowed. Two subscribers SHALL ship: an SLF4J renderer logging the feed under the attempt's MDC keys (raw stream events at DEBUG), and a status enricher adding the current tool name and call counter to the `Executing` activity. Judge rounds SHALL feed the same listeners; the status enricher SHALL apply to executor rounds only — a vote runs under the `verifying` activity.
<!-- implements FR7, NFR-O1, UX1, D9, D10 of add-agent-executor -->
<!-- implements FR4, NFR-O1, UX1 of fix-round-stdout-drain -->

#### Scenario: Progress observable without logs
- **WHEN** a recording listener subscribes to a round with three tool calls
- **THEN** it observes round-started, three tool-started events, and round-finished with the final summary

#### Scenario: Broken listener does not break the round
- **WHEN** a listener throws on every event
- **THEN** the round completes normally

#### Scenario: Progress is live, not post-mortem
- **WHEN** the CLI emits a tool-started line while the process is still running
- **THEN** subscribed listeners observe the event before the process exits

### Requirement: CLI judge vote
The CLI `JudgeVoter` adapter SHALL run one `vote()` as one CLI round via `exec()` of a task environment with the check's pinned model — in sandboxed mode a fresh environment materialized from the attempt commit, never the gnome-touched round environment (votes of one attempt may share it); in host mode the stage workspace, as today. The prompt SHALL contain the acceptance-criteria content read from the pipeline law bound at invocation start — never from the gnome-writable working copy — the task context (goal and human decisions), and a structured-verdict instruction — and SHALL NOT contain prior-attempt feedback. The verdict SHALL be extracted tolerantly from the final message (fences stripped, first JSON object taken); when no verdict is obtainable — unparseable message, dead process, missing result event — the vote SHALL be `CannotVerify`, never a silent pass, with the raw final message logged at WARN.
<!-- implements FR8, NFR-R1, NFR-O2, D5, D8 of add-agent-executor -->
<!-- implements FR4, FR15, FR19 of add-sandbox-core -->

#### Scenario: Fenced verdict accepted
- **WHEN** the judge's final message wraps `{"passed": false, "findings": [...]}` in a markdown code fence
- **THEN** the vote is Fail with the parsed findings

#### Scenario: No verdict is never a pass
- **WHEN** the judge process exits without a parseable verdict in its final message
- **THEN** the vote is `CannotVerify` and the raw message is logged at WARN

#### Scenario: Feedback is withheld from the judge
- **WHEN** a judge vote runs on attempt 3 after two quality failures
- **THEN** its prompt contains no prior-attempt findings

### Requirement: Judge votes report per-model tokens
A CLI judge vote SHALL report its token usage per resolved model id in the same form as executor rounds (four token counts per model, empty map = unreported), preserving per-vote granularity in the recorded judge usage.
<!-- implements FR9, NFR-C1, D4 of add-agent-executor -->

#### Scenario: Vote tokens keyed by model
- **WHEN** a judge vote's result event reports `modelUsage`
- **THEN** the vote carries `tokensByModel` with those resolved model ids

### Requirement: Manifest settings with strict validation
The manifest `settings` map of an `agent-cli` executor and of a judge check SHALL accept exactly `allowedTools`, `disallowedTools`, `maxTurns`, and `roundTimeout`; any unknown key SHALL be a startup error raised before any dialog, naming the stage/check and the offending key. Installation-level configuration — the CLI binary path (default: `claude` from PATH) and the child-environment allowlist — SHALL live in application properties, never in the manifest. The child environment SHALL follow the layered allowlist of the execution-environment capability — adapter base set, operator passthrough by exact name, factory-set variables; no variable is inherited implicitly. Variables declared as credentials by the active tracker adapter SHALL be refused in the passthrough as a startup error, so tracker credentials can never reach the gnome by construction.
<!-- implements FR11, UX2, D7 of add-agent-executor -->
<!-- implements NFR-S1 of add-tracker-port -->
<!-- implements FR9 of add-sandbox-core -->

#### Scenario: Typo fails fast
- **WHEN** a stage's settings contain `allowedTols`
- **THEN** startup fails before any dialog with a message naming the stage and the unknown key

#### Scenario: Binary path is installation config
- **WHEN** application properties point the CLI binary at a fake agent script
- **THEN** rounds execute that binary with no manifest change

#### Scenario: Credential variable cannot be allowlisted
- **WHEN** application properties allowlist a variable the active tracker adapter declares as a credential
- **THEN** startup fails before any dialog with an error naming the variable

### Requirement: Hard-wired adapter policy
The following SHALL be adapter policy, not configuration: the judge runs strictly read-only (Read/Grep/Glob-class tools; a judge check's `allowedTools` may only narrow that set, never widen it); the executor round receives a pinpoint write allowance for exactly the decision-file path the adapter generated; transport flags (`-p`, `--output-format stream-json --verbose`) are protocol internals invisible to configuration; the model is not a setting — it is first-class manifest data (`executor.model`, the judge check's `model`) mapped to `--model`.
<!-- implements FR12, NFR-S1, NFR-S2, D7 of add-agent-executor -->

#### Scenario: Judge cannot widen its tools
- **WHEN** a judge check's settings request a write-capable tool in `allowedTools`
- **THEN** the effective tool set for the vote remains read-only

### Requirement: Round timeout and control-file preflight
`roundTimeout` expiry SHALL kill the CLI process and classify the round as an infrastructure failure (no verdict exists, no attempt burned). The adapter SHALL obtain the stage's control file from the pipeline law bound at invocation start — never from the gnome-writable working copy at use time; an unreadable control file SHALL be an infrastructure failure before any process starts (a "cannot execute" escalation), never a silently control-less prompt. The same preflight SHALL cover the judge acceptance-criteria files embedded into the round prompt's verify plan. The judge adapter SHALL apply the same preflight to the check's acceptance-criteria file, read from the same law source: an unreadable criteria file SHALL yield `CannotVerify` before any process starts, never a criteria-less vote.
<!-- implements FR13, NFR-R1, D8 of add-agent-executor -->
<!-- implements FR19, NFR-S2 of add-sandbox-core -->

#### Scenario: Hung CLI cannot hang the engine
- **WHEN** the process outlives `roundTimeout`
- **THEN** it is killed and the round escalates as infrastructure, with `attemptsUsed` unchanged

#### Scenario: Unreadable control file stops before spawn
- **WHEN** the stage's instructions file cannot be read from the law source
- **THEN** no process starts and the failure is infrastructure, not a quality failure

#### Scenario: Unreadable criteria stop the vote before spawn
- **WHEN** a judge check's acceptance-criteria file cannot be read from the law source
- **THEN** no process starts and the vote is `CannotVerify`

### Requirement: Shared briefing renderer
The briefing section renderer SHALL be a shared component with an explicit public API whose sections accept pre-read data; file reading SHALL remain with each adapter. The interactive adapters' rendered output SHALL be unchanged by the extraction; the judge prompt SHALL use the section subset goal + decisions + criteria + verdict instruction.
<!-- implements FR14, D8 of add-agent-executor -->

#### Scenario: Extraction is invisible to the console
- **WHEN** the interactive executor renders a briefing before and after the extraction
- **THEN** the rendered text is identical

### Requirement: Contract compliance via fake agent
Both CLI adapters SHALL pass the existing `StageExecutorContract` and `JudgeVoterContract` suites driven by a fake agent binary substituted through the configurable CLI path, covering at minimum: decision file present/absent/garbage, verdict clean/fenced/garbage, and a killed process.
<!-- implements FR15, G4 of add-agent-executor -->

#### Scenario: One suite, real machinery
- **WHEN** the contract suites run against the CLI adapters with the fake binary
- **THEN** every suite scenario passes through a real subprocess, pipes, and exit codes

### Requirement: Reference-dump fixtures carry realism without sensitive or per-run data
The paid-smoke recorder SHALL commit a real CLI stream-json transcript only after scrubbing each captured line so that no committed `*.reference.json` fixture contains machine-identifying data (home- or temp-directory absolute paths — including the macOS `var/folders` per-user hash and the `/tmp/claude-<uid>` uid, in both slash and dashed-encoded form — and usernames), real session ids, cost figures (`total_cost_usd` and every nested `costUSD`), the `permission_denials` array (raw tool inputs), or any per-event `uuid` or `request_id`; resolved model ids, all token/cache counts (top-level `usage` and per-model `modelUsage`), and opaque per-run tokens that carry no machine or user identity (`agentId`, `task_id`, API `msg_`/`toolu_` ids) SHALL be preserved intact so the fixture stays representative of real CLI output. The committed fixtures SHALL be refreshable in place from disk deterministically, without invoking `claude`, and that refresh SHALL be idempotent.
<!-- implements FR1, FR2, FR3, FR4, FR5, FR6, FR7, NFR-R1, NFR-S1, NFR-C1 of harden-reference-dump-scrubber -->

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

### Requirement: CLI processes run through the task environment
Agent-CLI rounds and CLI judge votes SHALL be spawned via `exec()` of the bound `TaskExecutionEnvironment`, never as direct host subprocesses. The stream-json contract, decision-file semantics, timeouts, and telemetry SHALL be unchanged — the adapter consumes the same stdout stream and exit code regardless of where the process runs.
<!-- implements FR4 of add-sandbox-core -->

#### Scenario: Round in a container speaks the same protocol
- **WHEN** an agent round executes in container mode
- **THEN** stream-json events, the decision file, and the exit code are observed by the executor adapter exactly as in host mode

### Requirement: CLI child environment is allowlisted
The environment passed to CLI processes SHALL be the layered allowlist of the execution-environment capability: the adapter's base set, operator passthrough names, the AI base-url/auth-token seam variables, and required tool variables — nothing else. No factory-process variable SHALL be inherited implicitly.
<!-- implements FR9 of add-sandbox-core -->

#### Scenario: Tracker token is absent from the round
- **WHEN** an agent round starts while the factory holds the tracker token in its own environment
- **THEN** the CLI process environment contains no tracker token and no variable outside the allowlist

### Requirement: Prompts are delivered via stdin
Round and judge prompts SHALL be passed to the agent CLI via stdin, never as an argv argument, in all modes and both adapters — argv caps a single argument (Linux `MAX_ARG_STRLEN`) while prompts accumulate the findings history of all prior attempts, and argv content is visible to any host user via process listings.
<!-- implements FR24 of add-sandbox-core -->

#### Scenario: Late attempt with a large feedback history starts
- **WHEN** a round prompt exceeds the platform's single-argument size limit
- **THEN** the process starts and receives the full prompt on stdin, with no truncation

#### Scenario: Prompt content is absent from the command line
- **WHEN** a round or judge process is inspected while running
- **THEN** its command line contains transport flags only — no prompt text, no findings
