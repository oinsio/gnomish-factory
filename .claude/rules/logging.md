---
paths:
  - "**/*.java"
---

# Rule: logging

The one-page checklist for anyone adding or touching a log call. The reasoning,
the rejected alternatives and the accepted deviations live in
`docs/adr/0004-logging-policy.md`; this file is what you check the line against.
Together they implement FR1 of `harden-logging-observability`.

## Pick the level by the reader's required reaction

| Level | The reader …                                                 | Use for                                                         |
|-------|--------------------------------------------------------------|-----------------------------------------------------------------|
| ERROR | **must act** — work was lost, or a component cannot continue | unrecoverable failures, lost work, a daemon dying               |
| WARN  | **should look**; a persistent WARN means act                 | degraded results, fallbacks, destructive cross-instance actions |
| INFO  | reads it in a post-mortem timeline                           | lifecycle anchors, state changes, the per-task summary          |
| DEBUG | reads it only while diagnosing                               | per-item detail, retries in progress, reconciliation chatter    |

Not a WARN: a failure a bounded retry recovered (INFO at most, DEBUG per
attempt); a first-of-two attempts failing; anything emitted once per polled
item, per tool call, or per swept object (DEBUG — aggregate it if it matters).
A healthy hour of `serve` produces **zero** console output; WARN+ is the
console.

Not an ERROR: a death the daemon's own stop caused. Once
`ShutdownPhase.inProgress()` (module `:logtext`) is true, a killed subprocess,
an interrupted worker or an abandoned slot is one WARN without a stack — mark
the exemption with the `throwable-not-subject` comment below.

## Every WARN/ERROR carries its catalog code

The message head of a production WARN or ERROR is a stable `[GFnnn]` code from
`com.github.oinsio.gnomish.logtext.OperatorEvent` (module `:logtext`) — one
constant per call site, never reused, additive-only:

```java
log.error(OperatorEvent.SWEEP_LEDGER_APPEND_FAILED.head() + "failed to append sweep ledger line", e);
```

The code is what an alert, a grep and a spec key on, so the sentence beside it
may be rewritten at any time. Rules:

- **A new WARN/ERROR takes the next free number** in the catalog. Do not fill a
  gap left by a deleted constant — a retired code stays retired.
- **One code, one call site.** Two emitters of the same fault (a
  with-throwable twin, a roll-up branch) are two constants.
- **INFO and DEBUG never carry codes.** The catalog is the operator plane only.
- A module that cannot reach `:logtext` — today only the four `:domain`
  emitters ADR 0004 exempts — writes the literal `[GFnnn] ` head and is pinned
  to the catalog by `DomainOperatorEventHeadSpec`.

`LogContractGateSpec` fails the build on an uncoded site, a duplicated code, or
a code no test source names; exempt in place with `log-contract-exempt:
<reason>` when a site genuinely must stay uncoded.

## Best effort must still leave a trace

Catching a failure and continuing with a degraded result — an empty default, a
skipped cleanup, an unread cursor — **requires a log line** carrying enough
context to attribute it: the subject, the check identity, the environment key,
and, when the degraded value feeds a decision, what that decision now rests on.
A bare `catch (… e) { /* best effort */ }` is a finding. The one exception: a
failure that *is* the normal outcome being classified (an expected `NotFound`
on a probe) — log the classification at DEBUG.

## One failure, one log

The layer that makes the **final decision** writes the WARN/ERROR. Layers below
it rethrow or log at DEBUG. Two lines for one fault is a finding.

## Pass the throwable as the trailing argument

```java
log.warn("could not read {}", path, e);              // yes
log.warn("could not read {}: {}", path, e.toString());   // no — amputated stack
log.warn("could not read {}: {}", path, e.getMessage()); // no — prints null for cause-only
```

A source-scan gate fails the build on interpolated exceptions. Where the
throwable genuinely is not the subject, exempt in place with a comment on the
call's own line or directly above it:

```java
// throwable-not-subject: only the classification matters here; the cause is
//     re-thrown to the caller, which logs it.
log.debug("fetch classified as not-found for {}", ref);
```

## Route untrusted text through `LogText`

Agent/LLM output, subprocess stderr, tracker-sourced strings and in-container
command output are attacker-influenced: they enter a log line **only** through
`com.github.oinsio.gnomish.logtext.LogText` (module `:logtext`), which strips
control/ANSI sequences, flattens newlines so one event stays one line, and caps
length.

```java
log.warn("stage command failed: {}", LogText.forLog(result.stderr()));
```

A source-scan gate (`UntrustedLogTextGateSpec`) fails the build when one of the
recognized untrusted accessors — `stderr()`, `stdout()`, `getOriginalMessage()`,
`sessionId()`, `model()` — reaches a log call outside a `LogText.*(...)`
wrapper. Both SLF4J shapes are scanned: the classic `log.warn(...)` and the
fluent `log.atLevel(...)....log(...)`, whose arguments sit past the level call
and are followed through the builder chain. The accessor list is what the gate
can see, not the whole rule: a change that introduces a new untrusted accessor
adds it there in the same change.

### Known limit: untrusted text in exception messages

The gate scans **log call sites**. An exception whose *message* concatenates
subprocess output escapes it structurally — nothing untrusted appears at the
log call, yet Logback renders the message when the throwable is logged, so the
control characters and forged newlines land in the record anyway.

