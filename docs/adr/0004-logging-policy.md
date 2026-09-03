# ADR 0004: Logging Policy

Status: accepted (2026-08-31, introduced by `harden-logging-observability`)

Implements FR1 of harden-logging-observability.

## Context

The factory runs unattended: a `serve` daemon claims tasks, drives gnomes
through pipeline stages, and escalates only what a human must decide. Its text
log has exactly one job — letting an operator or a post-mortem investigator
trace a degradation path days later — and an August 2026 audit (four code audits, a
canonical best-practices review, and a survey of Kubernetes, Temporal, CI
runners, Airflow and Stripe) found that channel simultaneously **lossy** and
**noisy**: swallowed failures, stack traces amputated by `e.toString()`,
invisible retry storms and judge infrastructure failures on one side; WARN on
recovered flow, unbounded repetition in poll loops and per-object INFO on every
sweep tick on the other.

The audit reduced ~60 findings to four root causes, and all four are the same
kind of gap: **no written policy**. Every new emitter decided its level by
taste, "best effort" was read as "silently give up", nobody owned repeat
suppression, and untrusted text reached log lines unsanitized. Fixing the sites
without writing the policy would let the defect classes regrow with the next
change.

## Decision

### Levels are defined by required reader reaction, not by author sentiment

A level is a statement about **what the reader must do**, which is what makes it
checkable at review and gate time:

| Level | Contract                                                                      | Reaches               |
|-------|-------------------------------------------------------------------------------|-----------------------|
| ERROR | The operator **must act**. Work was lost or a component cannot continue.      | stderr console + file |
| WARN  | The operator **should look**; a *persistent* WARN means act. Degraded result. | stdout console + file |
| INFO  | Lifecycle anchors and state changes — the post-mortem timeline.               | file                  |
| DEBUG | Diagnosis: per-item detail, retries in progress, reconciliation chatter.      | file, when raised     |

Corollaries the audit's findings map onto directly:

- A failure that a bounded retry recovered is **not** a WARN. It is INFO at
  most, and DEBUG per attempt.
- A first-of-two attempts failing is normal flow, not a degradation.
- Anything emitted once per polled item, per tool call, or per swept object is
  DEBUG. If it is worth INFO, it is worth aggregating (see repeat suppression).
- WARN+ is the operator console. A healthy hour of `serve` produces none.
- A death the daemon's **own stop** caused is not an ERROR. Once the shutdown
  phase has begun (`ShutdownPhase`, `:logtext`), a killed gnome subprocess, an
  interrupted daemon worker and a slot abandoned mid-round are each one WARN
  without a stack — the stack would describe the stop, not a defect. ERROR
  stays reserved for the failure nobody asked for, so an ordinary SIGTERM ends
  a healthy run with no line demanding action.

### Best effort must still leave a trace

A path that catches a failure and continues with a degraded result — an empty
default, a skipped cleanup, an unread cursor — **logs it**. "Best effort" names
what the code does about the failure, never whether it tells anyone. The trace
must carry enough context to attribute the degradation: which subject, which
check, which environment key, and — when the degraded value feeds a decision —
what that decision now rests on.

The only permitted silence is a path where the failure is *itself* the normal
outcome being classified (an expected `NotFound` on a probe), and there the
classification is logged at DEBUG.

### One failure, one log — written by the layer that decides

A failure travelling through a retrying layer and a giving-up layer produces
**one** WARN/ERROR line: the one written by the layer that made the final
decision. Lower layers either rethrow or log at DEBUG. The rule kills the
duplicate-per-path lines the audit found (origin reconciliation, remote
delivery, first push, dispose vs verdict) and keeps the console count equal to
the fault count.

### The operator plane is addressed by code, not by prose

