# Proposal: add-parameter-count-gate

## Why

`process-invariants.md` has carried the seven-parameter rule since the project's early
days, together with an admission: *"A mechanical build gate for this limit should land
together with the refactoring that brings existing offenders under it — turning it on
earlier just paints the build red."* Neither half happened, and by 2026-09-12 the codebase
held 63 violations, including a 27-argument constructor. A rule nobody can check is a rule
that drifts, and this one drifted for the whole life of the project.

The three preceding changes remove the offenders. This change closes the loop: it brings the
last ten signatures under the limit and then makes the rule mechanical, so the count cannot
drift again.

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
  sources, with the two exemptions decided for this project — record owners and overriding
  methods — applied from the syntax tree rather than guessed from text.
- **ADDED**: a per-site suppression form for the rare justified exception, in the same
  shape `testing.md`'s `real-time-wiring:` marker already uses: the justification lives on
  the line, not in a central allowlist.
- **MODIFIED**: the last ten signatures over the limit — the `sandbox/docker` container
  environment builders, the two agent round executions, `GithubMarkerJson` and
  `PipelineModelBuilder.mapAndValidate` — come under it.
- **MODIFIED**: `process-invariants.md`'s parameter-count section states the exemptions,
  names the gate as the enforcement mechanism, and drops the "should land" placeholder.
- No behavior change; no runtime dependency added (the check runs at compile time only).

## Goals

- **G1** — Zero parameter-limit violations in `src/main` across every module when the gate
  is switched on, with zero baseline suppressions.
- **G2** — A signature that exceeds the limit fails `check`, in every module, without any
  per-module configuration.
- **G3** — The exemptions are applied by construction, not by list: a record owner or an
  overriding method is never reported, and no file needs naming to achieve that.
- **G4** — A justified exception is visible where it lives: on the declaration, with its
  reason, never in a file that can silence a whole class.

## Non-Goals

- **NG1** — Gating test sources. Error Prone is already scoped to `compileJava`, and Spock
  specs are Groovy; a spec's fixture builder is not the target.
- **NG2** — Adding Checkstyle or Sonar. A second tool for one rule would give the rule two
  owners.
- **NG3** — Enabling the stock `TooManyParameters` alongside the project check. One
  mechanism, one owner — enabling both would report the public subset twice and split the
  configuration.
- **NG4** — Changing the limit itself, or the exemptions agreed on 2026-09-12 (seven
  everywhere; records and overrides exempt; composition roots not exempt).
- **NG5** — A baseline of grandfathered violations. The preceding changes exist so the
  baseline is empty; a non-empty one means a preceding change did not finish.

## Users & Scenarios

- **U1** — A developer adds an eighth parameter and learns it at compile time in their own
  module, with the limit and the suggested transformation in the message — instead of at
  review, or never.
- **U2** — A developer has a genuine exception (a generated or externally-shaped
  signature). They suppress it on the declaration with a reason, and the next reader sees
  the reason without opening another file.
- **U3** — A reviewer auditing the codebase greps the suppression form and gets the
  complete, current list of exceptions with their justifications.

## Requirements

### Functional

- **FR1** — A constructor or method in production Java with more than seven parameters
  fails compilation, in every module, with a message naming the count, the limit and the
  transformation to use.
- **FR2** — A method whose owner is a record is never reported (records are themselves the
  parameter object the rule asks for).
- **FR3** — A method that overrides or implements another is never reported: it does not
  choose its own signature.
- **FR4** — A justified exception is expressed on the declaration itself, carrying a
  written reason; no mechanism exists to exempt a whole file or class in bulk.
- **FR5** — The gate is wired once, for every module, by the shared convention plugin — no
  module declares it.
- **FR6** — The last ten signatures over the limit come under it in this change, so the
  gate switches on with zero suppressions (G1, NG5).
- **FR7** — `process-invariants.md`'s parameter-count section names the limit, the two
  exemptions, the suppression form and the gate, replacing the "should land" sentence.

### Non-Functional — Reliability

- **NFR-R1** — The gate is verified by running it: a functional test builds a miniature
  project and asserts that an eight-parameter method fails, a seven-parameter one passes, a
  record and an override pass, and a suppressed site passes. Following the precedent of
  `TestTimeInjectionCheckFunctionalSpec`, the gate is not verified by reading its source.
- **NFR-R2** — The ten remaining refactors are behavior-preserving: the existing suite
  passes with no spec expectation edited.

### Non-Functional — Observability

- **NFR-O1** — The failure message is actionable on its own: it states the count, the
  limit, and which transformation applies, so a developer does not need to find the rule
  file to act.

### Non-Functional — Cost

- **NFR-C1** — No measurable build-time regression: the check is one pass over method
  declarations in a compiler already running. Measured before and after on a clean build.

## Operator Experience Criteria

- **UX1** — No operator-visible change: the gate is a build-time concern, and the ten
  refactors preserve behavior.

## Success Metrics

- **M1** — Violations in `src/main` when the gate is switched on: 0, with 0 suppressions.
- **M2** — The functional test covers all five cases of NFR-R1 and passes.
- **M3** — `./gradlew check` passes across every module with the gate on.
- **M4** — Clean-build wall time within noise of the pre-change measurement (NFR-C1).

## Open Questions

- **Q1** — Do the two agent round executions (`ExecutorRoundExecution.run`,
  `JudgeRoundExecution.run`) share a four-parameter head that should become one type, and
  if so, are they an undeclared manual-sync pair under `manual-sync-pairs.md`? Proposed
  answer: yes to the shared head; the pair question is decided in design.md against that
  rule's preference order, and whichever way it goes, it is recorded rather than left
  silent.

## Impact

- **Baseline freshness**: every count in this proposal comes from the 2026-09-12
  scan, taken before `fix-claim-epoch-fence` landed. The preceding change re-takes
  it (`introduce-take-order` task 0.2); correct these figures through
  `/opsx:update` if the fresh count differs, rather than reporting against a stale
  number.
- **Modules**: `build-logic` (the gate and its functional test), every module through the
  shared convention plugin, plus the ten signatures in `sandbox/docker`, `adapters/agent`,
  `adapters/github` and `adapters`.
- **Rules**: `.claude/rules/process-invariants.md`, parameter-count section (FR7).
- **Build**: one compile-time artifact on the Error Prone processor path; dependency
  lockfiles regenerated. No runtime dependency, no change to any published API.
- **Depends on**: `collapse-composition-roots` — the gate cannot be switched on until its
  twenty sites are gone, and this change owns only the last ten.
