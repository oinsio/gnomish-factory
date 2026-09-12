# Design: add-parameter-count-gate

## Context

See `proposal.md` — Why. Three findings from the 2026-09-12 investigation constrain the
design, and each was verified rather than assumed:

1. **Error Prone 2.50.0 is already on every module's compile path** (`java-conventions.gradle`
   applies `net.ltgt.errorprone` and scopes it to `compileJava`; test code is Groovy and the
   check is disabled for `compileTestJava`). Nothing new needs to enter the build to gate
   production Java.
2. **The stock `TooManyParameters` check cannot be the gate.** Read from the shipped
   artifact: its default limit is 8, it skips records and `*Inject*`-annotated members, and
   it returns early unless the method is public and not an override. Against this codebase
   that is 14 of 103 measured signatures — the take chain is almost entirely
   package-private.
3. **The project already has a precedent for a build gate with an on-the-line
   justification**: `checkTestTimeInjection`, a `build-logic` task verified by
   `TestTimeInjectionCheckFunctionalSpec` and satisfied in source by a
   `// real-time-wiring:` marker beside the call. That shape — gate plus local
   justification plus functional test — is what this change reuses, with a different
   implementation underneath.

## Goals / Non-Goals

**Goals:**

- One mechanism with one owner, applied everywhere by the shared convention plugin.
- Exemptions decided by what the code *is* (a record, an override), not by a list of files
  that rots.

**Non-Goals:**

- Catching the *reason* a signature is long. The gate counts; it cannot tell a parameter
  object from a bag. That judgment stays with review and with the criteria the preceding
  changes wrote into the rules.

## Decisions

**D1 — A project Error Prone check, not a source-scanning Gradle task.** The gate is a
`BugChecker` in a new `build-logic` subproject, placed on each module's `errorprone`
processor path by `java-conventions.gradle`. *Rationale:* at the agreed strictness the gate
inspects every production declaration in the repository, and the two exemptions are
load-bearing — "is the owner a record" and "does this method override something" are exact
questions in the syntax tree and guesses in a regular expression. A false positive silenced
by a marker erodes the gate; a false negative hides the violation the gate exists for.
Error Prone also gives the per-site suppression form for free. *Alternative rejected:* a
Groovy `SourceTask` scanning text, following `checkTestTimeInjection` exactly — cheaper and
already precedented, but its exemptions would be textual approximations, and the same
approximation applied to ~1500 declarations is where a gate quietly stops meaning anything.
Recorded as the fallback in Risks if the processor-path wiring proves unworkable.

**D2 — The stock check stays off (NG3).** *Rationale:* the project's own single-owner
principle — one mechanism, one owner. Enabling both would report the public subset twice,
and split the limit across two configurations that can disagree. *Alternative rejected:*
enabling the stock check for the public subset "for free" — free is not the criterion when
the cost is two owners for one rule.

