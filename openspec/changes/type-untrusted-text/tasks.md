# Tasks: type-untrusted-text

Sequenced after `split-logtext-leaves`. Groups 1–4 are cut A (design D9):
shippable alone. Groups 5–7 are cut B; if B overruns, it becomes
`type-untrusted-text-tracker` with these groups carried over verbatim. Every
group ends with the module gates green; the sink invariant from
`harden-untrusted-text-sinks` runs at the end of each cut.

## 1. The carrier and its exits in `:untrustedtext`

- [ ] 1.1 TDD (red first): `UntrustedTextSpec` (FR1, design D1): six mints with
      provenance; `raw()`; equality by (raw, provenance); `toString()` byte-equals
      `forLog()`; `excerpt(int)` bounded; null-hostile. Verify: red.
- [ ] 1.2 TDD (red first): `UntrustedTextExitIdentitySpec` (FR2): data-driven over the
      adversarial corpus × every provenance × every exit — `forLog` equals
      `TextSafety.forLog`, `forConsole` equals `TextSafety.forConsole`, `forComment`
      equals `TextSafety.forComment`. Verify: red.
- [ ] 1.3 Move `TrackerFence`'s fence/mention/label logic into `TextSafety.forComment`
      (design D7); TDD the mention/fence corpus first (NFR-S2): `@team` → `@​team`,
      `#123` → `#​123`, fence longer than any run, label present, line breaks kept.
      Verify: red, then green; `TrackerFence` delegates and its spec passes unchanged.
- [ ] 1.4 Implement `UntrustedText` and `@UntrustedExit` (`RUNTIME`, `TYPE`).
      Traceability: `Implements FR1, FR2, FR3 of type-untrusted-text`. Verify: 1.1–1.3
      green; `:untrustedtext:check` green, PIT 100%.

## 2. The type gate in `:bootstrap`

- [ ] 2.1 TDD (red first): `UntrustedTextGateSpec` rule (a) — ArchUnit: no method in a
      class not annotated `@UntrustedExit` calls `UntrustedText.raw()` (design D2, FR3).
      Verify: red on a seeded caller in a scratch class; green after the annotated set
      of task 2.4 exists.
- [ ] 2.2 TDD (red first): rule (b) — production methods in the capture vocabulary
      (`stderr`, `stdout`, `output`, `sessionId`, `model`, `question`, `title`, `body`,
      `instance`, `label`, `message`, `reason`, `details`, `cause` on the D4 carriers)
      return `UntrustedText` / `List<UntrustedText>` (FR7). Verify: red on the current
      tree (it names every carrier still `String`), green at the end of each cut for
      that cut's families.
- [ ] 2.3 TDD (red first): rule (c) — source scan in the retired gate's shape: no
      `UntrustedText`-typed argument (accessor result, local, field) directly in an
      SLF4J call, a `Throwable` constructor, `ConsoleIO.print*`, or `Tracker.park/
      comment` (FR7). Verify: seeded violations for all four sink kinds detected.
- [ ] 2.4 Annotate the machine writers and the funnel entry with `@UntrustedExit`
      (`TaskJsonMapper`, `StateJsonMapper`, `LedgerJsonMapper`, `SnapshotJsonMapper`,
      the `Finding`-building funnel callers, `TrackerFence`) and pin the annotated set
      in `UntrustedTextGateSpec` (a growth fails until acknowledged). Verify: rule (a)
      green; the pinned set equals the annotated set.
- [ ] 2.5 Delete `UntrustedLogTextGateSpec`; move its fluent-form and wrapped-form
      seeded cases into rule (c)'s spec so no detector shape is lost (M1). Verify:
      `:bootstrap:test` green.

## 3. Cut A families: git, docker, in-box, agent

- [ ] 3.1 `GitCommandResult(stdout, stderr)` → `UntrustedText` minted at
      `GitProcessRunner:230` (after `CredentialScrub`) and at every other
      `new GitCommandResult(` site (including `:133`'s synthetic result); `failureDetail`
      / `cannotVerifyDetail` compose from `toString()`; `RemoteBaseRef.Held.Unanswered`,
      `BaseRefreshOutcome`, `ResumeBaseOutcome`, `DefaultBranchDiscovery` reason/report
      fields typed; `InBoxGitCommand.Outcome` typed (design D3, D4). Verify:
      `:adapters:git` specs green with typed fixtures; rule (b) green for this family.
- [ ] 3.2 `DockerResult`, `EgressSelfCheckProbes.Probe`, `CapturedExec.output`,
      `DenialCursor.source` typed; mints at `DockerCli` and `CapturedExec:64`. Verify:
      `:sandbox:core`, `:sandbox:docker` specs green; rule (b) green.
- [ ] 3.3 Agent family: `AgentEvent` text fields, `DecisionFileReader.Decision/Payload`,
      `MissingResultEventException(UntrustedText sessionId)`, `JudgeRoundExecution:106`
      and `JudgeVerdictExtractor` carry the carrier into `Verdict.CannotVerify`
      (domain field typed in this task since the agent adapter constructs it). Verify:
      `:adapters:agent` and `:domain` specs green; rule (b) green.
- [ ] 3.4 The 33 exception sites (design D5): constructors take `UntrustedText`; new
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
      Observability, with *Never:* raw string, tainted string. Verify: entries present;
      terms used in code match.

## 5. Cut B families: tracker, manifest, branch document

- [ ] 5.1 `TaskSnapshot(String id, UntrustedText title, UntrustedText body)`; mints at
      `GithubTaskFetcher:104,170`, `InMemoryTracker:76`, `InMemoryTrackerSeeding:93`,
      `InMemoryTrackerHarness:101`; `ParsedMarker.instance/humanText` typed at
      `GithubMarker:187`; `TaskContext.title/body` and `StatusReport.title/body` typed
      downstream; `:test-fixtures` gains carrier helpers for fixtures (design D3, D4).
      Verify: tracker contract suites green on both adapters; rule (b) green.
- [ ] 5.2 Manifest family: the `.gnomish/` loader's `ConfigError(…, UntrustedText
      message)` (54 sites via one helper), `CheckRef(int, UntrustedText label)`,
      `EscalationReport.PipelineMismatch.staleStage`, `Activity.AwaitingInput.prompt`,
      `Activity.Executing.currentTool`. Verify: `:adapters` loader specs and `:domain`
      specs green; rule (b) green.
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
      `GuardedPark`, `TakeQuarantinePark` — receive builder output only; TDD (red first)
      `TrackerPublicationOwnerSpec` in `:bootstrap` scanning those eight for a raw
      carrier or a `LogText`/`FindingsSanitizer` call at the park (design D7, FR8).
      Verify: red on a seeded raw park, green on the tree (M3).
- [ ] 6.4 Cut B gates: `./gradlew check` at the root, the sink invariant spec, PIT 100%
      per touched module. Verify: BUILD SUCCESSFUL (M5).

## 7. Published contract and closing sweep

- [ ] 7.1 `gnomish-plugin-api` 0.6.0 → 0.7.0: header comment naming the typed-field
      break; `updateApiCompatibilityBaseline`; the sample plugin mints `TRACKER`
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
