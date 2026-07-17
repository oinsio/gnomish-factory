# Spec: agent-executor

Real agent-cli execution of stages and judge votes through the Claude Code CLI: process lifecycle, wire protocols (decision file, structured verdict), stream-json telemetry, live progress, settings, and wiring.

## ADDED Requirements

### Requirement: Fresh CLI round per attempt
The CLI `StageExecutor` adapter SHALL run one `execute()` call as one fresh `claude -p` subprocess in the stage workspace with stream-json output, never reusing a CLI session between attempts; work carries across retries through the shared working copy, and prior findings reach the agent only through the rendered `Request.feedback`.
<!-- implements FR1, D2 of add-agent-executor -->

#### Scenario: Retry is a fresh process
- **WHEN** attempt 2 of a stage executes after a quality failure
- **THEN** a new CLI process starts with no session-resume flag and its prompt contains the rendered findings of attempt 1

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
The adapter SHALL create a per-round temporary directory outside the workspace, pass a decision-file path to the agent via `$GNOMISH_DECISION_FILE`, and after process exit map: file present → `DecisionNeeded`, file absent → `Completed`. The adapter SHALL own the directory's lifecycle (create → run → read → delete). Reading SHALL be tolerant: invalid JSON → the entire file content becomes the question with empty options; empty file → a fallback question text; the raw file content SHALL be logged at WARN on any parse trouble. A `DecisionNeeded` result SHALL carry the same telemetry (usage and trace) as `Completed` — telemetry is collected from the stream regardless of the outcome.
<!-- implements FR3, NFR-R3, NFR-O2, D1 of add-agent-executor -->

#### Scenario: Agent asks for a decision
- **WHEN** the agent writes `{"question": "Refactor or patch?", "options": ["refactor", "patch"]}` to the decision file and exits
- **THEN** the adapter returns `DecisionNeeded` with that question and both options

#### Scenario: No signal means Completed
- **WHEN** the process exits without creating the decision file
- **THEN** the adapter returns `Completed` and verification proceeds

#### Scenario: Garbage decision file is not lost
- **WHEN** the decision file contains unparseable text
- **THEN** the adapter returns `DecisionNeeded` with the raw text as the question and logs the content at WARN

#### Scenario: No stale file across rounds
- **WHEN** two consecutive rounds of the same stage run
- **THEN** each round observes a distinct decision-file path and the prior round's directory no longer exists

### Requirement: Result event is essential, telemetry is best-effort
The adapter SHALL treat the stream-json result event as essential — a missing or unparseable result event is an infrastructure failure of the round — while telemetry parsing is best-effort: on telemetry parse trouble the round SHALL still complete with `ExecutorUsage.none()` and an empty trace. Unknown event types and unknown fields SHALL be ignored silently.
<!-- implements FR4, NFR-R1, NFR-R2, D3 of add-agent-executor -->

#### Scenario: Telemetry failure does not fail the round
- **WHEN** usage fields in an otherwise valid stream cannot be parsed
- **THEN** the round completes normally with `ExecutorUsage.none()` and an empty trace

#### Scenario: Missing result event is infrastructure
- **WHEN** the process exits without emitting a parseable result event
- **THEN** the round is an infrastructure failure and no stage attempt is burned

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
The adapter's parse loop SHALL emit sealed progress events — round started (model, session id), top-level tool started (name, no input payload), round finished (result subtype, token summary, the agent's final-message summary) — to registered listeners synchronously, swallowing listener exceptions. Two subscribers SHALL ship: an SLF4J renderer logging the feed under the attempt's MDC keys (raw stream events at DEBUG), and a status enricher adding the current tool name and call counter to the `Executing` activity. Judge rounds SHALL feed the same listeners; the status enricher SHALL apply to executor rounds only — a vote runs under the `verifying` activity.
<!-- implements FR7, NFR-O1, UX1, D9, D10 of add-agent-executor -->

#### Scenario: Progress observable without logs
- **WHEN** a recording listener subscribes to a round with three tool calls
- **THEN** it observes round-started, three tool-started events, and round-finished with the final summary

#### Scenario: Broken listener does not break the round
- **WHEN** a listener throws on every event
- **THEN** the round completes normally

### Requirement: CLI judge vote
The CLI `JudgeVoter` adapter SHALL run one `vote()` as one `claude -p` round in the workspace with the check's pinned model; the prompt SHALL contain the acceptance-criteria file content, the task context (goal and human decisions), and a structured-verdict instruction — and SHALL NOT contain prior-attempt feedback. The verdict SHALL be extracted tolerantly from the final message (fences stripped, first JSON object taken); when no verdict is obtainable — unparseable message, dead process, missing result event — the vote SHALL be `CannotVerify`, never a silent pass, with the raw final message logged at WARN.
<!-- implements FR8, NFR-R1, NFR-O2, D5, D8 of add-agent-executor -->

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
The manifest `settings` map of an `agent-cli` executor and of a judge check SHALL accept exactly `allowedTools`, `disallowedTools`, `maxTurns`, and `roundTimeout`; any unknown key SHALL be a startup error raised before any dialog, naming the stage/check and the offending key. Installation-level configuration — the CLI binary path (default: `claude` from PATH) and environment passthrough to the CLI process — SHALL live in application properties, never in the manifest.
<!-- implements FR11, UX2, D7 of add-agent-executor -->

#### Scenario: Typo fails fast
- **WHEN** a stage's settings contain `allowedTols`
- **THEN** startup fails before any dialog with a message naming the stage and the unknown key

#### Scenario: Binary path is installation config
- **WHEN** application properties point the CLI binary at a fake agent script
- **THEN** rounds execute that binary with no manifest change

### Requirement: Hard-wired adapter policy
The following SHALL be adapter policy, not configuration: the judge runs strictly read-only (Read/Grep/Glob-class tools; a judge check's `allowedTools` may only narrow that set, never widen it); the executor round receives a pinpoint write allowance for exactly the decision-file path the adapter generated; transport flags (`-p`, `--output-format stream-json --verbose`) are protocol internals invisible to configuration; the model is not a setting — it is first-class manifest data (`executor.model`, the judge check's `model`) mapped to `--model`.
<!-- implements FR12, NFR-S1, NFR-S2, D7 of add-agent-executor -->

#### Scenario: Judge cannot widen its tools
- **WHEN** a judge check's settings request a write-capable tool in `allowedTools`
- **THEN** the effective tool set for the vote remains read-only

### Requirement: Round timeout and control-file preflight
`roundTimeout` expiry SHALL kill the CLI process and classify the round as an infrastructure failure (no verdict exists, no attempt burned). The adapter SHALL read the stage's control file before starting the process; an unreadable control file SHALL be an infrastructure failure before any process starts (a "cannot execute" escalation), never a silently control-less prompt. The same executor preflight SHALL cover the judge acceptance-criteria files embedded into the round prompt's verify plan. The judge adapter SHALL apply the same preflight to the check's acceptance-criteria file: an unreadable criteria file SHALL yield `CannotVerify` before any process starts, never a criteria-less vote.
<!-- implements FR13, NFR-R1, D8 of add-agent-executor -->

#### Scenario: Hung CLI cannot hang the engine
- **WHEN** the process outlives `roundTimeout`
- **THEN** it is killed and the round escalates as infrastructure, with `attemptsUsed` unchanged

#### Scenario: Unreadable control file stops before spawn
- **WHEN** the stage's instructions file cannot be read
- **THEN** no process starts and the failure is infrastructure, not a quality failure

#### Scenario: Unreadable criteria stop the vote before spawn
- **WHEN** a judge check's acceptance-criteria file cannot be read
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
