# factory-logging — delta for split-logtext-leaves

Layered on the factory-logging capability as modified by
`harden-untrusted-text-sinks` (sequenced before this change), itself layered
on `add-subprocess-access-log`: the first requirement below is written over
the sinks delta's text. If this change syncs first, the two earlier syncs
must merge by hand — the sink-layer paragraph and its four scenarios, and
the findings-funnel sentences with their two scenarios — instead of
replacing the requirement. The second requirement is written over the main
spec; no active change modifies it.

## MODIFIED Requirements

### Requirement: Untrusted text enters logs only sanitized
Text from outside the factory's trust boundary — agent/LLM output, subprocess
stderr, tracker-sourced strings, in-container command output — SHALL enter log
lines only through a sanitizing choke point that strips control and ANSI
sequences, flattens newlines so one event renders as one log line, and caps
length. The choke point and the plugin-boundary findings sanitizer — a
distinct control at a distinct trust boundary, which deliberately preserves
line structure — SHALL share one character-class table and one set of
primitives owned by the untrusted-text leaf; each is a facade over that owner,
and a single-table spec SHALL assert that both facades and the owner compute
the same function over a common adversarial corpus. The findings sanitizer
SHALL NOT prepare log-line text outside the findings funnel: a log line
carrying untrusted text uses the logging choke point even where the same raw
value also flows into findings — the judge-verdict extraction warning
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
<!-- implements FR1, FR2, FR3 of split-logtext-leaves -->

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

#### Scenario: One table serves both facades
- **WHEN** the adversarial corpus is passed through the log-line sanitizer's
  strip, the findings sanitizer's strip, and the untrusted-text leaf's strip
- **THEN** the three outputs are identical for every corpus entry, and neither
  facade holds a character-class literal of its own

### Requirement: Operator lines carry a stable event identity
Every production WARN/ERROR message SHALL begin with a stable catalog code
(`[GFnnn]`) owned by a single operator-event catalog: one code per call site,
never reused, additive-only. The code — not the wording — is the operator
contract; prose may change freely without breaking alerts, greps, or specs
keyed on the code. Every emitter, the domain's included, SHALL render the head
from the catalog constant; no production source carries a literal code head.
INFO/DEBUG lines carry no codes.
<!-- implements FR14 of harden-logging-observability -->
<!-- implements FR4, FR5 of split-logtext-leaves -->

#### Scenario: Wording drifts, contract holds
- **WHEN** an operator line's prose is reworded without touching its code
- **THEN** every spec, alert, and grep keyed on the code still matches, and no
  test source needs editing

#### Scenario: A code cannot be minted twice
- **WHEN** a contributor adds a WARN line reusing an existing catalog code, or
  omits the code entirely
- **THEN** the build fails naming the site and the collision or omission

#### Scenario: A literal head fails the build
- **WHEN** a production source spells a `[GFnnn]` head as a string literal
  instead of rendering the catalog constant
- **THEN** the log-contract gate fails naming the site — the domain's four
  emitters included, since they now reach the catalog
