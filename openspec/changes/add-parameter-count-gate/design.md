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
`@BugPattern(suppressionAnnotations = ParameterLimitExemption.class)` (the attribute exists
in `error_prone_annotation` 2.50.0, verified), where `ParameterLimitExemption` is a
`@Target({METHOD, CONSTRUCTOR})`, source-retention annotation with one element,
`String reason()`, shipped as a compile-only artifact beside the check (D9). The check itself
reports an annotated site whose reason is blank, so "annotated" and "justified" cannot come
apart. *Rationale:* Error Prone's default form, `@SuppressWarnings("<CheckName>")`, has no
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
them. What is borrowed from it: the split is its own task with its own behavior check
(the serve wiring spec and the outage end-to-end spec stay green unedited), not a line
inside "bring under the limit".

**D8 — The two record-housed methods come under the limit by the transformations their
callers already suggest** (driven by FR2, FR6). `AbortHandler.handle:126` (8) has one
production caller (`TakeCrashAbort.java:82`) that supplies per-run constants (`threshold`,
`instanceId`) beside per-abort facts; the per-run constants join the record's own
components (it is `record AbortHandler(Tracker tracker, Clock clock)` today), which is the
record absorbing what its methods are always given. `BoardModel.build:130` (9) has one caller
(`BoardComposition.java:53`) and hands `base, cap, now, openFrontCount, wipLimit` straight to
`EligibilityPolicy.resolve`; the eligibility inputs travel as one value the policy also takes.
The exact shapes are decided at the task (2.6, 2.7) against the callers; the design fixes
only that neither becomes a bag. *Alternative rejected:* keeping the stock owner-is-a-record
exemption so the two need no change — D3 says why.

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
a multi-project `build-logic` (above).

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

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| the project Error Prone check (`parameter-count-check`) | compile-time diagnostic | every module's `compileJava`, wired once in `java-conventions.gradle` (FR5) | the unenforced prose rule in `process-invariants.md`, and the possibility of a per-module opt-out: no module declares or configures the check, so none can disable it. Exemptions by construction: record constructors and overrides (D3). **Exemption by layout:** the two `build-checks` projects (D9), which cannot apply the convention that would put the check on their own processor path; they carry no production code of the factory | the shared convention plugin — every module of the main build applies `java-conventions` (verified 2026-09-27: no `build.gradle` without it or `library-conventions`); the TestKit wiring case (D10, NFR-R1) |
| `ParameterLimitExemption` (`parameter-count-annotations`) | the only exemption token, with a non-blank `reason` | any exempted declaration (none at switch-on, M1) | `@SuppressWarnings("<CheckName>")` — the check's `suppressionAnnotations` names only its own annotation, so the default form is inert for it (D5) | the check itself: a blank reason is reported; `@Target` refuses the annotation on a type; the unit spec's two negative cases |
| `AgentRoundEquipment` (D6) | one record over `FactoryProperties, Clock, AgentProgressListener, AgentRoundResultExtractor` | built in the constructors of `CliStageExecutor` and `CliJudgeVoter` (one field each); taken whole by `ExecutorRoundExecution.run:49` and `JudgeRoundExecution.run:48` | the four separate fields in each caller and the four hand-listed parameters at both `run` sites; sweep: `grep -rn "AgentRoundResultExtractor resultExtractor" adapters/agent/src/main` must show only the record's own component (today: `CliStageExecutor.java:56`, `CliJudgeVoter.java:59`, `ExecutorRoundExecution.java:53`, `JudgeRoundExecution.java:52`). The three-member head in the two public constructor families stays: it is API, under the limit, and named in D6 | the parameter type of both `run` methods |
| `FeedAssembly` (D7) | `FeedAutomaton`, built — holding `Sleeper, Clock, IdleTiming, int wipLimit` as fields | `ServeAssembly.feedAutomaton:122` (production), `FeedAutomatonFixture` (`test-fixtures`), `RemoteOutageServeEndToEndSpec:150` | the five `new …(` constructions inside `FeedAutomaton`'s constructor, deleted with the constructor's ten-parameter form; sweep: `grep -rn "new FeedCycle(\|new FeedViewTracker(" application test-fixtures` must show only `FeedAssembly` | the automaton's constructor takes `FeedCycle` and `FeedViewTracker` as values — it can no longer build them; `ServeRuntimeWiringSpec` stays green unedited |

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
