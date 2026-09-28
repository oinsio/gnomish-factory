# Design: add-parameter-count-gate

## Context

See `proposal.md` — Why. Three findings from the 2026-09-12 investigation constrain the
design, and each was verified rather than assumed (re-verified 2026-09-27 against the
shipped jars and the current build):

1. **Error Prone 2.50.0 is already on every module's compile path** (`java-conventions.gradle`
   applies `net.ltgt.errorprone` and scopes it to `compileJava`; test code is Groovy and the
   check is disabled for `compileTestJava`). Nothing new needs to enter the build to gate
   production Java.
2. **The stock `TooManyParameters` check cannot be the gate.** Read from the shipped
   artifact: its default limit is 8, it skips any method whose owner is a record and any
   `*Inject*`-annotated member, and it returns early unless the method is public and not an
   override (`ASTHelpers.methodIsPublicAndNotAnOverride`). Against this codebase that is 14
   of 103 measured signatures — the take chain is almost entirely package-private.
3. **The project already has a precedent for a build gate with an on-the-line
   justification**: `checkTestTimeInjection`, a `build-logic` task verified by
   `TestTimeInjectionCheckFunctionalSpec` and satisfied in source by a
   `// real-time-wiring:` marker beside the call. That shape — gate plus local
   justification plus functional test — is what this change reuses, with a different
   implementation underneath.

Two facts about the build's layout also bind the design: `build-logic` is a **single-project
included build** (`build-logic/settings.gradle` declares only `rootProject.name`), whose
`functionalTest` suite is tied to that one project by `gradlePlugin.testSourceSets`; and the
shared catalog carries `error_prone_core` and the plugin, but not `error_prone_check_api` or
`error_prone_test_helpers`.

## Goals / Non-Goals

**Goals:**

- One mechanism with one owner, applied everywhere by the shared convention plugin.
- Exemptions decided by what the code *is* (a record constructor, an override), not by a
  list of files that rots.

**Non-Goals:**

- Catching the *reason* a signature is long. The gate counts; it cannot tell a parameter
  object from a bag. That judgment stays with review and with the criteria the preceding
  changes wrote into the rules.

## Decisions

**D1 — A project Error Prone check, not a source-scanning Gradle task.** The gate is a
`BugChecker` in its own included build (D9), placed on each module's `errorprone`
processor path by `java-conventions.gradle`. *Rationale:* at the agreed strictness the gate
inspects every production declaration in the repository, and the two exemptions are
load-bearing — "is this a record's constructor" and "does this method override something"
are exact questions in the syntax tree and guesses in a regular expression. A false positive
silenced by a marker erodes the gate; a false negative hides the violation the gate exists
for. *Alternative rejected:* a Groovy `SourceTask` scanning text, following
`checkTestTimeInjection` exactly — cheaper and already precedented, but its exemptions would
be textual approximations, and the same approximation applied to ~1500 declarations is where
a gate quietly stops meaning anything. Recorded as the fallback in Risks if the processor-path
wiring proves unworkable.

**D2 — The stock check stays off (NG3).** *Rationale:* the project's own single-owner
principle — one mechanism, one owner. Enabling both would report the public subset twice,
and split the limit across two configurations that can disagree. *Alternative rejected:*
enabling the stock check for the public subset "for free" — free is not the criterion when
the cost is two owners for one rule.

