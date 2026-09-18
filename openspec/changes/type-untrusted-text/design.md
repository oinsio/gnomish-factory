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
constructor, `equals`/`hashCode` over the **raw text alone**, and the seven static
mints (`subprocess`, `container`, `agent`, `tracker`, `manifest`,
`branchDocument`, `operator`).

*The seventh family, added at task 6.1.* `OPERATOR` — an argument the operator
handed the process on its command line — is the one constant that is not here
because the text is attacker-influenced: the operator is inside the trust
boundary everywhere else. It exists because exactly one operator value reaches a
*published report* rather than a syntax gate alone: a `--base` that
`RefNameSyntax` has just **refused**, quoted back into the park report so the
human knows what to fix. A ref name is refused precisely for holding whitespace
or a control character, so quoting it raw into a tracker comment is the one
place operator text needs an exit like any other. *Alternative rejected:* drop
the refused value from the report and name only the violated rule — strictly
safer, but `BaseRefResolverSpec` pins "the report names what the human has to
fix", and a report that says "malformed" without saying *what* was malformed
makes the operator re-derive their own input. *Alternative rejected:*
neutralize it in `:baseref` with a direct `TextSafety` call — the honest-debt
shape D5 rejects, and it would put a primitive call in the one module whose
emptiness is a security property.

