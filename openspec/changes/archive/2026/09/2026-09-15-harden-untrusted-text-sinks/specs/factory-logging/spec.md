# factory-logging — delta for harden-untrusted-text-sinks

Layered on the factory-logging capability as modified by
`add-subprocess-access-log` (sequenced before this change): the first
requirement below is written over that delta's text, so syncing in that order
merges cleanly. If this change syncs first, the later access-log sync must
merge by hand — its findings-funnel sentences and its two judge/local-string
scenarios — instead of replacing the requirement. The other two requirements
are written over the main spec; no active change modifies them.

## MODIFIED Requirements

### Requirement: Untrusted text enters logs only sanitized
Text from outside the factory's trust boundary — agent/LLM output, subprocess
stderr, tracker-sourced strings, in-container command output — SHALL enter log
lines only through a sanitizing choke point that strips control and ANSI
sequences, flattens newlines so one event renders as one log line, and caps
length. The choke point and the plugin-boundary findings sanitizer — a
distinct control at a distinct trust boundary, which deliberately preserves
line structure — SHALL keep their shared character-stripping vocabulary (the
ANSI/control table and cap semantics) in step as a declared pair verified by
an executable equivalence spec over a common adversarial corpus. The findings
sanitizer SHALL NOT prepare log-line text outside the findings funnel: a log
line carrying untrusted text uses the logging choke point even where the same
raw value also flows into findings — the judge-verdict extraction warning
included. The mechanical gate that enforces the choke point SHALL catch an
untrusted value that reaches a log call through a local variable, and a
findings-sanitizer call inside a log argument outside the findings funnel
SHALL fail it. No secret values appear in any log line.

The choke point is the first of two layers, not the only one. Every log
appender the factory configures SHALL additionally neutralize the *rendered*
record at the sink, independently of what the emitting call site did: the
formatted message has control, ANSI, C1 and bidirectional-override sequences
made inert, embedded line breaks rendered as the same visible marker the choke
point produces, and its length bounded by a per-record cap with a visible
truncation marker. The sink layer SHALL be idempotent over choke-point output:
a message the choke point already prepared renders byte-identically with and
without the sink layer. The sink layer SHALL never fail a record: a value it
cannot neutralize degrades to a bounded, control-free placeholder naming the
failure. The end-to-end property — no escape, no forged record, no unbounded
record, whatever the call site assembled — SHALL be asserted on the real
appenders over an adversarial corpus, for the message path, the exception
path and the MDC path.
<!-- implements FR6, NFR-S1 of harden-logging-observability -->
<!-- implements FR16 of add-subprocess-access-log -->
<!-- implements FR1, FR2, NFR-R1, NFR-O1, NFR-S1 of harden-untrusted-text-sinks -->

#### Scenario: Newline forgery is neutralized
- **WHEN** untrusted text containing newlines and a fake log-record prefix is
  logged
- **THEN** the log gains exactly one line, with the embedded newlines rendered
  inertly

#### Scenario: Unbounded payload is capped
- **WHEN** a malformed agent decision file of arbitrary size reaches its
  warning path
- **THEN** the logged excerpt is length-capped and ANSI-free

#### Scenario: The judge-verdict warning is flattened by the choke point
- **WHEN** judge-verdict extraction warns with a raw multi-line model message
- **THEN** the log gains exactly one line, sanitized by the logging choke
  point, while the findings path keeps its own funnel semantics unchanged

#### Scenario: The local-string bypass fails the gate
- **WHEN** a production log call carries untrusted text held in a local
  variable and prepared by the findings sanitizer outside the findings funnel
- **THEN** the untrusted-log-text gate fails the build naming the offending
  site

#### Scenario: A laundered string is neutralized at the sink
- **WHEN** a production log call carries a string that was assembled from
  untrusted text three calls earlier — an operator report, a `reason` field,
  an exception message read back through `getMessage()` — and no choke-point
  call sits at the log site
- **THEN** the appender still writes exactly one record, with every control,
  ANSI, C1 and bidi sequence rendered inertly and the record bounded by the
  per-record cap

