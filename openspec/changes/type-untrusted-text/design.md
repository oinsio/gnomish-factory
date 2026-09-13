# Design: type-untrusted-text

## Context

See proposal.md — Why. Facts that shape the approach (from the 2026-09-11
audit; file:line as of that tree):

- The two leafs exist (`split-logtext-leaves`): `:untrustedtext` owns the
  table and primitives and is reachable from `:domain`, `:gnomish-plugin-api`,
  `:sandbox:core` and every adapter. The sink backstop exists
  (`harden-untrusted-text-sinks`), so a mistake during migration is safe, not
  silent.
- Capture families and their first factory reader: git —
  `GitProcessRunner:230` (already scrubs credentials there); docker —
  `DockerCli` → `DockerResult:34`; in-box exec — `CapturedExec:64`
  (`:sandbox:core`); agent — `StreamJsonEventMapper` → `AgentEvent` records,
  `DecisionFileReader:78,86,95`; tracker — `GithubTaskFetcher:104,170`,
  `InMemoryTracker*` (3 sites), `GithubMarker:187` → `ParsedMarker`; manifest —
  the `.gnomish/` loader (54 `new ConfigError(` sites in `:adapters`, plus
  `CheckRef.of` deriving labels from `VerifyCheck`); branch document —
  `TaskJsonMapper:125` and the `RecordedOutcome`/escalation DTO readers.
- Carrier fields (~45) and the 33 exception-constructor sites are enumerated
  in the tables below.
- `TrackerFence` (`app/findings`) is JDK-only logic over `FindingsSanitizer`
  and has one consumer (`EscalationResumeDialog:98`); eight components write
  park reports around it.
- No custom Error Prone checker exists; `build-logic` is Groovy-only. ArchUnit
  is used by 11 `:bootstrap` architecture specs.
- Machine media that must carry raw bytes: `task.json`/`state.json` writers
  (`TaskJsonMapper`, `StateJsonMapper`), the ledger/snapshot JSON writers,
  the findings funnel entry (`FindingsSanitizer` callers building a
  `Finding`).

## Goals / Non-Goals

**Goals:**
- Trust status in the type; mint at ≤ 7 capture families; sinks accept only
  exit output (proposal G1–G4).
- Zero runtime behavior change for benign text; hostile text renders as the
  sink backstop already renders it (UX1).

**Non-Goals:**
- A compiler plugin (proposal NG5); secret masking; MDC (sink-covered);
  the findings funnel's own `String` (NG2).

## Decisions

**D1 — `UntrustedText` is a final class, not a record, with `toString()` =
`forLog()`.** A record's canonical `toString()` would print the raw text —
the exact hole `TakeOutcomeMapper:95` had. A final class with a private
constructor, `equals`/`hashCode` over (raw, provenance), and the six static
mints (`subprocess`, `container`, `agent`, `tracker`, `manifest`,
`branchDocument`). *Rationale:* proposal Q1 resolved as "render": every
accidental concatenation becomes safe text rather than a crash in an error
path; the gate (D5) still asks for an explicit exit so intent is visible.
*Alternative rejected:* `toString()` throws — a `catch` block that builds a
message from a carrier would crash the crash handler; and SLF4J's `{}`
formatting calls `toString()` on the appender thread, where a throw is
swallowed into a Logback status message, i.e. a lost record.

**D2 — Raw access by annotation, gate by ArchUnit; no Error Prone
checker.** `@UntrustedExit` (in the leaf, `RUNTIME` retention, target TYPE)
marks the classes allowed to call `raw()`: the leaf's three exit renderers,
`TaskJsonMapper`, `StateJsonMapper`, `LedgerJsonMapper`,
`SnapshotJsonMapper`, the `Finding`-building funnel entry
(`FindingsSanitizer` callers in `:adapters`/`:adapters:github`), and
`TrackerFence`'s facade. `UntrustedTextGateSpec` (`:bootstrap`, ArchUnit):
(a) `methods().that().areDeclaredInClassesThat().areNotAnnotatedWith(
UntrustedExit).should().notCallMethod(UntrustedText, "raw")`; (b) methods
named in the capture vocabulary (`stderr`, `stdout`, `output`, `sessionId`,
`model`, `question`, `title`, `body`, `instance`, `label`, `message`,
`reason`, `details`, `cause` on the carrier records) declared in production
classes return `UntrustedText` or `List<UntrustedText>`; (c) no call to
`org.slf4j.Logger.*`, `Throwable.<init>`, `ConsoleIO.print*` or
`Tracker.park/comment` passes an argument whose static type is
`UntrustedText` — ArchUnit sees call targets and parameter types but not
argument expression types, so (c) is a *source* scan in the shape of the
retired accessor gate, keyed on the carrier's type name at the argument
(`UntrustedText`-typed local, field or accessor result), with seeded
violations. *Rationale:* ArchUnit is already the project's architecture
gate; the annotation keeps the allowlist in the leaf beside the type (the
`@DoNotMutate` shape), so no name list in build logic can drift from the
code. *Alternative rejected:* a custom Error Prone `BugChecker` — exact on
argument types, but it needs a Java project inside the `build-logic`
included build, a second compile per module, and a checker version pinned
to the Error Prone release; the residual precision it buys over (c) is the
`var x = carrier.raw()` case, which (a) already catches at the `raw()`
call. *Alternative rejected:* Checker Framework tainting (proposal NG5).

