# Design: harden-untrusted-text-sinks

## Context

See proposal.md — Why. The facts that shape the approach:

- `LogText` (`:logtext`, slf4j-api only) owns the choke-point vocabulary:
  `strip`, `capTail`, `flatten`, composed by `forLog`. `:logtext` may not
  depend on a logging backend, so a Logback converter cannot live there.
- `bootstrap/src/main/resources/logback-spring.xml` renders three text-log
  appenders from one `GNOMISH_LOG_PATTERN` (`%msg%n` plain, MDC via `%X{…}`);
  the file appender is async with `discardingThreshold=0`. `logback-test.xml`
  mirrors it for specs. `add-subprocess-access-log`, sequenced before this
  change, adds a fourth appender to the same bootstrap config: a dedicated
  `gnomish.access` logger with its own JSONL encoder and its own redactor,
  additivity off. This change's scope is the three text-log appenders (FR1
  scoped accordingly); the access-log appender is a separate machine-readable
  sink at a separate trust boundary and is explicitly out of scope — see D1's
  exemption.
- The console has a port, `ConsoleIO` (`app/port/console`), with one
  implementation, `SystemConsoleIO`, and a `DialogConsole` wrapper; 6 sites
  use it. 24 production sites in `:application` and 1 in `:bootstrap` write
  to `System.out`/`System.err` directly (list under D5). Four of those print
  JSON.
- `LogContractGateSpec` keys on `[GFnnn]` message heads; `LogExpectationGate`
  reads events off Logback in every spec. Neither reads the rendered bytes.
- The three-change sequence is fixed (memory: `untrusted-text-design`):
  this change → `split-logtext-leaves` → `type-untrusted-text`. This change
  must not pre-empt the leaf split (no new modules, no move of `LogText`).

## Goals / Non-Goals

**Goals:**
- The rendered record is safe regardless of the call site (proposal G1, G2).
- Zero visible change for correct call sites (G3) — idempotence is a design
  constraint, not a nice-to-have.
- One owner per sink: one converter family for Logback, one console owner.

**Non-Goals:**
- Replacing the accessor-name gate; typing the carriers; touching the 33
  exception-constructor sites — `type-untrusted-text`.
- Any module edge change — `split-logtext-leaves`.

## Decisions

