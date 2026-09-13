# Tasks: harden-untrusted-text-sinks

Sequenced after `add-base-ref-resolution` (no module edges change here) and
before `split-logtext-leaves` / `type-untrusted-text`. Groups 1–2 are additive
and shippable alone (design — Migration Plan); group 3 depends on 1; group 5
is the end-to-end proof and closes the change.

## 1. Character table and the two new exits in `:logtext`

- [ ] 1.1 TDD (red first): extend `SanitizerPairEquivalenceSpec`'s adversarial corpus
      with 8-bit C1 controls (U+0080–U+009F, including U+009B CSI and U+009D OSC), the
      zero-width set (U+200B–U+200F, U+2060–U+2064, U+FEFF), tag characters
      (U+E0000–U+E007F), and OSC/DCS/APC/PM/SOS strings with and without an ST/BEL
      terminator (design D6). Verify: red against both `LogText.strip` and
      `FindingsSanitizer.strip`.
- [ ] 1.2 Extend the one character-class table in `LogText` and mirror it in
      `FindingsSanitizer` (declared pair, both ends in one commit; `Kept in sync with`
      markers unchanged). Traceability: `Implements FR1, NFR-S1 of
      harden-untrusted-text-sinks`. Verify: 1.1 green; existing `LogTextSpec` /
      `FindingsSanitizerSpec` green.
- [ ] 1.3 TDD (red first): `LogTextConsoleSpec` pinning `LogText.forConsole` (design D6,
      FR5): ESC → `^[`, other C0 → `^X`, DEL → `^?`, C1/bidi/format → `\uXXXX`, `\r` →
      literal `\r`; `\n` preserved; no cap; plain text byte-identical. Verify: red.
- [ ] 1.4 Implement `LogText.forConsole`. Verify: 1.3 green.
- [ ] 1.5 TDD (red first): `LogTextRecordCapSpec` pinning `LogText.capRecord` (design
      D2, FR1): a named 16 KB constant with a derivation comment; over-cap keeps the head
      and appends a visible marker naming the dropped count; at-cap passes verbatim;
      idempotent over its own output. Verify: red, then green after implementation.
- [ ] 1.6 Idempotence spec (FR2, design D2): data-driven over the whole corpus,
      `strip(flatten(capRecord(forLog(x)))) == forLog(x)` byte-for-byte. Verify: green;
      `:logtext:check` green including PIT.

## 2. Sink converters and Logback configuration in `:bootstrap`

- [ ] 2.1 TDD (red first): `SafeMessageConverterSpec` (FR1, design D1): a Logback
      `ILoggingEvent` with a hostile formatted message renders inertly, flattened and
      capped; a `forLog`-prepared message renders byte-identically (FR2). Verify: red.
- [ ] 2.2 TDD (red first): `SafeThrowableConverterSpec` (FR3, design D3): a thrown-and-
      caught exception whose message embeds `\n2026-… ERROR forged` renders with trace
      lines intact and indented, the forged line as `\t| ` continuation, `\r` removed,
      ANSI in any line inert; suppressed and cause-chain messages covered; an
      unrecognized line shape degrades to a continuation line. Verify: red.
- [ ] 2.3 TDD (red first): `SafeMdcConverterSpec` (FR4, design D4): a hostile `stage`
      value renders single-line and inert; a missing key renders as today (empty).
      Verify: red.
- [ ] 2.4 Implement the three `ClassicConverter` subclasses in `bootstrap/…/logging/`,
      each delegating to `:logtext` primitives and holding no character literal (single-
      owner table, row 3). Traceability: `Implements FR1, FR3, FR4, NFR-R1 of
      harden-untrusted-text-sinks`. Verify: 2.1–2.3 green.
- [ ] 2.5 TDD (red first): NFR-R1 in each converter spec — a converter whose primitive
      throws (injected failure) writes the bounded placeholder record and the appender
      keeps appending. Verify: red, then green.
- [ ] 2.6 Register the three `<conversionRule>`s and rewrite `GNOMISH_LOG_PATTERN` to use
      `%safeMsg`, `%safeEx`, `%safeX{…}` in `logback-spring.xml` and `logback-test.xml`;
      extend `LogbackConfigSpec` to assert both files declare the rules and the pattern
      contains no plain `%msg`, `%ex`, `%X{` (single-owner table, row 1). Verify:
      `LogbackConfigSpec` green.
- [ ] 2.7 Converter-source scan spec (single-owner table, row 3): the converter sources
      contain no escape-character literal or `\p{Cc}`-class pattern. Verify: green;
      seeded violation detected.
- [ ] 2.8 Run `LogContractGateSpec` and the `checkLogExpectationGate` task (NFR-O2).
      Verify: both green unchanged — no `[GFnnn]` head moved.

## 3. The console owner in `:application`

