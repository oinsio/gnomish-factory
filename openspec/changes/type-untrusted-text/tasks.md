# Tasks: type-untrusted-text

Sequenced after `split-logtext-leaves`. Groups 1–4 are cut A (design D9):
shippable alone. Groups 5–7 are cut B; if B overruns, it becomes
`type-untrusted-text-tracker` with these groups carried over verbatim. Every
group ends with the module gates green; the sink invariant from
`harden-untrusted-text-sinks` runs at the end of each cut.

## 1. The carrier and its exits in `:untrustedtext`

- [x] 1.1 TDD (red first): `UntrustedTextSpec` (FR1, design D1): six mints with
      provenance; `raw()`; equality by (raw, provenance); `toString()` byte-equals
      `forLog()`; `excerpt(int)` bounded; null-hostile. Verify: red.
- [x] 1.2 TDD (red first): `UntrustedTextExitIdentitySpec` (FR2): data-driven over the
      adversarial corpus × every provenance × every exit — `forLog` equals
      `TextSafety.forLog`, `forConsole` equals `TextSafety.forConsole`, `forComment`
      equals `TextSafety.forComment`. Verify: red.
- [x] 1.3 Move `TrackerFence`'s fence/mention/label logic into `TextSafety.forComment`
      (design D7); TDD the mention/fence corpus first (NFR-S2): `@team` → `@​team`,
      `#123` → `#​123`, fence longer than any run, label present, line breaks kept.
      Verify: red, then green; `TrackerFence` delegates and its spec passes unchanged.
- [x] 1.4 Implement `UntrustedText` and `@UntrustedExit` (`RUNTIME`, `TYPE`).
      Traceability: `Implements FR1, FR2, FR3 of type-untrusted-text`. Verify: 1.1–1.3
      green; `:untrustedtext:check` green, PIT 100%.
- [x] 1.5 The carrier's other ways out (FR10, design D11), added after the cut A
      families showed every `stdout` reader to be a parser, not a sink: queries
      `isBlank()`, `contains(String)`, `length()` — open to every caller, no
      annotation, no gate, because a boolean carries no text; `forParsing()`
      returning the captured bytes; `@UntrustedParser` (`RUNTIME`, `TYPE`), with a
      javadoc stating the membership criterion (converts to a value that is no longer
      untrusted text; a `String` result names its syntax gate or fixed shape). TDD
      (red first) in `UntrustedTextSpec`: `forParsing()` byte-equals `raw()` over the
      adversarial corpus; the three queries answer without rendering. Verify: red,
      then green; `:untrustedtext:check` green, PIT 100%.

## 2. The type gate in `:bootstrap`

- [x] 2.1 TDD (red first): `UntrustedTextGateSpec` rule (a) — ArchUnit: no method in a
      class not annotated `@UntrustedExit` calls `UntrustedText.raw()` (design D2, FR3).
      Verify: red on a seeded caller in a scratch class; green after the annotated set
      of task 2.4 exists.
- [x] 2.2 TDD (red first): rule (b) — production methods in the capture vocabulary
      return `UntrustedText` / `List<UntrustedText>` (FR7). The vocabulary is a
      per-family table in the spec (design D2, D9); this task declares cut A's
      families only: `stderr`, `stdout`, `output` (git, docker, in-box), `sessionId`,
      `model`, `question`, `options` (agent), `reason`/`details` on
      `Verdict.CannotVerify`. Cut B's names are added by task 5.0, so the spec is
      never red over a family the current cut does not migrate. Verify: red on the
      current tree (every cut A carrier still `String`), green at 3.5 with no
      allowlist.
- [x] 2.3 TDD (red first): rule (c) — source scan in the retired gate's shape: no
      `UntrustedText`-typed argument (accessor result, local, field) directly in an
      SLF4J call, a `Throwable` constructor, `ConsoleIO.print*`, or a text-carrying
      `Tracker` method — `park`, `finish`, `declineFinished`, `acknowledgeDecision`,
      `postNote` — or the `AbortRecord` constructor (FR7, design D2). Verify: seeded
      violations for all four sink kinds detected, one per tracker method.
- [x] 2.4 Annotate the machine writers and the funnel entry with `@UntrustedExit`
      (`TaskJsonMapper`, `StateJsonMapper`, `LedgerJsonMapper`, `SnapshotJsonMapper`,
      the `Finding`-building funnel callers — not `TrackerFence`, a `String → String`
      facade that never reads `raw()`, design D2) and pin the annotated set
      in `UntrustedTextGateSpec` (a growth fails until acknowledged). Verify: rule (a)
      green; the pinned set equals the annotated set.
- [x] 2.5 Copy `UntrustedLogTextGateSpec`'s fluent-form and wrapped-form seeded cases
      into rule (c)'s spec so no detector shape is lost (M1). Keep the gate itself:
      rule (c) sees only carriers, so a `String` accessor has no other gate until its
      family is typed. Narrow its `UNTRUSTED` pattern as families land (3.1–3.3, 5.2,
      5.3); when the last name, `source`, goes at 5.3, the spec is **retargeted at the
      capture point** rather than deleted (task 7.4, design D12, FR11) — the type guards
      only text already minted, so the scan moves to the layer no type reaches. A
      split-out cut B (D9) inherits it with `label` and `source`. Verify: `:bootstrap:test` green; the
      pattern names exactly the accessors of the families not yet typed.
