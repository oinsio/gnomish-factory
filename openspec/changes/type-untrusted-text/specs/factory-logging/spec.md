# factory-logging — delta for type-untrusted-text

Layered on the factory-logging capability as modified by
`split-logtext-leaves` (sequenced before this change), itself layered on
`harden-untrusted-text-sinks` and `add-subprocess-access-log`: the
requirement below is written over the leaves delta's text. If this change
syncs first, the three earlier syncs must merge by hand — the single-table
sentences and scenario, the sink-layer paragraph with its four scenarios, and
the findings-funnel sentences with their two scenarios — instead of replacing
the requirement.

## MODIFIED Requirements

### Requirement: Untrusted text enters logs only sanitized
Text from outside the factory's trust boundary — agent/LLM output, subprocess
stderr, tracker-sourced strings, in-container command output, manifest
strings, task-branch document values — SHALL be carried by the untrusted-text
type from the point of capture and SHALL enter log lines only through its log
exit, which strips control and ANSI sequences, flattens newlines so one event
renders as one log line, and caps length. The log exit and the plugin-boundary
findings sanitizer — a distinct control at a distinct trust boundary, which
deliberately preserves line structure — SHALL share one character-class table
and one set of primitives owned by the untrusted-text leaf; each is a facade
over that owner, and a single-table spec SHALL assert that both facades and
the owner compute the same function over a common adversarial corpus. The
findings sanitizer SHALL NOT prepare log-line text outside the findings
funnel: a log line carrying untrusted text uses the log exit even where the
same raw value also flows into findings — the judge-verdict extraction
warning included. The mechanical gate that enforces the rule SHALL be
type-level: a capture accessor returning a plain string, a raw read outside an
annotated exit, and a carrier passed to a log call without an exit each fail
the build; the accessor-name scan that preceded it is retired. No secret
values appear in any log line.

The log exit is the second of three layers. The first is capture: the seven
capture families mint the carrier where the text enters the process. The
third is the sink: every log appender the factory configures SHALL
additionally neutralize the *rendered* record, independently of what the
emitting call site did — the formatted message has control, ANSI, C1 and
bidirectional-override sequences made inert, embedded line breaks rendered as
the same visible marker the log exit produces, and its length bounded by a
per-record cap with a visible truncation marker. The sink layer SHALL be
idempotent over log-exit output: a message the exit already prepared renders
byte-identically with and without the sink layer. The sink layer SHALL never
fail a record: a value it cannot neutralize degrades to a bounded,
control-free placeholder naming the failure. The end-to-end property — no
escape, no forged record, no unbounded record, whatever the call site
assembled — SHALL be asserted on the real appenders over an adversarial
corpus, for the message path, the exception path and the MDC path.
<!-- implements FR6, NFR-S1 of harden-logging-observability -->
<!-- implements FR16 of add-subprocess-access-log -->
<!-- implements FR1, FR2, NFR-R1, NFR-O1, NFR-S1 of harden-untrusted-text-sinks -->
<!-- implements FR1, FR2, FR3 of split-logtext-leaves -->
<!-- implements FR4, FR7, NFR-R1, NFR-S1 of type-untrusted-text -->

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
- **THEN** the log gains exactly one line, sanitized by the log exit, while
  the findings path keeps its own funnel semantics unchanged

#### Scenario: The local-string bypass fails the gate
- **WHEN** a production log call carries a carrier held in a local variable
  and passed without an exit, or prepared by the findings sanitizer outside
  the findings funnel
- **THEN** the type gate fails the build naming the offending site

#### Scenario: A laundered string is neutralized at the sink
- **WHEN** a production log call carries a string that was assembled from
  untrusted text three calls earlier — an operator report, a `reason` field,
  an exception message read back through `getMessage()` — and no exit call
  sits at the log site
- **THEN** the appender still writes exactly one record, with every control,
  ANSI, C1 and bidi sequence rendered inertly and the record bounded by the
  per-record cap

#### Scenario: Choke-point output passes the sink unchanged
- **WHEN** a log call's argument was prepared by the log exit
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

#### Scenario: One table serves both facades
- **WHEN** the adversarial corpus is passed through the log exit's strip, the
  findings sanitizer's strip, and the untrusted-text leaf's strip
- **THEN** the three outputs are identical for every corpus entry, and neither
  facade holds a character-class literal of its own

#### Scenario: An exception built from subprocess output is inert by type
- **WHEN** a git persistence failure is thrown with the command's captured
  standard error and logged as the trailing argument
- **THEN** the exception's message is the carrier's log exit and no
  per-site sanitizing call exists at the throw site
