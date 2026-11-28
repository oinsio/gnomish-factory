# agent-executor

## Purpose

Real agent-cli execution of stages and judge votes through the Claude Code CLI: process lifecycle, wire protocols (decision file, structured verdict), stream-json telemetry, live progress, settings, and wiring.

## Requirements

### Requirement: Fresh CLI round per attempt
The CLI `StageExecutor` adapter SHALL run one `execute()` call as one fresh CLI process spawned via `exec()` of the bound task environment over the task working copy, with stream-json output, never reusing a CLI session between attempts; work carries across retries through the task working copy, and prior findings reach the agent only through the rendered `Request.feedback`.
**Implements**: FR1, D2 of add-agent-executor; FR4 of add-sandbox-core

#### Scenario: Retry is a fresh process
- **WHEN** attempt 2 of a stage executes after a quality failure
- **THEN** a new CLI process starts via the task environment with no session-resume flag and its prompt contains the rendered findings of attempt 1

### Requirement: Round prompt composition
The round prompt SHALL be built from the shared briefing sections (task goal, input artifacts, prior-attempt feedback, decisions, control-file content) followed by the executor epilogue: the stage's full verify plan including judge acceptance-criteria content, the dialect's answer instruction, and — on attempts after the first — a rework preamble stating that the working copy already contains the prior attempt's result and must be reworked, not restarted.
**Implements**: FR2, D8, D9 of add-agent-executor; FR19 of extract-agent-round-runner

#### Scenario: Verify plan is visible to the agent
- **WHEN** a stage with a command check and a judge check executes
- **THEN** the prompt lists both checks, including the judge's acceptance criteria text

#### Scenario: Rework preamble on retry only
- **WHEN** attempt 1 and attempt 2 prompts of the same stage are compared
- **THEN** only the attempt 2 prompt contains the rework preamble

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

### Requirement: Judge votes report per-model tokens
A CLI judge vote SHALL report its token usage per resolved model id in the same form as executor rounds (four token counts per model, empty map = unreported), preserving per-vote granularity in the recorded judge usage.
**Implements**: FR9, NFR-C1, D4 of add-agent-executor

#### Scenario: Vote tokens keyed by model
- **WHEN** a judge vote's result event reports `modelUsage`
- **THEN** the vote carries `tokensByModel` with those resolved model ids

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

### Requirement: The round's stderr is kept
The agent round SHALL direct the CLI process's stderr to a file in the round scratch directory — a host directory the round owns, on the host as for a box round — never to a pipe nothing reads, and SHALL read that file after the exit into the round's diagnostics as untrusted text; the round SHALL remove the scratch directory on every exit path.
**Implements**: FR20, NG12 of fix-terminal-receipt-convergence
**Notes**: The stdout stays a live pipe because the stream-json events are parsed per line while the process runs; a file has no live tail.
**Notes**: The decision file is not the neighbour: in container mode it lives in the task branch, which is the envelope and no place for a host diagnostic; the scratch directory is the one host place a round writes outside the branch.

#### Scenario: A chatty stderr cannot block the round
- **WHEN** the CLI writes more than an OS pipe buffer to its stderr during a round
- **THEN** the round completes on the CLI's exit, and the diagnostics carry the tail of that
  stderr

#### Scenario: The stderr file leaves with the round
- **WHEN** a round ends, however it ended — on the host or for a box
- **THEN** no file of the round remains on the host: the round scratch directory and the stderr
  file in it are removed by the round itself

#### Scenario: A failed round carries its stderr tail
- **WHEN** a round ends without success — the CLI failed to start, exited without a result event,
  timed out, or was interrupted
- **THEN** the failure's detail carries the tail of the CLI's stderr as untrusted text, and a
  successful round logs that tail at DEBUG only

Added 2026-10-17 (design D17 as amended): the round's post-exit wait takes the bound of its
handle; it replaces "Stdout is drained continuously from launch", removed below with the
installation property it named.

