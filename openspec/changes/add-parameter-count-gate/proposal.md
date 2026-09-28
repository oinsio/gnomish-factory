# Proposal: add-parameter-count-gate

## Why

`process-invariants.md` has carried the seven-parameter rule since the project's early
days, together with an admission: *"A mechanical build gate for this limit should land
together with the refactoring that brings existing offenders under it — turning it on
earlier just paints the build red."* Neither half happened, and by 2026-09-12 the codebase
held 63 violations, including a 27-argument constructor. A rule nobody can check is a rule
that drifts, and this one drifted for the whole life of the project.

The three preceding changes remove the offenders. This change closes the loop: it brings the
last thirteen signatures under the limit and then makes the rule mechanical, so the count
cannot drift again.

Enabling the gate **last** is deliberate, not incidental. A count gate cannot distinguish a
real parameter object from a bag of arguments — no reviewed source offers an operational
test for that, and a gate that counts will happily accept the bag. Turned on first, it would
have rewarded exactly the outcome the preceding changes were designed to avoid.

Two facts settle the gate's shape. Error Prone is already on every module's compile path,
so no new tool enters the build. But its stock `TooManyParameters` check fires only on
public, non-overriding methods — verified against the shipped 2.50.0 artifact — and would
therefore see 14 of the 103 measured signatures in this codebase, whose take chain is almost
entirely package-private. The stock check cannot be the gate; a project check can.

## What Changes

- **ADDED**: a project Error Prone check enforcing the limit at compile time on production
  sources, with the two exemptions decided for this project — record constructors and
  overriding methods — applied from the syntax tree rather than guessed from text.
- **ADDED**: a per-site exemption annotation carrying a mandatory reason, in the same spirit
  as `testing.md`'s `real-time-wiring:` marker: the justification lives on the declaration,
  not in a central allowlist, and no bulk form exists — the check does not honour
  `@SuppressWarnings`.
- **MODIFIED**: the last thirteen signatures over the limit come under it — the
  `sandbox/docker` container environment builders, the two agent round executions,
  `GithubMarkerJson`, `PipelineModelBuilder.mapAndValidate`, the `FeedAutomaton` constructor
  (split: the automaton takes its cycle and view tracker built, as `collapse-composition-roots`
  named for it), and the two methods housed in records that the narrowed record exemption no
  longer covers, `AbortHandler.handle` and `BoardModel.build`.
- **MODIFIED**: `process-invariants.md`'s parameter-count section states the exemptions,
  names the gate as the enforcement mechanism, and drops the "should land" placeholder.
- No behavior change; no runtime dependency added (the check runs at compile time only, the
  exemption annotation is compile-only).

## Goals

- **G1** — Zero parameter-limit violations in `src/main` across every module when the gate
  is switched on, with zero exemption annotations.
- **G2** — A signature that exceeds the limit fails `check`, in every module, without any
  per-module configuration.
- **G3** — The exemptions are applied by construction, not by list: a record constructor or
  an overriding method is never reported, and no file needs naming to achieve that.
- **G4** — A justified exception is visible where it lives: on the declaration, with its
  reason, never in a file or on a class that can silence more than one declaration.

## Non-Goals

- **NG1** — Gating test sources. Error Prone is already scoped to `compileJava`, and Spock
  specs are Groovy; a spec's fixture builder is not the target.
- **NG2** — Adding Checkstyle or Sonar. A second tool for one rule would give the rule two
  owners.
- **NG3** — Enabling the stock `TooManyParameters` alongside the project check. One
  mechanism, one owner — enabling both would report the public subset twice and split the
  configuration.
- **NG4** — Changing the limit itself, or the exemptions agreed on 2026-09-12 (seven
  everywhere; records and overrides exempt; composition roots not exempt). Narrowing the
  record exemption from "any method whose owner is a record" to "a record's constructors" is
  a precision of that agreement, not a change to it: the rationale given on 2026-09-12 — the
  record *is* the parameter object — applies to the constructor only.
- **NG5** — A baseline of grandfathered violations. The preceding changes exist so the
  baseline is empty; a non-empty one means a preceding change did not finish.
- **NG6** — Redesigning the serve feed. The `FeedAutomaton` split moves construction out of
  the automaton; the automaton's cycle, states and observability view are untouched.

## Users & Scenarios