#### Scenario: Choke-point output passes the sink unchanged
- **WHEN** a log call's argument was prepared by the choke point
- **THEN** the written record is byte-identical to the record the same call
  produced before the sink layer existed — the visible newline marker is not
  escaped a second time and no second cap is applied

#### Scenario: The sink never drops a record
- **WHEN** the sink neutralizer fails on a record (a malformed surrogate pair,
  a pattern-engine failure)
- **THEN** the appender writes a bounded, control-free placeholder record that
  names the failure, and the appender stays alive for the next record

#### Scenario: The end-to-end invariant holds on the real appenders
- **WHEN** the adversarial corpus (ESC/CSI/OSC/DCS with and without
  terminators, 8-bit C1, bidi overrides, U+2028/U+2029, zero-width and tag
  characters, CRLF forged-record prefixes, a 1 MB payload) is logged through
  the real configuration into a captured appender — as a message argument, as
  the message of a thrown-and-caught exception passed as the trailing
  argument, and as an MDC value
- **THEN** the captured bytes contain no ESC, no C1 byte, no bidi override, no
  bare line break inside a record, and no record longer than the cap

### Requirement: Exceptions keep their stack traces
Every log call site that reports an exception SHALL pass the throwable as the
trailing argument so the stack and cause chain are preserved; interpolating
`toString()`/`getMessage()` into the message instead SHALL fail the build.
The rendered throwable SHALL be neutralized line by line at the sink: control,
ANSI, C1 and bidi sequences made inert in every line and carriage returns
removed, while trace lines keep their indentation. Every line of the rendering
that is not a recognized trace line SHALL be written as a marked continuation
line, so a line break embedded in an exception's message can never place text
at column 0 of the log and forge a record.
<!-- implements FR7 of harden-logging-observability -->
<!-- implements FR3, NFR-S1 of harden-untrusted-text-sinks -->

#### Scenario: Gate rejects amputated diagnosis
- **WHEN** a change introduces a log call interpolating an exception's message
  without passing the throwable
- **THEN** the build fails naming the site

#### Scenario: A multi-line exception message cannot forge a record
- **WHEN** an exception whose message embeds a newline followed by a fake
  record prefix is logged as the trailing argument
- **THEN** the rendering keeps the stack trace lines indented and readable,
  and the message's second line is written as a marked continuation line,
  never at column 0

#### Scenario: Escapes in a trace line are visible, not executed
- **WHEN** an exception message carrying an ANSI cursor sequence is rendered
- **THEN** the written trace shows the sequence as inert visible text

### Requirement: Complete and leak-free MDC context
Every log line emitted while working a task SHALL carry that task's MDC
context: virtual-thread hops copy and clear the context map; `stage`/`attempt`
are cleared at the same thread boundaries that clear `taskId`; daemon threads
carry a `component` key naming the worker (janitor, reaper, snapshot, sweep,
heartbeat); per-task decisions made by daemon components run under that task's
`taskId` MDC so a grep by taskId finds them. Every MDC value the encoder
renders SHALL pass the same sink-side neutralization as the message and SHALL
be single-line, so a stage name or task id taken from a manifest or a tracker
can neither forge a record nor drive the terminal through the record's
context fields.
<!-- implements FR8 of harden-logging-observability -->
<!-- implements FR4, NFR-S1 of harden-untrusted-text-sinks -->

#### Scenario: Pump thread keeps the task
- **WHEN** a task's round spawns a helper thread that logs
- **THEN** the helper's lines carry the round's taskId/stage/attempt

#### Scenario: Reaper decisions join the task's story
- **WHEN** the reaper removes a stale claim for a task
- **THEN** that line is found by a taskId grep alongside the task's own lines

#### Scenario: A hostile stage name in the MDC is inert
- **WHEN** the `stage` MDC value carries a newline and an ANSI sequence taken
  from the target repository's manifest
- **THEN** every record emitted under it renders the value on one line with
  the sequence inert, and the `taskId` grep still finds the task's lines
