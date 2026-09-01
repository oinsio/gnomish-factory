---
paths:
  - "**/*.java"
---

# Rule: logging

The one-page checklist for anyone adding or touching a log call. The reasoning,
the rejected alternatives and the accepted deviations live in
`docs/adr/0004-logging-policy.md`; this file is what you check the line against.

## Pick the level by the reader's required reaction

| Level | The reader …                                                            | Use for                                                              |
|-------|-------------------------------------------------------------------------|----------------------------------------------------------------------|
| ERROR | **must act** — work was lost, or a component cannot continue            | unrecoverable failures, lost work, a daemon dying                    |
| WARN  | **should look**; a persistent WARN means act                            | degraded results, fallbacks, destructive cross-instance actions      |
| INFO  | reads it in a post-mortem timeline                                       | lifecycle anchors, state changes, the per-task summary               |
| DEBUG | reads it only while diagnosing                                          | per-item detail, retries in progress, reconciliation chatter         |

Not a WARN: a failure a bounded retry recovered (INFO at most, DEBUG per
attempt); a first-of-two attempts failing; anything emitted once per polled
item, per tool call, or per swept object (DEBUG — aggregate it if it matters).
A healthy hour of `serve` produces **zero** console output; WARN+ is the
console.

Not an ERROR: a death the daemon's own stop caused. Once
`ShutdownPhase.inProgress()` (module `:logtext`) is true, a killed subprocess,
an interrupted worker or an abandoned slot is one WARN without a stack — mark
the exemption with the `throwable-not-subject` comment below.

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
wrapper. The list is what the gate can see, not the whole rule: a change that
introduces a new untrusted accessor adds it there in the same change.

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

## Before you commit the line, check

1. Level matches the reader's required reaction (table above).
2. No swallowed failure without a trace; no second line for the same fault.
3. Throwable is the trailing argument.
4. Untrusted text went through `LogText`; no secret values.
5. Loop sites suppress or aggregate.
6. The line is findable by `taskId` if it concerns a task.