- **U1** — A developer adds an eighth parameter and learns it at compile time in their own
  module, with the limit and the suggested transformation in the message — instead of at
  review, or never.
- **U2** — A developer has a genuine exception (a generated or externally-shaped
  signature). They annotate the declaration with a reason, and the next reader sees the
  reason without opening another file.
- **U3** — A reviewer auditing the codebase greps the exemption annotation's name and gets
  the complete, current list of exceptions with their justifications — and nothing else,
  because the annotation is the gate's own and appears nowhere for any other purpose.

## Requirements

### Functional

- **FR1** — A constructor or method in production Java with more than seven parameters
  fails compilation, in every module, with a message naming the count, the limit and the
  transformation to use.
- **FR2** — A constructor of a record — canonical, compact or explicit — is never reported
  (the record is itself the parameter object the rule asks for). An ordinary method housed in
  a record is reported like any other method: it does not inherit the record's exemption.
- **FR3** — A method that overrides or implements another is never reported: it does not
  choose its own signature.
- **FR4** — A justified exception is expressed on the declaration itself, through the gate's
  own annotation, whose `reason` element is mandatory and must not be blank; the annotation
  is applicable to methods and constructors only, and the check ignores `@SuppressWarnings`,
  so no mechanism exists to exempt a whole file or class in bulk.
- **FR5** — The gate is wired once, for every module, by the shared convention plugin — no
  module declares it. The only production Java outside the gate is the build of the check
  itself, which cannot apply the convention that would place it on its own processor path.
- **FR6** — The last thirteen signatures over the limit come under it in this change, so the
  gate switches on with zero exemptions (G1, NG5): the ten of the 2026-09-27 hand-over, the
  `FeedAutomaton` constructor (split, not grouped), and the two record-housed methods FR2's
  narrowing exposes.
- **FR7** — `process-invariants.md`'s parameter-count section names the limit, the two
  exemptions, the exemption annotation and the gate, replacing the "should land" sentence.

### Non-Functional — Reliability

- **NFR-R1** — The check is verified by compiling against it, in two layers. A unit spec on
  Error Prone's own compilation harness covers the seven semantic cases: an eight-parameter
  method fails, a seven-parameter one passes, a record constructor passes, an eight-parameter
  method housed in a record fails, an override passes, an annotated site with a reason
  passes, and neither a blank reason nor `@SuppressWarnings` on the enclosing class
  suppresses. A functional test on a miniature project —
  following `TestTimeInjectionCheckFunctionalSpec`'s shape — proves the wiring: a module
  applying the convention plugin fails `compileJava` on an eight-parameter method. The gate
  is not verified by reading its source.
- **NFR-R2** — The thirteen refactors are behavior-preserving: the existing suite passes
  with no spec *expectation* edited. A spec or fixture that calls a changed signature
  directly is updated at its call site only.

### Non-Functional — Observability

- **NFR-O1** — The failure message is actionable on its own: it states the count, the
  limit, and which transformation applies, so a developer does not need to find the rule
  file to act.

### Non-Functional — Cost

- **NFR-C1** — No measurable build-time regression: the check is one pass over method
  declarations in a compiler already running. Measured before and after as the median of
  three clean builds (`--no-build-cache --rerun-tasks`, same machine); the after-median is
  within 5 % of the before-median.

## Operator Experience Criteria

- **UX1** — No operator-visible change: the gate is a build-time concern, and the thirteen
  refactors preserve behavior.

## Success Metrics

- **M1** — Violations in `src/main` when the gate is switched on: 0, with 0 exemption
  annotations.
- **M2** — The unit spec covers all seven cases of NFR-R1 and the functional test its one
  wiring case; both pass, and the functional test fails when the wiring line is removed.
- **M3** — `./gradlew check` passes across every module with the gate on.
- **M4** — Clean-build median within 5 % of the pre-change median (NFR-C1).

## Open Questions

- **Q1** — Do the two agent round executions (`ExecutorRoundExecution.run`,
  `JudgeRoundExecution.run`) share a four-parameter head that should become one type, and
  if so, are they an undeclared manual-sync pair under `manual-sync-pairs.md`? Answered in
  design.md D6 (one type, held by the two callers; the pair is declared, not abstracted).