- [ ] 3.1 TDD (red first): `SystemConsoleIOSpec` (FR5): `print` applies `forConsole`
      over a captured `PrintStream`; new `printMachine` writes byte-identically; the
      corpus is inert on the human path and untouched on the machine path (NFR-S2).
      Verify: red.
- [ ] 3.2 Add `ConsoleIO.printMachine(String)`; implement in `SystemConsoleIO`; update
      `DialogConsole` and the scripted test double in `:test-fixtures` to carry both
      paths. Traceability: `Implements FR5 of harden-untrusted-text-sinks`. Verify: 3.1
      green; existing dialog specs green.
- [ ] 3.3 Reroute the 25 direct print sites per design D5's table — `StatusCommand`
      (:85, :102, :112, :120, :121), `UsageCommand` (:59, :65, :67), `BoardCommand:80`,
      `RunExceptionReporting` (:46, :49, :56, :62), `RunnerOutcomeLoop` (:183, :184),
      `ServeCommand` (:220, :244), `ContainerGitModeRunner` (:79, :80), `GitModeRunner`
      (:186, :187), `GitResumeContinuation:143`, `ContainerResumeOutcomes:141`,
      `bootstrap/ManualRunDrive:26` — injecting `ConsoleIO` by constructor where a
      command has none (composition root wires `SystemConsoleIO`). JSON branches use
      `printMachine`. Verify: every existing command spec green; `--json` outputs
      byte-identical in `StatusCommandSpec` / `UsageCommandSpec` (UX3).
- [ ] 3.4 TDD (red first): `ConsoleOwnerGateSpec` in `:bootstrap` (FR6, single-owner
      table, row 2): source scan over production sources for `System.out.print*` /
      `System.err.print*` outside `SystemConsoleIO`; a seeded violation is detected.
      Verify: red on a seeded site, green on the tree after 3.3 (M2).
- [ ] 3.5 `AbortHandler` (design D7): pass the original throwable as the trailing
      argument where one exists; keep the rendered text as a message only on the
      throwable-less path. Update `AbortHandlerSpec` to assert the throwable rides the
      line. Verify: green; `LogExpectationGate` green.
- [ ] 3.6 `TakeOutcomeMapper:95` (design D8, FR7): build the report through the
      escalation renderer; `TakeOutcomeMapperSpec` asserts no `EscalationReport`
      record `toString()` shape (`CannotVerify[` etc.) appears in the report. Verify:
      red first, then green.

## 4. Durable documents

- [ ] 4.1 Amend `docs/adr/0004-logging-policy.md` (FR8): replace "Known limit: untrusted
      text in exception messages" with the three-layer statement (capture → per-consumer
      exit → sink backstop), name this change as the third layer's provenance and the two
      follow-ups by change name, and record the console's visible-notation convention
      with its precedents (git sideband masking, kubectl `EscapeTerminal`). Verify:
      `grep -n "Known limit" docs/adr/0004-logging-policy.md` returns nothing.
- [ ] 4.2 Update `.claude/rules/logging.md`: the "Known limit" subsection points at the
      ADR's three layers; add the console rule (no direct process-stream writes; human vs
      machine path). Verify: section present; no reference to a deleted heading.
- [ ] 4.3 `docs/glossary.md` (Observability): add *operator console*; extend *log text
      sanitization* with the sink layer sentence. Verify: entries present; no banned
      synonym introduced.

## 5. End-to-end invariant and module gates

- [ ] 5.1 `UntrustedTextSinkInvariantSpec` in `:bootstrap` (NFR-S1, design D9): load the
      real `logback-test.xml`, attach a byte-capturing appender after the encoder, drive
      the corpus through (a) a message argument, (b) a thrown-and-caught exception passed
      as the trailing argument, (c) an MDC value; assert on bytes: no ESC, no C1 byte, no
      bidi override, no bare line break inside a record, no record over the cap; plus the
      FR2 byte-identity feature for `forLog`-prepared messages. Verify: green on all
      three appender patterns (M1).
- [ ] 5.2 Console half of the invariant (NFR-S2): the corpus through `SystemConsoleIO`
      over a captured stream — human path inert with line breaks preserved, machine path
      byte-identical. Verify: green (M1).
- [ ] 5.3 Module gates: `./gradlew :logtext:check :bootstrap:check :application:check`
      and `./gradlew check` at the root — Spotless, Error Prone/NullAway, Spock, JaCoCo,
      PIT 100% per touched module, dependency-analysis, layering gate unchanged. Verify:
      BUILD SUCCESSFUL (M3, M4).
- [ ] 5.4 Old-way sweep report (implementation.md): `grep -rn "System\.\(out\|err\)\.print"
      --include=*.java` over production sources lists only `SystemConsoleIO`; `grep -n
      '%msg\|%ex\|%X{' bootstrap/src/*/resources/logback*.xml` returns nothing. Record
      both results in the task report. Verify: as stated.
