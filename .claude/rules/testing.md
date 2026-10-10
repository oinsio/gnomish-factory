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

An **assembly object** is the standing member of this category: a class that was once a
static helper "extracted for file size", taking its origin's fields as parameters, and was
turned back into an instance holding them (the fields-into-parameters clause of
`process-invariants.md`; ADR 0010, "An assembly object gets no spec of its own"). It carries
no decision, so a spec written for it can only restate the constructor calls it makes. It
gets no spec; it is exercised through the effect on the real flow (`ServeRuntimeWiringSpec`
is the model) and listed here with that suite named. Precedent: `ServeRuntimeAssembly`
(`collapse-composition-roots`, design D7). An instance that came out of the same refactoring
but gained a decision of its own (`ContainerRunSupportFactory` merges three credential sources
in `create`; `ServeAssembly` keeps its effect-asserting specs) is not an assembly object: it
stays in the mutation scope with its spec.

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

The factory has one type for the current instant, `java.time.InstantSource`, and one carrier for
real time's two halves, `TimeEquipment(InstantSource clock, Sleeper sleeper)` in `:domain`
(ADR 0014). A component that only reads "now" takes the `InstantSource`; one that also waits — a
retry, a poll, a loop's wait — takes the `TimeEquipment`. Real time is built in one place: the
`timeEquipment` bean in `:bootstrap`'s `ManualRunConfiguration`, where `ThreadSleeper` lives, so
no other module can even construct the real sleeper. No `system()` factory wiring time exists.

Specs build the virtual equipment: `VirtualTimeEquipment` in `:test-fixtures` (the one fake
instant source, `VirtualClock`, with a budgeted sleeper advancing it), or `VirtualTimeRetries`,
built on it. A spec on real time does not go red: its collaborator never reports the failure the
retry waits on — until a later change makes it, and then the spec *blocks* for the production
bound (under PIT, the "mutant hangs on real I/O" mode above). Nothing red, so review cannot see it.