- [x] 2.6 TDD (red first): rule (a2) — ArchUnit, in `UntrustedTextGateSpec`: no method
      in a class not annotated `@UntrustedParser` calls `UntrustedText.forParsing()`
      (FR10, design D11); the annotated parser set is pinned as a **map, class to what
      it converts the text into**, so a reviewer reads the warrant beside the name and
      a class joining the set fails until acknowledged (the pin starts empty and is
      filled by 3.1–3.3). Seeded cases in `architecture.seeded`: an unannotated caller
      of `forParsing()` fails; an annotated one passes; an annotated parser whose
      method returns the text straight back (`return result.stdout().forParsing();`)
      fails, naming the method. Keep rule (a) unchanged — `raw()` stays the closed
      exit set rule (a) already pins (M6); tasks 2.4, 3.3 and 5.0 grow it to twelve. Verify: red on each seeded case, green on the tree.

## 3. Cut A families: git, docker, in-box, agent

- [x] 3.1 `GitCommandResult(stdout, stderr)` → `UntrustedText` minted at
      `GitProcessRunner:230` (after `CredentialScrub`) and at every other
      `new GitCommandResult(` site (including `:133`'s synthetic result); `failureDetail`
      / `cannotVerifyDetail` compose from `toString()`; `RemoteBaseRef.Held.Unanswered`,
      `BaseRefreshOutcome`, `ResumeBaseOutcome`, `DefaultBranchDiscovery` reason/report
      fields typed; `InBoxGitCommand.Outcome` typed (design D3, D4). Verify:
      `:adapters:git` specs green with typed fixtures; rule (b) green for this family;
      `stderr`/`stdout` dropped from the retired gate's pattern (2.5).
      Annotate this family's 16 parsers `@UntrustedParser` and add them to 2.6's pinned
      map with what each converts to (design D11): `FactoryCloneHardening`,
      `GitProcessRunner`, `HarvestedBoundaryCheck`, `LocalBranchTip`, `OriginRemote`,
      `RemoteBaseRef`, `RemoteBranchTip`, `RemoteDefaultBranch`, `ReplicaPairReconciler`,
      `RoundBoundaryCheck`, `SnapshotTipCheck`, `TaskBranchLister`, `TaskWorktreeManager`,
      `UsageHistoryWalker`, `VerifiedTip`, `ContainerHarvestFetch`. (`WorktreeSalvage` stood here
      when this task was written and left the set while it was being done: its only captured read
      is the dirty-tree question, which the carrier's own `isBlank()` answers with no text
      leaving it. `ContainerHarvestFetch` took its place, classifying a fetch's *stderr* into a
      failure class — a stderr parse D11's stdout-derived list did not consider. The count is
      unchanged at 16; `PARSER_CONVERSIONS` is the enforced set.) `GitShowTip.readAtTip` and
      `DeliveredBranchReader.show` are **not** parsers: they yield a branch document's
      content, so `BranchTipSource.readAtTip`, `TipEnvelopeRead.Loaded` and
      `TaskJsonMapper`/`StateJsonMapper.readDto` take `UntrustedText` and the mappers —
      already `@UntrustedExit` — re-mint each lifted field `BRANCH_DOCUMENT` (D3 row,
      D11). Emptiness and substring checks use the queries, not `forParsing()`.
- [x] 3.2 `DockerResult`, `EgressSelfCheckProbes.Probe`, `CapturedExec.output` typed;
      mints at `DockerResult.of` (over `DockerCli`'s `Captured`) and `CapturedExec:64`.
      `DenialCursor.source` is **not** typed: it is an identity two leases compare across
      two media, so it joins NG4 through a named syntax gate instead — `ContainerIdSyntax`
      in `:sandbox:docker`, applied by `GuardSourceIdentity` as its `@UntrustedParser`
      warrant (design D11, decided at this task). The
      third-party accessor the retired gate named (design D5, D3 fold row):
      `GuardDenialLog:136` mints `UntrustedText.container(e.getOriginalMessage())`
      at the catch, `GuardDenialDrops.malformed(UntrustedText)` keeps the carrier and
      its `report` log call renders `forLog()`; the `LogText.forLog` call at the catch
      is deleted. A `GuardDenialDrops` spec scenario asserts the first malformed reason
      is logged in its `forLog()` form for a hostile line. Verify: `:sandbox:core`,
      `:sandbox:docker` specs green; rule (b) green; rule (c) green on the `report`
      call; `output` and `getOriginalMessage` dropped from the retired gate's pattern
      (2.5) — `source` stays there until 5.3, since the restored id passes no reader gate
      yet. Annotate this family's 9 parsers `@UntrustedParser` and add them to
      2.6's pinned map (design D11): `ContainerMaterializer`, `DeclaredVolumeOverrides`,
      `EgressGuard`, `EgressSelfCheckProbes`, `EnvironmentSelfCheck`, `GuardDenialReads`,
      `GuardSourceIdentity`, `OomAnnotatedExecHandle`, `SandboxLifecycleObjectReader`.
      `DockerCli` and `CapturedExec` stay unannotated — they read `:subprocess`'s
      `Captured`, still a `String` at the mint (D3); `DockerCli`'s daemon-unreachable
      classification and the `already exists` retries use the queries.
- [x] 3.3 Agent family: `AgentEvent` text fields, `DecisionFileReader.Decision/Payload`,
      `MissingResultEventException(UntrustedText sessionId)`, `JudgeRoundExecution:106`
      and `JudgeVerdictExtractor` carry the carrier into `Verdict.CannotVerify`
      (domain field typed in this task since the agent adapter constructs it). Verify:
      `:adapters:agent` and `:domain` specs green; rule (b) green; `sessionId`/`model`
      dropped from the retired gate's pattern (2.5). `TokenUsageMapper:89` uses the model
      id as a `Map<String, TokenUsage>` key that reaches `:domain`'s `ExecutorUsage`,
      `state.json` and the dashboard: add `ModelIdSyntax` in `:adapters:agent` accepting
      `[A-Za-z0-9._:/\[\]-]{1,128}` — the brackets are a deviation from design D11's
      proposed class, found by the reference dump: a real id is `claude-opus-4-8[1m]`, so
      without them every such round's telemetry would key on the placeholder — and
      degrading anything else to a bounded placeholder, annotate `TokenUsageMapper`
      `@UntrustedParser` with that gate as its warrant, and keep the key a `String` (NG4
      by construction, design D11); the gate is applied on **both** paths, the
      `modelUsage` keys as well as the init event's model. TDD the gate first with
      a hostile model id. `StreamJsonEventMapper` stays unannotated — it is the mint.
      Two consequences of the verdict becoming a carrier, both recorded in design D2/D6:
      the two `--json` mappers (`status.json.AttemptMapper`,
      `usage.json.UsageReportJsonMapper`) join `@UntrustedExit` — their document is a
      parser's input, written byte for byte — and the three ports cut B types
      (`ExecutionResult.DecisionNeeded`, `EscalationReport.CannotVerify`,
      `AgentProgressEvent`) receive `forConsole()` at the boundary until task 5.0: the
      exit that keeps line structure and length, so a stack trace and a question reach a
      human whole and benign text passes byte for byte.
- [x] 3.4 The 37 exception sites (design D5): constructors take `UntrustedText`; new
      `GitResyncFailedException` (landed at 3.1) and `DockerCommandFailedException`
      replace the `IllegalStateException` folds; `getMessage()` folds re-mint at the fold
      (`GithubTransportException`, `HttpExternalCheckClient`, the two law-source reads,
      and the two docker folds that re-state a lower failure). The law-read chain is typed
      end to end rather than rendered at its fold — `LawSource.Unreadable.reason`,
      `PipelineLaw.Unreadable.reason` and `UnreadableLawFileException` all take the
      carrier — because a reason rendered at the mint is the honest-debt shape D5 rejects.
      One spec asserts the property across the whole set rather than one per class,
      `TypedExceptionMessageSpec` in `:bootstrap`: every typed exception built from a
      hostile carrier carries that carrier's `forLog()` and no line break, carriage return
      or escape (the untrusted-text spec scenario "An exception carrying subprocess output
      renders safely"). Verify: every listed site compiles only with the typed parameter;
      rule (c) green for `Throwable` constructors. Old-way sweep on the three classes that
      kept a `String` overload — `GuardUnavailableException`, `DockerUnavailableException`,
      `SelfCheckFailedException`: every surviving caller passes factory-authored text only
      (a passport, an allowlist entry, a binary path, a JDK cause, or a fold of a message
      already composed from a carrier), so the overload is not an escape hatch for captured
      text; a caller that acquires one must take the typed constructor beside it.
      One site the design's first draft listed left the set rather than being converted:
      `BranchTipFactsReader.faultOf` folds into `EnvelopeStatus.Unreadable`, a domain status
      record and not an exception, whose `reason` is factory-authored prose — the exemption
      and its enforcing spec are recorded in D5.
- [x] 3.5 Cut A gates: `./gradlew :untrustedtext:check :bootstrap:check :domain:check
      :sandbox:core:check :sandbox:docker:check :adapters:git:check :adapters:agent:check`
      — plus `:adapters:check` and `:application:check`, which cut A reaches too (the
      check runners and the `--json` mappers) — and the sink invariant spec (NFR-R1).
      Rules (a), (a2), (b), (c) all green with no allowlist; `@UntrustedExit` names the
      twelve classes of 2.4 as amended at 3.3 and 5.0, and `@UntrustedParser` exactly the 27 of
      3.1–3.3, each pinned with what it converts to (M6). Verify: BUILD SUCCESSFUL,
      PIT 100%.
      **One gate outside this list is red until task 7.1, by construction of the cut:**
      `:gnomish-plugin-api:japicmpApiGate` reports one binary change,
      `Verdict$CannotVerify`, because the published contract re-exposes that domain type
      and cut A types its two components (design D4). D8 assigns the version bump and the
      re-baseline to cut B, so root `./gradlew check` closes there (M5), not here — which
      makes "cut A is shippable alone" true of the runtime and of every module gate, but
      not of the published-contract gate. A cut A that must ship on its own bumps the
      contract early instead; that is a decision for whoever ships it, not a defect in
      this task.

## 4. Cut A documents

- [x] 4.1 `.claude/rules/logging.md`: replace the accessor list and the "honest debt"
      paragraph with the type rule (mint at capture, exit at sink, `raw()` only in
      `@UntrustedExit` classes, `forParsing()` only in `@UntrustedParser` classes with
      the membership criterion, queries open to all — FR10, design D11); ADR 0004: the
      type is layer two (FR9). Verify: `grep -n
      "honest debt\|stderr()" .claude/rules/logging.md` returns nothing (M4).
- [x] 4.2 `docs/glossary.md`: *untrusted text* and *provenance* entries under
      Observability, with *Never:* raw string, tainted string; the existing *Log text
      sanitization* entry rewritten — the choke point is now the carrier's exit, with
      `LogText` a facade for `String` callers (`process-invariants.md`: a shifted
      meaning updates the entry). Verify: entries present; terms used in code match.

## 5. Cut B families: tracker, manifest, branch document

- [x] 5.0 Extend rule (b)'s vocabulary table with cut B's families (design D9), and delete
      the three `forConsole()` boundary renderings task 3.3 left at the ports this task
      types (`ExecutionResult.DecisionNeeded` in `ExecutorRoundExecution`,
      `EscalationReport.CannotVerify` in `StageAttemptLoop`, `AgentProgressEvent` — grep
      `forConsole()` over production sources and check each hit against design D6):
      `title`, `body` (tracker snapshot, `TaskContext`, `StatusReport`), `instance`,
      `humanText` (`ParsedMarker`), `label` (`CheckRef`), `message`, `cause`
      (`Aborted`, `AbortRecord`, `CannotExecute`), `prompt`, `currentTool`
      (`Activity`), `staleStage`, and `reason`/`details` on `EscalationReport` /
      `PollStatus`. Verify: red on the tree before 5.1 (it names every cut B carrier
      still `String`), green at 6.4; if cut B is split out (D9), this is the first task
      of `type-untrusted-text-tracker`.
- [x] 5.1 `TaskSnapshot(String id, UntrustedText title, UntrustedText body)`; mints at
      `GithubTaskFetcher:104,170`, `InMemoryTracker:76`, `InMemoryTrackerSeeding:93`,
      `InMemoryTrackerHarness:101`; `ParsedMarker.instance/humanText` typed at
      `GithubMarker:187`; `TaskContext.title/body` and `StatusReport.title/body` typed
      downstream; `:test-fixtures` gains carrier helpers for fixtures (design D3, D4).
      Verify: tracker contract suites green on both adapters; rule (b) green.
- [x] 5.2 Manifest family: `ConfigError.render()` returns a `MANIFEST` carrier (design
      D10; the record's three `String` fields and its 97 constructor sites are
      untouched); its five consumers take the carrier — `BaseLawReport` (`forComment()`,
      its `LogText.forLog` call deleted), `PipelineStartup`, `TakeCommandSupport`,
      `TrustedTierStartup`, `CheckClientConfiguration`; `CheckRef(int, UntrustedText
      label)`, `EscalationReport.PipelineMismatch.staleStage`,
      `Activity.AwaitingInput.prompt`, `Activity.Executing.currentTool`. Verify:
      `:adapters` loader specs and `:domain` specs green; rule (b) green; a
      `ConfigErrorSpec` scenario asserts `render()` equals the manifest mint of the
      joined line; `label` dropped from the retired gate's pattern (2.5, M1; the spec
      itself is retargeted at 7.4, not deleted — design D12).
- [x] 5.3 Branch-document family. **First, equality by text alone** (design D1, revised
      2026-09-17): `UntrustedText.equals`/`hashCode` drop provenance; `UntrustedTextSpec`
      asserts that two carriers of the same text under different provenances are equal and
      hash alike, and the delta spec's scenario "A value read back from a document equals
      the value written" is the flow assertion — this task is where the first value
      crosses a medium, so it is where the old equality would first be wrong. Then:
      `TaskJsonMapper` / `StateJsonMapper` readers mint
      `BRANCH_DOCUMENT` for `Aborted.cause` and escalation DTO text, and hold a restored
      `DenialCursor.source` to `ContainerIdSyntax` at the read (design D11: the id stays a
      `String`, so what a reader owes it is the gate, not a mint); `DenialIdentity.source`,
      restored at `StateDenialMapper:55` and compared one call later, takes the same gate,
      while its `eventAt` stays ungated with the D3 row's reason recorded in the mapper's
      javadoc (stored and compared, rendered by no sink). After that `source`
      leaves the old gate's pattern and the `LogText.forLog` call in `RestoredDenials`
      goes; the spec itself is retargeted at 7.4, not deleted. Writers stay raw under
      `@UntrustedExit`; a round-trip spec asserts a
      pre-change `task.json` (raw strings) reads into carriers with no wire change
      (NFR-R2) and that the carrier read back **equals** the carrier written.
      Verify: round-trip green; `EscalationReport` and `TaskOutcome.Aborted`
      fields typed; `StackTraces.render` returns a carrier; `:untrustedtext:check` green
      with PIT 100% after the equality change.
      **Q3 is resolved as D13** and lands here, since it is what blocked this task:
      the leaf gains `UntrustedText cappedTo(int)` (head+tail with a marker, same
      provenance, no annotation — no text leaves), `AbortCauseBudget.cap` becomes
      carrier-in / carrier-out keeping its single ownership of the budget, and
      `AbortHandler:147` stays its one caller. TDD (red first) in `UntrustedTextSpec`:
      a carrier over the budget caps to the marker shape with provenance preserved, one
      under it is returned unchanged; `AbortCauseBudgetSpec` passes with carrier
      fixtures. Verify: `@UntrustedExit` still names exactly the classes of 2.4 as
      amended at 3.3 (M6) — the allowlist did not grow.
- [x] 5.4 `TakeResult` free-text fields and `TakeResultDescription` typed per D4;
      `TaskSummaryAssembler`, `SlotOutcomeLog`, `DrainReport`, `TakeBatchSummary` call
      `forLog()` explicitly (rule (c)). Verify: serve/take specs green; rule (c) green.

## 6. Reports, renderers, tracker publication

- [x] 6.1 Report builders take carriers and render through `forComment()`:
      `FreshClaimBaseReport`, `ResumeBaseReport`, `BaseLawReport` — delete the per-field
      `LogText.forLog` calls `add-base-ref-resolution` added; `BaseReportSanitizingSpec`
      is rewritten against carriers and gains the fence/label assertion (design D6).
      Verify: red first (fence absent), then green.
      **Three pieces the task text assumed and the tree did not have**, all decided with the
      user and folded into the design rather than absorbed silently:
      (a) `BaseRefreshOutcome.Refused.report` / `ResumeBaseOutcome.Refused` are typed here —
      D4's table names them and task 3.1 typed only the `Unavailable.reason` half, so the
      builders had no carrier to render.
      (b) The **designator chain** is typed (new D3 row, new D4 row): the values in
      `underdetermined` are tracker labels, so deleting their `LogText` calls without the
      carrier would have sent a hostile label to a park comment raw — comments have no sink
      backstop. The mint is `BaseDesignatorMapping`, not the adapters: the port's
      `Designator` is a published contract over open kinds and `base` is the one whose
      refusal is published, so the contract takes no extra break (D8) and future routing on
      kind `type` is untouched. `BaseDesignator$Single` joins 2.6's parser map, with
      `BasePattern.matches`'s `RefNameSyntax` gate as its warrant. `:baseref`'s
      `allowedProjects` goes `[]` → `[':untrustedtext']`, restated in its build file,
      `settings.gradle` and `ModuleBuildFileSpec`'s leaf registry.
      (c) `Provenance.OPERATOR` and `UntrustedText.operator` (D1's seventh family) for the
      one value in that list that is not tracker text: a `--base` argument `RefNameSyntax`
      refused, which `BaseRefResolverSpec` pins as named in the report.
      Two scan-visible consequences, both fixed in place: `CarrierArguments.MINTS` gains the
      seventh mint, and `report`/`value` join `UntrustedTextSinkGateSpec`'s ambiguous-name
      pin. Two carrier-typed locals were renamed (`refusal`) where they collided with a
      `String report`/`reason` in the same file — the source scan's names are file-scoped.
      The ref names the three builders interpolate stay `String` (NG4): the resolver gates a
      fresh claim's, `PinnedRefGate` gates a resume's at the `task.json` read, so their
      `LogText` calls are deleted rather than replaced, and `ResumeLawBindingSpec`'s
      hostile-pin scenario is narrowed to the detail with that reason recorded.
- [x] 6.2 Renderers: `EscalationResumeDialog.renderEscalation` (`forComment()` for
      the report, `forConsole()` for the console print), `StatusLineFormatter`,
      `StatusTextRenderer`, `BoardTextRenderer` (`forConsole()`), `TakeOutcomeMapper`
      (through the dialog renderer). Verify: renderer specs green; the corpus through
      each renderer shows no ESC/C1 (reuse the sink invariant's corpus).
      Tasks 5.0–5.3 had already typed most of what these render, so what was left was the
      exit choice at each site: `renderEscalation` now takes `forComment()` for
      `CheckRef.label`, `staleStage` and `CannotExecute.cause` (they were reaching the
      tracker through `toString()`, i.e. the log exit — flattened and capped);
      `StatusLineFormatter` takes `forConsole()` for the same four plus `Activity`'s
      `prompt` and `currentTool`. `StatusTextRenderer`/`BoardTextRenderer` were already on
      the console exit. `TakeOutcomeMapper` goes through the dialog renderer unchanged.
- [x] 6.3 The eight park writers — `FreshClaimBaseBinding`, `ResumeLawBinding`,
      `TakeDecisionResume`, `TaskTierLaw`, `EpochRecordingTracker`, `AbortHandler`,
      `GuardedPark`, `TakeQuarantinePark` — receive builder output only. The other
      text-carrying writes are consumers too (design D7): `FinishEffect` publishes the
      `TakeFinishReport` summary as the builder handed it over — since D6's 2026-09-19
      revision the builder renders each quoted field through `forCommentInline()` on
      `ReportPlane.COMMENT`, so the write neutralizes nothing a second time;
      `AbortRecord(UntrustedText cause, …)` is typed and `GithubStateWrites:128`
      renders it through the exit at the comment; `DecisionAck` renders the echoed
      human reply through the exit; the two stop notes (`TakeContainerEngineExecution`,
      `RevocationHandler`) render the revocation reason through it. TDD (red first)
      `TrackerPublicationOwnerSpec` in `:bootstrap` scanning every production call of
      a text-carrying `Tracker` method and every `new AbortRecord(` for a raw carrier
      or a `LogText`/`FindingsSanitizer` call at the write (FR8). Verify: red on a
      seeded raw park and a seeded raw `postNote`, green on the tree; `grep -rn
      "forComment()" --include=*.java` over production sources lists only the builders
      and comment-rendering writers design D7 names — none of the eight park writers
      (M3).
- [x] 6.4 Cut B gates: `./gradlew check` at the root, the sink invariant spec, PIT 100%
      per touched module. Verify: BUILD SUCCESSFUL (M5).
      **Green except one gate, and it is the one task 3.5 already declared red by
      construction:** `:gnomish-plugin-api:japicmpApiGate`. Its report names exactly the
      typed-field breaks task 7.1 enumerates — `TaskSnapshot`, `AbortRecord.cause`,
      `Verdict.CannotVerify` and the re-exposed domain types — and nothing this group added
      beyond them, which is what minting the designator values in `BaseDesignatorMapping`
      rather than in the port bought (D3, D8). M5 therefore closes at 7.1, with the version
      bump and the re-baseline, so this box stays open until then rather than claiming a
      BUILD SUCCESSFUL the tree does not have.
      One gate outside the task's list had to be closed here: `buildHealth`
      (dependency-analysis) went red because the typing moved `:untrustedtext` onto the
      published surface of `gnomish-plugin-api` (`AbortRecord.cause`, `TaskSnapshot`) and
      onto `:adapters`' and `:test-fixtures`' own signatures, made `:bootstrap`'s edge a
      production one, and left `:adapters:agent` with no reference to the contract at all
      once task 3.3 replaced the `FindingsSanitizer` call with the carrier's exits. Scopes
      corrected in the five build files; green.
      Evidence: `./gradlew check -x :gnomish-plugin-api:japicmpApiGate` is BUILD SUCCESSFUL
      end to end, PIT included. Two earlier runs each carried one failure under parallel
      load and both were flakes — `GithubTrackerContractSpec > declineFinished ...` and a PIT
      `RUN_ERROR` on `HttpCheckRequest.build` in `:adapters`; each passed on a rerun of its
      own module's `check`, and neither reappeared in the clean run.
      **Closed at 7.1, as this box said it would.** With `gnomish-plugin-api` at 0.8.0 and
      `compat-baseline/` regenerated over the four-jar set, `./gradlew check` at the root is
      BUILD SUCCESSFUL with no task excluded — `japicmpApiGate` included, re-confirmed on its
      own with `--rerun-tasks` — PIT at 100% in every module it runs in, and the sink invariant
      spec green (M5).

## 7. Published contract and closing sweep

- [x] 7.1 `gnomish-plugin-api` 0.7.0 → 0.8.0, the sixth BREAKING move: header comment
      naming the typed-field break — `TaskSnapshot`, `AbortRecord.cause` and
      `Verdict.CannotVerify`, whose two components cut A typed at task 3.3 and which has
      held `japicmpApiGate` red since (see 3.5) — (above the existing 0.6.0 → 0.7.0 entry of
      `split-logtext-leaves`); `updateApiCompatibilityBaseline` regenerated over the
      current four-jar set (`domain`, `untrustedtext`, `operatorevent`, the api); the sample plugin mints `TRACKER`
      carriers for its snapshot and compiles with the api as its only dependency
      (design D8; contract spec scenario "A typed-field break is bumped and re-baselined
      together"). Verify: `japicmpApiGate` green; sample module green.
- [x] 7.2 `manual-sync-pairs.md`: narrow the `GitAttemptPersistence ↔
      EnvironmentAttemptPersistence` row's invariant to the commit + state-file
      sequence (the message half is now by construction). Verify: row text updated; both
      `Kept in sync with` markers still present and consistent.
- [x] 7.3 Old-way sweep report (implementation.md): (1) `grep -rn "\.stderr()\|\.stdout()\|
      \.output()" --include=*.java` over production sources — every hit returns
      `UntrustedText` (M2); (2) `grep -rn "LogText.forLog(" --include=*.java` over
      production sources — every hit is inside `:logtext`, a converter, or an exit facade;
      (3) `grep -rn "new TaskSnapshot(\|new GitCommandResult(\|new CapturedExec(\|new
      DockerResult(" --include=*.java` — every hit is in D3's mint table; (4) `grep -rn
      "forParsing()" --include=*.java` over production sources — every hit is in a class
      annotated `@UntrustedParser` and listed in 2.6's pinned map, and the method it sits
      in converts the text rather than returning it (the review obligation of D11);
      (5) `grep -rn "@UntrustedExit" --include=*.java` — exactly the twelve classes of
      design D2 (M6). Record all five with their hit lists in the task report. Verify: as
      stated.
      **Sweep report.** All five run over `src/main` only, `/build/` excluded. Three of the
      five confirm the task's claim as written; two do not, and both are stale task prose
      rather than missing work — each is recorded here with what the tree actually enforces.
      (1) **`.stderr()|.stdout()|.output()` — 1 deviation from M2 as worded.** Every hit on a
      subprocess/docker/in-box *result* returns `UntrustedText`: `GitCommandResult`,
      `DockerResult`, `CapturedExec`, `EgressSelfCheckProbes.Probe`, `InBoxGitCommand.Outcome`.
      Three hit families do not, and none is a text capture: `TokenUsage.output()` (a `long`
      token count — `UsageTextRenderer`, `AnchorLog`, the four usage/ledger mappers,
      `DashboardTokensCardRenderer`, `LedgerAggregator`, `RunSummaryAccumulator`,
      `LedgerTokenUsage`, `StateUsageMapper`); `ProcessHandle`-shaped `output()` returning the
      raw stream at the capture point itself (`JudgeRoundExecution:74`,
      `ExecutorRoundExecution:81`, `CommandProcessRunner:129`, `CapturedExec:65`,
      `OomAnnotatedExecHandle:60`) — these are the mints, not readers of one; and `:gitobjects`
      (`CommitBuilder:120,145`, `GitObjects:77,82,116`), whose `GitExec.Result` is
      `(int, byte[] stdout, String stderr)`. The last is **D3's own carve-out stated from the
      other end**: `Captured` in `:subprocess` deliberately stays `String` because that module's
      contract is mechanics, not policy, "and `:gitobjects`'s extraction-readiness depends on
      it". M2's sentence — "finds only `UntrustedText`-returning accessors" — was written
      before D3 fixed the mint line and does not name that carve-out; the carve-out is the
      controlling decision, so the hits stand and M2's wording is the thing that is stale.
      (2) **`LogText.forLog(`** — 17 production hits, none inside `:logtext`: `StreamJsonParser:151`,
      `StreamJsonEventMapper:160`, `LoggingAgentProgressListener:70,74`, `RoundDenialRead:77`,
      `GithubMarker:229`, `TaskBranchLocator:121`, `TipRecordedDenials:62`, `MidRoundPollLog:58`,
      `SnapshotTipCheck:102`, `RemoteDefaultBranch:97`, `PinnedRefGate:38`,
      `ShellCommandCheckRunner:193`, `HttpExternalCheckClient:112`,
      `SandboxLifecycleObjectReader:123`, `RemoteOutageGate:148`, `StatusLineFormatter:102`.
      The task's claim — "every hit is inside `:logtext`, a converter, or an exit facade" — is
      wrong about where they sit and right about what matters, and the compiler says so:
      `LogText.forLog` is declared only over `String` (`LogText:62,75`), so **no carrier can
      reach any of these 17 without `raw()` or `toString()`, and rule (a) gates `raw()`**. Every
      hit is therefore `String`-side sink hygiene over a value that was never a carrier — an
      enum label, a wire token, a validated ref name, a manifest command, a stack of denial
      destinations. None is a laundered carrier, which is the property the sweep exists to
      check; none is an old way of doing what the carrier now owns.
      (3) **Mint constructors** — 10 hits, all in D3's mint table or a fixture the table admits:
      `GithubTaskFetcher:107,174`, `InMemoryTracker:78`, `InMemoryTrackerSeeding:94`,
      `InMemoryTrackerHarness:103`, `GitCommandResult:58` (the `of` mint),
      `DockerResult:57` (likewise), `CapturedExec:74` (likewise), plus two the table's closing
      line covers — `GithubTrackerFixtureAdapter:123` (a fixture re-wrapping carriers it was
      handed, minting nothing) and `SampleTracker:40` (the sample plugin, task 7.1's own
      `TRACKER` mint). No hit constructs a carrier-bearing result outside an owner.
      (4) **`forParsing()`** — 30 production files. 27 carry `@UntrustedParser` and are exactly
      the 27 entries of `PARSER_CONVERSIONS`, each with its conversion recorded beside it; the
      other three — `GitCommandResult`, `DockerResult`, `CapturedExec` — are **javadoc
      mentions only**, the carrier-bearing results pointing their readers at the parsing exit,
      with no call. D11's review obligation holds at every one of the 27: the pinned map states
      the converted value, and ArchUnit rule (a2) fails the build on an unannotated caller.
      (5) **`@UntrustedExit`** — 11 production classes plus the carrier itself, twelve in all — the count the task text above now carries, corrected from the "seven" it was written with:
      `JudgeVerdictExtractor`, `GithubWorkflowJobsFetcher`, `StateJsonMapper`, `TaskJsonMapper`,
      `BoardJsonMapper`, `LedgerJsonMapper`, `SnapshotJsonMapper`, `AttemptMapper`,
      `EscalationMapper`, `StatusReportJsonMapper`, `UsageReportJsonMapper` — plus
      `UntrustedText` itself, which is the twelfth entry of `ANNOTATED_EXITS`. Three seeded
      classes in `:bootstrap` are test sources and outside the scan. The counts this task's text
      and M6 were written with — "seven" and "nine" — both predate tasks 2.4, 3.3 and 5.0, which
      amended the list while it was being written; both were corrected to twelve in place once
      this sweep established the number. The pinned `ANNOTATED_EXITS` in `UntrustedTextGateSpec`
      is the enforced set, it fails the build on growth, and it is green. **No sweep found an unlisted old-way
      survivor.**
- [x] 7.4 Retarget the capture gate (FR11, design D12). `UntrustedLogTextGateSpec` keeps
      its whole-tree scan and changes its subject: instead of accessor names at log calls
      (rules (a)–(c) own that now), it fails the build on **raw capture outside the mint
      owners** — a subprocess stream read, an HTTP response body read, a document file
      read — with D3's mint table as its allowlist, each entry naming the family it mints,
      and an assertion that the scan reached every allowlisted file
      (`BaseHeadDefaultBoundarySpec` precedent). Rename it for its new subject. TDD (red
      first): a seeded adapter reading a stream into a `String` outside the allowlist
      fails; an allowlisted mint owner passes; a removed allowlisted file fails the
      reached-every-file assertion. `.claude/rules/logging.md` gains the matching rule —
      a new capture source mints at the point of capture, and the gate that enforces it is
      named. Verify: `:bootstrap:test` green; the three seeded cases; the delta-spec
      scenario "A capture source that never mints fails the build" satisfied.
      **Landed as `RawCaptureGateSpec` + `RawCaptureOwners`**, the scan renamed for its new
      subject and its allowlist split into a registry class beside `RepoSourceTree`
      (`process-invariants.md`: the split moves a responsibility — the data a reviewer reads and
      an author extends — out of the detector that judges it). Three capture shapes:
      `getInputStream()`/`getErrorStream()` on any receiver; a `body()` call in a file naming
      `java.net.http.HttpResponse`; `Files.readString|readAllLines|readAllBytes|lines|
      newBufferedReader`. The HTTP shape is keyed on the import because `body()` alone is
      answered by a task context, a tracker comment and a recorded decision — and because a
      `Fresh` envelope answering it downstream is propagation of text already captured, which
      this gate deliberately does not judge.
      **26 allowlist entries**, each naming its family or why no mint is owed: 4 subprocess-stream
      owners (`CaptureRunner`, `GitExec` — D3's mechanics carve-out stated from the other end —
      `HostExecHandle`, `ContainerFileChannel`), 11 HTTP transports (the github bundle plus
      `JdkHttpCheckExchange`), 10 document readers (the four branch-document mints,
      `DecisionFileTransport`, `WorkingTreeLawSource`, `AdHocTaskSynthesizer`, and three that owe
      no mint — a secret value and two factory-authored documents read back), and
      `SourceMarkerScan`, the one `src/main` file outside the running factory that reads files
      (`RepoSourceTree` walks every `src/main` in the build, `:test-fixtures` included).
      Five seeded violations, four seeded non-captures, the allowlisted-owner case and the
      reached-every-file case: 12 features, red first on the real tree (it found two things the
      hand-written allowlist had wrong — a mis-spelled `StateFileWrite` path and the unlisted
      `SourceMarkerScan`), green after. `:bootstrap:test` green end to end.
      Three stale `{@link UntrustedLogTextGateSpec}` references were repointed in the same
      change — `LogCallSites`, `UntrustedTextSinkGateSpec`, `LogCallScannerSpec` — since the
      class they name no longer exists.
- [x] 7.5 ADR 0004 `Alternatives Considered` gains the three alternatives this change
      deferred, each with the condition that reopens it, so the record outlives the
      archived `design.md` (`crash-consistency.md`, "Referencing"): branded exit types —
      revisit when the sink backstop or rule (c) catches a laundered string at a real
      production site, or when the log call sites are touched wholesale anyway;
      `@RestrictedApi` beside ArchUnit — revisit when `:untrustedtext` takes a compile
      dependency for any other reason, or when the two pinned annotation sets grow more
      than once; `ContainerId` / `ModelId` as value objects — revisit at the next
      breaking bump of `:gnomish-plugin-api`, at a fourth named syntax gate, or at the
      first gate-checked identity found rendered at a sink, noting that three gates
      already exist so the deferral rests on the contract break, not on the count.
      The ADR's "layer two" text (task 4.1) is not restated; each entry cites design
      D1/D2/D11/D12/D13 of this change as provenance, by change name, never by path.
      Verify: three entries present, each with its revisit condition; `grep -n
      "Revisit" docs/adr/0004-logging-policy.md` shows four (the existing Error Prone
      one plus these three).
- [x] 7.6 Factory prose gets its own family (FR4, NFR-S1, design D3, added 2026-09-19).
      The 2026-09-18 review of the implementation counted 67 production mints whose
      argument begins with a string literal: sentences the factory wrote itself, filed
      under whichever capture family sat beside them — `RemoteAttemptDelivery`'s "no
      remote to deliver the attempt commit to" as `SUBPROCESS`, `FilesExistCheckRunner`'s
      empty `details` as `MANIFEST`, `GithubWorkflowRunPoll`'s "GitHub Actions runs query
      failed" as `TRACKER`. The convention was deliberate and unrecorded
      (`JudgeVerdictExtractor`'s javadoc stated it), and it contradicted both D4's
      `:baseref` row and D5's `BranchTipFactsReader` exemption, which refuse exactly this
      in the other direction. `Provenance.FACTORY` + `UntrustedText.factory` is the
      answer, with D3 carrying the full mint rule: the family of the text first wrapped;
      `FACTORY` for what the factory composed; a quote that left its carrier through an
      exit keeps the sentence factory-composed, a raw interpolation does not.
      **62 call sites converted** across `:adapters:git` (21), `:application` (16),
      `:adapters` (12), `:adapters:agent` (7), `:adapters:github` (3),
      `:gnomish-plugin-api:sample` (2) and `:domain` (1), the shared `NO_DETAILS` constants
      among them; 101 capture mints remain, which is what the table describes. The sweep's
      own counter-examples stay in their capture family — `TakeCrashAbort`'s
      `"… : " + crash` (a raw throwable fold), `EgressRefusal.describe()`'s target,
      `Engine`'s `stageName` off a recorded position, and the fixture mints D3's closing
      line already admits. `UntrustedTextExitIdentitySpec`'s exhaustive switch over
      `Provenance.values()` is what made the new constant impossible to add silently.
      Verify: `:untrustedtext:check` and every touched module's `check` green; the
      delta-spec scenario "A factory-composed sentence is not filed under the capture it
      quotes" satisfied.
      **Not in this task:** whether a mint site is *pinned* by a gate. FR4's "no other
      production code SHALL call a mint" and NFR-S1's seeded-violation clause still have
      no enforcement — the tree holds 163 mint calls in 71 files, 101 of them captures — and the choice between an ArchUnit class→family map and a revision of
      FR4/NFR-S1 is deliberately left open; this task only makes the table true again, so
      that whichever is chosen counts the right thing.
