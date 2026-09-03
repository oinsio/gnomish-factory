# factory-logging — delta for add-subprocess-access-log

Layered on the factory-logging capability as added by
`harden-logging-observability` (sequenced before this change), whose
requirement text this delta restates and extends.

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
<!-- implements FR6, NFR-S1 of harden-logging-observability -->
<!-- implements FR16 of add-subprocess-access-log -->

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