### Requirement: The round drains its stdout from launch within the handle's post-exit bound
Both CLI round executions (executor and judge) SHALL consume and parse the process's stream-json stdout concurrently with the running process, starting immediately after launch — never deferring the read until after process exit — through one shared drain; after the exit the round SHALL wait for that drain at most the post-exit bound of the round's handle, never a bound of its own.
**Implements**: FR1, FR2, FR3, FR6, FR7, NFR-R1, NFR-R2, NFR-P1 of fix-round-stdout-drain; FR19 of fix-terminal-receipt-convergence; FR6, NFR-R2 of make-pipe-reads-non-parking
**Notes**: A `roundTimeout` kill ends the process; the JDK's reaper closes the pipe at the exit and the drain ends on its own, so the end of the stream is the drain's expected end signal there, not an error.
**Notes**: The bound replaces the retired installation property `factory.agent-cli-tail-drain-grace`; after the exit only one pipe buffer remains to read.
**Notes**: The round closes no pipe: its close is the owner's stop (`lifecycle/subprocess-supervision`, "A drain never parks"), observed by the drain within a tick on every platform; the pipe is the JDK's to close at the exit (ADR 0018).

#### Scenario: Megabyte stream keeps its result event
- **WHEN** a round's CLI emits over 1 MB of stream-json noise followed by a valid result event and exits
- **THEN** the round completes normally with the result event, usage, and trace extracted

#### Scenario: Chatty synchronous writer does not hang
- **WHEN** the CLI writes more than the pipe buffer synchronously before exiting
- **THEN** the process is never blocked on a full pipe and the round completes well within `roundTimeout`

#### Scenario: Timeout kill still classifies as infrastructure
- **WHEN** the process outlives `roundTimeout` and is killed mid-stream
- **THEN** the drain ends without error and the round is an infrastructure failure, exactly as before this change

#### Scenario: An interrupted wait is not blamed on the bound
- **WHEN** the round thread is interrupted while waiting for its drain to finish
- **THEN** the round is an infrastructure failure of its own kind — reported as an interruption, never as an expired post-exit bound

#### Scenario: No reader outlives the round
- **WHEN** a round ends by any path — normal exit, timeout kill, or adapter exception
- **THEN** the drain thread has ended and no read of the stream is pending; the round closed no
  pipe, since the pipe is the JDK's to close at the exit

#### Scenario: The round's close leaves no drain thread
- **WHEN** an agent round ends while a descendant of the agent process still holds its stdout
- **THEN** the round's close returns at once, the drain thread ends within one tick, and no thread
  is left parked on the pipe, on Linux as on macOS

#### Scenario: The round waits for its drain after exit
- **WHEN** the process exits while the tail of its stream is still in the pipe
- **THEN** the round completes only after the drain has consumed that tail, waiting at most the post-exit bound of the round's handle

#### Scenario: A drain unfinished after the post-exit bound is infrastructure
- **WHEN** the drain has not finished when the post-exit bound of the round's handle runs out
- **THEN** the round is an infrastructure failure, never a round completed on a partial stream

### Requirement: Per-model cost mapping
The adapter SHALL map each `modelUsage` entry's `costUSD` beside that model's four token
counts, for stage rounds and judge votes alike; an entry without `costUSD` SHALL carry no
cost, never zero, and the flat-usage fallback of older CLIs carries no cost. The adapter SHALL
NOT price tokens itself.
**Implements**: FR22, NG10 of define-executor-contract

#### Scenario: Cost rides with its model
- **WHEN** the result event reports two model ids, one with `costUSD`
- **THEN** the round's usage carries the cost under that model only and the other model's cost is absent

#### Scenario: Judge vote carries cost
- **WHEN** a judge vote's result event reports `modelUsage` with `costUSD`
- **THEN** the vote's `verdict` entry carries that cost per model

### Requirement: Reference-dump fixtures carry realism and cost without sensitive or per-run data
The paid-smoke recorder SHALL commit a real CLI stream-json transcript only after scrubbing
each captured line so that no committed `*.reference.json` fixture contains machine-identifying
data (home- or temp-directory absolute paths, with the usernames they embed), real session ids,
the `permission_denials` array, or any per-event `uuid` or `request_id`; cost figures
(`total_cost_usd` and every nested `costUSD`) SHALL be kept.
**Implements**: FR1, FR2, FR3, FR4, FR5, FR6, FR7, NFR-R1, NFR-S1, NFR-C1 of harden-reference-dump-scrubber; FR22 of define-executor-contract
**Notes**: The `permission_denials` array carries raw tool inputs; cost figures are a derived view of the token counts already kept, not sensitive data; what the scrub keeps is kept so the fixture stays representative of real CLI output.

#### Scenario: Money is kept
- **WHEN** a captured result line carries `total_cost_usd` and per-model `costUSD` entries
- **THEN** the scrubbed line keeps both fields
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