**D3 — Exemptions are exactly two: record constructors and overriding methods.**
*Rationale:* a record *is* the parameter object the rule prescribes, so its canonical,
compact and explicit constructors are the parameter object being declared; an overriding
method does not choose its signature, which is why Checkstyle ships
`ignoreOverriddenMethods` and Sonar checks `isOverriding`. The exemption stops at the
record's constructors: an ordinary method housed in a record chooses its own signature like
any other method, and the 2026-09-27 review found two such methods over the limit
(`AbortHandler.handle`, 8; `BoardModel.build`, 9) that the stock check's owner-is-a-record
test would have hidden. Every other exemption those tools ship — dependency-injection
annotations, a raised constructor threshold, public-API-only scope — is deliberately **not**
taken: they exist to excuse composition roots, and the decision of 2026-09-12 is that
composition roots are fixed, not excused. *Alternatives rejected:* a separate, higher
threshold for constructors (Sonar's `constructorMax`) — it would have let `ManualRunRunner`'s
27 arguments live, the single worst instance the investigation found; and the stock
owner-is-a-record exemption — it turns every record into a place where long methods are
free.

**D4 — The record exemption is honest only with a written criterion beside it.** FR7's rule
text carries the test the preceding changes used: a record earns the exemption when its
component group recurs across many signatures, names a domain concept, and absorbs behavior
— otherwise it is an argument bag wearing the exemption. *Rationale:* the gate cannot make
this call, so the rules must; without it, the exemption becomes the escape hatch that
re-opens everything the three refactors closed. *Alternative rejected:* trusting the count
alone — that is precisely how a count gate rewards bags.

**D5 — Exemption is per declaration, through the gate's own annotation with a mandatory
reason; `@SuppressWarnings` is not honoured.** The check declares
`@BugPattern(suppressionAnnotations = {})` — Error Prone suppresses it by no annotation at
all — and reads `ParameterLimitExemption` itself, on the declaration being judged: a
non-blank reason exempts that declaration, a blank one is reported. `ParameterLimitExemption`
is a `@Target({METHOD, CONSTRUCTOR})`, source-retention annotation with one element,
`String reason()`, shipped as a compile-only artifact beside the check (D9). So "annotated"
and "justified" cannot come apart. *Revised at apply (task 1.3, 2026-09-27):* the first
draft named the annotation in `suppressionAnnotations`. Measured against the compiler
harness, that hands the annotation to Error Prone's suppression, which skips the check on the
annotated element's whole subtree — so the check never sees a blank reason, and the methods
of local and anonymous classes declared inside an exempted method are exempted with it, a
bulk exemption FR4 forbids. Reading the annotation in the check keeps the exemption to one
declaration; the unit spec pins both properties. *Rationale:* Error Prone's default form, `@SuppressWarnings("<CheckName>")`, has no
reason and is honoured on any enclosing element — placed on a class it silences every member,
which is exactly the bulk exemption FR4 forbids; and the codebase already carries 35
`@SuppressWarnings` for other checks, so U3's grep would not be a list of this gate's
exceptions. Kafka's checkstyle suppressions file shows the same failure one level up: a
regex over file names silences a whole file permanently, new violations included. A
per-site annotation dies with the declaration it annotates and is greppable as a complete
list. *Alternatives rejected:* the default `@SuppressWarnings` (above); a central allowlist
file (above); a comment marker like `real-time-wiring:` — invisible to the syntax tree, so
the check could not verify the reason exists.

**D6 — The last sites are fixed in this change, and the shared head of the two agent
round executions becomes one type, held by the two callers** (proposal Q1).
`ExecutorRoundExecution.run` and `JudgeRoundExecution.run` share the head
`FactoryProperties, Clock, AgentProgressListener, AgentRoundResultExtractor`. It becomes one
record, `AgentRoundEquipment` — the equipment every agent round is launched with —
constructed **once** in each caller's constructor: `CliStageExecutor` and `CliJudgeVoter` today
hold the three injected values and a locally built `AgentRoundResultExtractor` as four fields
(`CliStageExecutor.java:53-56`, `CliJudgeVoter.java:55-59`) and re-list them at the `run`
call; each holds the record as one field instead and passes it whole, which is the
"parameter object stops at the last relay" clause of `process-invariants.md`. The public
constructors of `CliStageExecutor` and `CliJudgeVoter` keep their signatures: they are the
composition root's entry and are already under the limit. Both `run` methods drop to five
parameters. *Rationale:* a recurring group across two signatures with a shared meaning is a
parameter object by the same criterion the earlier changes used; holding it where it is
assembled removes the four-field duplication as well. *Alternatives rejected:* trimming each
signature separately — it leaves two copies of the same four-member group; widening the
record to the public constructors' three-member head — it would change public API in every
bootstrap wiring for a group that is already under the limit there.

**D7 — `FeedAutomaton` is split, not grouped, in this change** (proposal Q2; driven by FR6,
G1, NG5). Its constructor (`FeedAutomaton.java:74`, ten parameters) is a composition root
inside the class: from its ten inputs it builds `FeedTracker`, `FeedOutageRetry`,
`FeedResilience`, `FeedCycle` and `FeedViewTracker`, then runs the cycle. The split moves
the building out: the automaton's constructor takes what it keeps as fields plus the two
built collaborators — `SlotLedger, Sleeper, Clock, IdleTiming, int wipLimit, FeedCycle,
FeedViewTracker` — seven parameters, all already fields today. The building moves out of
the class into a package-private instance in `app.serve`, `FeedAssembly`, because `FeedCycle`,
`FeedViewTracker`, `FeedTracker`, `FeedOutageRetry` and `FeedResilience` are package-private
there and stay so. `FeedAssembly` is constructed with the timing equipment — `Sleeper, Clock,
IdleTiming, int wipLimit` — and exposes one method, `feedAutomaton(Tracker, InstanceId,
SlotLedger, SlotRunner, DirtyNotifier, RemoteOutageGate)`, six per-call values: the
fields-not-parameters shape of `process-invariants.md`. `ServeAssembly.feedAutomaton`
(`ServeAssembly.java:122`, the one production caller, seven parameters today and unchanged)
builds the `FeedAssembly` from its own fields and its `trackerConfig` argument and delegates.
The two test callers change at the call site only: `FeedAutomatonFixture`
(`test-fixtures/src/main`) builds through `FeedAssembly` too, so the 40 specs behind it are
untouched, and `RemoteOutageServeEndToEndSpec:150` is a call-site update (NFR-R2). The
`RepeatSuppressor.system()` default inside `FeedOutageRetry`'s construction moves with the
building into `FeedAssembly`, in `src/main`, where `checkTestTimeInjection` does not apply —
the fixture reaches it through `FeedAssembly` rather than spelling it. `FeedAssembly` carries
no decision — it is an assembly object in the sense of `testing.md` — so it gets no spec of
its own and is listed in `:application`'s `pitest { excludedClasses }` under the
arid-wiring category, naming `ServeRuntimeWiringSpec` and `FeedAutomatonOutageIntegrationSpec`
(both in `:bootstrap`, where the root wiring they assemble lives) as the suites that drive it. *Rationale:* the
gate cannot switch on while this constructor stands at ten; a parameter object over the ten
would be the argument bag this family of changes refuses (`collapse-composition-roots` D5
recorded the finding for exactly that reason); and depending on a follow-up would leave the
loop open for a fourth change while new violations can still appear. What the split is not:
a redesign of the feed (NG6) — `step`, `drain`, the states and the view are untouched.
*Alternative rejected:* a follow-up change `split-feed-automaton-composition`, as
`collapse-composition-roots` named it — cleaner scope on paper, but the gate would wait on
it, and the split is three lines of construction moved to the place that already starts
them. *Revised at apply (task 2.5):* `FeedAssembly` is `public`, not
package-private — `ServeAssembly` lives in `app`, one package up, and cannot reach a
package-private type in `app.serve`; the single-owner property holds through `FeedAutomaton`'s
package-private constructor instead, which only `app.serve` can call. What is borrowed from it: the split is its own task with its own behavior check
(the serve wiring spec and the outage end-to-end spec stay green unedited), not a line
inside "bring under the limit".

**D8 — The two record-housed methods come under the limit by the transformations their
callers already suggest** (driven by FR2, FR6; revised 2026-09-27 at apply, after the
`/architect` review of task 2.6). `AbortHandler.handle:126` (8) has two production callers,
`TakeCrashAbort.java:82` and `TakeOutcomeDispatch.java:72`, and both spell the same message chain:
`abortFuse.handler().handle(…, abortFuse.threshold(), …)` — the fuse is taken apart to hand its own
threshold back to its own handler. The fix is two moves, not a re-homing of constants:

1. **The call moves onto the fuse.** `AbortFuse` gains `handle(TaskRef, TaskState, UntrustedText,
   AbortFacts, InstanceId, AbortTrigger)` — six parameters — and is the only entry the callers
   use; it passes its own `threshold` to `AbortHandler.handle`, which becomes package-private at
   seven (`…, int threshold, InstanceId, AbortTrigger`). The protocol stays in `AbortHandler`;
   the fuse relays (Fowler: Move Method against feature envy; Preserve Whole Object — the fuse's
   own javadoc already says handler and threshold "are never used apart").
2. **The trigger becomes one value.** `record AbortTrigger(RecoveryCause category, @Nullable
   Throwable crash)` in `app.take`, with two factories so no caller builds the nullable by hand:
   `engineAborted(RecoveryCause)` (an engine `Aborted` outcome, no throwable) and
   `crashed(RecoveryCause, Throwable)` (an uncaught take-run exception). The two facts were only
   meaningful together — the log site branches on `crash == null` and always renders `category`.

The three `handle` overloads on `AbortHandler` collapse to the one seven-parameter form; the
convenience overloads (default category `INSTANCE_CRASH`, no crash) become the fuse's or the
trigger's concern. `AbortHandlerSpec` and `AbortCauseCapWiringSpec` live in `app.take` and keep
calling `AbortHandler.handle` directly — call-site edits only (two trailing arguments → one
trigger), no expectation changes (NFR-R2).

*Risk named:* `AbortFuse` is a record, and `handle` is an explicit method inside it — the shape
PIT 1.25.7 has crashed on (hcoles/pitest#1285: `TaskJsonDto`'s withers, the former
`GithubMarkerJson`'s behaviour methods). The method carries no decision, so if the minion reports
RUN_ERROR it is marked `@DoNotMutate` under `testing.md`'s JVMTI reason, with
`TakeCrashAbortSpec`/`TakeOutcomeDispatch` specs named as the covering suites; the PIT bump that
removes the reason is a separate change (proposal, Impact). *Measured at apply (task 2.6):* `AbortFuse.handle`
mutated cleanly; the RUN_ERROR landed on the null-return mutation of `AbortTrigger.engineAborted`
— the same record-method shape — so that one factory carries the `@DoNotMutate`, naming
`AbortHandlerSpec`, `AbortCauseCapWiringSpec` and the `TakeOutcomeDispatch` specs; `crashed`
stays in scope. *Alternative rejected — D8's first
text:* fold `threshold` and `instanceId` into `AbortHandler`'s components. The threshold would
then live in two records (`AbortFuse` and `AbortHandler`) with nothing keeping them equal — an
undeclared pair under `manual-sync-pairs.md` — and `instanceId` would have to be threaded into
`SlotWiringFactory` and the eleven test constructions of `AbortHandler` for no gain in meaning.
*Alternative rejected:* keeping the stock owner-is-a-record exemption so the method needs no
change — D3 says why.

`BoardModel.build:130` (9) has one production caller (`BoardComposition.java:53`) and hands
`base, cap, now, openFrontCount, wipLimit` straight to `EligibilityPolicy.resolve`, which takes the
same five beside the task. Four of them become `record EligibilityInputs(Duration base, Duration
cap, int openFrontCount, int wipLimit)` in `board`, taken by both `build` (five parameters) and
`resolve` (three: the task, the record, the instant). It is the group the feed evaluates a ready
task against — the recurring-across-two-signatures criterion of D4 — not a bag. The fifth, `now`,
stays out: every caller passed the observation instant `generatedAt` for it, so `build` hands
`generatedAt` to `resolve` itself and the board cannot report one moment while judging backoff at
another. *Alternative rejected:* `now` as a record component, which kept two `Instant`s that no
caller ever set apart and that nothing kept equal. The four-parameter `build` overload
is untouched. Test callers of the nine-parameter form — `BoardReferenceFixture`,
`BoardModelEligibilitySpec`, `BoardJsonMapperSpec` — are call-site updates (NFR-R2).

**D9 — The check and its annotation live in a second included build, `build-checks`.**
`build-checks/` has its own `settings.gradle` (importing the shared catalog by file, as
`build-logic/settings.gradle` does), two Java projects — `parameter-count-check` (the
`BugChecker`, compiled against `error_prone_check_api`) and `parameter-count-annotations`
(`ParameterLimitExemption`, no dependencies) — group `com.github.oinsio.build`, dependency
locking on, and is composed by `includeBuild 'build-checks'` in the root `settings.gradle`.
`java-conventions.gradle` then declares `errorprone 'com.github.oinsio.build:parameter-count-check'`
and `compileOnly 'com.github.oinsio.build:parameter-count-annotations'`; the coordinates are
substituted by the included build. *Rationale:* `build-logic` is a single-project build of
Groovy convention plugins whose functional-test wiring covers that one project; putting Java
checks inside it means either turning it into a multi-project build (and re-plumbing
`gradlePlugin.testSourceSets`) or compiling checks as Groovy. A sibling included build keeps
each build one thing and gives the check its own unit-test suite. Neither `build-checks`
project applies `java-conventions` — it would place the check on its own processor path —
and they are the one exemption in the single-owner table below. *Alternatives rejected:* a
subproject of the main build (`:build-checks`) — every main-build module applies
`java-conventions`, so the check module would either apply it (a cycle) or be the first
main-build module outside the conventions, which the layering gate would have to special-case;
a multi-project `build-logic` (above). *Found at apply (task 3.3, 2026-09-27):* the layering
gate (`layering-conventions.gradle`, `verifyModuleLayering`) collected every
`ProjectComponentIdentifier` on a module's compile classpath, and a project substituted from an
included build is one — so the first full `check` under the gate reported every module
"reaching `:parameter-count-annotations`". The gate now counts only projects of the module's
own build (`ProjectComponentIdentifier.getBuild()` equal to the root component's): its universe
is the main build's module tree, the one the FR2 direction of `split-into-modules` is stated
over, and a compile-time artifact of `build-checks` is not a layer of the factory. Listing the
annotation in every module's `allowedProjects` was rejected — thirteen files saying the same
thing about a project that carries no factory code. Two whole-tree gates in `:bootstrap` had
the mirror blind spot — `GitTransferBoundarySpec` read `includeBuild 'build-checks'` as one
source root where the walk found two, and `ModuleBuildFileSpec` asked the `build-checks` build
files for a convention id — and were taught the second included build the way they knew the
first: an included build contributes its own `include` entries, and the two included builds
are excluded from the convention scan together. Root `check` also gained the edge to
`:parameter-count-check:check`, the `build-logic` precedent (FR9 of
add-functional-api-gate-test): without it the unit spec of D10 ran only by hand.

**D10 — Verification is two-layered: semantics on Error Prone's harness, wiring on TestKit**
(NFR-R1). The seven semantic cases (NFR-R1) are a Spock spec in `parameter-count-check` driving
`CompilationTestHelper` from `error_prone_test_helpers` — the harness every Error Prone check
is tested with, in-process, no Gradle. The one wiring case is a scenario in `build-logic`'s
existing `functionalTest` suite, following `TestTimeInjectionCheckFunctionalSpec`'s shape:
a miniature project applying `java-conventions` fails `compileJava` on an eight-parameter
method. The miniature project's settings `includeBuild` the real `build-checks` directory
(passed as a system property, as `gnomish.gradleUserHome` is today), so the offline TestKit
build resolves the check without a repository. *Rationale:* one test that exercised both
layers at once would not say which layer broke, and it could not exist before the wiring
question (D9, task 1.5) was settled. *Alternative rejected:* the miniature project alone, as
the first draft of NFR-R1 had it — the semantic cases would each pay a Gradle launch to
answer a question the compiler harness answers in milliseconds.

**D11 — The container environment builders become instances that hold their equipment, and
three groups become values** (driven by FR6; decided 2026-09-27 at apply, task 2.3, after the
`/architect` review). `ContainerMaterializer` and `ContainerEnvironmentBuilder` were extracted from
`ContainerTaskExecutionEnvironment` and `ContainerEnvironments` "for file size" as static recipes,
so each re-lists its origin's fields as parameters (10 and 9–10). That is the fields-into-parameters
failure `process-invariants.md` names; the correction it prescribes is the one taken here: each
becomes an instance constructed once with the collaborators it uses (Fowler, Replace Function
with Command), and its methods take only the per-call job. Three values join, each passing the
delete-one test (remove one component — do the others still name a thing?):

- `TaskContainerSettings(String image, String runtime, ResourceLimits limits, boolean
  enforceDiskQuota)` — the operator configuration one task container is created with; it absorbs
  `requireImage` (the non-blank check moves from the environment's constructor into the record's
  compact constructor, so an unset `factory.sandbox.image` is refused where the value is built).
  It is read off `SandboxProperties` once, in the builder. *Revised at apply (task 2.3):* the
  image check is an explicit canonical constructor (the parameter is `@Nullable`, as
  `SandboxProperties.image()` is, while the component is non-null — a compact constructor cannot
  express that under NullAway), and the record is built in `build(key)`, not in the builder's
  constructor, so an unset image is refused at the first environment build, where it was refused
  before, rather than at seam construction, which several bootstrap fixtures reach with a
  null-image `SandboxProperties`.
- `BoxGitLink(Path sourceClone, ContainerHarvest harvester)` — the factory's local clone the box is
  seeded from and the fetch that brings the box's commits back into it. It is a facade under ADR
  0010, so it earns a method: `harvest(String container, String branch)` delegates to the
  harvester, and `ContainerTaskExecutionEnvironment.harvest` calls that rather than reading the
  port back through an accessor. `sourceClone()` stays an accessor: the seed helper needs the
  path as a mount argument.
- `BoxTiming(Clock clock, Sleeper sleeper, Duration dockerCommandTimeout)` — the timing equipment
  every box operation runs on: the clock that stamps exec starts, the pause the self-check waits
  with, and the deadline every `docker` management command is bounded by. All three are about
  time; `guardConfigRoot` (a host directory) is deliberately **not** in it — it stays a parameter
  of its own, because "the directory guard config renders under" fails the delete-one test
  against a clock.

The existing `ObjectOwnership` replaces the `OwnershipMode mode, String projectId` pair at every
site that still spelled it. The resulting signatures, all at or under seven:

| Signature                                      | Today                            | After                                                                                                                                                                                                                                              |
|------------------------------------------------|----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ContainerEnvironments.forTask` (public)       | 11                               | 7: `String baseKey, BoxGitLink, SandboxProperties, BoxTiming, ChildEnvAllowlist, Path guardConfigRoot, ObjectOwnership` — builds `new DockerCli(timing.dockerCommandTimeout())` and the builder                                                    |
| `ContainerEnvironments` ctor (package-private) | 11                               | 3: `DockerCli, String baseKey, ContainerEnvironmentBuilder` — `ownershipMode()` and `scrubsCredential()` delegate to the builder, which holds the ownership and the allowlist                                                                      |
| `ContainerEnvironmentBuilder`                  | static `build`, 10               | instance, ctor 7: `DockerCli, BoxGitLink, SandboxProperties, BoxTiming, ChildEnvAllowlist, Path guardConfigRoot, ObjectOwnership`; `build(String key)`                                                                                             |
| `ContainerTaskExecutionEnvironment` ctor       | 11                               | 7: `DockerCli, String key, BoxGitLink, TaskContainerSettings, Clock, ChildEnvAllowlist, ObjectOwnership`                                                                                                                                           |
| `ContainerMaterializer`                        | static `reattach` 9, `create` 10 | instance, ctor 5: `DockerCli, String key, Path sourceClone, TaskContainerSettings, ObjectOwnership`; `reattach(String name, DockerResult inspect, String branch, @Nullable String commitPin)`, `create(String branch, @Nullable String commitPin)` |

`EgressGuard` (6) and `EnvironmentSelfCheck` (7) are unchanged. The builder is a relay for the
timing and the link — it takes each whole and hands the members on; `ContainerTaskExecutionEnvironment`
and `ContainerMaterializer` are leaves and declare exactly the members they use
(`process-invariants.md`, "the parameter object stops at the last relay"). The two instances carry
no decision of their own — `ContainerEnvironmentBuilder.build` constructs, `ContainerMaterializer`
keeps the branching it has today (a reattach's `running` check, the network-exists tolerance).
Both stay in the mutation scope. *Revised after the 2026-09-28 audit:* the builder was first
listed in `pitest { excludedClasses }` as an assembly object, but it also holds the allowlist
probe `ContainerEnvironments.scrubsCredential` delegates to — a computed value, which the
arid-wiring bar of `testing.md` rules out — so the exclusion was removed; its four mutants are
killed by `ContainerEnvironmentsSeamSpec` with no spec added. *Alternative rejected:* keep the statics and group
the parameters into records only — the same nine values in three bags, still re-supplied at every
call, which is the shape the rule forbids by name. *Alternative rejected:* a single
`BoxEquipment` over everything the environments take — the argument bag this family of changes
refuses (D7's rationale).

**D12 — `GithubMarkerJson` splits into a data record and its codec; `mapAndValidate` takes the
parsed tree whole** (driven by FR6; decided 2026-09-27 at apply, task 2.4). `GithubMarkerJson` is a
final class with an eight-parameter `@JsonCreator` constructor because, when it was written, two
behaviour methods inside a record (`serialize`, `identity`) crashed PIT's minion
(hcoles/pitest#1285). The crash is on explicit methods inside a record, not on a record as such:
`TaskJsonDto` in `adapters/git` is a fourteen-component Jackson record with `@Nullable` components,
mutates cleanly under PIT 1.25.7, and carries `@DoNotMutate` on its withers alone. So the data
moves into `record GithubMarkerFields(kind, instance, at, version, reason, task, intent, epoch)` —
components only, `@JsonInclude(NON_NULL)` and `@JsonPropertyOrder` on the record, `@JsonProperty`
per component, no `@JsonCreator` (Jackson binds a record's canonical constructor since 2.12) — and
`GithubMarkerJson` keeps `serialize`, `deserialize` and `identity` as the codec over it. The
record's constructor is exempt by FR2; the codec's methods stay in the mutation gate; the class
javadoc that called the record shape unfixable is replaced by a pointer to the `TaskJsonDto`
precedent. `GithubMarker.render:134` and `parse:167` are the only callers. *Alternative rejected:*
a Jackson builder (`@JsonDeserialize(builder = …)`) — idiomatic for types with many optional
fields, and for an eight-field wire marker it adds a mutable second type for nothing.

`PipelineModelBuilder.mapAndValidate:50` (8) takes `config, pipeline, stages` — the three
components of `ParsedTree`, which its one caller (`PipelineLoader.java:212`) has already built. It
takes the `ParsedTree` instead, landing at six; `ParsedTree` is an existing record, so no new type
is introduced.

**Sync surfaces.** The change touches no pair declared in `manual-sync-pairs.md` — verified
2026-09-27 by grep against all thirteen sites; the only marker in `adapters/agent/src/main`
is `HostRoundEnvironmentSource`'s, which names a different pair. It does, however, surface a
**candidate undeclared pair**: `ExecutorRoundExecution` and `JudgeRoundExecution` are two
implementations of "run one agent round", sharing the head D6 unifies, a launch sequence
and a failure-wrapping rule, with no `Kept in sync with` marker at either end. Decision, by
the preference order in `manual-sync-pairs.md`: **declare the pair** in this change — both
ends get the marker naming `AgentRoundEquipment` and the launch/failure invariant — and do
**not** extract a shared abstraction. No registry row is added: the registry lists pairs
that predate the rule or share no classpath, and these two are one package apart, so each
`{@link}` resolves. *Rationale:* the rule's preference order puts the abstraction first, but
its own rule of three has not been reached (two implementations), and the two differ in their
result extraction and verdict handling; forcing one abstraction now would merge an executor's
result contract with a judge's verdict contract. *Alternative rejected:* leaving it
undeclared — that is an audit finding by the rule, and this change is already at both ends.
If a third round kind appears, the abstraction becomes mandatory. The `FeedAutomaton` split
(D7) adds no second implementation of anything: construction moves, it is not duplicated.

**Single-owner mechanisms.**

| Owner                                                     | Value (type)                                                                                 | Consumers                                                                                                                                                             | Old way removed                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Enforced by                                                                                                                                                                                                  |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| the project Error Prone check (`parameter-count-check`)   | compile-time diagnostic                                                                      | every module's `compileJava`, wired once in `java-conventions.gradle` (FR5)                                                                                           | the unenforced prose rule in `process-invariants.md`, and the possibility of a per-module opt-out: no module declares or configures the check, so none can disable it. Exemptions by construction: record constructors and overrides (D3). **Exemption by layout:** the two `build-checks` projects (D9), which cannot apply the convention that would put the check on their own processor path; they carry no production code of the factory                                  | the shared convention plugin — every module of the main build applies `java-conventions` (verified 2026-09-27: no `build.gradle` without it or `library-conventions`); the TestKit wiring case (D10, NFR-R1) |
| `ParameterLimitExemption` (`parameter-count-annotations`) | the only exemption token, with a non-blank `reason`                                          | any exempted declaration (none at switch-on, M1)                                                                                                                      | `@SuppressWarnings("<CheckName>")` — the check's `suppressionAnnotations` is empty, so the default form is inert for it, and the check reads its own annotation per declaration (D5)                                                                                                                                                                                                                                                                                            | the check itself: a blank reason is reported; `@Target` refuses the annotation on a type; the unit spec's two negative cases                                                                                 |
| `AgentRoundEquipment` (D6)                                | one record over `FactoryProperties, Clock, AgentProgressListener, AgentRoundResultExtractor` | built in the constructors of `CliStageExecutor` and `CliJudgeVoter` (one field each); taken whole by `ExecutorRoundExecution.run:49` and `JudgeRoundExecution.run:48` | the four separate fields in each caller and the four hand-listed parameters at both `run` sites; sweep: `grep -rn "AgentRoundResultExtractor resultExtractor" adapters/agent/src/main` must show only the record's own component (today: `CliStageExecutor.java:56`, `CliJudgeVoter.java:59`, `ExecutorRoundExecution.java:53`, `JudgeRoundExecution.java:52`). The three-member head in the two public constructor families stays: it is API, under the limit, and named in D6 | the parameter type of both `run` methods                                                                                                                                                                     |
| `AbortFuse.handle` (D8)                                   | the one entry into the infrastructure-abort protocol from a take run                         | `TakeCrashAbort.onCrash:82`, `TakeOutcomeDispatch.dispatch:72`                                                                                                        | the message chain `abortFuse.handler().handle(…, abortFuse.threshold(), …)` at both sites; sweep: `grep -rn "handler().handle(\|abortFuse.threshold()" application/src/main bootstrap/src/main` must be empty                                                                                                                                                                                                                                                                   | `AbortHandler.handle` is package-private, so only `app.take` can reach it; `AbortTrigger`'s two factories are the only constructions of the trigger                                                          |
| `ContainerEnvironmentBuilder` (D11)                       | one `SelfCheckedEnvironment` per role key, built from equipment held as fields               | `ContainerEnvironments.roundEnvironment/judgeEnvironment/verificationEnvironment`                                                                                     | the static `build` with ten parameters and the `ContainerEnvironments` constructor that re-listed them; sweep: `grep -rn "new ContainerTaskExecutionEnvironment(" sandbox adapters bootstrap test-fixtures` shows the builder plus the unit specs that construct the environment directly                                                                                                                                                                                       | the `ContainerEnvironments` constructor takes the builder as a value — it can no longer build an environment itself                                                                                          |
| `TaskContainerSettings` (D11)                             | the validated image plus runtime, limits and quota flag                                      | `ContainerTaskExecutionEnvironment`, `ContainerMaterializer`, `ContainerRunSpec`'s construction                                                                       | `requireImage` in the environment's constructor and the four separate parameters at each site; sweep: `grep -rn "requireImage\|factory.sandbox.image must be set" sandbox/docker/src/main` must show only the record                                                                                                                                                                                                                                                            | the compact constructor: a blank image cannot become a settings value                                                                                                                                        |
| `FeedAssembly` (D7)                                       | `FeedAutomaton`, built — holding `Sleeper, Clock, IdleTiming, int wipLimit` as fields        | `ServeAssembly.feedAutomaton:122` (production), `FeedAutomatonFixture` (`test-fixtures`), `RemoteOutageServeEndToEndSpec:150`                                         | the five `new …(` constructions inside `FeedAutomaton`'s constructor, deleted with the constructor's ten-parameter form; sweep: `grep -rn "new FeedCycle(\|new FeedViewTracker(" application test-fixtures` must show only `FeedAssembly`                                                                                                                                                                                                                                       | the automaton's constructor takes `FeedCycle` and `FeedViewTracker` as values — it can no longer build them; `ServeRuntimeWiringSpec` stays green unedited                                                   |

No row claims two values are one by construction, so no identity spec is required.

## Risks / Trade-offs

- **The processor-path wiring may not work cleanly from an included build.** → The check
  is built and its unit spec run before any module depends on it; task 1.5 proves the
  substitution against one consuming module before `java-conventions` is touched. If
  substitution proves unworkable, the fallback is D1's rejected alternative (a
  source-scanning task following `checkTestTimeInjection`), taken with its approximation
  limits recorded rather than silently accepted. The task list makes this an explicit
  decision point, not a discovery during rollout.
- **A strict gate with only two exemptions will eventually block legitimate work.** →
  That is what D5's per-site annotation is for; if exemptions accumulate past a handful,
  the honest response is to revisit the limit in a new change, not to grow an allowlist.
- **The record exemption can be abused to smuggle a bag.** → D4 writes the criterion into
  the rules; review owns it, and the gate never claimed to. Narrowing it to constructors
  (D3) closes the other door: a long method cannot hide in a record.
- **The `FeedAutomaton` split touches a concurrent component.** → It moves construction
  only; the automaton's fields, `step` and `drain` are unchanged, and the outage end-to-end
  spec and the serve wiring spec pin the behavior without edits (D7, NFR-R2).
- **Gate adds compile-time work.** → NFR-C1 measures it before and after with a stated
  threshold; the check is one pass over declarations in a compiler that already walks them.

## Migration Plan

The gate is switched on in the same change that removes the last thirteen violations, in
this order: build the check, its annotation and their unit spec; prove the wiring against one
module; fix the thirteen sites; then wire the check into `java-conventions.gradle` and run a
full `./gradlew check`. There is no window in which the gate is on and the build is red.
`fix-operator-blockers` is applied before task 2.1 (proposal, Impact). Rollback is removing
the two dependency lines in `java-conventions.gradle`; the refactors stand on their own.
