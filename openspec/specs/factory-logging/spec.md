# factory-logging Specification

## Purpose

The factory's human-readable logging contract: what the rolling log and the
operator console must carry (lifecycle anchors, degrade traces, one summary per
task), what they must never carry (noise floods, forged lines, secrets, test
output), and the conventions every emitter follows. The JSON ledgers/snapshots
remain the machine-readable plane; this capability governs the text log.

## Requirements

### Requirement: Written level policy governs every emitter
A logging-policy ADR SHALL define log levels by required reader reaction —
ERROR: the operator must act; WARN: the operator should look, and a persistent
WARN means act; INFO: lifecycle anchors and state changes; DEBUG: diagnosis —
together with the best-effort-must-log rule (a degrade path that swallows a
failure must leave a trace), the one-failure-one-log boundary rule (the layer
that decides logs it; layers below do not), and the retention rationale (the
log is expendable; ledgers, the task branch, and the tracker are the durable
record). A companion rules file SHALL give emitters a one-page checklist.
<!-- implements FR1 of harden-logging-observability -->

#### Scenario: Recovered transient is not a WARN
- **WHEN** an operation fails once and succeeds on its bounded retry
- **THEN** the recovery path logs at most INFO and the operator console (WARN+)
  stays silent

#### Scenario: One failure, one log
- **WHEN** a failure propagates through a layer that retries and a layer that
  gives up
- **THEN** exactly one WARN/ERROR line records the outcome, written by the
  layer that made the final decision

### Requirement: Lifecycle anchor lines
The operator-plane lifecycle SHALL be anchored at INFO through a single owner
of the anchor vocabulary: claim acquired (both claim paths emit the identical
form), serve started (instance, WIP limit, grace, intervals), serve stopping
(reason), and the per-task summary. Remote-module lifecycle transitions —
container environment created/reattached/disposed, git task-lifecycle commits —
SHALL log one INFO line at their own choke points following the same policy.
<!-- implements FR2 of harden-logging-observability -->

#### Scenario: Claim is the first correlated line
- **WHEN** a task is claimed by any claim path
- **THEN** the log carries a claim-acquired INFO line for that taskId before
  any engine event of that task

#### Scenario: Container lifecycle is reconstructible
- **WHEN** a task's container environment is created and later disposed
- **THEN** the log carries one INFO line for the creation (environment key,
  image) and one for the disposal, and a failed dispose step logs which step
  failed for which environment

### Requirement: Canonical per-task summary line
Every task leaving the factory SHALL produce exactly one summary line carrying
outcome, stage, attempts used, wall time, and token usage by model — rendered by
one renderer for all modes (serve, take, manual run) from the same summary
facts that feed the ledger where a ledger line exists. The summary SHALL be
emitted on crash-shaped exits of the work as well as clean ones.
<!-- implements FR3 of harden-logging-observability -->

#### Scenario: Summary closes the grep story
- **WHEN** an operator greps the log by one taskId after the task finished
- **THEN** the last line of the result is the task's summary with outcome,
  duration, attempts, and token usage

#### Scenario: Manual run gets the same summary
- **WHEN** a manual run finishes a task in any terminal outcome
- **THEN** the identical summary form is emitted, assembled from engine events

### Requirement: Repeated failures log edges, not floods
Poll and retry loops SHALL log state edges: the first occurrence of a failure
(or a changed reason) at the site's level, repeats at DEBUG, a periodic
roll-up naming the repeat count, and one recovery line when the condition
clears. Suppression state is in-memory only; a process restart may repeat the
first-occurrence line. One owner component SHALL provide this behavior for
poll sites; sites in modules that cannot reach it SHALL aggregate counts
locally to the same observable effect.
<!-- implements FR4, NFR-R2 of harden-logging-observability -->

#### Scenario: Dead dependency floods no console
- **WHEN** a polled dependency stays down across many poll intervals
- **THEN** the console shows the first WARN, periodic counted roll-ups, and no
  per-poll repetition

#### Scenario: Recovery is announced
- **WHEN** the polled dependency comes back
- **THEN** one line reports the recovery and the elapsed outage

### Requirement: No silent degradation
Every path that returns a degraded result after an internal failure SHALL log
the failure with enough context to attribute it: external-API retries,
backoffs, and exhaustion; verification votes that cannot be cast; egress
refusals in guarded checks; destructive cross-instance actions (stale-claim
removal, index repair) and their converge-aborts; best-effort cleanup failures
(claim-comment delete, worktree removal, environment dispose steps); fallbacks
that fabricate defaults (abort-facts unavailable); readers that drop malformed
or unreadable data (dashboard sources distinguishing missing from malformed,
token-usage extraction yielding empty); silent adoption of another instance's
work (resume-branch recreation from the origin tracking ref); degradation
warnings SHALL name their subject (check identity, secret variable name —
never the value).
<!-- implements FR5 of harden-logging-observability -->