### Requirement: Agent CLI is a named executor of the contract
The agent CLI SHALL be one executor in the executor registry implementing the contract's
interface in roles `transform`, `verdict`, `decision`, built from the agent round runner and a
dialect; role-specific round classes SHALL NOT exist.
**Implements**: FR1, FR2, G2 of extract-agent-round-runner

#### Scenario: Round through the registry
- **WHEN** a stage names the Claude executor
- **THEN** its round runs through the registry-resolved executor and the one runner

### Requirement: Decision request is the answer file
The gnome's decision request SHALL be the `decision-request` answer at the round's answer file
path, read by the one answer read on the live path and at recovery alike; the decision file
SHALL have no transport of its own, and no reader SHALL turn content that is not a valid answer
into a decision request.
**Implements**: FR9, FR21, NFR-R5 of extract-agent-round-runner

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying the answer file
- **THEN** a resuming instance finds the decision request in the branch and escalates it

#### Scenario: Answer file read is capped
- **WHEN** an answer file larger than the result cap is read
- **THEN** at most the cap is read and the answer is refused as over the cap, never read as a
  decision request

#### Scenario: Recovery reads a snapshot's answer as the live round does
- **WHEN** an interrupted verification resumes from a snapshot commit whose answer path holds
  the bytes a live round would have refused
- **THEN** the resumed round has the outcome the live round would have had, and no decision
  request is raised from them

#### Scenario: A salvage commit carries the request
- **WHEN** a round whose answer is a decision request ends in a salvage commit instead of a
  snapshot commit
- **THEN** the request is on the branch under its token, as it would be after a snapshot commit

#### Scenario: A snapshot without a request resumes as Completed
- **WHEN** an interrupted verification resumes from a snapshot commit whose tree holds nothing
  at the round's token path
- **THEN** the resumed round maps to `Completed`

### Requirement: The answer file is named by the round that wrote it
In branch modes the answer file SHALL live at `.gnomish-task/decisions/<stage>-a<attempt>-<token>.json`,
`<token>` being the round token, and a round SHALL read that path only; in the in-place mode it
SHALL be a per-round file outside the workspace, removed with the round.
**Implements**: FR9 of extract-agent-round-runner

#### Scenario: An answered request is not asked again
- **WHEN** a container-mode task parked on a decision request is answered, the stage restarts at
  attempt 0, and the next round's box is cloned from a tip that still carries the earlier file
- **THEN** the next round is given a path under its own token, reads only that path, and maps to
  `Completed` unless a new request is at that path

#### Scenario: Stale request from another round is ignored
- **WHEN** an answer file named for a previous round — another stage, another attempt number,
  or the same stage and attempt number under an earlier round's token — is in the working copy
- **THEN** the current round maps to `Completed` and the stale file changes nothing

#### Scenario: A decision file beside the real one changes nothing
- **WHEN** a file under `.gnomish-task/decisions/` with any name other than the round's own path
  is present, and the round's own path holds nothing
- **THEN** the round maps to `Completed`, whether or not the other file was removed

#### Scenario: The in-place answer file leaves with the round
- **WHEN** a round runs in the git-less in-place mode
- **THEN** its answer path is a per-round file outside the workspace, gone after the round's
  close or discard

### Requirement: The answer instruction is the dialect's
The round prompt of a `transform` request SHALL tell the agent how to ask for a decision in the
words of the dialect's declared answer form: its final structured reply for a schema-enforced
answer, the answer file path for a file answer. No vendor-neutral prompt builder SHALL spell
either.
**Implements**: FR19 of extract-agent-round-runner

#### Scenario: A schema-enforced dialect is not told to write a file
- **WHEN** the prompt of a `transform` round of a dialect declaring a schema-enforced answer is
  built
- **THEN** it tells the agent to ask through its final reply and names no answer file path

### Requirement: A decision is rendered with the question it answers
The briefing's decisions section SHALL render a decision that answers a decision request as the
question, its options and the reply, through the one shared renderer, in the stage round prompt
and the judge prompt alike; a decision that answers no request SHALL render as author and body.
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: Question, options and reply reach the next round
- **WHEN** a task parked on a decision request is answered and the next round starts
- **THEN** the round's prompt carries the question, each option and the reply in its decisions section

#### Scenario: A decision without a question renders as author and body
- **WHEN** the briefing renders a decision that records no question
- **THEN** its line carries the author and the body only

#### Scenario: Any instance starts the round after an answer alike
- **WHEN** a task parked on a decision request is answered and a second instance with a fresh clone, or a fresh box, starts the next round
- **THEN** the round's prompt equals the one the parking instance would have built, and no argv carries a session flag