- **Q2** — Does the `FeedAutomaton` constructor come under the limit in this change, or does
  this change depend on a follow-up? Answered 2026-09-27 in design.md D7: in this change, by
  a split, because the gate cannot switch on while the constructor stands at ten and a
  follow-up would leave the loop open for a fourth change.

## Impact

- **Baseline freshness**: the counts in Why come from the 2026-09-12 scan. Re-taken
  2026-09-27 at the end of `collapse-composition-roots` (its task 5.2) and verified again on
  2026-09-27 by this change's review: **13** signatures over the limit in `src/main` under
  FR2's narrowed exemption — `ExecutorRoundExecution.java:49` run (8),
  `JudgeRoundExecution.java:48` run (8), `GithubMarkerJson.java:62` ctor (8),
  `PipelineModelBuilder.java:50` mapAndValidate (8), `ContainerEnvironmentBuilder.java:22`
  build (10), `ContainerEnvironments.java:64` forTask (11) and `:106` ctor (11),
  `ContainerMaterializer.java:42` reattach (9) and `:74` create (10),
  `ContainerTaskExecutionEnvironment.java:83` ctor (11),
  `application/.../app/serve/FeedAutomaton.java:74` ctor (10),
  `application/.../app/take/AbortHandler.java:126` handle (8) and
  `application/.../board/BoardModel.java:130` build (9). The last two are ordinary methods
  housed in records; the 2026-09-27 hand-over scan did not count them. Line numbers move
  with the code; the scan in task 2.8 is authoritative.
  **Closed 2026-09-27:** 0 with the gate on — the gate's own scan over every module's
  `compileJava`, full `check` green, zero exemption annotations (tasks 2.8, 3.3, 4.5).
- **Modules**: a new included build for the check and its annotation (design D9), every
  module through the shared convention plugin, plus the thirteen signatures in
  `sandbox/docker`, `adapters/agent`, `adapters/github`, `adapters` and `application`.
  Call sites of the changed signatures also live in production in `bootstrap`
  (`ContainerRunSupportFactory`, the one `forTask` caller) and in the test trees of
  `adapters/git` (six container-environment specs), `adapters/agent` (four round specs),
  `sandbox/docker` (`ContainerEnvironmentsFixture`, `ContainerEnvironmentsSeamSpec`,
  `ContainerTaskExecutionEnvironmentUnitSpec`, `ContainerTaskExecutionEnvironmentExecSpec`,
  `ContainerExecHandleSpec`), `bootstrap` (`ContainerModeIsolationE2ESpec`), `application`
  (`BoardReferenceFixture`, `BoardModelEligibilitySpec`, `BoardJsonMapperSpec`,
  `AbortHandlerSpec`, `AbortCauseCapWiringSpec`), and in `test-fixtures`
  (`ScriptedSandboxDocker`, `FeedAutomatonFixture`); those are call-site updates under NFR-R2.
- **Rules**: `.claude/rules/process-invariants.md`, parameter-count section (FR7);
  `.claude/rules/manual-sync-pairs.md` is not touched (design, Sync surfaces).
- **Build**: one compile-time artifact on the Error Prone processor path and one
  compile-only annotation artifact; two catalog entries (`error_prone_check_api`,
  `error_prone_test_helpers`); dependency lockfiles regenerated. No runtime dependency, no
  change to any published API.
- **Depends on**: `collapse-composition-roots` — landed and archived 2026-09-27
  (`openspec/changes/archive/2026/09/2026-09-27-collapse-composition-roots`); it left the
  `FeedAutomaton` constructor as its one residual, which D7 absorbs.
- **Follow-up, out of scope**: PIT is pinned at 1.25.7; the record-redefinition crash
  (hcoles/pitest#1285) that motivates every `@DoNotMutate` of the JVMTI category — and the
  one D8 may need on `AbortFuse.handle` — is fixed in 1.25.9 and carried into 1.30.0. The bump
  is its own change (catalog, verification metadata, a full mutation run, and the retirement of
  the JVMTI reason in `testing.md`), decided 2026-09-27 not to ride this one.
- **Sequenced after**: `fix-operator-blockers` — its task 2.2 edits the same two methods
  (`ExecutorRoundExecution.run`, `JudgeRoundExecution.run`) whose signatures D6 changes;
  applying it first keeps D6 a signature change over settled bodies. That change's proposal
  records the mirror line through its own `/opsx:update`.