**D1 — Sink converters live in `:bootstrap`, primitives in `:logtext`.**
Three Logback `ClassicConverter` subclasses in `:bootstrap`
(`bootstrap/…/logging/`): `SafeMessageConverter` (`%safeMsg`),
`SafeThrowableConverter` (`%safeEx`), `SafeMdcConverter` (`%safeX{key}`),
registered by `<conversionRule>` in both Logback files. Each calls `:logtext`
primitives (`LogText.strip`, `LogText.flatten`, a new `LogText.capRecord`)
and holds no vocabulary of its own. *Rationale:* a converter needs
`logback-classic` at compile time; `:logtext`'s contract (module-layering
spec: "no external dependency beyond slf4j-api") forbids it, and the
composition root is where the backend is chosen (ADR 0004). Placing the
vocabulary in `:logtext` keeps one character table (the pair with
`FindingsSanitizer` stays a *declared* pair — no shared abstraction is
extracted by this change — even though both ends' table content changes;
see Sync surfaces, below). **Exemption:** the `gnomish.access` JSONL
appender `add-subprocess-access-log` attaches to the same bootstrap Logback
config is not a consumer of these converters — it carries its own redactor
at its own trust boundary (that change's D5/D11) and its JSON structure must
not be flattened or capped by the text-log vocabulary. FR1 and the
single-owner table below name this exemption explicitly so a future reader
does not read "every appender" as including it. *Alternative rejected:*
Logback `%replace(%msg){regex}` in the pattern — a second, hand-written
regex vocabulary in XML beside `LogText`'s, with no cap primitive and no
idempotence guarantee; exactly the drift the sync-pair rule exists to stop.
*Alternative rejected:* a `LayoutWrappingEncoder` subclass post-processing
the whole line — it would also neutralize the timestamp/level/logger fields
(harmless) but cannot tell the message from the throwable, so it could not
apply D3's continuation marking to the throwable only.

**D2 — Idempotence by construction: the visible newline marker is not
re-escaped.** `LogText.flatten` renders `\n` as the two characters `\` `n`.
The sink applies `strip` then `flatten` then `capRecord`; `strip` and
`flatten` are already idempotent over their own output (a stripped string has
nothing left to strip; a flattened string has no line separator left to
flatten, and the literal backslash-n it contains is plain text to `flatten`).
`capRecord` caps at 16 KB (proposal Q1) — above any single `forLog` cap
(2 000) times the arguments a line carries, so a choke-point-prepared
message never reaches it. FR2's byte-identity is asserted, not assumed: a
spec feeds `forLog(corpus)` through the converter and compares. *Alternative
rejected:* a marker character telling the sink "already sanitized" — a
second contract every call site could forge or forget.

**D3 — Throwable rendering: strip per line, mark continuation lines,
keep trace indentation.** `SafeThrowableConverter` delegates to Logback's
`ThrowableProxyConverter` for the text, then rewrites it line by line:
`strip` each line; drop `\r`; a line that matches a trace-line shape
(`^\tat `, `^Caused by: `, `^Suppressed: `, `^\t\.\.\. \d+ more$`,
`^Wrapped by: `) is written as-is; every other line — which can only be the
first line of a `Class: message` rendering or a message-embedded newline —
is written as `\t| ` + line, except the very first line of the rendering,
which stays at column 0 as today. CERT IDS03-J's compliant solution 2
(indent continuation lines so a forged line never starts at column 0) is
the precedent. *Rationale:* flattening the whole trace into one line (FR1's
treatment) would destroy the diagnosis ADR 0004 exists to keep; marking
keeps the trace readable while making forgery visibly impossible.
*Alternative rejected:* neutralize only `getMessage()` of each throwable in
the chain — `ThrowableProxy` also renders suppressed exceptions' messages
and the cause chain; post-processing the rendered text covers every message
the framework prints, present and future.

**D4 — MDC values through `%safeX{key}`; `stage` stays as the manifest
names it.** The converter neutralizes and single-lines each value. The
source (`MdcEventListener`, `MdcAwareThread`) is unchanged: the MDC is a
*carrier*, and neutralizing at the put would make `grep taskId=<id>` fail to
match the raw id a spec or an operator holds. *Alternative rejected:*
sanitize in `MdcAwareThread.taskScope` — one of three put sites, and the
value's identity (the grep key) must stay raw in the map.

**D5 — The console owner is `ConsoleIO`, grown by one method; direct writes
are rerouted.** `ConsoleIO` gains `printMachine(String)` (verbatim); the
existing `print(String)` becomes the human path and `SystemConsoleIO.print`
applies the new `LogText.forConsole` (caret/`\uXXXX` visible notation,
newlines and length preserved). The 24 direct sites, classified (line
numbers current as of 2026-09-14; re-grep
`System\.\(out\|err\)\.print` before starting task 3.3, since numbers drift):

| Site | Path |
|---|---|
| `StatusCommand:85,112,120` | machine when `json`, human otherwise (split per branch) |
| `StatusCommand:102,121`, `UsageCommand:59,67`, `BoardCommand:77`, `RunExceptionReporting:46,49,56,62`, `RunnerOutcomeLoop:183,184`, `ServeCommand:213,239`, `ContainerGitModeRunner:79,80`, `GitModeRunner:186,187`, `GitResumeContinuation:152`, `ContainerResumeOutcomes:151`, `bootstrap/ManualRunDrive:26` | human |
| `UsageCommand:65` | machine |

Commands that today have no `ConsoleIO` in hand (`StatusCommand`,
`UsageCommand`, `BoardCommand`, `ServeCommand`, `RunExceptionReporting`)
receive it by constructor from the composition root, the same way
`bootstrap/…/ManualRunConfiguration:177-178` already wires `SystemConsoleIO`.
*Rationale:*
the port exists, has one production implementation and a scripted test
double; adding a second "operator output" abstraction would be the twin
implementation `manual-sync-pairs.md` forbids. *Alternative rejected:*
`System.setOut` with a neutralizing stream at startup — invisible to the
JSON path (would corrupt `--json`), invisible to specs, and a process-global
side effect in a JVM that also hosts specs.

**D6 — Visible notation on the console, stripping in the log: two exits,
one table.** `LogText.forConsole` renders ESC as `^[`, C0 as `^X`, DEL as
`^?`, C1/bidi/format as `\uXXXX`, `\r` as `\r`; it does not flatten `\n`
and does not cap. `forLog` is unchanged (NG6). Both read one character-class
table in `LogText` (extended in this change with C1 8-bit controls, the
zero-width set U+200B–U+200F/U+2060–U+2064/U+FEFF, and tag characters
U+E0000–U+E007F; OSC/DCS/APC/PM/SOS strings consumed to ST or BEL). The
table extension is the one behavior change to `forLog` this change makes;
`SanitizerPairEquivalenceSpec` and `FindingsSanitizer` are updated together
(sync surfaces, below). *Rationale:* git (`sideband.allowControlCharacters`),
kubectl (`EscapeTerminal`) and `less` all make controls visible on a
terminal — the operator must see the attempt; the log plane keeps ADR
0004's one-event-one-line contract. *Alternative rejected:* visible
notation in the log too — changes every existing log-text expectation for
a plane whose reader is grep, not eyes; deferred to `type-untrusted-text`
where the exits are re-cut anyway.

**D7 — `AbortHandler` moves the rendered trace to the throwable slot
(proposal Q2 resolved: move).** `AbortHandler:103` logs `cause` — a
`StackTraces.render` string — as a message argument; under D1 it would
flatten into one 16 KB line. The site passes the original throwable where
one exists and the rendered text only where the abort came from a
`TaskOutcome.Aborted` with no throwable in hand; in that second case the
text is logged as a message and flattened, which is the correct contract
for a message. *Alternative rejected:* exempting `AbortHandler` from the
sink — there is no exemption mechanism, by design.

**D8 — `TakeOutcomeMapper:95` renders through `EscalationResumeDialog`'s
renderer.** The `"Escalated: " + escalated.report()` text is replaced by the
existing `renderEscalation` output. No new render path. *Alternative
rejected:* an Error Prone check banning record `toString()` in string
concatenation — worth having, but it is a gate on a class of code, and the
gate rewrite belongs to `type-untrusted-text`.

**D9 — The end-to-end spec drives the real configuration.** A spec in
`:bootstrap` loads `logback-test.xml` (which carries the same conversion
rules and pattern as production), attaches a capturing appender *after* the
encoder (an `OutputStreamAppender` over a byte buffer, not `ListAppender`,
which sees events before rendering), and asserts on bytes over the corpus
for the three text-log paths (message, exception, MDC) on each of the three
`GNOMISH_LOG_PATTERN` appenders. It also asserts the `gnomish.access`
appender is untouched by this change's converters (D1's exemption) — a
regression guard, not a fourth path under the neutralizer. The same corpus
drives `SystemConsoleIO` over a captured `PrintStream`. *Rationale:*
`testing.md`, "Invariant specs across a flow": component specs of the
converters prove each link; only bytes off the real encoder prove the links
are joined.

## Sync surfaces

**Sync surfaces: one declared pair touched, no new implementation.** D6
extends the character-class table in `LogText`; `FindingsSanitizer`
(`gnomish-plugin-api`) is the other end of the declared pair
(`manual-sync-pairs.md`, "Declared pairs with no shared classpath"). Both
ends change in this change, and `SanitizerPairEquivalenceSpec`'s corpus
gains the new classes so the pair stays verified. The pair itself is
dissolved by `split-logtext-leaves`, not here. No second implementation of
any rule is added: the three converters and `forConsole` consume the one
table.

## Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `SafeMessageConverter` / `SafeThrowableConverter` / `SafeMdcConverter` (`:bootstrap`) | the rendered record bytes | the three text-log appenders in `logback-spring.xml` and `logback-test.xml` using `GNOMISH_LOG_PATTERN` (file, stdout, stderr). Exempt: the `gnomish.access` JSONL appender `add-subprocess-access-log` owns — its own encoder and redactor, a distinct trust boundary; converting it would corrupt its machine-readable structure | the plain `%msg`, `%ex` (implicit) and `%X{…}` conversions in `GNOMISH_LOG_PATTERN` — deleted | `LogbackConfigSpec` asserts both files declare the three conversion rules on `GNOMISH_LOG_PATTERN` and that the pattern uses only the safe forms; the D9 byte-level spec asserts the effect on the three text-log appenders and asserts the access-log appender is untouched |
| `ConsoleIO` (`SystemConsoleIO`) | terminal bytes, split by `print` (human) / `printMachine` (verbatim) | the 24 sites in D5's table and the 6 existing `console.print` sites | every `System.out.print*` / `System.err.print*` in production code outside `SystemConsoleIO` — deleted | `ConsoleOwnerGateSpec` (`:bootstrap`, source scan over production sources, allowlist = `SystemConsoleIO`), same shape as `UntrustedLogTextGateSpec` |
| `LogText` (`:logtext`) | the character-class table | `forLog`, `forConsole`, the three converters, `FindingsSanitizer` (via the declared pair) | none — the table already had one owner; the change adds consumers | `SanitizerPairEquivalenceSpec`; the converters hold no character literal (asserted by a spec that scans the converter sources for escape-character literals — the `\u001B`/`\u009B` code points and `\p{Cc}`-class patterns — and finds none) |

Identity claims: FR2 says choke-point output "renders byte-identically" —
paired with the D9 spec's idempotence feature over `forLog(corpus)`.

## Risks / Trade-offs

- [Per-record cap truncates a legitimately long INFO line, e.g. the canonical
  task summary with many models] → 16 KB is two orders above the longest
  summary observed; the marker names the drop; the cap is a named constant
  with a derivation comment.
- [The async appender's queue holds unrendered events; neutralization runs on
  the appender thread] → cost is one pass per record; NFR-R2's budget
  (`queueSize`, `maxFlushTime`) is unchanged and no new benchmark is added —
  the existing serve-tick specs (`:application`) exercise the file appender
  under load and stay green, which is NFR-R2's implementing evidence per
  `traceability.md`; task 5.3's module-gate run is where that is confirmed.
- [`%safeEx` diverges from Logback's default rendering in a future Logback
  release (new trace-line shapes)] → the shape list is data in one converter;
  an unrecognized shape degrades to a marked continuation line, never to a
  column-0 line — fail-safe in the right direction.
- [Rerouting 24 print sites touches many command classes at once] →
  mechanical; each site is one of two calls; the gate names any survivor.
- [A spec that captured `System.out` directly (e.g. through
  `System.setOut`) now sees the console owner's output] → those specs assert
  on human text with no control characters, so the notation change is
  invisible; the D5 split keeps JSON byte-identical.
- [Visible notation on the console could be mistaken by a reader for the
  agent's literal text] → it is git's and kubectl's convention; the ADR
  amendment documents it once.

## Migration Plan

1. `:logtext`: table extension + `capRecord` + `forConsole` (pair spec
   updated in the same commit).
2. `:bootstrap`: converters, both Logback files, `LogbackConfigSpec`, D9
   spec. Everything above is additive and shippable alone.
3. `:application`: `ConsoleIO.printMachine`, `SystemConsoleIO`, the 24
   reroutes, `ConsoleOwnerGateSpec`, D7, D8.
4. ADR 0004 amendment, `logging.md`, glossary.
No rollback concern: every step is a superset of the previous rendering for
correct call sites (FR2, M3).

## Open Questions

None that change the specs or tasks. Q1 and Q2 of the proposal are resolved
above (D2: 16 KB; D7: move the trace).