So `check` asks instead: **`checkTestTimeInjection`** (`TestTimeInjectionCheck`, registered by
`test-conventions` over every module's test tree and by `:test-fixtures` over its own `src/main`)
fails on any real-time literal in a test source — `Clock.systemUTC(`, `Clock.systemDefaultZone(`,
`InstantSource.system(`, `Instant.now(`, `new SystemClock(`, `new ThreadSleeper(`, and any
`.system(...)` call with or without arguments. The set is the one `TimeSourceOwnerBoundarySpec`
bans in production outside the root; the two are a declared sync pair, and that spec fails when
the sets differ. Where real time really is the subject (a fixture assembling the shipped
composition, a real Docker daemon or remote that stamps on the wall clock), justify it in place:

```groovy
// real-time-wiring: a real Docker daemon stamps the boxes' creation on the wall clock, and the
//     pass ages them against its clock; the end-to-end layer needs the same time.
def summary = SandboxLifecyclePassFactory.create(tinyAges, factoryProperties, InstantSource.system())
```

The marker goes on the call's own line or in the comment block directly above it, so the
justification lives beside the call rather than in a central allowlist — the same shape
`@DoNotMutate` uses for the mutation gate.

## Fixtures assemble through production owners

A decorator, a wrapper, or a wiring step applied only at the composition root is **not**
covered by end-to-end specs that assemble the commands by hand. Each such spec builds its own
graph, skips the root, and stays green over an assembly production never runs — so the flow the
spec claims to exercise is not the flow that ships.

The failure this exists for: the claim epoch was recorded by a tracker decorator and stamped by
a git layer, each wired in its own Spring bean, while every lifecycle fixture built a raw
registry and a claimless git layer. No commit was stamped in any test, so the read-side fence
that quarantined every legitimate reclaim in production was invisible (`fix-claim-epoch-fence`).

- **The owner is a value the command receives**, not a step an assembler is expected to perform.
  A bundle the command already takes (`TaskGit` carries the `ClaimEpochBook`) makes the wrong
  assembly unconstructible — the "escape hatch is gone" item of `implementation.md`.
- **An architecture spec pins the fixture path**, per `implementation.md` item 4 ("Enforcement
  named"): an allowlisted whole-tree scan over the test sources, asserting it reached every
  allowlisted file, so the next fixture that bypasses the owner fails the build rather than
  passing quietly. `ClaimlessGitBoundarySpec` in `:bootstrap` is the precedent.
- **A fixture that legitimately needs the claimless variant says so in its name**
  (`TaskGitFixture.realClaimless()`) and is listed in the scan with its reason.

## Test processes never inherit the operator's environment

A test gets a fixed environment and depends on no variable it did not set itself. The
operator's shell is not part of that environment, and neither is the gnome round that may
be running the build.

The failure this exists for: a gnome's agent process holds `GNOMISH_DECISION_FILE` for its
round. The Gradle build the gnome ran inherited it, Gradle's `Test` task hands the forked JVM
the build's environment by default, and the fixture box (`LocalBoxEnvironment`) started the
fake agent on that inherited environment. The fake agent copied a scenario's decision into
the round's real decision file (introduced by `make-checkpoint-gate-durable`, design D14).
The fixture box was laxer than the production host adapter it stands in for, which clears
the environment and composes it through `ChildEnvAllowlist`. This is the shape the previous
section forbids.

Two layers, each at its owner:

- **The build strips, subtractively.** `test-conventions` applies
  `TestEnvironmentHygiene` (`build-logic`), next to `AdversarialGitConfig`, to every `Test`
  task and to `pitest`. PIT's minions inherit the `pitest` JVM's environment. It removes
  every inherited `GNOMISH_*` variable from the forked JVM. It is subtractive and not an
  allowlist because the test JVM legitimately needs the machine's Docker, Gradle and
  Testcontainers variables, and listing them would be a list of the operator's tooling to
  maintain. A `GNOMISH_*` variable that a build script sets on purpose survives.
  `TestEnvironmentHygieneFunctionalSpec` pins both directions.
- **Every test spawner clears, then composes from an explicit list.** A test that starts a
  child process clears the `ProcessBuilder`'s environment first. It then puts back only
  what the child needs:
  - Where a production allowlist exists, the composition goes through it. The fixture box
    composes through `ChildEnvAllowlist` with the host base set, not a copy of it.
  - Otherwise it goes through `TestChildEnvironment.cleared(builder)` in `:test-fixtures`.
    That is the one spelling of the kept set: the host base set, the git test configuration,
    `JAVA_HOME`, and the Docker and Testcontainers client variables.
  - The spawner then adds the test-owned variables it sets itself (`GNOMISH_FAKE_*`,
    `GNOMISH_HOME`, `GNOMISH_DECISION_FILE`).

Gates, both in `:bootstrap`:

- `TestEnvironmentHygieneSpec` asserts that the test JVM sees no `GNOMISH_*` key. It also
  asserts that a fake-agent round run through the fixture box, with `GNOMISH_DECISION_FILE`
  planted in the parent environment, leaves that file untouched.
- `ProcessEnvironmentOwnerSpec` permits `environment().put(` and `environment().putAll(` in
  test sources and `test-fixtures/src/main` only in files that clear first, or in its
  exemption list, each with a reason. It asserts that the scan reached every listed file.

A new spawner that needs a variable the kept set lacks adds it to `TestChildEnvironment`, not
at the call site.

## Stand-ins are prepared, not generated

A stand-in binary a spec runs in place of `git`, `docker`, the agent CLI, a hook or a supervised
process is **committed** under `test-fixtures/src/main/resources/stand-in/` (ADR 0015): a
**preset** — the link `links/<preset>` to the table interpreter `stand-in.sh` and the section
`[<preset>]` of a table under `tables/` (grammar in the script's header; several presets to a table,
each table at most 120 lines) — or, for the supervisor specs whose subject is a signal or a fork,
one of the scripts under `process/`. The answers rows print are sections of the files under `data/`
(`data/stderr#unable-to-access`), shell steps live under `steps/`. A spec selects a preset by name
through `StandIn` in `:test-fixtures` (`StandIn.git('refuse-fetch')`, `StandIn.process('polite')`);
it never writes an executable file and never carries shell text. The one per-run artefact is a
symbolic link to a preset (`StandIn.recording`, `StandIn.link` for a name the spec chooses — a hook,
or the value a preset takes from its link's name), for a scenario that records or reads a file the
spec writes beside the link; the stand-in writes only there — its `<link>.log`, or a
`<link>.<name>` a `write` row names. A stand-in that must change behaviour mid-spec is re-pointed at
another preset (`StandIn.repoint`), not given a marker file to test.

The failure this exists for: 61 inline shell scripts in about fifty specs, each written into the
spec's temporary directory per test. macOS assesses every new executable file on its first direct
run (1–4 s, queued across PIT's minions; the second run 10 ms), so every mutant paid it again and
PIT spent 90 % of its minion time waiting on scripts, invisible on Linux CI and to the count-based
cost report. The same mechanism slowed the ordinary `test` task and PIT's coverage phase.

- **New behaviour is a new preset** — a link and a section in the table of its group — covered by
  the library's data-driven spec (`StandInLibrarySpec`), never shell in a spec; a new table action is
  a new feature there. Two presets that would differ in one word are one preset taking that word
  from its link's name. A preset holds no absolute path and writes nothing into the library — a
  `record`, `write`, `export-name` or `@name` row reached through the committed link is refused —
  so parallel JVMs share it and the OS assesses the script once per checkout.
- **A text a spec asserts is read from the library**, never retyped: `StandIn.data('stderr#name')`.
- **The library is immutable in a build.** Rewriting a committed script in place would change it
  under parallel JVMs and may trigger a fresh assessment.
- **Exemptions are named in the gate**, each with its reason: scripts run inside a container
  (`ContainerGitMechanicsSpec`, `FakeAgentSandboxImage` — Linux inside, a host link would not
  resolve; mount the library directory instead), specs of shipped scripts (`LauncherScriptSpec`,
  `ReleasePreflightScriptSpec`, `NightlyMutationIssueScriptSpec`), which run once per build, and
  `StallingGitOwnerSpec`, whose detector is fed shebang text as data.
- **The gate**: `StandInOwnerSpec` in `:bootstrap` scans every module's test tree and
  `test-fixtures/src/main` (comments stripped) for three shapes — an executable bit set from code
  (`executable = true`, `setExecutable(`), shebang text (`#!/`, which a directly run script needs),
  and a `chmod` granting execute spelled as command text; the files that match must be exactly the
  exemptions, each still matching, and the owner must be reached and clean. Permission-mode calls
  are not a shape: specs lock directories with them, and a file made executable that way still
  needs the shebang the gate catches.

Before designing anything "per test" — a script, a configuration file, a repository — list what
truly varies per run. Everything constant becomes a committed preset; only the remainder is
created, by one owner. The project record of that question is `design-decisions.md`,
"Alternative zero".

## Diagnosing a slow gate

A slow `test` or `pitest` is attributed before it is changed. The count-based mutation cost report
(`expensive-mutants.txt`) answers "which mutant needed many tests", not "where the seconds went":
on 2026-10-10 every module reported zero above threshold while PIT ran for 90 minutes.

1. **Split the phases.** PIT's log gives `Calculated coverage in N seconds` (one minion, the whole
   covering suite, single-threaded) and `Completed in N seconds`; the difference is the mutation
   phase. Multiply its wall time by `threads` for minion-seconds.
2. **Attribute the mutation phase.** Rerun with `--verbosity=VERBOSE` (the Gradle plugin has no
   switch: take the `MutationCoverageReport` command line from `./gradlew :<module>:pitest --rerun
   --info`, add the flag, run it under the build's `GIT_CONFIG_GLOBAL`). Each mutant's `Running
   mutation …` and result line give its duration; sum them. On 2026-10-10 the sum was 500–900 s
   of 8800 minion-seconds — the time was not in the tests.
3. **Look at the threads.** `jcmd <minion pid> Thread.print` during the mutation phase, three
   minions, two or three samples. A main thread in `ProcessImpl.waitFor` names the child it waits
   on; `ps -o ppid` lists the children. That one look found the stand-in scripts.
4. **Group by PIT's units, not by source class.** PIT runs all mutants of one *runtime* class
   (`Outer$Inner` is its own unit) serially in one fresh minion JVM; a gap between two result
   lines of what looks like one class may be two units. The parser mistake that produced the
   refuted "deadline-waiting mutants" reading was exactly this.
5. **Only then change something**, and re-measure the same five numbers (coverage seconds,
   mutation wall, minion-seconds, summed mutant time, sampled thread state) so the task report
   carries a before/after pair.

## Rules

- Maximize automated verification in task plans — avoid manual testing steps
- One capability per spec file; descriptive method names in natural language (Spock convention)
- Contract tests for every port: each adapter must pass the same port-level spec suite
- Integration tests are the slowest — scope runs to what the change affects
- **Specs observe effects, not private fields.** Groovy's direct field access (`obj.@field`) and
  plain property access to a private field bind a spec to names the production code is free to
  change: a rename fails the spec with no behavior regressed, and a chain of them
  (`a.@b.@c.assembly()`) fails on every refactoring of the graph between. Assert the wiring by
  the effect only it produces — a line in the ledger, a field of the snapshot, a payload the
  tracker received — on the real flow (`ServeRuntimeWiringSpec` is the in-repo model: one
  feature per wiring, each shown red with that wiring removed). Where no effect exists, the gap
  is a missing seam to design, not a field to reach. New `.@` reads are not added; the survivors
  in `ServeShutdownWiringSpec` and `ManualRunContainerDispatchSpec` are known debt
- **Every wire vocabulary has a round-trip spec.** When an enum is serialized to wire tokens
  by one component and parsed back by another (ledger, snapshot, state files), a data-driven
  spec must assert `fromWire(wire(e)) == e` for **every** constant — iterate `values()`, no
  hand-listed subset — and pin the unknown-token behavior (the documented forward-compat
  `default` arm). This is what keeps a writer/reader pair (see `manual-sync-pairs.md`) from
  drifting silently: adding a constant mapped on only one side fails the spec, not production
- **Invariant specs across a flow.** When a design decision claims that two values are one by
  construction (the law commit and the branch's start point; the pin and the commit it was
  read from), one spec asserts the identity end to end on the real medium — a bare origin, a
  clone, the real adapters — rather than trusting that each component's spec passed. Component
  specs prove each link is correct; only the flow spec proves the links are joined. The spec
  is named in the design's single-owner table (`design-decisions.md`)
- **Git fixtures are adversarial by default.** A clone fixture whose local refs equal origin's
  cannot see a resolution that took the wrong ref. The shared clone fixture
  (`BareGitRepoFixture` and its callers) therefore diverges the clone from origin as its
  default posture: a local branch under the base's name behind origin's tip, and a local tag
  under the same name pointing elsewhere. A spec that needs the converged posture asks for it
  explicitly. Any bare-name resolution then fails an existing spec instead of waiting for a
  regression spec someone thought to write

  The operator's git configuration is adversarial the same way: the whole test build — every
  `Test` task, the `pitest` task and the minions it forks, and the packaged jar the E2E harness
  spawns — runs with `GIT_CONFIG_GLOBAL` pointed at the committed
  `test-fixtures/src/main/resources/adversarial-gitconfig` (design D11 of
  `own-git-transfer-argv`), a global configuration that tries to widen every transfer
  (submodule recursion, prune, a URL rewrite onto `ext::`, a marking credential helper), and
  with an inherited per-process configuration (`GIT_CONFIG_COUNT` naming one inert key), so a
  spec asserting that a transfer strips what the factory process inherited sees a real
  difference rather than an unset that was never set. No
  spec plants a global git configuration of its own (a temporary global file, a `HOME`
  override): `AdversarialGitConfig` in `:test-fixtures` owns the file's meaning, and a spec that
  proves isolation from the operator's configuration calls `assertInEffect(runner)` first, so a
  run outside Gradle — where the file is not in force — fails instead of passing vacuously. A
  side effect is hermeticity: the developer's `~/.gitconfig` reaches no test, so the file itself
  supplies the committer identity the production adapters commit with (without it every such
  commit fails on a CI runner, where git cannot guess one, while passing on a workstation), and a fixture fetch
  spells its source in full (`refs/heads/<branch>:refs/remotes/origin/<branch>`): under
  `fetch.prune=true` git deletes the destination of a short-name source instead of writing it