So the obligation is on the throw site, not on the gate: **an exception that
carries subprocess or in-container output into its message sanitizes it at
construction.**

```java
throw new TaskListingFailedException(pattern, result.exitCode(), LogText.forLog(result.stderr()));
```

Sites predating this rule still concatenate raw `stderr()` (across
`adapters/git`, `gitobjects`, `sandbox/docker`); they are honest debt, not
precedent. Bringing them under the rule — and extending the gate to
exception-constructor arguments so it stops being a limit — is its own change.

Never log a secret **value**; a warning about a secret names the variable only.
`FindingsSanitizer` is a different control at a different boundary (plugin
findings, line structure preserved) — do not use it for log lines, and do not
add a production edge between the two (they are a declared pair, see
`manual-sync-pairs.md`).

## Suppress repeats in poll and retry loops

A loop that can fail every tick uses `RepeatSuppressor` (`:logtext`): first
occurrence (or a changed reason) at the site's level, repeats DEBUG, periodic
roll-up with the count at the site's level, one recovery line with the outage.

```java
switch (suppressor.failed(key, reason)) {
    case RepeatOccurrence.First f -> log.warn("workflow poll failing: {}", f.reason());
    case RepeatOccurrence.Repeat r -> log.debug("workflow poll still failing ({})", r.count());
    case RepeatOccurrence.RollUp u -> log.warn("workflow poll failing {}x over {}", u.count(), u.elapsed());
}
```

A site that floods *within one operation* (a parse loop over one file, a bulk
deletion) counts locally and emits **one** aggregate line per operation instead
— a different invariant, deliberately not a second suppressor.

## Keep the MDC complete and leak-free

- Every line emitted while working a task carries `taskId` (plus `stage` /
  `attempt` where they apply).
- A virtual-thread hop that logs wraps its body with `MdcAwareThread` so the
  context is copied in and cleared on exit.
- `stage` / `attempt` are cleared at the same boundaries that clear `taskId`.
- A daemon worker sets `component` once at start by framing its loop with
  `DaemonComponent.<NAME>.framing(...)` — that enum owns the vocabulary
  (janitor, reaper, snapshot, sweep, heartbeat); a new daemon adds a constant
  there rather than a string literal at the call site.
- Per-task work done by a daemon runs inside `MdcAwareThread.taskScope(id)`
  (try-with-resources), so `grep taskId=<id>` finds it. That helper owns the
  key's spelling; a module that cannot reach `:logtext` and must repeat the
  literal says why in place and pins the two equal with a spec.

## Assert logging with the shared helper

Specs that assert emitted lines use
`com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport` (module
`:test-fixtures`; it saves and restores the logger's level and detaches its own
appender). Do not hand-roll a `ListAppender` block in a new spec; existing
hand-rolled blocks migrate when their spec is touched, not in bulk.

Assert the **event**, not the sentence: the emitter's `OperatorEvent` head, the
level, and the attribution key (`taskId`, the check identity) the line must
carry. A `startsWith('some prose')` re-freezes the wording the code exists to
free — use the constant's `head()`, or `contains` for the fragment that really
is the subject of the assertion.

## Before you commit the line, check

1. Level matches the reader's required reaction (table above).
2. No swallowed failure without a trace; no second line for the same fault.
3. Throwable is the trailing argument.
4. Untrusted text went through `LogText`; no secret values.
5. Loop sites suppress or aggregate.
6. The line is findable by `taskId` if it concerns a task.
7. A new WARN/ERROR took the next free `OperatorEvent` code as its message head.
8. A spec pins that code — its level, and its attribution key where the line
   concerns a task or a check — through `LogCaptureSupport`.

## The two gates that ask for you

- **Static** (`LogContractGateSpec`, `:bootstrap`): every WARN/ERROR site
  carries a code, every code belongs to one site, every code is named by some
  test source. In-place escape hatch: `log-contract-exempt: <reason>`.
- **Runtime** (`LogExpectationGate` in `:test-fixtures` +
  `checkLogExpectationGate` in `build-logic`): a global Spock extension watches
  every feature's operator plane. It **reports** — per module, in
  `build/reports/log-expectation-gate/<task>/features-*.txt` — every WARN/ERROR
  no capture was watching, and the build **fails** on a code the whole build's
  `test` run emitted that no capture anywhere in it was watching. The verdict is
  one build-wide task; its report is `build/reports/log-expectation-gate.txt` at
  the root.

  A spec declares "I know about this line" by attaching a capture — either
  `LogCaptureSupport` or an older hand-rolled `ListAppender`; both are read off
  Logback, so no migration is owed. Appearing in the per-feature report is not a
  failure: crossing a line another spec pins is normal, and the pin may live in
  another module. Close a real failure by pinning the line where its emitter
  lives. Where a spec must traverse a path nothing pins,
  `@AllowsUnexpectedLogEvents(reason = "...")` on the feature or the spec; the
  reason is mandatory, the same shape as `real-time-wiring` and
  `log-contract-exempt`.

  Do not invent a `[GFnnn]` literal in a test source: `GF999` is pinned by
  `LogContractGateSpec` as the code no test source names. Take codes from
  `OperatorEvent`.
