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

## 2. The type gate in `:bootstrap`

- [ ] 2.1 TDD (red first): `UntrustedTextGateSpec` rule (a) — ArchUnit: no method in a
      class not annotated `@UntrustedExit` calls `UntrustedText.raw()` (design D2, FR3).
      Verify: red on a seeded caller in a scratch class; green after the annotated set
      of task 2.4 exists.
- [ ] 2.2 TDD (red first): rule (b) — production methods in the capture vocabulary
      return `UntrustedText` / `List<UntrustedText>` (FR7). The vocabulary is a
      per-family table in the spec (design D2, D9); this task declares cut A's
      families only: `stderr`, `stdout`, `output` (git, docker, in-box), `sessionId`,
      `model`, `question`, `options` (agent), `reason`/`details` on
      `Verdict.CannotVerify`. Cut B's names are added by task 5.0, so the spec is
      never red over a family the current cut does not migrate. Verify: red on the
      current tree (every cut A carrier still `String`), green at 3.5 with no
      allowlist.
- [ ] 2.3 TDD (red first): rule (c) — source scan in the retired gate's shape: no
      `UntrustedText`-typed argument (accessor result, local, field) directly in an
      SLF4J call, a `Throwable` constructor, `ConsoleIO.print*`, or a text-carrying
      `Tracker` method — `park`, `finish`, `declineFinished`, `acknowledgeDecision`,
      `postNote` — or the `AbortRecord` constructor (FR7, design D2). Verify: seeded
      violations for all four sink kinds detected, one per tracker method.
- [ ] 2.4 Annotate the machine writers and the funnel entry with `@UntrustedExit`
      (`TaskJsonMapper`, `StateJsonMapper`, `LedgerJsonMapper`, `SnapshotJsonMapper`,
      the `Finding`-building funnel callers — not `TrackerFence`, a `String → String`
      facade that never reads `raw()`, design D2) and pin the annotated set
      in `UntrustedTextGateSpec` (a growth fails until acknowledged). Verify: rule (a)
      green; the pinned set equals the annotated set.
- [ ] 2.5 Copy `UntrustedLogTextGateSpec`'s fluent-form and wrapped-form seeded cases
      into rule (c)'s spec so no detector shape is lost (M1). Keep the gate itself:
      rule (c) sees only carriers, so a `String` accessor has no other gate until its
      family is typed. Narrow its `UNTRUSTED` pattern as families land (3.1–3.3, 5.2)
      and delete the spec with its last name, `label`, at 5.2; a split-out cut B (D9)
      inherits it with `label` alone. Verify: `:bootstrap:test` green; the pattern
      names exactly the accessors of the families not yet typed.

## 3. Cut A families: git, docker, in-box, agent

