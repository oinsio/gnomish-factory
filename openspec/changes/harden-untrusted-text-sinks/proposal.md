# Proposal: harden-untrusted-text-sinks

## Why

The factory's rule for untrusted text — agent output, subprocess stderr, tracker
strings, `task.json` values, the target repository's `.gnomish/` — is "route it
through `LogText` before it reaches a log line", enforced by a source gate that
recognizes untrusted *accessor names* at log call sites. The 2026-09-11 review of
`add-base-ref-resolution` (report 4) found the structural hole in that model: the
text is concatenated into a factory-authored string *first* — an operator report,
an exception message, a `TakeResult` field, a record's `toString()`, an MDC value
— and the assembled string is logged later, where no accessor is left for the
gate to see. A whole-tree audit counted 33 exception-constructor sites folding raw
`stderr()`/`output()`, a `toString()` dump of every `EscalationReport` field
(`TakeOutcomeMapper:95`), manifest stage names written into the MDC
(`MdcEventListener:75,80`), and ~25 direct `System.out/err` writes with no owner
at all. Every standard that names this problem — CWE-117, OWASP ASVS V16.4.1,
CERT IDS03-J, the Apache Logging security policy — places the *mandatory* control
in the logging component itself; design D9 of `harden-logging-observability`
cited that same "sanitize at the writing layer" precedent, yet the Logback
configuration renders a plain `%msg`. The factory's sink trusts its callers, and
its callers have three documented ways to be wrong.

This change adds the layer that does not depend on caller discipline: the log
encoder and the operator console neutralize whatever reaches them. It is the first
of three sequenced changes (`split-logtext-leaves`, `type-untrusted-text` follow);
it closes the *symptom* for every known laundering path at once, so the later
type-level work can proceed without an open terminal-injection window.

## What Changes

- **MODIFIED** `factory-logging`: the requirement "Untrusted text enters logs
  only sanitized" gains a second, sink-side layer — every Logback appender's
  encoder neutralizes the rendered message, the rendered throwable, and every MDC
  value regardless of what the call site did; the exception-rendering requirement
  states how a multi-line exception message is kept from forging a record
  (continuation lines are marked, never at column 0); the MDC requirement states
  that MDC values are neutralized at the sink.
- **ADDED** `operator-console`: one owner for text written to the operator's
  terminal outside the logger — human-readable output is neutralized with
  *visible* control-character notation, machine-readable output (`--json`) is
  written verbatim, and no production class writes to `System.out`/`System.err`
  directly.
- **MODIFIED** (application code, no spec-level behavior change): the
  escalation text `TakeOutcomeMapper` builds from a record's `toString()` is
  replaced by the existing renderer; the audit's list of direct console writes is
  routed through the console owner.
- **MODIFIED** `docs/adr/0004-logging-policy.md`: the "Known limit: untrusted
  text in exception messages" section is superseded by a three-layer statement
  (capture → per-consumer exit → sink backstop), with this change owning the
  third layer and naming the two that follow.

## Capabilities

### New Capabilities
- `operator-console`: the neutralizing owner of all non-logger terminal output,
  and the build gate that keeps `System.out`/`System.err` out of production code.

### Modified Capabilities
- `factory-logging`: "Untrusted text enters logs only sanitized" — adds the
  sink-side layer and the end-to-end invariant spec; "Exceptions keep their
  stack traces" — adds continuation-line marking of multi-line throwable
  messages; "Complete and leak-free MDC context" — adds sink-side
  neutralization of MDC values.

## Goals

- G1: No byte sequence from an untrusted source can forge a log record, drive
  the operator's terminal, or exceed a bounded size in the log file or on the
  console, *whatever the emitting call site did* — the property holds end to end
  on the real appenders, not per component.
- G2: Every laundering path the audit named (exception messages, record
  `toString()`, MDC values, assembled reports, direct console writes) is covered
  by one of the two sinks without editing the 33+ emitting sites.
- G3: The sink layer is invisible to correct callers: text already prepared by
  `LogText.forLog` renders identically before and after this change (no double
  escaping of the visible `\n` marker, no second cap).

## Non-Goals

- NG1: Typed carriers for untrusted text, the accessor-name gate's replacement,
  and the per-field sanitizing of report builders and exception constructors —
  `type-untrusted-text`.
