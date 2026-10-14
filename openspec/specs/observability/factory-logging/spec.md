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
together with the best-effort-must-log rule, the one-failure-one-log boundary
rule, and the retention rationale.
**Implements**: FR1 of harden-logging-observability

#### Scenario: The policy ADR defines its three rules
- **WHEN** a contributor reads the logging-policy ADR
- **THEN** it states the best-effort-must-log rule (a degrade path that
  swallows a failure must leave a trace), the one-failure-one-log boundary
  rule (the layer that decides logs it; layers below do not), and the
  retention rationale (the log is expendable; ledgers, the task branch, and
  the tracker are the durable record)

#### Scenario: Emitters have a one-page checklist
- **WHEN** a contributor adds or changes a log call
- **THEN** a companion rules file gives them the policy as a one-page
  checklist

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
**Implements**: FR2 of harden-logging-observability

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
**Implements**: FR3 of harden-logging-observability

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
**Implements**: FR4, NFR-R2 of harden-logging-observability

#### Scenario: Dead dependency floods no console
- **WHEN** a polled dependency stays down across many poll intervals
- **THEN** the console shows the first WARN, periodic counted roll-ups, and no
  per-poll repetition

#### Scenario: Recovery is announced
- **WHEN** the polled dependency comes back
- **THEN** one line reports the recovery and the elapsed outage

### Requirement: No silent degradation
Every path that returns a degraded result after an internal failure SHALL log
the failure with enough context to attribute it.
**Implements**: FR5 of harden-logging-observability

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

#### Scenario: An egress refusal in a guarded check is logged
- **WHEN** a guarded check's request is refused by the egress guard
- **THEN** the refusal is logged with the check it concerns

#### Scenario: A destructive cross-instance action is logged
- **WHEN** an instance removes another instance's stale claim or repairs a
  task's index, or aborts such an action to converge with another instance
- **THEN** the action or its converge-abort is logged with the task it
  concerns

#### Scenario: A failed best-effort cleanup is logged
- **WHEN** a best-effort cleanup step fails — a claim-comment delete, a
  worktree removal, an environment dispose step
- **THEN** the failure is logged naming the step and its subject, and the
  caller's degraded result stands

#### Scenario: A reader that drops data says so
- **WHEN** a reader drops malformed or unreadable data — a dashboard source,
  or a token-usage extraction that yields empty
- **THEN** the drop is logged, and a dashboard source distinguishes a missing
  input from a malformed one

#### Scenario: Adopting another instance's work is logged
- **WHEN** a resume recreates the task branch from the origin tracking ref,
  adopting another instance's work
- **THEN** the adoption is logged rather than happening silently

#### Scenario: A degradation warning names its subject, never a secret
- **WHEN** a degradation warning concerns a check or a secret
- **THEN** it names the check identity or the secret's variable name, and
  never the secret's value

### Requirement: Operator lines carry a stable event identity
Every production WARN/ERROR message SHALL begin with a stable catalog code
(`[GFnnn]`) owned by a single operator-event catalog: one code per call site,
never reused, additive-only. The code — not the wording — is the operator
contract; prose may change freely without breaking alerts, greps, or specs
keyed on the code. INFO/DEBUG lines carry no codes.
**Implements**: FR14 of harden-logging-observability; FR4, FR5, FR10 of split-logtext-leaves

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
gate SHALL fail when a catalog code appears in no test source.
**Implements**: FR15, FR16, FR17 of harden-logging-observability

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

#### Scenario: Every unwatched operator line is reported
- **WHEN** a spec emits a WARN/ERROR event that no attached capture was
  watching
- **THEN** the runtime gate names the event in the run's report

#### Scenario: A declared allowance excuses an unwatched code
- **WHEN** the build's test run emits an operator-event code that no attached
  capture anywhere in that run was watching, and a declared allowance covers
  the emitting feature
- **THEN** the runtime gate does not fail the build on that code, while the
  same code with no allowance and no watching capture fails it

### Requirement: Untrusted text enters logs only sanitized
Text from outside the factory's trust boundary — agent/LLM output, subprocess
stderr, tracker-sourced strings, in-container command output, manifest
strings, task-branch document values — SHALL be carried by the untrusted-text
type from the point of capture and SHALL enter log lines only through its log
exit, which strips control and ANSI sequences, flattens newlines so one event
renders as one log line, and caps length. No secret values appear in any log
line.
**Implements**: FR6, NFR-S1 of harden-logging-observability; FR16 of add-subprocess-access-log; FR1, FR2, NFR-R1, NFR-O1, NFR-S1 of harden-untrusted-text-sinks; FR1, FR2, FR3 of split-logtext-leaves; FR4, FR7, FR10, FR11, NFR-C1, NFR-R1, NFR-S1 of type-untrusted-text; FR10, NFR-S1, UX3 of fix-envelope-medium
**Notes**: The log exit is the second of three layers: the first is capture, where the seven capture families mint the carrier as the text enters the process; the third is the sink, every log appender the factory configures.
**Notes**: The plugin-boundary findings sanitizer is a distinct control at a distinct trust boundary, and deliberately preserves line structure; it and the log exit are facades over one owner.
**Notes**: The accessor-name scan that preceded the type gate was retargeted rather than retired: names no longer guard sinks — the type does — and the scan guards the capture point instead.

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