#### Scenario: Retry storm is visible
- **WHEN** an external API call is retried with backoff until exhaustion
- **THEN** each retry logs its attempt number and wait, and the exhaustion
  names the final failure

#### Scenario: A vote that cannot be cast says so
- **WHEN** a judge vote fails for infrastructure reasons before producing a
  verdict
- **THEN** a WARN names the reason and detail, in addition to the cannot-verify
  result the caller receives

#### Scenario: Fabricated default is attributed
- **WHEN** a tracker read fails and the caller substitutes an empty default
  that influences an escalation decision
- **THEN** a WARN records the substitution and its consequence before the
  decision is made

### Requirement: Operator lines carry a stable event identity
Every production WARN/ERROR message SHALL begin with a stable catalog code
(`[GFnnn]`) owned by a single operator-event catalog: one code per call site,
never reused, additive-only. The code — not the wording — is the operator
contract; prose may change freely without breaking alerts, greps, or specs
keyed on the code. Every emitter, the domain's included, SHALL render the head
from the catalog constant; no production source carries a literal code head,
and the log-contract gate SHALL fail the build on one — the catalog constant's
rendering is the only accepted form. INFO/DEBUG lines carry no codes.
<!-- implements FR14 of harden-logging-observability -->
<!-- implements FR4, FR5, FR10 of split-logtext-leaves -->

#### Scenario: Wording drifts, contract holds
- **WHEN** an operator line's prose is reworded without touching its code
- **THEN** every spec, alert, and grep keyed on the code still matches, and no
  test source needs editing

#### Scenario: A code cannot be minted twice
- **WHEN** a contributor adds a WARN line reusing an existing catalog code, or
  omits the code entirely
- **THEN** the build fails naming the site and the collision or omission

#### Scenario: A literal head fails the build
- **WHEN** a production source in any module spells a `[GFnnn]` head as a
  string literal instead of rendering the catalog constant — in a log call, in
  a constant the call prepends, or in any other string
- **THEN** the log-contract gate fails naming the site — the domain's four
  emitters included, since they now reach the catalog — while the same call
  rendering `OperatorEvent.<CONSTANT>.head()` passes

### Requirement: Every operator line is pinned by a spec
Every production WARN/ERROR call site SHALL have at least one spec asserting
the event it emits — code, level, and attribution key where the line concerns
a task or a check. A suppression site pins every edge the suppressor can emit
(first occurrence, counted roll-up, recovery), not only the first. A build
gate SHALL fail when a catalog code appears in no test source. A runtime gate
SHALL report every WARN/ERROR event a spec emitted that no attached capture was
watching, and SHALL fail the build on an operator-event code the build's test
run emitted that no attached capture anywhere in that run was watching and no
declared allowance covers.
<!-- implements FR15, FR16, FR17 of harden-logging-observability -->

#### Scenario: A degrade line cannot land unasserted
- **WHEN** a new WARN line is added with a fresh catalog code but no spec
  asserts it
- **THEN** the static gate fails on the unreferenced code, and the runtime gate
  fails the build that emitted it with nothing watching

#### Scenario: Crossing a pinned degrade path is reported, not failed
- **WHEN** a behavior spec traverses an operator line that another spec pins,
  in this module or in the one that owns the emitter
- **THEN** the line is named in the run's per-feature report, and the build
  does not fail — the code is watched, and the traversal is not the defect

#### Scenario: The level is part of the pin
- **WHEN** a pinned WARN line is demoted to DEBUG without its spec changing
- **THEN** the pinning spec goes red — the level, not only the text, is the
  asserted contract

#### Scenario: Roll-up edges are contract too
- **WHEN** a suppression site's first-occurrence line is pinned but its
  counted roll-up branch is broken
- **THEN** a spec driving the streak past the roll-up threshold goes red

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

### Requirement: Runtime verbosity without rebuild
The log level SHALL be raisable to DEBUG for a run via environment or
configuration property, with no rebuild, and the default remains INFO. Test
runs SHALL use a test-only logging configuration and never write to the
operator's log location; specs assert logging through the shared capture
helper that saves and restores logger state.
<!-- implements FR10, FR11 of harden-logging-observability -->

#### Scenario: Operator raises verbosity for one run
- **WHEN** the operator sets the documented level variable and starts the
  factory
- **THEN** DEBUG lines appear in the file for that run without any rebuild

#### Scenario: Test suite leaves the operator log untouched
- **WHEN** the full build and test suite runs
- **THEN** the operator's log directory receives no new lines from it

### Requirement: Async file, synchronous console, owned flush
INFO-volume traffic SHALL NOT block worker threads on file I/O: the file
appender is asynchronous with no event discarding while the JVM lives; console
appenders remain synchronous and carry WARN+ only; the shutdown sequence owns
the final flush so buffered lines survive a signal-initiated stop. All
encoders pin UTF-8.
<!-- implements FR10, NFR-P1 of harden-logging-observability -->

#### Scenario: Signal does not eat the tail
- **WHEN** the process stops via the owned shutdown sequence
- **THEN** every line logged before the stop began is present in the file