**D3 — Mint at the first factory reader, not in `:subprocess`.** `Captured`
(`:subprocess`) stays `String`: that module's contract is mechanics, not
policy, and `:gitobjects`'s extraction-readiness depends on it. The mint
table:

| Family | Mint site | Provenance |
|---|---|---|
| git | `GitProcessRunner:230` (after `CredentialScrub`) → `GitCommandResult(…, UntrustedText stdout, UntrustedText stderr, …)` | `SUBPROCESS` |
| docker | `DockerCli` → `DockerResult(…, UntrustedText stdout, UntrustedText stderr, …)` | `SUBPROCESS` |
| in-box exec | `CapturedExec:64` → `CapturedExec(int, UntrustedText output)` | `CONTAINER` |
| agent | `StreamJsonEventMapper` → `AgentEvent.*` fields; `DecisionFileReader:78,86,95` → `Decision(UntrustedText question, List<UntrustedText> options)`; `MissingResultEventException` takes `UntrustedText sessionId` | `AGENT` |
| tracker | `GithubTaskFetcher:104,170`, `InMemoryTracker:76`, `InMemoryTrackerSeeding:93`, `InMemoryTrackerHarness:101` → `TaskSnapshot(String id, UntrustedText title, UntrustedText body)`; `GithubMarker:187` → `ParsedMarker(…, UntrustedText instance, …, UntrustedText humanText, …)` | `TRACKER` |
| manifest | the `.gnomish/` loader's `ConfigError(…, UntrustedText message)` sites and `CheckRef.of` → `CheckRef(int, UntrustedText label)`; `PipelineDefinition` stage names stay validated `String` (NG4) | `MANIFEST` |
| branch document | `TaskJsonMapper` / `StateJsonMapper` readers for `Aborted.cause`, escalation DTOs, `DenialCursor.source`; `BasePin.ref` stays a validated `String` (`RefNameSyntax` at read, from `add-base-ref-resolution`) | `BRANCH_DOCUMENT` |

Fakes in `:test-fixtures` and the in-memory tracker mint `TRACKER` for
fixture titles — a fixture is a tracker.

**D4 — Carrier table (fields that become `UntrustedText`).**

| Module | Type.field |
|---|---|
| `:domain` | `EscalationReport.DecisionNeeded.question/options`, `.CannotVerify.reason/details`, `.CannotExecute.cause`, `.PipelineMismatch.staleStage`; `Verdict.CannotVerify.reason/details`; `PollStatus.CannotVerify.reason/details`; `TaskOutcome.Aborted.cause`; `TaskContext.title/body`; `ConfigError.message`; `CheckRef.label`; `Activity.AwaitingInput.prompt`, `Activity.Executing.currentTool`; `StackTraces.render` returns `UntrustedText` (a rendered trace carries every message in the chain) |
| `:gnomish-plugin-api` | `TaskSnapshot.title/body` |
| `:sandbox:core` | `CapturedExec.output`; `DenialCursor.source` |
| `:sandbox:docker` | `DockerResult.stdout/stderr`; `EgressSelfCheckProbes.Probe.output` |
| `:adapters:git` | `GitCommandResult.stdout/stderr`; `RemoteBaseRef.Held.Unanswered.reason`; `BaseRefreshOutcome.Refused.report/.Unavailable.reason`; `ResumeBaseOutcome.Refused/.Unavailable`; `DefaultBranchDiscovery.Undetermined/.Unavailable.reason`; `InBoxGitCommand.Outcome` output |
| `:adapters:agent` | `AgentEvent.InitEvent/AssistantEvent/ResultEvent` text fields; `DecisionFileReader.Decision/Payload` |
| `:adapters:github` | `ParsedMarker.instance/humanText` |
| `:application` | `TakeResult.Delivered.summary`, `.AwaitingHuman.report`, `.Aborted.cause`, `.Revoked.note`, `.Skipped.reason`, `.InfrastructureUnavailable.reason` — each becomes `UntrustedText` where the text embeds a carrier, built by the report builders from typed inputs; `StatusReport.title/body` |