#### Scenario: A capture source that never mints fails the build
- **WHEN** production code outside the declared mint owners reads a subprocess
  stream, an HTTP response body, or a document file into a plain `String`
- **THEN** the capture-point scan fails the build naming the file, because no
  type rule can see text that was never carried

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

#### Scenario: A caller-bounded excerpt keeps the caller's words under the record cap
- **WHEN** a git invocation fails with a standard error of 1 400 line-separator
  characters (`U+2028`), each of which renders as a six-character visible
  escape, and the failure detail quotes it under the caller's bound
- **THEN** the excerpt that leaves the exit is no longer than the caller's
  bound, so the rendered record still begins with the caller's prose and the
  `exited <code>` opening rather than losing them to the record cap's
  tail-keeping truncation

#### Scenario: Every bypass shape fails the type gate
- **WHEN** a production capture accessor returns a plain string, or a class
  reads the carrier's raw text outside an annotated exit, or reads it for
  parsing outside an annotated parser
- **THEN** the type gate fails the build naming the offending site

#### Scenario: The capture-point scan proves its reach
- **WHEN** the capture-point scan runs
- **THEN** it asserts that it reached every file it claims to cover, and its
  allowlist names each mint owner with the family it mints, so a moved or
  renamed mint owner fails the build instead of silently leaving the scan

#### Scenario: The sink marks a truncated record
- **WHEN** a rendered record exceeds the per-record cap at a log appender
- **THEN** the written record is bounded by the cap and carries a visible
  truncation marker

#### Scenario: The sink renders a line break as the log exit does
- **WHEN** a rendered record that bypassed the log exit embeds a line break
- **THEN** the appender writes the break as the same visible marker the log
  exit produces, on one record line

### Requirement: Exceptions keep their stack traces
Every log call site that reports an exception SHALL pass the throwable as the
trailing argument so the stack and cause chain are preserved; interpolating
`toString()`/`getMessage()` into the message instead SHALL fail the build.
**Implements**: FR7 of harden-logging-observability; FR3, NFR-S1 of harden-untrusted-text-sinks

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

#### Scenario: Every line of a rendered throwable is neutralized
- **WHEN** an exception whose message carries carriage returns, C1 and bidi
  override sequences is logged as the trailing argument
- **THEN** the sink neutralizes the rendering line by line: every line has
  its control, ANSI, C1 and bidi sequences made inert and its carriage returns
  removed, and the trace lines keep their indentation

#### Scenario: A line that is not a trace line is marked
- **WHEN** the rendering of a logged throwable holds a line that is not a
  recognized trace line — a cause's or a suppressed exception's message, an
  unrecognized line shape
- **THEN** that line is written as a marked continuation line, never at
  column 0

### Requirement: Complete and leak-free MDC context
Every log line emitted while working a task SHALL carry that task's MDC
context: virtual-thread hops copy and clear the context map; `stage`/`attempt`
are cleared at the same thread boundaries that clear `taskId`.
**Implements**: FR8 of harden-logging-observability; FR4, NFR-S1 of harden-untrusted-text-sinks

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

#### Scenario: A daemon worker's lines carry its component
- **WHEN** a daemon thread logs
- **THEN** its lines carry a `component` MDC key naming the worker by a key
  of the closed `DaemonComponent` vocabulary (janitor, reaper, snapshot,
  sweep, heartbeat, dashboard)

#### Scenario: A daemon's per-task decision runs under the task's id
- **WHEN** a daemon component makes a decision about one task
- **THEN** the decision is logged under that task's `taskId` MDC, so a grep by
  taskId finds it

#### Scenario: Every MDC value renders single-line and inert
- **WHEN** the encoder renders any MDC value — a task id taken from a tracker,
  a stage name taken from a manifest
- **THEN** the value passes the same sink-side neutralization as the message
  and renders on one line, so it can neither forge a record nor drive the
  terminal through the record's context fields

### Requirement: Runtime verbosity without rebuild
The log level SHALL be raisable to DEBUG for a run via environment or
configuration property, with no rebuild, and the default remains INFO. Test
runs SHALL use a test-only logging configuration and never write to the
operator's log location; specs assert logging through the shared capture
helper that saves and restores logger state.
**Implements**: FR10, FR11 of harden-logging-observability

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
**Implements**: FR10, NFR-P1 of harden-logging-observability

#### Scenario: Signal does not eat the tail
- **WHEN** the process stops via the owned shutdown sequence
- **THEN** every line logged before the stop began is present in the file
