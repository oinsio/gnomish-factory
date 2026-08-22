# Delta: agent-executor — continuous stdout drain

## ADDED Requirements

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

## MODIFIED Requirements

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
