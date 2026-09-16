# Design: type-untrusted-text

## Context

See proposal.md — Why. Facts that shape the approach (from the 2026-09-11
audit, re-verified 2026-09-16 against `67e907cb`; file:line as of that tree):

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
  the `.gnomish/` loader and `CheckRef.of` deriving labels from `VerifyCheck`.
  `new ConfigError(` has 97 production sites in 28 files across five modules
  (`:domain` rules, `:adapters`, `:adapters:github`, `:gnomish-plugin-api`'s
  `ConnectionProfiles`, `:bootstrap`'s `CheckProviderSeam`), and the SPI
  validator interfaces (`CheckParamsValidator`, `CheckSubsectionValidator`,
  `TrackerSubsectionValidator`) hand third-party code the same constructor;
  the rendered line reaches a park report through `BaseLawReport:40` and the
  console through `PipelineStartup:63`; branch document —
  `TaskJsonMapper:187,225` and the `RecordedOutcome`/escalation DTO readers.
- Carrier fields (~45) and the 38 exception-constructor sites are enumerated
  in the tables below.
- `TrackerFence` (`app/findings`) is JDK-only logic over `FindingsSanitizer`
  and has one consumer (`EscalationResumeDialog:104`); eight components write
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
the exact hole `TakeOutcomeMapper:97` documents. A final class with a private
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
(`FindingsSanitizer` callers in `:adapters`/`:adapters:github`). `TrackerFence`
is not in the set: it is a `String → String` facade over `TextSafety.forComment`
(D7) and never reads `raw()`, so annotating it would widen the allowlist for
nothing. `UntrustedTextGateSpec` (`:bootstrap`, ArchUnit):
(a) `methods().that().areDeclaredInClassesThat().areNotAnnotatedWith(
UntrustedExit).should().notCallMethod(UntrustedText, "raw")`; (b) methods
named in the capture vocabulary (`stderr`, `stdout`, `output`, `sessionId`,
`model`, `question`, `title`, `body`, `instance`, `label`, `message`,
`reason`, `details`, `cause` on the carrier records) declared in production
classes return `UntrustedText` or `List<UntrustedText>` — the vocabulary is
declared per capture family, so the rule runs over exactly the families
already migrated (D9), and a third-party method (`getOriginalMessage()`) is
outside it by construction; (c) no call to
`org.slf4j.Logger.*`, `Throwable.<init>`, `ConsoleIO.print*` or a text-carrying `Tracker`
method — `park`, `finish`, `declineFinished`, `acknowledgeDecision`,
`postNote`, and the `AbortRecord` constructor that feeds `recordAbort`
(the port has no `comment` method; `heartbeat`'s payload is a machine
document, not prose) — passes an argument whose static type is
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
| manifest | `ConfigError.render()` → `UntrustedText` (the one mint for validation text, D10; the record's fields stay `String`) and `CheckRef.of` → `CheckRef(int, UntrustedText label)`; `PipelineDefinition` stage names stay validated `String` (NG4) | `MANIFEST` |
| branch document | `TaskJsonMapper` / `StateJsonMapper` readers for `Aborted.cause`, escalation DTOs, `DenialCursor.source`; `BasePin.ref` stays a validated `String` (`RefNameSyntax` at read, from `add-base-ref-resolution`) | `BRANCH_DOCUMENT` |
| fold | the `getMessage()` folds among the D5 sites, and the one third-party accessor the retired gate named: `GuardDenialLog:136` (`JsonProcessingException.getOriginalMessage()` echoing the malformed guard line) | the provenance of the operation that failed (`SUBPROCESS` for git/docker folds, `CONTAINER` for the guard line) |

Fakes in `:test-fixtures` and the in-memory tracker mint `TRACKER` for
fixture titles — a fixture is a tracker.

**D4 — Carrier table (fields that become `UntrustedText`).**

| Module | Type.field |
|---|---|
| `:domain` | `EscalationReport.DecisionNeeded.question/options`, `.CannotVerify.reason/details`, `.CannotExecute.cause`, `.PipelineMismatch.staleStage`; `Verdict.CannotVerify.reason/details`; `PollStatus.CannotVerify.reason/details`; `TaskOutcome.Aborted.cause`; `TaskContext.title/body`; `CheckRef.label`; `StackTraces.render` returns `UntrustedText` (a rendered trace carries every message in the chain) |
| `:gnomish-plugin-api` | `TaskSnapshot.title/body`; `AbortRecord.cause` (the github bundle's `GithubStateWrites:128` posts it as a comment) |
| `:sandbox:core` | `CapturedExec.output`; `DenialCursor.source` |
| `:sandbox:docker` | `DockerResult.stdout/stderr`; `EgressSelfCheckProbes.Probe.output` |
| `:adapters:git` | `GitCommandResult.stdout/stderr`; `RemoteBaseRef.Held.Unanswered.reason`; `BaseRefreshOutcome.Refused.report/.Unavailable.reason`; `ResumeBaseOutcome.Refused/.Unavailable`; `DefaultBranchDiscovery.Undetermined/.Unavailable.reason`; `InBoxGitCommand.Outcome` output |
| `:adapters:agent` | `AgentEvent.InitEvent/AssistantEvent/ResultEvent` text fields; `DecisionFileReader.Decision/Payload` |
| `:adapters:github` | `ParsedMarker.instance/humanText` |
| `:application` | `TakeResult.Delivered.summary`, `.AwaitingHuman.report`, `.Aborted.cause`, `.Revoked.note`, `.Skipped.reason`, `.InfrastructureUnavailable.reason` — each becomes `UntrustedText` where the text embeds a carrier, built by the report builders from typed inputs; `StatusReport.title/body`; `Activity.AwaitingInput.prompt`, `Activity.Executing.currentTool` (`status` package) |

Lists (`options`, `values`) are `List<UntrustedText>`. Fields already
validated by a parser (`taskId`, ref names, stage names, wire tokens) are
NG4 and stay `String`.

**D5 — Exceptions take the carrier; message = `forLog()`.** The 38 sites:
`GitAttemptPersistence:112,120`, `GitTaskRepository:253,261`,
`WorktreeSalvage:75,84,115,119`, `TaskWorktreeManager:75`,
`DeliveredBranchReader:93`, `FactoryCloneHardening:66,73`,
`ContainerHarvestFetch:73`, `WorktreeResync:56`, `InBoxGitCommand:72`,
`EnvironmentAttemptPersistence:148`, `EnvironmentRoundSnapshot:83`,
`EnvironmentSalvage:132`, `ContainerMaterializer:84,153,170`, `EgressGuard`
(4 sites: 115, 234, 246, 252), `SandboxLifecycleObjectReader:43`, `DockerCli:117`,
`MissingResultEventException:62,72`, `JudgeRoundExecution:106`,
`JudgeCriteriaPreflight:62`, `ShellCommandCheckRunner:151`,
`GitObjectsLawSource:128`, `WorkingTreeLawSource:91`,
`HttpExternalCheckClient:165`, `GithubTransportException:17`,
`BranchTipFactsReader:102`, `GitFreshTaskSupport:68`. Each exception's
constructor takes `UntrustedText` (or an `UntrustedText`-typed cause
detail) and composes its message with `toString()`; `WorktreeResync` and
the docker `IllegalStateException` sites move to `GitResyncFailedException`
/ `DockerCommandFailedException` so the parameter type exists to enforce.
`getMessage()` folds (`e.getMessage()` into a new message) are replaced by
passing the cause and, where a detail is needed, by
`UntrustedText.subprocess(e.getMessage())` at the fold — the message of an
exception built from a carrier is already inert, so the re-mint costs
nothing and keeps provenance. **Third-party accessors are minted in the
`catch`.** The retired gate's list named one method this repository does not
declare, `JsonProcessingException.getOriginalMessage()`; rule (b) cannot type
it and rule (c) cannot see a `String`. Its one site, `GuardDenialLog:136`,
today wraps it in `LogText.forLog` outside any log call — the text is stored
as `GuardDenialDrops.firstReason` and logged later, where the retired gate
never looked. It becomes `UntrustedText.container(e.getOriginalMessage())` at
the catch, `GuardDenialDrops.malformed(UntrustedText)` keeps the carrier, and
its `report` log call renders `forLog()` under rule (c); the `LogText.forLog`
call there is deleted (sweep 7.3). A future third-party accessor follows the
same shape: mint at the catch, in the fold row of D3's table. *Alternative
rejected:* per-site `forLog()` calls with `String` parameters kept — that is the pattern `logging.md`
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
through `forComment()` (D6): `FreshClaimBaseReport`, `ResumeBaseReport`,
`BaseLawReport`, `AbortReportBuilder`, `BranchQuarantineReport`,
`EscalationResumeDialog.renderEscalation`, and the report functions
`TakeEscalationExit` / `TakePauseExit` hand to `GuardedPark`. The writers are
consumers of the exit's *value*, not callers of the exit: none of the eight
calls `forComment()` itself, and `EpochRecordingTracker` is a decorator that
forwards what it was given. Proposal M3 therefore counts the builders. The
port's other text-carrying writes are consumers too, not exemptions: `FinishEffect` (the `TakeFinishReport`
summary), `AbortHandler` → `AbortRecord.cause` (typed, D4; rendered by
`GithubStateWrites` at the comment), `DecisionAck.acknowledgeDecision` (a
human reply read from the tracker and echoed back), and the two stop notes
(`TakeContainerEngineExecution`, `RevocationHandler`, quoting a revocation
reason). `declineFinished` posts a factory-authored constant and carries
nothing. The gate's rule (c) keeps a raw carrier out of every one of them. *Alternative rejected:* wrapping the whole park report in a
fence at `Tracker.park` — the factory-authored instruction lines ("return
the task to work") would land inside the "untrusted machine output" label,
which is a lie to the operator and to the next LLM reader.

**D8 — Published contract 0.7.0 → 0.8.0, the sixth BREAKING move.**
`TaskSnapshot`, `AbortRecord` and five re-exposed domain types change field
types (`ConfigError` and the SPI validator interfaces that return it are
untouched, D10): a pre-1.0
MINOR bump, the four-jar baseline `split-logtext-leaves` left (`domain`,
`untrustedtext`, `operatorevent`, the api itself) regenerated over the same
set, the break named in `build.gradle` (spec: "Surface growth is
additive and re-baselined", new scenario). The sample plugin is updated as
the third-party proof. *Alternative rejected:* keeping `TaskSnapshot` as
`String` and minting in `:application` — then the contract would not say
which fields the tracker controls, and every adapter author would be free
to hand over text the factory later treats as its own.

**D9 — Cut line (proposal Q2).** Group order in tasks.md puts the type,
the gate, the subprocess/container/agent families and the exceptions first
(cut A); tracker/manifest/branch-document families, the report builders,
the fence ownership and the contract bump second (cut B). Rule (b)'s
vocabulary is declared per family (D2): cut A's spec run names the git,
docker, in-box and agent families (`stderr`, `stdout`, `output`, `sessionId`,
`model`, `question`, `options`, and `reason`/`details` on
`Verdict.CannotVerify`); group 5 opens with a task that adds the cut B names
(`title`, `body`, `instance`, `humanText`, `label`, `message`, `cause`,
`prompt`, `currentTool`, `staleStage`, and `reason`/`details` on
`EscalationReport` / `PollStatus`), red on the tree until that cut's families
land. So `:bootstrap:check` is green at the close of each cut with no
allowlist, and proposal M1 is measured at the close of cut B. If cut B
overruns, it becomes `type-untrusted-text-tracker`, sequenced after, with this
design's tables carried over verbatim and the vocabulary extension as its
first task.

**D10 — `ConfigError` stays a `String` record; `render()` is the manifest
mint.** Proposal NG6. The message is a factory-authored template
(`"unknown tracker type '%s'"`, `"missing required field 'stages'"`); the
manifest fragment it interpolates is the only untrusted part, and most
messages carry none. The 97 constructor sites live in `:domain` rules, the
loader, the vendor bundle, `ConnectionProfiles` and `CheckProviderSeam`, and
the SPI validator interfaces let a third-party plugin construct one — so a
typed `message` would either put mints in every rule and in foreign code
(against FR4 and NFR-S1's gate) or force each site to render a fragment by
hand (the honest-debt pattern D5 rejects). Instead `render()` returns
`UntrustedText.manifest(file + ": " + where + ": " + message)`: one mint,
in the record that owns the line, at the point the loader's outcome leaves
the loader. Consumers take the carrier: `BaseLawReport` (`forComment()`,
replacing its `LogText.forLog` call), `PipelineStartup` / `TakeCommandSupport`
/ `TrustedTierStartup` (`forConsole()` / `forLog()` per D6),
`CheckClientConfiguration:105` (`toString()` into its exception message).
Rule (b)'s `message` vocabulary entry applies to the D4 carriers and does
not name `ConfigError`. *Alternative rejected:* typing `message` and
extending the mint table to every rule and validator — 97 mints in five
modules plus every plugin, which is the opposite of "mint once at the
boundary" and leaves NFR-S1's "a new mint outside the table fails" with no
table to check against.

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
| `UntrustedText.forLog/forConsole/forComment` (`:untrustedtext`) | `String`, already neutralized | every log call, console print, exception constructor and report builder in D5/D6 | direct `LogText.forLog(...)` calls at sites that now hold a carrier (the `add-base-ref-resolution` per-field calls included) — deleted; `UntrustedLogTextGateSpec` — its accessor pattern narrowed per family as each lands, the spec deleted at task 5.2 when the pattern is empty (no accessor is ever ungated) | gate rules (a) and (c); `UntrustedTextExitIdentitySpec` (FR2) |
| `TextSafety.forComment` via `TrackerFence` facade (`:untrustedtext` / `:application`) | fenced comment text | callers of the exit: the builders D7 names (`FreshClaimBaseReport`, `ResumeBaseReport`, `BaseLawReport`, `AbortReportBuilder`, `BranchQuarantineReport`, `EscalationResumeDialog`, the `GuardedPark` report functions), `FinishEffect`, `GithubStateWrites` (abort cause), `DecisionAck`, the two stop-note writers; consumers of the value: the eight park writers (D7) | `FindingsSanitizer.strip(...).replace("@", …)` inside `TrackerFence` — moved; ad-hoc report concatenation in the eight writers — routed through builders; `"aborted: " + record.cause()` in `GithubStateWrites` — routed through the exit | gate rule (c) on every text-carrying `Tracker` method and the `AbortRecord` constructor; `TrackerPublicationOwnerSpec` scanning every tracker write site |
| `ConfigError.render()` (`:domain`) | `UntrustedText`, `MANIFEST` | `BaseLawReport`, `PipelineStartup`, `TakeCommandSupport`, `TrustedTierStartup`, `CheckClientConfiguration` (D10) | `LogText.forLog(error.render())` in `BaseLawReport` — deleted; `render()` returning `String` — changed | the return type; rule (b) does not apply (`ConfigError` is not a carrier) |

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
families; the 38 exceptions; sink invariant re-run. Build green, shippable.
Cut B (groups 5–7): tracker/manifest/branch-document families; domain and
contract carriers; report builders and renderers; fence ownership; 0.8.0;
documents. Build green.
Rollback per cut: cut A is additive to types the sink already protects.

## Open Questions

None that change specs or tasks; Q1 and Q2 of the proposal are resolved
(D1, D9).
