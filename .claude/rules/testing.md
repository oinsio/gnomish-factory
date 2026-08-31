# Rule: testing

## Frameworks

- **Spock 2** (Groovy) for all unit and integration tests — BDD style (`given/when/then`), built-in mocks/stubs (no Mockito), data-driven tables for stage-verification matrices
- **`spock-spring`** when a Spring context is required
- **WireMock** (in-JVM) for tracker/AI API contract tests — no Docker needed
- **Local bare git repos** (`git init --bare` in a temp dir) for git-workflow tests (task branch, state file, resume by another instance)
- **Testcontainers + `testcontainers-spock`** only for the E2E layer: Gitea container as a real git remote with HTTP auth, sandbox containers for real agent-CLI runs. Docker is a dev/CI prerequisite

## TDD

Red-Green-Refactor: write a failing Spock spec first, make it pass, refactor. Every FR gets at least one spec referencing it (see `traceability.md`).

## Coverage and mutation testing

- **JaCoCo** for coverage reports (XML + HTML)
- **PIT** for mutation testing with `pitest-junit5-plugin` (Spock 2 runs on the JUnit Platform)
- PIT `targetClasses` MUST cover Java production code only — never Groovy test bytecode; mutating Groovy produces noisy false-positive survivors
- Mutation score target is **100%**; ≥95% is acceptable ONLY where behavior genuinely cannot be exercised by unit tests (integration boundaries, `main()` wiring) — each exception must be explicitly justified
- PIT is scoped **per module**: each module's `targetClasses` is bound to its own production packages and its `pitest` runs under its own `check`, so `:module:check` mutates only that module and root `check` covers the union. A module's own build file carries only its exclusions; the engine configuration lives in the `pitest-conventions` plugin

### Per-method exemptions (`@DoNotMutate`)

A method that cannot be mutated meaningfully carries the project's own `@com.github.oinsio.gnomish.DoNotMutate` marker rather than a `build.gradle` glob. PIT honours it build-wide with no Gradle wiring (`ExcludedAnnotationInterceptorFactory`, feature `FANN`, on by default — it matches any annotation *named* `Generated` / `DoNotMutate` / `CoverageIgnore`, which is why each module may keep its own copy of the marker).