- [ ] 3.1 `GitCommandResult(stdout, stderr)` → `UntrustedText` minted at
      `GitProcessRunner:230` (after `CredentialScrub`) and at every other
      `new GitCommandResult(` site (including `:133`'s synthetic result); `failureDetail`
      / `cannotVerifyDetail` compose from `toString()`; `RemoteBaseRef.Held.Unanswered`,
      `BaseRefreshOutcome`, `ResumeBaseOutcome`, `DefaultBranchDiscovery` reason/report
      fields typed; `InBoxGitCommand.Outcome` typed (design D3, D4). Verify:
      `:adapters:git` specs green with typed fixtures; rule (b) green for this family;
      `stderr`/`stdout` dropped from the retired gate's pattern (2.5).
- [ ] 3.2 `DockerResult`, `EgressSelfCheckProbes.Probe`, `CapturedExec.output`,
      `DenialCursor.source` typed; mints at `DockerCli` and `CapturedExec:64`. The
      third-party accessor the retired gate named (design D5, D3 fold row):
      `GuardDenialLog:136` mints `UntrustedText.container(e.getOriginalMessage())`
      at the catch, `GuardDenialDrops.malformed(UntrustedText)` keeps the carrier and
      its `report` log call renders `forLog()`; the `LogText.forLog` call at the catch
      is deleted. A `GuardDenialDrops` spec scenario asserts the first malformed reason
      is logged in its `forLog()` form for a hostile line. Verify: `:sandbox:core`,
      `:sandbox:docker` specs green; rule (b) green; rule (c) green on the `report`
      call; `output`, `getOriginalMessage`, `source` dropped from the retired gate's
      pattern (2.5).
- [ ] 3.3 Agent family: `AgentEvent` text fields, `DecisionFileReader.Decision/Payload`,
      `MissingResultEventException(UntrustedText sessionId)`, `JudgeRoundExecution:106`
      and `JudgeVerdictExtractor` carry the carrier into `Verdict.CannotVerify`
      (domain field typed in this task since the agent adapter constructs it). Verify:
      `:adapters:agent` and `:domain` specs green; rule (b) green; `sessionId`/`model`
      dropped from the retired gate's pattern (2.5).
- [ ] 3.4 The 38 exception sites (design D5): constructors take `UntrustedText`; new
      `GitResyncFailedException` and `DockerCommandFailedException` replace the
      `IllegalStateException` folds; `getMessage()` folds re-mint at the fold. Each
      exception's spec asserts the message equals the carrier's `forLog()` (the
      untrusted-text spec scenario "An exception carrying subprocess output renders
      safely"). Verify: every listed site compiles only with the typed parameter (the
      `String` overloads are deleted); rule (c) green for `Throwable` constructors.
- [ ] 3.5 Cut A gates: `./gradlew :untrustedtext:check :bootstrap:check :domain:check
      :sandbox:core:check :sandbox:docker:check :adapters:git:check :adapters:agent:check`
      and the sink invariant spec (NFR-R1). Verify: BUILD SUCCESSFUL, PIT 100%.

## 4. Cut A documents

- [ ] 4.1 `.claude/rules/logging.md`: replace the accessor list and the "honest debt"
      paragraph with the type rule (mint at capture, exit at sink, `raw()` only in
      `@UntrustedExit` classes); ADR 0004: the type is layer two (FR9). Verify: `grep -n
      "honest debt\|stderr()" .claude/rules/logging.md` returns nothing (M4).
- [ ] 4.2 `docs/glossary.md`: *untrusted text* and *provenance* entries under
      Observability, with *Never:* raw string, tainted string; the existing *Log text
      sanitization* entry rewritten — the choke point is now the carrier's exit, with
      `LogText` a facade for `String` callers (`process-invariants.md`: a shifted
      meaning updates the entry). Verify: entries present; terms used in code match.

## 5. Cut B families: tracker, manifest, branch document

- [ ] 5.0 Extend rule (b)'s vocabulary table with cut B's families (design D9):
      `title`, `body` (tracker snapshot, `TaskContext`, `StatusReport`), `instance`,
      `humanText` (`ParsedMarker`), `label` (`CheckRef`), `message`, `cause`
      (`Aborted`, `AbortRecord`, `CannotExecute`), `prompt`, `currentTool`
      (`Activity`), `staleStage`, and `reason`/`details` on `EscalationReport` /
      `PollStatus`. Verify: red on the tree before 5.1 (it names every cut B carrier
      still `String`), green at 6.4; if cut B is split out (D9), this is the first task
      of `type-untrusted-text-tracker`.
- [ ] 5.1 `TaskSnapshot(String id, UntrustedText title, UntrustedText body)`; mints at
      `GithubTaskFetcher:104,170`, `InMemoryTracker:76`, `InMemoryTrackerSeeding:93`,
      `InMemoryTrackerHarness:101`; `ParsedMarker.instance/humanText` typed at
      `GithubMarker:187`; `TaskContext.title/body` and `StatusReport.title/body` typed
      downstream; `:test-fixtures` gains carrier helpers for fixtures (design D3, D4).
      Verify: tracker contract suites green on both adapters; rule (b) green.
- [ ] 5.2 Manifest family: `ConfigError.render()` returns a `MANIFEST` carrier (design
      D10; the record's three `String` fields and its 97 constructor sites are
      untouched); its five consumers take the carrier — `BaseLawReport` (`forComment()`,
      its `LogText.forLog` call deleted), `PipelineStartup`, `TakeCommandSupport`,
      `TrustedTierStartup`, `CheckClientConfiguration`; `CheckRef(int, UntrustedText
      label)`, `EscalationReport.PipelineMismatch.staleStage`,
      `Activity.AwaitingInput.prompt`, `Activity.Executing.currentTool`. Verify:
      `:adapters` loader specs and `:domain` specs green; rule (b) green; a
      `ConfigErrorSpec` scenario asserts `render()` equals the manifest mint of the
      joined line; `label` dropped from the retired gate's pattern and
      `UntrustedLogTextGateSpec` deleted with it (2.5, M1).
- [ ] 5.3 Branch-document family: `TaskJsonMapper` / `StateJsonMapper` readers mint
      `BRANCH_DOCUMENT` for `Aborted.cause`, escalation DTO text and `DenialCursor.
      source`; writers stay raw under `@UntrustedExit`; a round-trip spec asserts a
      pre-change `task.json` (raw strings) reads into carriers with no wire change
      (NFR-R2). Verify: round-trip green; `EscalationReport` and `TaskOutcome.Aborted`
      fields typed; `StackTraces.render` returns a carrier.
- [ ] 5.4 `TakeResult` free-text fields and `TakeResultDescription` typed per D4;
      `TaskSummaryAssembler`, `SlotOutcomeLog`, `DrainReport`, `TakeBatchSummary` call
      `forLog()` explicitly (rule (c)). Verify: serve/take specs green; rule (c) green.

## 6. Reports, renderers, tracker publication

- [ ] 6.1 Report builders take carriers and render through `forComment()`:
      `FreshClaimBaseReport`, `ResumeBaseReport`, `BaseLawReport` — delete the per-field
      `LogText.forLog` calls `add-base-ref-resolution` added; `BaseReportSanitizingSpec`
      is rewritten against carriers and gains the fence/label assertion (design D6).
      Verify: red first (fence absent), then green.
- [ ] 6.2 Renderers: `EscalationResumeDialog.renderEscalation` (`forComment()` for
      the report, `forConsole()` for the console print), `StatusLineFormatter`,
      `StatusTextRenderer`, `BoardTextRenderer` (`forConsole()`), `TakeOutcomeMapper`
      (through the dialog renderer). Verify: renderer specs green; the corpus through
      each renderer shows no ESC/C1 (reuse the sink invariant's corpus).
- [ ] 6.3 The eight park writers — `FreshClaimBaseBinding`, `ResumeLawBinding`,
      `TakeDecisionResume`, `TaskTierLaw`, `EpochRecordingTracker`, `AbortHandler`,
      `GuardedPark`, `TakeQuarantinePark` — receive builder output only. The other
      text-carrying writes are consumers too (design D7): `FinishEffect` renders the
      `TakeFinishReport` summary's carriers through `forComment()`;
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
- [ ] 6.4 Cut B gates: `./gradlew check` at the root, the sink invariant spec, PIT 100%
      per touched module. Verify: BUILD SUCCESSFUL (M5).

## 7. Published contract and closing sweep

- [ ] 7.1 `gnomish-plugin-api` 0.7.0 → 0.8.0, the sixth BREAKING move: header comment
      naming the typed-field break — `TaskSnapshot` and `AbortRecord.cause` — (above the existing 0.6.0 → 0.7.0 entry of
      `split-logtext-leaves`); `updateApiCompatibilityBaseline` regenerated over the
      current four-jar set (`domain`, `untrustedtext`, `operatorevent`, the api); the sample plugin mints `TRACKER`
      carriers for its snapshot and compiles with the api as its only dependency
      (design D8; contract spec scenario "A typed-field break is bumped and re-baselined
      together"). Verify: `japicmpApiGate` green; sample module green.
- [ ] 7.2 `manual-sync-pairs.md`: narrow the `GitAttemptPersistence ↔
      EnvironmentAttemptPersistence` row's invariant to the commit + state-file
      sequence (the message half is now by construction). Verify: row text updated; both
      `Kept in sync with` markers still present and consistent.
- [ ] 7.3 Old-way sweep report (implementation.md): (1) `grep -rn "\.stderr()\|\.stdout()\|
      \.output()" --include=*.java` over production sources — every hit returns
      `UntrustedText` (M2); (2) `grep -rn "LogText.forLog(" --include=*.java` over
      production sources — every hit is inside `:logtext`, a converter, or an exit facade;
      (3) `grep -rn "new TaskSnapshot(\|new GitCommandResult(\|new CapturedExec(\|new
      DockerResult(" --include=*.java` — every hit is in D3's mint table. Record all
      three with their hit lists in the task report. Verify: as stated.