Every production WARN and ERROR line begins with a stable catalog code —
`[GF042] failed to append sweep ledger line` — drawn from one enum,
`OperatorEvent` (`:logtext`). One constant per call site; a code is never
reused; the catalog only grows. **The code is the contract and the sentence is
not**: an alert, an operator's grep and a spec's assertion all key on the code,
so wording may be rewritten freely without breaking any of them. That inverts
the previous state, where a WARN line's only identity was its prose, and every
test asserting one froze the wording it happened to have.

One code is one *call site*, not one kind of fault: two emitters of the same
condition take two constants, because what the operator needs from the code is
*where* the factory degraded. INFO and DEBUG carry no codes — the catalog's
scope is the operator plane, and extending it downward would make every
diagnostic line a versioned interface.

Two gates hold the contract up, and they ship together because each covers the
other's blind spot: a **static** source scan fails the build on an uncoded
WARN/ERROR site, a code used by two sites, or a code named by no test source
(the in-place `log-contract-exempt: <reason>` idiom is its escape hatch); a
**runtime** Spock extension fails any feature during which a production logger
emits a WARN/ERROR that no attached capture observed and no declared allowance
covers. The static half alone would accept a code that appears only in a
comment; the runtime half is what makes it behavioral. A runtime assertion gate
of this shape was passed over as too disruptive while hundreds of specs
legitimately traversed WARN paths unasserted — it is adopted here because the
pin sweep (FR15) closes those paths first, and the flip to enforcing is the
last step rather than the first.

Migration note for anyone with a saved grep: the head moved the prose off
column 0. A pattern anchored at the start of the message (`^failed to append`,
or a `startsWith` in a spec) no longer matches; an unanchored one still does.
Prefer the code.

The four `:domain` emitters of accepted deviation 1 cannot reach `:logtext`
without giving the domain a module edge it exists to refuse, so they carry the
literal `[GFnnn]` head. A round-trip spec pins each literal to its catalog
constant, the same shape the project's wire-vocabulary rule prescribes: delete
either side and it goes red.

### The exception is the trailing argument, always

`log.warn("could not read {}", path, e)` — never `e.toString()`, never
`e.getMessage()` (which prints `null` for exceptions carrying only a cause)
interpolated into the message. The stack and the cause chain are the diagnosis;
a message string is a label for it. Enforced by a build gate, with an
inline-comment exemption idiom for the rare site where the throwable genuinely
is not the subject.

### Untrusted text enters a log line only through the sanitizer

Agent/LLM output, subprocess stderr, tracker-sourced strings and in-container
command output are attacker-influenced. They reach a log line only through
`LogText` (module `:logtext`): control/ANSI stripping, newline flattening — so
one event is one line and no embedded `\n` can forge a record — and a length
cap. No secret value, token, or credential material appears in any log line;
warnings about secrets name the *variable*, never the value.

`FindingsSanitizer` (`gnomish-plugin-api`) guards a different trust boundary
with a different contract — plugin findings, where line structure is preserved
deliberately — and stays self-contained. The two share only their character
vocabulary (the ANSI/control table and the tail cap) and are kept in step as a
declared pair under `.claude/rules/manual-sync-pairs.md`, verified by an
executable equivalence spec rather than by a production dependency.

### Repeat suppression has one owner; edges are the signal

Poll and retry loops log **state edges**, not states: the first occurrence (or
a changed reason) at the site's level, repeats at DEBUG, a periodic roll-up
naming the count, and one recovery line with the outage duration.
`RepeatSuppressor` (`:logtext`) owns that decision; the call site owns the
levels. Suppression state is in-memory per process — a restart may repeat the
first-occurrence line, which is correct: a new process has told no one yet.

Sites that flood *within a single operation* rather than across calls (a parse
loop over one file, a bulk deletion) use a local aggregate counter emitting one
summary line per operation. That is a different invariant — aggregate-per-call,
not edge-across-calls — and deliberately not a second suppressor.

### MDC carries the correlation, and it is cleared at thread boundaries