**D3 — Exemptions are exactly two: record owners and overriding methods.** *Rationale:* a
record *is* the parameter object the rule prescribes, and both Sonar and Error Prone exempt
records unconditionally for that reason; an overriding method does not choose its signature,
which is why Checkstyle ships `ignoreOverriddenMethods` and Sonar checks `isOverriding`.
Every other exemption those tools ship — dependency-injection annotations, a raised
constructor threshold, public-API-only scope — is deliberately **not** taken: they exist to
excuse composition roots, and the decision of 2026-09-12 is that composition roots are
fixed, not excused. *Alternative rejected:* a separate, higher threshold for constructors
(Sonar's `constructorMax`) — it would have let `ManualRunRunner`'s 27 arguments live, which
is the single worst instance the investigation found.

**D4 — The record exemption is honest only with a written criterion beside it.** FR7's rule
text carries the test the preceding changes used: a record earns the exemption when its
component group recurs across many signatures, names a domain concept, and absorbs behavior
— otherwise it is an argument bag wearing the exemption. *Rationale:* the gate cannot make
this call, so the rules must; without it, the exemption becomes the escape hatch that
re-opens everything the three refactors closed. *Alternative rejected:* trusting the count
alone — that is precisely how a count gate rewards bags.

**D5 — Suppression is per declaration, with a reason, and there is no bulk form.**
*Rationale:* Kafka's checkstyle suppressions file shows the failure mode — a regex over file
names silences a whole file permanently, new violations included. A per-site suppression
dies with the method it annotates and is greppable as a complete list. *Alternative
rejected:* a central allowlist file, for the reason above.

**D6 — The last ten sites are fixed in this change, and the shared head of the two agent
round executions becomes one type** (proposal Q1). `ExecutorRoundExecution.run` and
`JudgeRoundExecution.run` share the head `FactoryProperties, Clock, AgentProgressListener,
AgentRoundResultExtractor`; it becomes one value, which brings both to five parameters.
*Rationale:* a recurring group across two signatures with a shared meaning is a parameter
object by the same criterion the earlier changes used. *Alternative rejected:* trimming each
signature separately — it leaves two copies of the same four-member group.

**Sync surfaces.** The change touches no pair declared in `manual-sync-pairs.md` — verified
by grep against all ten sites. It does, however, surface a **candidate undeclared pair**:
`ExecutorRoundExecution` and `JudgeRoundExecution` are two implementations of "run one agent
round", sharing a four-parameter head, a launch sequence and a failure-wrapping rule, with
no `Kept in sync with` marker at either end. Decision, by the preference order in
`manual-sync-pairs.md`: **declare the pair** in this change — both ends get the marker naming
the shared head type and the launch/failure invariant — and do **not** extract a shared
abstraction. *Rationale:* the rule's preference order puts the abstraction first, but its own
rule of three has not been reached (two implementations), and the two differ in their result
extraction and verdict handling; forcing one abstraction now would merge an executor's
result contract with a judge's verdict contract. *Alternative rejected:* leaving it
undeclared — that is an audit finding by the rule, and this change is already at both ends.
If a third round kind appears, the abstraction becomes mandatory.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| the project Error Prone check | compile-time diagnostic | every module's `compileJava`, wired once in `java-conventions.gradle` (FR5) | the unenforced prose rule in `process-invariants.md`, and the possibility of a per-module opt-out: no module declares or configures the check, so none can disable it. Exemptions: records and overrides (D3), by construction | the shared convention plugin — a module that does not apply `java-conventions` compiles nothing, so there is no module the gate does not reach; verified by the functional test (NFR-R1) |
| the agent round head (D6) | one record over `FactoryProperties, Clock, AgentProgressListener, AgentRoundResultExtractor` | `ExecutorRoundExecution.run:49`, `JudgeRoundExecution.run:47` | the four hand-listed parameters at both sites; sweep `grep -rn "AgentProgressListener progressListener" adapters/agent/src/main` must show only the record's own declaration | the parameter type |

Neither row claims two values are one by construction, so no identity spec is required;
NFR-R1's functional test is the gate's own verification.

## Risks / Trade-offs

- **The processor-path wiring may not work cleanly from an included build.** → The gate is
  built and its functional test run before any module depends on it; if substitution proves
  unworkable, the fallback is D1's rejected alternative (a source-scanning task following
  `checkTestTimeInjection`), taken with its approximation limits recorded rather than
  silently accepted. The task list makes this an explicit decision point, not a discovery
  during rollout.
- **A strict gate with only two exemptions will eventually block legitimate work.** →
  That is what D5's per-site suppression is for; if suppressions accumulate past a handful,
  the honest response is to revisit the limit in a new change, not to grow an allowlist.
- **The record exemption can be abused to smuggle a bag.** → D4 writes the criterion into
  the rules; review owns it, and the gate never claimed to.
- **Gate adds compile-time work.** → NFR-C1 measures it before and after; the check is one
  pass over declarations in a compiler that already walks them.

## Migration Plan

The gate is switched on in the same change that removes the last ten violations, in this
order: build the check and its functional test, fix the ten sites, then wire the check into
`java-conventions.gradle` and run a full `./gradlew check`. There is no window in which the
gate is on and the build is red. Rollback is removing the wiring line; the refactors stand
on their own.