Lists (`options`, `values`) are `List<UntrustedText>`. Fields already
validated by a parser (`taskId`, ref names, stage names, wire tokens) are
NG4 and stay `String`.

**D5 — Exceptions take the carrier; message = `forLog()`.** The 33 sites:
`GitAttemptPersistence:112,120`, `GitTaskRepository:253,261`,
`WorktreeSalvage:75,84,115,119`, `TaskWorktreeManager:75`,
`DeliveredBranchReader:93`, `FactoryCloneHardening:66,73`,
`ContainerHarvestFetch:73`, `WorktreeResync:56`, `InBoxGitCommand:72`,
`EnvironmentAttemptPersistence:148`, `EnvironmentRoundSnapshot:83`,
`EnvironmentSalvage:132`, `ContainerMaterializer:83,134`, `EgressGuard`
(3 sites), `SandboxLifecycleObjectReader:43`, `DockerCli:117`,
`MissingResultEventException:62,72`, `JudgeRoundExecution:106`,
`JudgeCriteriaPreflight:62`, `ShellCommandCheckRunner:151`,
`GitObjectsLawSource:128`, `WorkingTreeLawSource:91`,
`HttpExternalCheckClient:165`, `GithubTransportException:17`,
`BranchTipFactsReader:94`, `GitFreshTaskSupport:68`. Each exception's
constructor takes `UntrustedText` (or an `UntrustedText`-typed cause
detail) and composes its message with `toString()`; `WorktreeResync` and
the docker `IllegalStateException` sites move to `GitResyncFailedException`
/ `DockerCommandFailedException` so the parameter type exists to enforce.
`getMessage()` folds (`e.getMessage()` into a new message) are replaced by
passing the cause and, where a detail is needed, by
`UntrustedText.subprocess(e.getMessage())` at the fold — the message of an
exception built from a carrier is already inert, so the re-mint costs
nothing and keeps provenance. *Alternative rejected:* per-site `forLog()`
calls with `String` parameters kept — that is the pattern `logging.md`
called honest debt; it leaves the signature as the escape hatch
(`implementation.md`, item 3).

**D6 — Report builders and renderers take carriers; the
`add-base-ref-resolution` per-field calls are removed.** `FreshClaimBaseReport`,
`ResumeBaseReport`, `BaseLawReport`, `EscalationResumeDialog.renderEscalation`,
`StatusLineFormatter`, `StatusTextRenderer`, `BoardTextRenderer` take
`UntrustedText` parameters and call the exit matching their consumer:
`forConsole()` in console renderers, `forComment()` in report text bound for
the tracker, `toString()`/`forLog()` where the text is bound for a log. A
report bound for *both* the tracker and the log (`AwaitingHuman.report`) is
rendered once through `forComment()` — line structure preserved, fenced —
and the sink backstop flattens it for the log; that is the documented
division of labor between layers two and three. *Alternative rejected:* two
renderings of every report — the sink layer exists so this is unnecessary.