*Why provenance is outside equality* (revised 2026-09-17, after the
best-practices review; the carrier shipped with it inside at task 1.4, and
task 5.3 takes it back out before the first value crosses a medium). Provenance
is evidence a report may name — the class's own javadoc says "never a policy
input", and all seven families render identically. A value that is not a policy
input has no business deciding identity, and three things break if it does.
The round trip of D3's branch-document row is the concrete one: a cause minted
`SUBPROCESS` is written to `task.json` and read back `BRANCH_DOCUMENT`, so the
NFR-R2 identity this change owes is false by construction, and `testing.md`'s
"invariant specs across a flow" has nothing to assert. The second is quiet:
a carrier in a `Set` or a `Map` key stops deduplicating across media without
failing anything. The third is that moving a mint — a refactor this design
invites family by family — would change comparison results, which a value type
must never do. No industry carrier of this shape puts trust metadata in
equality: MarkupSafe's `Markup` and Go's `template.HTML` compare by content,
Checker Framework's qualifiers are erased, and Trusted Types offers no content
comparison at all. *Alternative rejected:* keeping provenance in equality and
forbidding cross-medium comparison by review — an unenforceable rule against a
silent failure, and it would leave the round-trip spec asserting exits rather
than values. *Rationale:* proposal Q1 resolved as "render": every
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
(`FindingsSanitizer` callers in `:adapters`/`:adapters:github`), and — added at
task 3.3, when `Verdict.CannotVerify` became a carrier — the two `--json`
mappers that write a check's own words into a parser's input, `AttemptMapper`
(`status.json`) and `UsageReportJsonMapper` (`usage.json`), joined at task 5.0
by `AttemptMapper`'s own `status.json` siblings `EscalationMapper` and
`StatusReportJsonMapper` when the escalation report and the task title became
carriers, and by `BoardJsonMapper` — `board --json` is the same machine plane:
the machine plane's
contract is byte-for-byte output, so rendering there would corrupt the document
`ConsoleIO.printMachine` exists to keep verbatim. `TrackerFence`
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
`postNote` (`heartbeat`'s payload is a machine
document, not prose) — passes an argument whose static type is
`UntrustedText` — ArchUnit sees call targets and parameter types but not
argument expression types, so (c) is a *source* scan in the shape of the
retired accessor gate, keyed on the carrier's type name at the argument
(`UntrustedText`-typed local, field or accessor result), with seeded
violations. The `AbortRecord` constructor was in (c)'s list while its `cause`
was a `String`; at task 6.3 the field became the carrier, so the constructor is
a port type rather than a sink and a carrier at its call is the contract being
honoured — (c) already reasons that way about a throwable that declares its
detail as untrusted text. What the marker's text does on its way to a reader is
`TrackerPublicationOwnerSpec`'s question instead (D7). *Rationale:* ArchUnit is already the project's architecture
gate; the annotation keeps the allowlist in the leaf beside the type (the
`@DoNotMutate` shape), so no name list in build logic can drift from the
code. *Alternative rejected:* a custom Error Prone `BugChecker` — exact on
argument types, but it needs a Java project inside the `build-logic`
included build, a second compile per module, and a checker version pinned
to the Error Prone release; the residual precision it buys over (c) is the
`var x = carrier.raw()` case, which (a) already catches at the `raw()`
call. *Alternative rejected:* Checker Framework tainting (proposal NG5). The
annotation has a sibling for the other way text leaves the carrier — parsing a
machine-readable answer rather than rendering prose — in D11; rule (a) is about
`raw()` alone, and stays that narrow because of it.

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
| tracker (designator) | `BaseDesignatorMapping.baseOf` → `BaseDesignator.Single(UntrustedText)` / `Conflict(List<UntrustedText>)` (added at task 6.1). The mint is here, not at the adapters, because the port's `Designator` is a *published contract over open kinds* — most of which a reader routes on rather than publishes — and `base` is the one kind whose refusal is quoted back into a tracker comment. So the carrier starts where the kind stops being open, and the contract takes no sixth breaking field (D8) | `TRACKER` |
| operator | `BaseRefResolver.resolve`'s explicit-argument arm → `UntrustedText.operator(explicit)` for a `--base` `RefNameSyntax` refused (D1's seventh family). The only command-line value that reaches a published report rather than a gate alone | `OPERATOR` |
| manifest | `ConfigError.render()` → `UntrustedText` (the one mint for validation text, D10; the record's fields stay `String`) and `CheckRef.of` → `CheckRef(int, UntrustedText label)`; `PipelineDefinition` stage names stay validated `String` (NG4) | `MANIFEST` |
| branch document | `TaskJsonMapper` / `StateJsonMapper` readers for `Aborted.cause` and escalation DTOs; `BasePin.ref` stays a validated `String` (`RefNameSyntax` at read, from `add-base-ref-resolution`), and `DenialCursor.source` likewise (`ContainerIdSyntax`, D11); `DenialIdentity.source`, restored at `StateDenialMapper:55` and compared one call later, takes the same gate, while its `eventAt` stays ungated — a source-assigned stamp the factory stores and compares and no sink renders (`RecordedDenialMerge` logs counts only), recorded here rather than left as an unexamined survivor | `BRANCH_DOCUMENT` (re-minted: the envelope reaches the reader as a `SUBPROCESS` carrier off `git show`, and the reader mints each field it lifts out — D11) |
| fold | the `getMessage()` folds among the D5 sites, and the one third-party accessor the retired gate named: `GuardDenialLog:136` (`JsonProcessingException.getOriginalMessage()` echoing the malformed guard line) | the provenance of the operation that failed (`SUBPROCESS` for git/docker folds, `CONTAINER` for the guard line) |

Fakes in `:test-fixtures` and the in-memory tracker mint `TRACKER` for
fixture titles — a fixture is a tracker.

**D4 — Carrier table (fields that become `UntrustedText`).**

| Module | Type.field |
|---|---|
| `:domain` | `ExecutionResult.DecisionNeeded.question/options` and `RoundOutcome.NeedsDecision.question/options` — the executor-side port the cut boundary of D6 rendered across, typed at task 5.0 when that rendering was deleted; `AttemptDelivery.Outcome.Undeliverable.reason/details`, typed at the same task and for the same reason (`ExternalPolling` minted it from a `String` until then); `EscalationReport.DecisionNeeded.question/options`, `.CannotVerify.reason/details`, `.CannotExecute.cause`, `.PipelineMismatch.staleStage`; `Verdict.CannotVerify.reason/details`; `PollStatus.CannotVerify.reason/details`; `TaskOutcome.Aborted.cause`; `TaskContext.title/body`; `CheckRef.label`; `StackTraces.render` returns `UntrustedText` (a rendered trace carries every message in the chain) |
| `:baseref` | `BaseDesignator.Single.value` / `Conflict.values`; `DesignatorSelection.Refused.values`; `BaseResolution.Underdetermined.values` — the base a task's author typed into the tracker, carried through the policy so the park report can fence it (task 6.1). The `reason` beside each stays a `String`: it is policy-authored prose whose quoted values entered it through the carrier's `toString()`, i.e. the log exit, so it is inert by construction. This is the one module edge the change adds — `:baseref`'s `allowedProjects` goes from `[]` to `[':untrustedtext']`, which costs NFR-S3 nothing because the leaf is itself dependency-free |
| `:gnomish-plugin-api` | `TaskSnapshot.title/body`; `ReadyTask.title` and `OpenTask.title`, the same tracker title on the feed the board reads (added at task 5.1: `BoardTextRenderer` is a D6 renderer, so its rows cannot be fed a laundered `String`); `AbortRecord.cause` (the github bundle's `GithubStateWrites:128` posts it as a comment) |
| `:sandbox:core` | `CapturedExec.output` |
| `:sandbox:docker` | `DockerResult.stdout/stderr`; `EgressSelfCheckProbes.Probe.output` |
| `:adapters:git` | `GitCommandResult.stdout/stderr`; `RemoteBaseRef.Held.Unanswered.reason`; `BaseRefreshOutcome.Refused.report/.Unavailable.reason`; `ResumeBaseOutcome.Refused/.Unavailable`; `DefaultBranchDiscovery.Undetermined/.Unavailable.reason`; `InBoxGitCommand.Outcome` output |
| `:adapters:agent` | `AgentEvent.InitEvent/AssistantEvent/ResultEvent` text fields; `DecisionFileReader.Decision/Payload` |
| `:adapters:github` | `ParsedMarker.instance/humanText` |
| `:application` | `TakeResult.Delivered.summary`, `.AwaitingHuman.report`, `.Aborted.cause`, `.Revoked.note`, `.Skipped.reason`, `.InfrastructureUnavailable.reason` — each becomes `UntrustedText` where the text embeds a carrier, built by the report builders from typed inputs; `StatusReport.title/body`; `Activity.AwaitingInput.prompt`, `Activity.Executing.currentTool` (`status` package); the board rows `ReadyRow.title`, `WorkingRow.title`, `AwaitingHumanRow.title`, which carry the feed's title to the two board surfaces (task 5.1) |

Lists (`options`, `values`) are `List<UntrustedText>`. Fields already
validated by a parser (`taskId`, ref names, stage names, wire tokens) are
NG4 and stay `String`.

A carrier answers `isBlank()`, `contains(String)` and `length()` to anyone: a
boolean or an int is not text, nothing leaves the carrier, so no gate applies.
That is what the emptiness and substring checks on captured output use
(`GitShowTip.cleanupCommitInHistory`, `WorktreeSalvage`'s dirty-tree check,
`EnvironmentSalvage`, `DockerCli`'s daemon-unreachable classification,
`ContainerMaterializer` / `EgressGuard`'s `already exists` retry) — those sites
need neither an exit nor the parser marker of D11.

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

**The cut boundary renders through the console exit, not the log one.** Cut A
types a capture family whose text then crosses a port cut B types
(`ExecutionResult.DecisionNeeded`, `EscalationReport.CannotVerify`,
`AgentProgressEvent`). Those three sites call `forConsole()` and not `forLog()`,
and task 5.0 deletes the calls when the ports take the carrier. The reason is
UX1: `forLog` caps at the tail and flattens, so a stack trace in a
cannot-verify detail and a multi-line question would reach their human reader
truncated and on one line — a visible regression for the duration of the cut.
`forConsole` keeps line structure and length and is the identity on benign
text, so nothing changes for it, and hostile text renders visibly instead of
raw.

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

**D11 — Machine-readable capture leaves through its own exit,
`@UntrustedParser`.** `stdout` is a carrier like `stderr` (D3, D4), but the two
are read for opposite reasons: across `:adapters:git` and `sandbox/*` there is
not one site that logs or reports captured `stdout` — every one of them
*parses* it (a SHA from `rev-parse`, ref names from `branch --list`, a URL from
`remote get-url`, a path from `rev-parse --git-common-dir`, `docker inspect`
state, `docker ps` lines). Parsing needs the bytes as they arrived; an exit's
rendering would cap and flatten them and break the parse. So the carrier has
four ways out, not two, and each has its own allowlist:

| Way out | Who may call it | What it yields |
|---|---|---|
| `isBlank()`, `contains(String)`, `length()` | anyone | a boolean or an int — no text leaves |
| `forLog()` / `forConsole()` / `forComment()` | anyone | neutralized text for a human plane |
| `forParsing()` | classes annotated `@UntrustedParser` | the bytes as captured, to be turned into a value |
| `raw()` | classes annotated `@UntrustedExit` | the bytes as captured, to be written to a machine medium |

`forParsing()` returns the same string `raw()` does. It is a separate name with
a separate allowlist because it answers a separate question, and the two lists
are read for separate reasons: `@UntrustedExit` answers "who can put untrusted
bytes in front of a reader", `@UntrustedParser` answers "who converts untrusted
bytes into a value". Folding parsers into `@UntrustedExit` — the obvious
shortcut — would take that first list from seven classes to thirty-three and
leave it meaning nothing, which is the whole reason rule (a) exists.

**Membership is by return type, not by intent** ("parse, don't validate"). An
`@UntrustedParser` method turns the text into something that is no longer
untrusted text: a typed value (`Path`, `Optional<String>` holding a commit id,
a boolean, an enum), a `String` that passed a *named* syntax gate
(`RefNameSyntax`, `ModelIdSyntax`), or a carrier re-minted with another
provenance. A method that hands the text back unchanged as a `String` is not a
parser — it is the escape hatch this change exists to close.

What the gate checks mechanically is the part it can: `forParsing()` only
inside an annotated class, and the annotated set pinned, with a seeded
offender that returns the text straight back. The rest of the criterion — a
parser that yields a `String` (a commit id, a ref name, a URL) says in its own
javadoc what makes that string inert, either a named syntax gate it applies or
the fixed shape it checks — is a review obligation, checked by
`/audit-implementation`, on the precedent of `lock-scope.md`: the property is
about what a method does with a value, which no bytecode rule decides. The
pinned set is therefore a map, class to what it converts the text into, so a
reviewer reads the answer beside the name rather than chasing it.

**Pass-through is not parsing.** A file's content read off a branch
(`GitShowTip.readAtTip`, `DeliveredBranchReader.show`, and `BranchTipSource` /
`TipEnvelopeRead.Loaded` behind them) is the document itself, not an answer
about it — so those keep carrying `UntrustedText` down to `TaskJsonMapper` /
`StateJsonMapper`, which are `@UntrustedExit` already and re-mint each field
they lift out as `BRANCH_DOCUMENT` (D3's branch-document row). The envelope's
transport provenance is discarded there on purpose: what matters is that the
value came off a branch another instance wrote.

**Cut A's parser set** (26 classes; the gate pins it exactly as it pins the
exit set, so a class joining it fails the spec until acknowledged):

- `:adapters:git` (16) — `FactoryCloneHardening`, `GitProcessRunner`
  (`--git-common-dir` at `:184`), `HarvestedBoundaryCheck`, `LocalBranchTip`,
  `OriginRemote`, `RemoteBaseRef`, `RemoteBranchTip`, `RemoteDefaultBranch`,
  `ReplicaPairReconciler`, `RoundBoundaryCheck`, `SnapshotTipCheck`,
  `TaskBranchLister`, `TaskWorktreeManager`, `UsageHistoryWalker`,
  `VerifiedTip`, `WorktreeSalvage`
- `:sandbox:docker` (9) — `ContainerMaterializer`, `DeclaredVolumeOverrides`,
  `EgressGuard`, `EgressSelfCheckProbes`, `EnvironmentSelfCheck`,
  `GuardDenialReads`, `GuardSourceIdentity`, `OomAnnotatedExecHandle`,
  `SandboxLifecycleObjectReader`
- `:adapters:agent` (1) — `TokenUsageMapper`, below

`DockerCli` and `CapturedExec` are absent because they are mint sites: what
they read is `:subprocess`'s `Captured`, still a `String` at that point (D3).
`GitShowTip`, `WorktreeSalvage`'s dirty-tree check, `EnvironmentSalvage`,
`DockerCli`'s daemon classification and the `already exists` retries are
absent for the other reason — they ask a question, and D4's queries answer it.

**`TokenUsageMapper` and `ModelIdSyntax`.** `AgentEvent.InitEvent.model` is a
carrier (rule (b)'s cut A vocabulary), and `TokenUsageMapper:89` uses it as the
key of `Map<String, TokenUsage>` — a map that flows into `:domain`'s
`ExecutorUsage`, into `state.json`, and onto the dashboard, where it is
displayed. Typing the key would ripple the carrier through all three for a
value that is a machine identifier; leaving it a bare `String` would launder it
onto a screen. So the model id joins NG4 by being *made* parsed: a
`ModelIdSyntax` gate in `:adapters:agent` accepts
`[A-Za-z0-9._:/\[\]-]{1,128}` — the brackets added at task 3.3, where the
reference dump showed a real id to be `claude-opus-4-8[1m]` — and degrades
anything else to a bounded placeholder, `TokenUsageMapper` is the parser that
applies it **on both paths** (the `modelUsage` keys the agent wrote as well as
the init event's model), and the map key stays `String` with the gate as its
warrant. *Alternative rejected:* `Map<UntrustedText, TokenUsage>` — it carries
a rendering decision into the state file's wire format and the dashboard's
aggregation for no security gain over a syntax gate.

**`GithubMarker` and `InstanceIdSyntax`** (found at task 5.1, the same shape a third
time). D4 listed `ParsedMarker.instance` as a carrier. It cannot be one, for
`DenialCursor.source`'s reason exactly: the marker's instance id is an **identity**, compared
against this process's own configured id (`GithubHeartbeat`), against the claim holder at a
comment boundary (`GithubCommentBoundary`), and carried on as
`ClaimResult.Held.otherInstance` and a `TrackerTaskState.Working` holder — all against plain
`String`s the factory itself holds. Typed, every one of those comparisons would be false and
every heartbeat would read its own claim as someone else's. So the id joins NG4 the way the
model id and the container id did: `GithubMarker` holds it to `InstanceIdSyntax`, which — since
`factory.instance-name` is operator-set and only checked non-blank — admits anything an
operator would plausibly name a machine and refuses only what makes a value dangerous (control
characters, line and paragraph separators, bidi overrides, and the `@`, `#` and backtick a
tracker comment acts on), bounded at 128 characters. A marker whose id fails the gate is
dropped exactly like one whose kind is unreadable: a forged holder is worse than a missing
marker. `ParsedMarker.humanText` — prose, never compared — becomes the carrier D4 asks for.

**`GuardSourceIdentity` and `ContainerIdSyntax`** (found at task 3.2, the same
shape one layer down). `DenialCursor.source` is the guard container's runtime id,
read off `docker inspect`'s stdout — so D4 first listed it as a carrier. It cannot
be one: it is an **identity compared across two media**, minted at the daemon by
the live lease and read back from a branch document by the next, and comparison is
not something the carrier does — FR10 gives it three questions (`isBlank`,
`contains`, `length`) and no way to ask whether two carriers hold the same text
without leaving the type. (Until 2026-09-17 this paragraph argued from the
carrier's equality including provenance; D1 took provenance out of equality, so
that argument is gone and this one — an identity is the wrong *shape* for a
carrier, not merely an awkward one — is what carries the decision.) A typed
`source` would also have to be un-typed again at `GuardDenialReads`, which hands
the same id to `DenialIdentity`, a `String` by D4.
So the id joins NG4 the way the model id does: `GuardSourceIdentity` is the parser,
`ContainerIdSyntax` (`[A-Za-z0-9._:-]{1,128}`) is its named gate, an answer outside
it is refused — which the caller already degrades safely, since an unidentifiable
source commits no cursor — and `DenialCursor.source` stays a `String`. The restored
id passes no reader gate until the branch-document family lands (task 5.3), so the
one site that renders it keeps the log choke point until then. *Alternative
rejected:* a `sameTextAs` question on the carrier — it would widen
the type's contract beyond FR10's three questions and would still leave
`DenialIdentity.source` a `String` compared the same way one call later.
*Alternative deferred, not rejected:* a `ContainerId` / `ModelId` **value object** —
a record over one validated field, parsed in its compact constructor rather than
checked by a static gate that hands a bare `String` back ("parse, don't validate":
the gate's proof is discarded the moment it returns, so nothing downstream can tell
a checked id from an unchecked one, and `DenialIdentity(String source, String
eventAt)` is the transposition hazard `process-invariants.md` names). It is the
better shape and it is a different initiative: it touches the sandbox lease
protocol, `state.json`'s DTOs and the published `:gnomish-plugin-api`, none of
which this change is about. *Revisit when* the published contract next takes a
breaking bump — the cost that defers it is already being paid at that moment —
or when a fourth named syntax gate appears, or at the first case of a
gate-checked identity reaching a sink. The honest note for that reader: three
named gates already exist (`RefNameSyntax`, `ModelIdSyntax`, `ContainerIdSyntax`),
so the rule-of-three bar is met today; what defers the extraction is the contract
break, not the count. Carried to ADR 0004 by task 7.5.

*Alternative rejected: leave `stdout` a `String`.* It is the smaller change and
it is wrong: taint is a property of the source, not of the stream. `git show`'s
stdout is a file another instance wrote, `branch --list`'s is a set of
remote-controlled names, `docker inspect`'s is a container label an image
author chose. Dropping the type there puts that text back outside every gate —
the condition this change exists to remove — and the sites that already carry
it into reports today (`DeliveredBranchReader` → `task.json` values) would have
no carrier to hand on.

**D12 — the accessor-name scan is retargeted at capture, not deleted**
(decided 2026-09-17, FR11). The plan through task 5.2 was to delete
`UntrustedLogTextGateSpec` with its last name, `source`, on the ground that
rules (a), (b) and (c) had taken over its job. They have — for text the factory
already carries. They say nothing about text it never wrapped: an adapter added
next quarter that reads `process.getInputStream()` or an HTTP body straight into
a `String` and logs it compiles green under every rule here, because there is no
carrier for a rule to see. Rule (b) reaches it only if the new accessor happens
to be named in the capture vocabulary — which is the name-keyed reasoning the
type was supposed to replace. Deleting the scan would therefore answer
`implementation.md` item 4 ("Enforcement named") with nothing, at exactly the
boundary this change cares most about.

So the spec survives its names: it stops scanning log calls for accessor names
and starts scanning production sources for **raw capture** — a subprocess stream
read, an HTTP response body read, a document file read — outside the mint owners
of D3's table. Its allowlist is the mint table itself, each entry naming the
family it mints, and the scan asserts it reached every allowlisted file, on the
`BaseHeadDefaultBoundarySpec` precedent. The name it loses (`source`) and the
name it gains (a call shape) are the same mechanism pointed at the layer the
type cannot reach. *Alternative rejected:* delete now and open a follow-up change
for the capture gate — it leaves the window this change is least able to afford
open, and `implementation.md` item 2 calls an unlisted survivor of the old way a
defect of this change, not a follow-up. *Borrowed from:* Trusted Types' rollout,
where the report-only phase runs alongside enforcement rather than instead of it.

**Alternatives recorded, deferred to their own initiative.** Three upgrades the
2026-09-17 best-practices review surfaced sit above this change's cut line. This
section is the analysis; the durable record is **ADR 0004's
`Alternatives Considered`, with a revisit trigger for each** (task 7.5) — a
`design.md` archives with its change and governs nothing afterwards
(`crash-consistency.md`, "Referencing"), so a deferral recorded only here would
disappear at `/opsx:archive`, which is exactly how a considered alternative comes
back as an oversight. The third, `ContainerId` / `ModelId` as value objects, is
argued in D11 and carried to the ADR by the same task.

- **Brand the safe side, not only the unsafe one.** The exits return `String`,
  so a logger, `ConsoleIO` and a tracker write still accept any string, and an
  uncovered path is a review finding rather than a compile error. Trusted Types
  and `google/safe-html-types` instead type the *sink*: `innerHTML` takes
  `TrustedHTML`, and plain text is a `TypeError`. Our equivalent — `SafeLogText`
  / `SafeConsoleText` / `SafeCommentText` returned by the exits and required by
  the facades — would make rule (c) unnecessary and double-rendering
  unrepresentable (MarkupSafe's invariant). It changes every log call site in the
  repository, which is its own change, not a task here. *Revisit when* the sink
  backstop or rule (c) catches a laundered string at a real production site — the
  evidence that `String`-returning exits leak — or when the log call sites are
  being touched wholesale for another reason.
- **`@RestrictedApi` beside ArchUnit.** Error Prone is already wired build-wide
  (`java-conventions.gradle`), and its built-in `@RestrictedApi` checks at the
  *call site*, at compile time, with a mandatory `explanation` and `link` and a
  second allowlist level for legacy callers — strictly more than rule (a)'s
  class-level ArchUnit check, and not the custom checker NG5 rejects. The cost is
  `error_prone_annotations` on the compile classpath of `:untrustedtext`, today a
  JDK-only leaf; whether that leaf takes a dependency is a decision for whoever
  revisits D2, not one to make in a cut-B task. *Revisit when* `:untrustedtext`
  takes a compile dependency for any other reason — the JDK-only objection dies
  with it — or when the `@UntrustedExit` / `@UntrustedParser` pinned sets grow
  more than once, the "revisit if exemptions accumulate" shape ADR 0004 already
  uses for the throwable-convention checker.

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
| `TextSafety.forComment` via `TrackerFence` facade (`:untrustedtext` / `:application`) | fenced comment text | callers of the exit: the builders D7 names (`FreshClaimBaseReport`, `ResumeBaseReport`, `BaseLawReport`, `AbortReportBuilder`, `BranchQuarantineReport`, `EscalationResumeDialog`, the `GuardedPark` report functions), `FinishEffect`, `GithubStateWrites` (abort cause), `DecisionAck`, the two stop-note writers; consumers of the value: the eight park writers (D7) | `FindingsSanitizer.strip(...).replace("@", …)` inside `TrackerFence` — moved; ad-hoc report concatenation in the eight writers — routed through builders; `"aborted: " + record.cause()` in `GithubStateWrites` — routed through the exit | gate rule (c) on every text-carrying `Tracker` method; `TrackerPublicationOwnerSpec` scanning every tracker write site and every `AbortRecord` construction for the *wrong* exit (`LogText`, `FindingsSanitizer`) as well as a raw carrier. The `AbortRecord` constructor left rule (c) at task 6.3, when its `cause` became the carrier: a carrier there is the contract being honoured, not laundering — the same reasoning rule (c) already applies to a throwable that declares its detail as untrusted text |
| `UntrustedText.forParsing()` (`:untrustedtext`) | the captured bytes, for conversion into a value | the 26 `@UntrustedParser` classes D11 lists, by module | direct `raw()` reads from a parser — never introduced; `String`-typed `stdout`/`output` accessors, which let any class parse without declaring it — changed to the carrier | the annotation; gate rule (a2), whose pinned parser set fails on growth and whose seeded offender returns the text unchanged as a `String` |
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
- [`@UntrustedParser` becomes the laundering hatch `@UntrustedExit` was kept
  from being — 26 classes may read the bytes] → membership is checkable, not
  declarative: rule (a2) pins the set as a class-to-conversion map, a seeded
  parser returning the text unchanged fails the spec, and each member's javadoc
  states what makes its output inert (D11) — so the set is reviewable at a
  glance and `/audit-implementation` has a per-class claim to check.
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

Q1 and Q2 of the proposal are resolved (D1, D9). Q3, opened while task 5.3 was
being implemented, is **resolved as D13**; its analysis is kept below because
the two rejected answers are the ones a later reader will reach for again.

**D13 — a transformation that keeps the text inside the carrier is not a way
out** (resolves Q3, decided 2026-09-17). The leaf gains
`UntrustedText cappedTo(int)`: the head+tail truncation with a marker that
`AbortCauseBudget` owns, applied to the raw text and returned as a carrier of
the same provenance. It takes no annotation and widens no allowlist, because
no text leaves the carrier — the same reason FR10's three questions need none.
`AbortCauseBudget.cap` keeps its single ownership of the budget and changes
signature to carrier-in, carrier-out; `AbortHandler` stays the one caller.
*Rationale:* it is MarkupSafe's invariant — a transformation of branded text
returns the brand, which is what makes double-escaping unrepresentable there
and what keeps the exit allowlist at seven classes here. The alternative of
annotating `AbortCauseBudget` `@UntrustedExit` is an unchecked conversion in
`google/safe-html-types`' sense: permitted only inside a self-contained library
whose safety is checkable without reading its callers, which a take-flow helper
in `:application` is not. The alternative of capping the rendered comment at
each tracker write splits one owner into two call sites and adds a declared
sync pair, against `manual-sync-pairs.md`'s preference order. Implemented at
task 5.3, which is the task the question blocked.

**Q3 (resolved by D13) — how a capped cause stays a carrier.** D4 types `AbortRecord.cause` and
`TaskOutcome.Aborted.cause`, but `cap-abort-cause-length` owns an invariant
those two sit inside: every cause byte reaching a tracker write passes
`AbortCauseBudget.cap` first, a head+tail truncation with a marker, applied
once in `AbortHandler` and handed to both write paths. That function needs the
raw text, and rule (b) needs `cause()` to return the carrier — so the cap has
to yield a carrier, which nothing in the type's four ways out does. Three
answers, none yet chosen:

- add a provenance-preserving transformation to the leaf (`cappedTo(int)`
  returning `UntrustedText`) — no text leaves the carrier, so it needs no
  annotation, and `AbortCauseBudget` keeps its single owner;
- carry the full text and cap the *rendered* comment at each tracker write —
  nothing is added to the leaf, but one owner becomes two call sites and a new
  declared sync pair;
- annotate `AbortCauseBudget` `@UntrustedExit` so it may read `raw()` and
  re-mint — the smallest diff, and exactly the widening of the exit allowlist
  D2's "laundering hatch" risk is written against.

Answered by D13: the first option, with the reasons recorded there.