- NG2: Moving `LogText`'s primitives or `OperatorEvent` out of `:logtext` —
  `split-logtext-leaves`.
- NG3: Tracker-comment hygiene (`TrackerFence` ownership of every park report,
  `@`-mention neutralization) — the tracker is not a sink this change owns.
- NG4: Structured (JSON) log output — ADR 0004 accepted deviation 2 stands.
- NG5: Secret masking beyond what `CredentialScrub` and the access-log redactor
  already do; a general secret masker is its own change.
- NG6: Changing what `LogText.forLog` produces for the log plane (it strips and
  flattens; the console plane's *visible* notation is new and separate).

## Users & Scenarios

- U1: An operator tails `~/.gnomish/logs/gnomish.log` after a failed take. A
  hostile branch name that git echoed into `GitTaskRepositoryException` reached
  `AbortHandler`'s ERROR line as a rendered stack trace containing `ESC[2J`
  and a forged `2026-… ERROR` line. They see one record whose message shows the
  escape as inert visible text and the forged line as a marked continuation line —
  nothing cleared their screen, nothing looks like a second event.
- U2: An operator runs `gnomish board`. An issue titled with an OSC 52 clipboard
  sequence renders on their terminal as `^[]52;…` in the title column instead of
  writing to their clipboard.
- U3: An operator runs `gnomish status --json <id>` and pipes it to `jq`. The
  JSON arrives byte-for-byte as the mapper produced it; the console owner did
  not touch it.
- U4: A developer adds a new `log.warn` whose argument is a `String` assembled
  three methods up from agent output and forgets `LogText`. The accessor gate
  cannot see it; the file still shows one inert line, and the end-to-end
  invariant spec is what proves that.

## Requirements

### Functional

- FR1: Every Logback appender the factory configures (rolling file, stdout
  WARN+, stderr ERROR) SHALL render its message through a sink-side neutralizer
  that applies `LogText`'s vocabulary to the *formatted* message: control and
  ANSI/C1/bidi sequences stripped or made inert, line breaks inside the message
  rendered as the visible marker `LogText.flatten` produces, and the message
  bounded by a per-record cap with a visible truncation marker.
- FR2: The sink-side neutralizer SHALL be idempotent over `LogText.forLog`
  output: a message already prepared by the choke point renders byte-identically
  with and without the sink layer (no double escaping of the visible `\n`
  marker, no second cap below the first).
- FR3: The rendered throwable (`%ex` / the trailing-argument stack trace) SHALL
  be neutralized line by line: control/ANSI/C1/bidi sequences made inert in
  every line, `\r` removed, and every line that is not a recognized trace line
  (`\tat …`, `Caused by:`, `Suppressed:`, `… N more`) SHALL be rendered as a
  marked continuation line so a newline embedded in an exception message can
  never place text at column 0 of the log.
- FR4: Every MDC value the encoder renders (`taskId`, `stage`, `attempt`,
  `component`, and any key added later) SHALL pass the same neutralization as
  the message, and SHALL be single-line.
- FR5: One owner SHALL write all non-logger text to the operator's terminal.
  Its human-readable output path applies *visible* neutralization — ESC as
  `^[`, other C0 as `^X`, DEL as `^?`, C1 and bidi/format characters as
  `\uXXXX`, `\r` as `\r` — preserving line structure and length (operator
  reports are long by design). Its machine-readable path (`--json` renderers,
  JSON mappers) writes verbatim.
- FR6: No production class outside the console owner SHALL write to
  `System.out` or `System.err`; a build gate fails on a new site. The 25
  existing sites (24 in `:application`, 1 in `:bootstrap`, listed in
  design.md) are routed through the owner in this change, each classified as
  human or machine output.
- FR7: `TakeOutcomeMapper` SHALL build the `AwaitingHuman` report text from the
  escalation renderer, never from `EscalationReport`'s generated `toString()`.
- FR8: `docs/adr/0004-logging-policy.md` SHALL state the three-layer model
  (capture → per-consumer exit → sink backstop), record that the sink backstop
  is a defense-in-depth layer and not a license to skip the choke point, and
  replace the "Known limit: untrusted text in exception messages" section;
  `.claude/rules/logging.md` SHALL point to it.

### Non-Functional Reliability

- NFR-R1: The sink neutralizer SHALL never throw: a malformed surrogate pair,
  an `OutOfMemoryError`-shaped input, or a pattern-engine failure degrades to
  a bounded, control-free placeholder record naming the failure, never to a
  lost record or a dead appender.
- NFR-R2: The neutralizer's cost on a record that contains nothing to
  neutralize SHALL be one pass over the message; the async file appender's
  queue budget (`queueSize`, `maxFlushTime`) is unchanged.

### Non-Functional Observability

- NFR-O1: Neutralization at the sink SHALL leave evidence: a record whose
  message or throwable contained control/ANSI sequences renders them as
  visible escapes rather than silently dropping them, so a post-mortem can see
  that a hostile source tried.
- NFR-O2: The log-contract gates (`LogContractGateSpec`, `LogExpectationGate`)
  SHALL keep passing unchanged: the sink layer changes rendering, not the
  `[GFnnn]` message heads the gates key on.

### Non-Functional Security

- NFR-S1: The end-to-end invariant SHALL be asserted on the real medium: an
  adversarial corpus (ESC/CSI/OSC/DCS sequences with and without terminators,
  8-bit C1, bidi overrides, U+2028/2029, zero-width and tag characters, CRLF
  forged-record prefixes, a 1 MB payload) driven through the real Logback
  configuration into a captured appender, asserting on the produced bytes: no
  ESC, no C1, no bare line break inside a record, no bidi override, and length
  within the cap — for the message path, the exception path (`throw` … `catch`
  … `log.error(msg, e)`), and the MDC path.
- NFR-S2: The same corpus SHALL be driven through the console owner's human
  path with the same assertions, plus the assertion that the machine path
  passes the corpus through byte-identically.

## Operator Experience Criteria

- UX1: A log line from a correct call site looks exactly as it does today; the
  operator notices the sink layer only on a hostile input, where the escape is
  visible instead of executed.
- UX2: A rendered stack trace remains readable: trace lines keep their
  indentation; only a message-embedded newline gains a `⏎` -style continuation
  marker at the line start.
- UX3: `--json` output is untouched — scripts that parse it see no change.

## Success Metrics

- M1: The end-to-end invariant spec (NFR-S1, NFR-S2) is green over the whole
  corpus on all three appenders and both console paths.
- M2: `grep -rn "System\.\(out\|err\)\.print" --include=*.java` over
  production sources returns exactly the console owner's own lines.
- M3: Every existing spec in `:application`, `:adapters:*`, `:sandbox:docker`,
  `:bootstrap` passes unchanged in substance — the sink layer altered no
  expectation a correct call site had (G3).
- M4: PIT stays at 100% for every touched module; `:bootstrap:check` green.

## Open Questions

- Q1: Per-record cap at the sink — proposed 16 KB after neutralization, above
  `LogText.DEFAULT_CAP_CHARS` × the largest number of untrusted arguments one
  line carries today, below the async queue's comfort. Confirm or adjust.
- Q2: Whether `AbortHandler`'s ERROR (which passes a rendered stack trace as a
  message argument, not as the throwable) should move the trace to the
  throwable slot in this change (then FR3 governs it and it stays multi-line)
  or accept flattening under FR1. Proposed: move it — it is one site and the
  trace is genuinely the diagnosis.

## Impact

- `:bootstrap`: `logback-spring.xml` pattern gains sink converters;
  `logback-test.xml` likewise so specs see production rendering; new
  architecture spec for FR6; end-to-end invariant spec (NFR-S1).
- `:logtext`: the sink converter classes (Logback `ClassicConverter`
  subclasses) and the console notation primitive. This adds a *test-scope*
  Logback dependency only if the converters are placed in `:logtext`; design.md
  decides placement — a converter needs `logback-classic` at compile time,
  which `:logtext`'s contract forbids, so the converters live in `:bootstrap`
  beside the configuration and call `:logtext` primitives.
- `:application`: `ConsoleIO`/`SystemConsoleIO` become the console owner (a
  second method for machine output); 24 direct print sites rerouted;
  `TakeOutcomeMapper:95`.
- `docs/adr/0004-logging-policy.md`, `.claude/rules/logging.md`,
  `docs/glossary.md` (the *operator console* term; *log text sanitization*
  gains the sink layer sentence).
- Sequencing: after `add-base-ref-resolution` (its `module-layering` delta is
  the base the later `split-logtext-leaves` layers on; this change touches no
  module edges). Before `split-logtext-leaves` and `type-untrusted-text`.
