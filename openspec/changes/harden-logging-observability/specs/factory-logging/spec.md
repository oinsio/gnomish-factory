# factory-logging — delta for harden-logging-observability

## Purpose

The factory's human-readable logging contract: what the rolling log and the
operator console must carry (lifecycle anchors, degrade traces, one summary per
task), what they must never carry (noise floods, forged lines, secrets, test
output), and the conventions every emitter follows. The JSON ledgers/snapshots
remain the machine-readable plane; this capability governs the text log.

## ADDED Requirements

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
keyed on the code. Emitters that cannot reach the catalog module carry the
literal code head, pinned to the catalog by a round-trip spec. INFO/DEBUG
lines carry no codes.
<!-- implements FR14 of harden-logging-observability -->

#### Scenario: Wording drifts, contract holds
- **WHEN** an operator line's prose is reworded without touching its code
- **THEN** every spec, alert, and grep keyed on the code still matches, and no
  test source needs editing

#### Scenario: A code cannot be minted twice
- **WHEN** a contributor adds a WARN line reusing an existing catalog code, or
  omits the code entirely
- **THEN** the build fails naming the site and the collision or omission

### Requirement: Every operator line is pinned by a spec
Every production WARN/ERROR call site SHALL have at least one spec asserting
the event it emits — code, level, and attribution key where the line concerns
a task or a check. A suppression site pins every edge the suppressor can emit
(first occurrence, counted roll-up, recovery), not only the first. A build
gate SHALL fail when a catalog code appears in no test source; a runtime gate
SHALL fail any spec during which a production logger emits a WARN/ERROR event
no attached capture observed and no declared allowance covers.
<!-- implements FR15, FR16, FR17 of harden-logging-observability -->

#### Scenario: A degrade line cannot land unasserted
- **WHEN** a new WARN line is added with a fresh catalog code but no spec
  asserts it
- **THEN** the static gate fails on the unreferenced code, and any spec whose
  run traverses the new path fails on the unexpected event

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
line structure — SHALL keep their shared character-stripping vocabulary (the
ANSI/control table and cap semantics) in step as a declared pair verified by
an executable equivalence spec over a common adversarial corpus. No secret
values appear in any log line.
<!-- implements FR6, NFR-S1 of harden-logging-observability -->

#### Scenario: Newline forgery is neutralized
- **WHEN** untrusted text containing newlines and a fake log-record prefix is
  logged
- **THEN** the log gains exactly one line, with the embedded newlines rendered
  inertly

#### Scenario: Unbounded payload is capped
- **WHEN** a malformed agent decision file of arbitrary size reaches its
  warning path
- **THEN** the logged excerpt is length-capped and ANSI-free

### Requirement: Exceptions keep their stack traces
Every log call site that reports an exception SHALL pass the throwable as the
trailing argument so the stack and cause chain are preserved; interpolating
`toString()`/`getMessage()` into the message instead SHALL fail the build.
<!-- implements FR7 of harden-logging-observability -->

#### Scenario: Gate rejects amputated diagnosis
- **WHEN** a change introduces a log call interpolating an exception's message
  without passing the throwable
- **THEN** the build fails naming the site

### Requirement: Complete and leak-free MDC context
Every log line emitted while working a task SHALL carry that task's MDC
context: virtual-thread hops copy and clear the context map; `stage`/`attempt`
are cleared at the same thread boundaries that clear `taskId`; daemon threads
carry a `component` key naming the worker (janitor, reaper, snapshot, sweep,
heartbeat); per-task decisions made by daemon components run under that task's
`taskId` MDC so a grep by taskId finds them.
<!-- implements FR8 of harden-logging-observability -->

#### Scenario: Pump thread keeps the task
- **WHEN** a task's round spawns a helper thread that logs
- **THEN** the helper's lines carry the round's taskId/stage/attempt

#### Scenario: Reaper decisions join the task's story
- **WHEN** the reaper removes a stale claim for a task
- **THEN** that line is found by a taskId grep alongside the task's own lines

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