Every line emitted while working a task carries that task's `taskId`, and
`stage`/`attempt` where they apply. Virtual-thread hops copy the context map in
and clear it on exit (`MdcAwareThread`); `stage`/`attempt` are cleared at the
same boundaries that clear `taskId`, so a leak cannot survive a run that ended
without its bookend event. Daemon workers set a `component` key naming
themselves (janitor, reaper, snapshot, sweep, heartbeat), and per-task decisions
made by daemons run under that task's `taskId` — so `grep taskId=<id>` returns
the task's whole story, reaping decisions included.

### The log is expendable; the ledgers are durable

Retention rationale, and the reason none of the above is a durability
mechanism: the rolling file is a **diagnostic convenience** with a bounded
lifetime. The durable record is the task branch, the tracker, and the JSON
ledgers/snapshots — which are also the machine-readable plane. Consequences we
accept on purpose:

- The FILE appender is asynchronous, so a `kill -9` can lose the last instants.
  SIGTERM/Ctrl+C are protected by the owned shutdown sequence's flush; `kill -9`
  is not, and nothing that matters lives only in the log.
- Nothing reads the log programmatically, and no *sentence* is a compatibility
  surface: readers grep by MDC key or by operator-event code, never by column
  position. The codes themselves are the one exception — they are stable on
  purpose, which is what lets the prose stay free.

### Accepted deviations

Recorded so they are decisions rather than drift:

1. **Domain classes log.** Four `:domain` classes (`RoundExecution`,
   `VerifyOrchestrator`, `Events`, `AttemptJournal`) hold an SLF4J logger for
   port-failure paths. The framework-free alternative — an
   `EngineEvent.PortFailed` variant carrying them out to a listener — has no
   consumer today and was deferred as scope creep. An ArchUnit rule pins the
   list at exactly these four, so a fifth is a deliberate decision, not an
   accident. Revisit if `:domain` must become logging-framework-free.
2. **The log stays unstructured text.** No JSON log output. The structured
   plane already exists (ledgers, snapshots, `state.json`), and the log's reader
   is a human or an AI with `grep`. Revisit only if log shipping arrives.

## Alternatives Considered

- **Logback's `DuplicateMessageFilter` for repeat suppression** — rejected: it
  keys on the raw rendered message, has no expiry, emits no roll-up and no
  recovery line, and its blast radius is the whole configuration.
- **A custom Error Prone `BugChecker` for the throwable convention** — precise,
  but it needs a build-logic subproject of its own; a source-scanning Spock spec
  is two orders cheaper and its false positives are suppressible by the
  inline-comment idiom the codebase already uses. Revisit if exemptions
  accumulate.
- **JSON logs plus a structured-logging framework** — rejected for a
  single-process CLI factory whose machine plane is already the ledgers (NG2 of
  `harden-logging-observability`).
- **Per-task log files / remote log shipping / numeric verbosity levels** —
  surveyed and rejected: the task branch already is the per-task artifact store,
  and there is no fleet to ship to.

## Consequences

- Every emitter has a citable rule, and four of the rules are mechanical gates
  (throwable position, untrusted-text routing, code coverage of the operator
  plane, and no unasserted WARN in a spec run) rather than review vigilance.
- Console volume becomes a health signal: a healthy `serve` hour is silent, so
  any WARN is worth reading.
- New mechanisms carry ownership: `AnchorLog` for lifecycle anchors and the
  canonical task summary, `RepeatSuppressor` for edges, `LogText` for untrusted
  text — a second implementation of them is a review finding.
- The cost is indirection: a site that wants to log agent output must reach for
  `LogText`, and a poll loop must thread a suppressor key. Both are one line.

## See also

- `.claude/rules/logging.md` — the emitter's one-page checklist.
- `docs/glossary.md` — *anchor line*, *canonical task summary*, *operator
  event*, *log contract*, *repeat suppression*, *log text sanitization*.
- `.claude/rules/manual-sync-pairs.md` — the `LogText` ↔ `FindingsSanitizer`
  row.
- `docs/adr/0003-crash-consistency.md` — why the durable record is the media,
  not the log.