**D7 — The comment exit owns tracker publication; `TrackerFence` becomes
a facade.** The fence/mention/label logic moves into the leaf as the
`forComment` renderer (`TextSafety` gains it; JDK-only). `TrackerFence.fence`
delegates. The eight park writers — `FreshClaimBaseBinding`,
`ResumeLawBinding`, `TakeDecisionResume`, `TaskTierLaw`,
`EpochRecordingTracker`, `AbortHandler`, `GuardedPark`, `TakeQuarantinePark`
— receive report text whose untrusted parts were rendered by the builders
through `forComment()` (D6); the gate's rule (c) keeps a raw carrier out of
`Tracker.park`. *Alternative rejected:* wrapping the whole park report in a
fence at `Tracker.park` — the factory-authored instruction lines ("return
the task to work") would land inside the "untrusted machine output" label,
which is a lie to the operator and to the next LLM reader.

**D8 — Published contract 0.6.0 → 0.7.0.** `TaskSnapshot` and six
re-exposed domain types change field types: a pre-1.0 MINOR bump, baseline
regenerated, the break named in `build.gradle` (spec: "Surface growth is
additive and re-baselined", new scenario). The sample plugin is updated as
the third-party proof. *Alternative rejected:* keeping `TaskSnapshot` as
`String` and minting in `:application` — then the contract would not say
which fields the tracker controls, and every adapter author would be free
to hand over text the factory later treats as its own.

**D9 — Cut line (proposal Q2).** Group order in tasks.md puts the type,
the gate, the subprocess/container/agent families and the exceptions first
(cut A); tracker/manifest/branch-document families, the report builders,
the fence ownership and the contract bump second (cut B). If cut B overruns,
it becomes `type-untrusted-text-tracker`, sequenced after, with this
design's tables carried over verbatim.

## Sync surfaces

**Sync surfaces: none added; one facade retired into its owner.**
`TrackerFence`'s fence/mention logic moves into the leaf and the class
becomes a delegate (D7) — one implementation, one owner, no pair. No second
implementation of any rule is introduced: every exit and every mint resolves
to `:untrustedtext`. The `GitAttemptPersistence ↔ EnvironmentAttemptPersistence`
registry row narrows again: both now throw `GitPersistFailedException(…,
UntrustedText)`, so the "message carries output" half is by construction and
the row's invariant is reduced to the commit + state-file sequence.

## Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `UntrustedText` mints (`:untrustedtext`) | `UntrustedText` | the seven capture families in D3's table, by file | `String` fields on every carrier in D4's table — changed to `UntrustedText`; the `String`-typed constructors deleted | the parameter type; gate rule (b) (`UntrustedTextGateSpec`) for accessor return types |
| `UntrustedText.forLog/forConsole/forComment` (`:untrustedtext`) | `String`, already neutralized | every log call, console print, exception constructor and report builder in D5/D6 | direct `LogText.forLog(...)` calls at sites that now hold a carrier (the `add-base-ref-resolution` per-field calls included) — deleted; `UntrustedLogTextGateSpec` — deleted | gate rules (a) and (c); `UntrustedTextExitIdentitySpec` (FR2) |
| `TextSafety.forComment` via `TrackerFence` facade (`:untrustedtext` / `:application`) | fenced comment text | `EscalationResumeDialog` and the eight park writers (D7) | `FindingsSanitizer.strip(...).replace("@", …)` inside `TrackerFence` — moved; ad-hoc report concatenation in the eight writers — routed through builders | gate rule (c) on `Tracker.park/comment`; `TrackerPublicationOwnerSpec` scanning the eight writers |

Identity claims: FR2 (`exit(mint(x)) == primitive(x)`, `toString() ==
forLog()`) — `UntrustedTextExitIdentitySpec` over the corpus, every
provenance, every exit.

## Risks / Trade-offs

- [~45 field-type changes ripple through every constructor call in specs]
  → the fakes in `:test-fixtures` gain `UntrustedText` helpers; the ripple is
  mechanical and the compiler enumerates it; cut A/B bounds it per change.
- [SLF4J accepts `Object`, so a raw carrier at a log call compiles] → D1
  makes it render safely; gate rule (c) makes it visible; the sink backstop
  makes it safe twice.
- [`toString()` = `forLog()` hides double sanitizing in a console path
  (`forLog` output then `forConsole`)] → `forConsole` over already-flattened
  text is a no-op on control characters and keeps the visible `\n`; the
  renderers call `forConsole()` explicitly (D6) so the path is not taken.
- [Machine writers annotated `@UntrustedExit` could be misused as a
  laundering hatch (`mapper.raw()` helper)] → the annotation is class-level
  and the annotated set is enumerated in `UntrustedTextGateSpec`'s own
  expectation (a growth in the set fails the spec until acknowledged).
- [japicmp gate sees a large breaking diff] → named in the build script,
  regenerated in the same commit, the sample plugin proves the migration.
- [Wire-format reader minting `BRANCH_DOCUMENT` for text the factory itself
  wrote from a `SUBPROCESS` carrier loses the finer provenance] → provenance
  is for reports and evidence, not for policy; the branch is a medium other
  instances write, so `BRANCH_DOCUMENT` is the honest answer.

## Migration Plan

Cut A (groups 1–4): leaf type + exits + gate; git/docker/in-box/agent
families; the 33 exceptions; sink invariant re-run. Build green, shippable.
Cut B (groups 5–7): tracker/manifest/branch-document families; domain and
contract carriers; report builders and renderers; fence ownership; 0.7.0;
documents. Build green.
Rollback per cut: cut A is additive to types the sink already protects.

## Open Questions

None that change specs or tasks; Q1 and Q2 of the proposal are resolved
(D1, D9).