Why not `excludedMethods`: it is a plain glob over bare method names — no class-qualified syntax exists (hcoles/pitest#301, unresolved) — so excluding one `requireNonBlank` would silently exempt every same-named validator in the build from the 100% gate.

Three accepted reasons to apply it, and no others:

- **JVMTI redefinition limit.** PIT's Gregor engine hot-swaps bytecode into an already-loaded class; the JVM rejects redefinition that changes a class's `NestHost` / `NestMembers` / `Record` / `PermittedSubclasses` attributes, which some (not all) mutations of `record` accessors trigger (hcoles/pitest#1285, open on JDK 17+). PIT reports RUN_ERROR — its minion crashed before observing any test — not SURVIVED, and sibling mutations of the same method are killed normally. No PIT config or JVM flag works around it
- **Provably equivalent mutant.** The mutation has no externally observable effect (e.g. `<` vs `<=` on a running minimum that is reassigned to the value it already holds), so no covering test can kill it. The method's own comment must carry the trace
- **Out-of-process delegation.** The method's whole body is one hand-off into a real Docker daemon, git subprocess, or remote, whose observable effect in a fast, daemon-free unit test is *identical to the call never happening* — an unreachable listing and an empty one yield the same value, so no in-process assertion can kill a "call removed" mutant without either mocking a deliberately unmockable adapter type or depending on a live daemon's actual state. This is the per-method twin of the `excludedTestClasses` category below, which excludes the out-of-process *suite*; use this one when the offending line sits in an otherwise ordinary class. The bar:
  - The body holds **no decision** — no conditional, no loop, no computed value. A single `if` disqualifies it; extract the decision and unit-test it instead
  - The collaborator really is out-of-process and really is unmockable in-module (a package-private `DockerCli`, a `ProcessBuilder` seam) — "it would be awkward to fake" is not the same claim
  - The suite that genuinely exercises the line end to end is **named** in the method's own comment, so the exemption removes the mutation gate, not the coverage
  - `excludedClasses` is preferred when the *whole class* qualifies (see below); reach for `@DoNotMutate` only when the class also holds assertable methods that must stay in the mutation scope

Every annotated method still needs full unit-spec coverage — the marker stops PIT from *mutating* it, so it emits no mutation entry at all. A RUN_ERROR from anywhere else is a broken run, not an accepted exception, and `pitestVerifyAllKilled` fails the build on it.

### Per-class exemptions (`excludedClasses`, arid wiring)

A class whose *every* mutation is delegation-shaped — a removed void hand-off, or a nulled return of an object it only constructed from its own parameters — is listed in its module's `pitest { excludedClasses }` with a written rationale. The category is the one Google's mutation infrastructure suppresses wholesale as "arid nodes": a unit test killing such a mutant asserts "method calls method", which duplicates the implementation, resists refactoring and adds no confidence.

`excludedClasses` (not `@DoNotMutate`) is the mechanism, because the exemption is a *testing-strategy* decision about a whole class and belongs where a reviewer reads the module's mutation scope; `@DoNotMutate` is reserved for the two engine-level reasons above.

The bar, checked per class and never as a blanket:

- The class carries **no decision** — no conditional, no loop, no computed value. A single `if` disqualifies it: that is decision-bearing orchestration and gets a port-fake unit spec instead
- Its production role is composition (a factory, an assembly, a parameter-object wither), so the collaborator graph it builds is the thing under test, not the calls that build it
- An end-to-end suite that really drives it is **named in the rationale**, so the exemption removes the mutation gate, not the coverage

Because the exclusion is class-level, a class holding both arid construction and one genuinely assertable method is *not* exempted — write the spec instead.

### Per-class exemptions (`excludedClasses`, integration-covered)

The second accepted reason to list a class: its behavior is already covered, scenario by scenario, by a suite that legitimately lives in a **different module** — so per-module mutation scoping reports it as uncovered while the coverage genuinely exists. Duplicating that suite one module down, over hand-built stand-ins for collaborators it drives for real, produces a weaker test of the same behavior and two places to keep in step.

The bar, again per class:

- The covering suite is **named** in the rationale, with its scenario count, and it really drives this class's own decisions — not merely a flow that passes through it
- It cannot move down (a spec that assembles the real run through the composition root's own wiring belongs where that wiring lives). If it *can* move, move it: relocation beats exemption, since it restores the gate instead of documenting its absence
- The class's collaborators are concrete `final` types with no observable state for their hand-offs, so a same-module spec could assert only "method calls method"

Prefer, in order: move the spec down → write a port-fake spec → exempt with this record.

### Excluded *test* classes (`excludedTestClasses`, out-of-process suites)

`excludedClasses` removes production classes from the mutation scope; `excludedTestClasses` removes *specs* from PIT's test scan while every production line they touch stays mutated. One category qualifies: a suite that drives the system **out of process** — a packaged jar spawned via `java -jar`, a real Docker daemon, a real remote — where PIT's own minions defeat it for reasons unrelated to any mutation.

Two concrete failure modes, both observed in this build:

- **The property the suite needs never reaches the minion.** PIT's coverage and mutation minions are separate JVM launches inheriting nothing from the `test` task, so a suite resolving its subject through a `test`-wired system property (`e2e.jarPath`) fails its precondition for every mutant. Where the property points at a *deterministic on-disk directory* (`fakeAgentDir`, `referenceDumpDir`, `repoRoot`) the fix is `pitest { jvmArgs }`, not an exclusion — exclude only when the value is a build artifact PIT cannot be handed
- **A mutant hangs on real I/O instead of failing fast.** In Docker/subprocess territory a broken wait/retry/kill loop blocks on OS I/O that PIT's per-mutation timeout cannot always interrupt (observed: a killed-container resume mutant hung a minion 30+ minutes past its budget, leaving an orphaned box)

The bar: excluding the suite must remove **no production line** from mutation coverage — every class it exercises is also covered by fast, in-process unit specs that already feed the gate. The exclusion is per module (each module's PIT sees only its own test tree), so it lives in the build file of the module owning the spec, with the rationale next to it. PIT's exclusion glob has no per-feature granularity, so a single offending feature costs the whole spec class.

## Time is injected in tests, and the build checks it

Components that retry or poll take their `Sleeper` and `Clock` as constructor arguments so a
spec can drive them on virtual time (`VirtualClock`/`VirtualSleeper`, or the ready-made
`VirtualTimeRetries` in `:test-fixtures`). Beside each such component sits a no-argument
`system()` factory that wires the real `ThreadSleeper`/`SystemClock` with the production bound —
**for the composition root, not for specs**.

A spec that calls `system()` does not go red. Its collaborator never reports the failure the
retry waits on, so it never sleeps, and the call looks correct indefinitely — until some later
change makes that collaborator report an outage. Then the spec does not fail, it *blocks*, for
the whole production bound, once per exercise of the path; under PIT that is the "mutant hangs
on real I/O instead of failing fast" mode recorded above. Because there is nothing red to
notice, review is the wrong instrument for it.

So `check` asks instead: **`checkTestTimeInjection`** (registered by `test-conventions` in every
module, and by `:test-fixtures` over its own `src/main`) fails on a `SomeType.system()` call in a
test source. Satisfy it by building the component with virtual time — which keeps the production
bound and elapses it instantly — or, where the call really is right (a spec asserting the
production defaults themselves, a factory with no time in it), justify it in place:

```groovy
// real-time-wiring: the production defaults ARE the subject here — the retry is only
//     constructed and read, never run, so no sleep can happen.
GitInfrastructureRetry.system().attempts() == GitInfrastructureRetry.DEFAULT_ATTEMPTS
```

The marker goes on the call's own line or in the comment block directly above it, so the
justification lives beside the call rather than in a central allowlist — the same shape
`@DoNotMutate` uses for the mutation gate.

## Rules

- Maximize automated verification in task plans — avoid manual testing steps
- One capability per spec file; descriptive method names in natural language (Spock convention)
- Contract tests for every port: each adapter must pass the same port-level spec suite
- Integration tests are the slowest — scope runs to what the change affects
- **Every wire vocabulary has a round-trip spec.** When an enum is serialized to wire tokens
  by one component and parsed back by another (ledger, snapshot, state files), a data-driven
  spec must assert `fromWire(wire(e)) == e` for **every** constant — iterate `values()`, no
  hand-listed subset — and pin the unknown-token behavior (the documented forward-compat
  `default` arm). This is what keeps a writer/reader pair (see `manual-sync-pairs.md`) from
  drifting silently: adding a constant mapped on only one side fails the spec, not production
