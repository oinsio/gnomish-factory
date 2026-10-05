# quality-gates

## Purpose

Defines the automated quality gates that guard the codebase: a single `./gradlew check` command that runs the test suite, coverage, and mutation testing, plus format, static-analysis, and dependency-hygiene gates, all enforced in CI alongside security scanning, on a reproducible build.

## Requirements

### Requirement: Single verification command
The build SHALL expose one command — `./gradlew check` — that compiles
production and test code, runs the full Spock suite, produces the JaCoCo
report, and runs PIT mutation testing. With the module tree in place, root
`check` SHALL aggregate every module's `check`, each running that module's PIT.
<!-- implements FR11 of split-into-modules -->

#### Scenario: One command runs everything
- **WHEN** a developer runs `./gradlew check` on a fresh clone with JDK 25
- **THEN** compilation, Spock tests, JaCoCo, and PIT all execute across every
  module
- **AND** the command exits 0 only if every gate in every module passes

#### Scenario: A single module's check is self-contained
- **WHEN** a developer runs `check` for one module
- **THEN** that module's tests, coverage, and PIT mutation testing run
- **AND** no other module's production classes are mutated

### Requirement: Mutation testing gate
PIT SHALL mutate Java production classes only and SHALL fail the build when the mutation score is below the threshold (100%; explicitly documented exceptions may lower it to 95% for code unreachable by unit tests). The set of mutated classes MAY be narrowed to an explicit scope (see "Branch-scoped mutation target"); the threshold and documented exceptions apply unchanged to whichever classes are in scope.
<!-- implements FR1 of scope-pit-to-changed-files -->

#### Scenario: Surviving mutant fails the build
- **WHEN** a production class in scope contains logic not killed by any test
- **THEN** `./gradlew check` fails
- **AND** the failure output names the mutation threshold and links to the HTML report

#### Scenario: Only Java production code is mutated
- **WHEN** PIT runs
- **THEN** its target classes include only Java production packages

### Requirement: Coverage reporting
JaCoCo SHALL produce XML and HTML coverage reports on every test run.

#### Scenario: Reports generated
- **WHEN** the test task completes
- **THEN** JaCoCo XML and HTML reports exist under the build directory

### Requirement: Code format gate
The build SHALL enforce a consistent code format via a Spotless check wired into `./gradlew check`; formatting violations SHALL fail the build and be auto-fixable with `spotlessApply`.

#### Scenario: Misformatted code fails the gate
- **WHEN** a source file violates the configured format
- **THEN** `./gradlew check` fails naming the file
- **AND** running `spotlessApply` fixes the violation

### Requirement: Static analysis gate
Compilation SHALL fail on Error Prone bug patterns and NullAway null-safety violations within the production base package; Error Prone's unused-code checks (`UnusedMethod`, `UnusedVariable`) SHALL be treated as errors.

#### Scenario: Null-safety violation fails compilation
- **WHEN** production code dereferences a value that NullAway cannot prove non-null
- **THEN** compilation fails naming the violation

#### Scenario: Dead private code fails compilation
- **WHEN** production code contains an unused private method or variable
- **THEN** compilation fails with the unused-code check named

### Requirement: Dependency hygiene
The build SHALL detect unused and misdeclared dependencies and fail the gate on violations.

#### Scenario: Unused dependency fails the gate
- **WHEN** a declared dependency is not used by any source set
- **THEN** the dependency-analysis task fails naming the dependency

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every branch push and pull request
and SHALL publish JaCoCo and PIT reports and a Gradle profile report as build
artifacts. A tag push SHALL NOT start the check workflow: the release workflow
judges the run the tagged commit already has on `main`, and a second run for the
same commit would waste the budget and shadow that verdict. The workflow SHALL
pass no mutation scope: the build's own scope owner narrows the gate to the
branch's changes exactly as a local run does, and the whole-tree gate is the
scheduled run's. The CI build job SHALL NOT declare a per-job timeout: it runs
to completion under GitHub's 6-hour default, and superseded in-flight runs for
the same ref SHALL be cancelled by workflow concurrency. Every other CI workflow
job that supports a per-job timeout SHALL set `timeout-minutes: 30`; a job whose
reusable-workflow form forbids a per-job timeout is exempt.
<!-- implements FR5, FR7 of scope-pit-locally -->
<!-- implements FR2, NFR-C1 of add-release-pipeline -->
<!-- implements FR1, FR2, NFR-C1 of remove-ci-build-timeout -->
<!-- implements FR2, FR3, FR4, NFR-C1 of scope-pit-to-changed-files -->
<!-- implements FR11 of split-into-modules -->

#### Scenario: CI enforces the gates
- **WHEN** a commit is pushed with a failing test or a surviving mutant in a
  changed production class
- **THEN** the CI run fails

#### Scenario: A tag push starts no check run
- **WHEN** a `v*` tag is pushed on a commit that already has a CI run on `main`
- **THEN** no CI, license-gate, OSV-Scanner or Gitleaks run starts for the tag
  ref, and the release workflow finds exactly the `main` push run for that
  commit

#### Scenario: Mutation gate is scoped to the diff
- **WHEN** the check workflow runs on a branch push
- **THEN** its Gradle invocation carries no `pitScope` value and no step
  resolves a diff base or a changed-file list
- **AND** the mutation gate's scope equals what `./gradlew check` on that tree
  resolves locally

#### Scenario: Diff with no production changes still passes
- **WHEN** a branch changes only docs or CI configuration
- **THEN** the CI mutation run targets no classes and passes without a "No
  mutations found" failure

#### Scenario: Reports are downloadable
- **WHEN** a CI run completes (pass or fail)
- **THEN** JaCoCo and PIT reports and the Gradle profile report are attached as
  artifacts

#### Scenario: A long build run is not cancelled
- **WHEN** the CI build job runs longer than 30 minutes
- **THEN** it keeps running and reports its real pass/fail verdict rather than being cancelled by a per-job timeout

#### Scenario: A superseded build run is cancelled
- **WHEN** a new commit is pushed to a ref whose CI build job is still running
- **THEN** the in-flight run for that ref is cancelled by workflow concurrency

#### Scenario: A hung job is bounded by the timeout
- **WHEN** a CI job other than the build job, one that supports a per-job timeout, runs longer than 30 minutes
- **THEN** GitHub cancels the job with its standard timeout error rather than letting it run to the 6-hour default

### Requirement: Security scanning
CI SHALL run security scanning: OSV-Scanner failing the run on known-vulnerable dependency versions and Gitleaks failing the run on committed secrets on every branch push and pull request, never on a tag push; CodeQL analysis of the codebase on every pull request and on pushes to `main`.

The OSV-Scanner gate SHALL evaluate **every** committed Gradle lockfile in the
repository — root and per-module, project and buildscript — against a **single**
repository-root suppression allowlist, independent of the directory each
lockfile lives in. Adding a module that contributes a lockfile SHALL require no
per-module scan configuration.

A suppression in that allowlist SHALL be permitted only for an artifact confined
to test or buildscript classpaths, SHALL state the affected scope and why no
adoptable fix exists, and SHALL carry an expiry date after which the gate fails
again. No advisory affecting a production runtime classpath may be suppressed;
it is fixed by pinning a non-vulnerable version instead.

Security version overrides SHALL be declared in the project's single version
source (`gradle/libs.versions.toml`, or `gradle.properties` for buildscript
classpaths, which cannot read the catalog), each naming the advisory it clears
and the condition for dropping the override; the committed lock state SHALL be
regenerated so the scanned lockfiles reflect them.

The project SHALL document one command that reproduces the CI scan verdict
locally.
<!-- implements FR2, NFR-C1 of add-release-pipeline -->
<!-- implements FR1, FR2, FR6, FR7, FR8, FR9, NFR-S1, NFR-S2, NFR-S3 of fix-osv-dependency-gate -->

#### Scenario: Vulnerable dependency fails CI
- **WHEN** a dependency version with a known OSV/CVE advisory is present in the build
- **THEN** the OSV-Scanner job fails naming the dependency and advisory

#### Scenario: Committed secret fails CI
- **WHEN** a commit contains a string matching a known secret pattern
- **THEN** the Gitleaks job fails identifying the offending commit and location

#### Scenario: The allowlist governs a module lockfile
- **WHEN** an advisory listed in the repository-root allowlist appears in a
  lockfile inside a module directory rather than at the repository root
- **THEN** the scan treats it as suppressed and does not fail the run
- **AND** no configuration file exists in that module directory to make it so

#### Scenario: A newly added module needs no scan wiring
- **WHEN** a module is added to the build and contributes its own lockfile
- **THEN** the existing allowlist applies to it with no edit to the scan job or
  to any per-module configuration

#### Scenario: A production-scope advisory is fixed, never suppressed
- **WHEN** an advisory affects an artifact resolved onto a production runtime
  classpath
- **THEN** the build pins a non-vulnerable version of that artifact
- **AND** the allowlist contains no entry for that advisory

#### Scenario: A suppression expires
- **WHEN** the current date passes a suppression's recorded expiry date
- **THEN** the OSV-Scanner job fails on that advisory again, forcing a
  re-decision

#### Scenario: A reviewer can judge a suppression in place
- **WHEN** a reviewer opens the allowlist
- **THEN** each entry states the affected classpath scope, why no adoptable fix
  exists, and its expiry date, without consulting git history or an external
  document

#### Scenario: The failure names the responsible module
- **WHEN** the scan fails on a vulnerable artifact
- **THEN** the reported row identifies the package, version, advisory ID, and the
  source lockfile path, so the owning module is identifiable without re-running
  the scan

#### Scenario: A developer reproduces the CI verdict locally
- **WHEN** a developer regenerates the lock state and runs the documented local
  scan command
- **THEN** the verdict matches what CI reports for the same lock state,
  including which advisories are suppressed

### Requirement: Reproducible build
The build SHALL be reproducible: the Gradle version is fixed by the wrapper, the Java toolchain is pinned to 25, and dependency versions are declared in a single location. A security override of a BOM-managed or `strictly`-constrained transitive SHALL be applied on every configuration where the affected artifact resolves, and its effect SHALL be visible in the committed lock state rather than implied by build-script intent.

The build's outputs SHALL be reproducible as well as its inputs: every archive task of every module (jars, the boot jar, distribution archives) SHALL write a fixed entry order, no file timestamps and fixed file modes, and no archive SHALL embed a generated file that records when it was made — the boot jar's build info SHALL carry no build time, and the SBOM SHALL stay outside the jar — so that two builds of the same commit with the same version produce byte-identical archives.
<!-- implements NFR-R1 of add-release-pipeline -->
<!-- implements FR3, FR4, FR5, NFR-R1, NFR-R2 of fix-osv-dependency-gate -->

#### Scenario: Wrapper pins the toolchain
- **WHEN** the project is built on a machine with a different default JDK
- **THEN** Gradle uses the pinned Java 25 toolchain or fails with a clear message

#### Scenario: An override reaches every configuration that resolves the artifact
- **WHEN** a security override pins an artifact that several modules resolve
  transitively
- **THEN** every lockfile listing that artifact records the pinned version
- **AND** a module the override fails to reach is detected as a stale lockfile
  entry rather than passing silently

#### Scenario: The scan reads regenerated lock state
- **WHEN** a version override is changed in the single version source
- **THEN** the lock state is regenerated and committed before the scan is
  considered authoritative

#### Scenario: Archive outputs are byte-identical
- **WHEN** any module's archive task runs twice from the same commit with the
  same version
- **THEN** both outputs have the same SHA-256 sum

### Requirement: Source file size cap
Every production Java source file SHALL stay within the 200-line hard cap of
`.claude/rules/process-invariants.md`; a file that exceeds it SHALL be split
along a cohesive seam into focused units, each within the cap, without changing
observable behaviour. The cap is enforced in review, not by build tooling.
<!-- implements FR1, FR2 of fix-oversized-adapters -->

#### Scenario: An oversized adapter is split into compliant units
- **WHEN** a production source file exceeds the 200-line cap
- **THEN** it is split so the file and every unit extracted from it is ≤ 200 lines
- **AND** the extraction is behaviour-preserving — no existing spec is modified
  and `./gradlew check` stays green

#### Scenario: Extracted units keep the same behaviour and observability
- **WHEN** logic is moved from an oversized file into a package-private collaborator
- **THEN** the public entry point's behaviour, exit codes, and log output are unchanged
- **AND** coverage and PIT mutation score on the affected classes are no lower than before

### Requirement: Shared app-layer assembly fixture
The test suite SHALL provide a single shared fixture (a plain Groovy
trait, no Spring test context) that constructs the standard app-layer
engine-assembly collaborator set and its `FactoryProperties` test
values; app-layer specs SHALL obtain the assembly through this fixture
instead of inlining the construction block, keeping exactly one
construction site for the standard set in test sources.
<!-- implements FR1, FR2, FR3 of refactor-app-spec-fixtures -->

#### Scenario: One construction site for the standard assembly
- **WHEN** test sources are searched for direct construction of the
  standard engine assembly (`new ManualRunAssembly`)
- **THEN** exactly one site is found — inside the shared fixture trait

#### Scenario: A spec deviates from the fixture defaults
- **WHEN** an app-layer spec needs a non-default collaborator (custom
  console streams, agent binary, instance name)
- **THEN** it passes the deviation as an explicit named argument to the
  fixture factory method
- **AND** all defaulted parts remain invisible at the call site

#### Scenario: Fixture adoption preserves behavior
- **WHEN** the app-layer specs are migrated to the shared fixture
- **THEN** `./gradlew test` passes with the same number of executed
  tests as before the migration
- **AND** no production source changes and no assertion changes are part
  of the migration

### Requirement: Hardware-derived heavy-JVM budget
The build SHALL limit how many memory-heavy forked JVMs (test JVMs and the mutation engine)
run concurrently to a budget computed from the host's total RAM and processor count, reserving
fixed allowances for the build daemon and OS headroom. On hardware where the formula yields
less than one slot, the budget SHALL clamp to 1 so heavy tasks degrade to serial execution
instead of failing. Tasks that do not fork heavy JVMs (compilation, static analysis,
formatting) SHALL NOT be constrained by this budget.
<!-- implements FR1, FR2, NFR-R1 of adapt-build-load-to-hardware -->

#### Scenario: Concurrency scales with the machine
- **WHEN** a clean `./gradlew build` runs on a machine whose RAM fits N test JVMs beside the
  daemon and headroom
- **THEN** at most N heavy JVMs execute concurrently
- **AND** the build completes green with no resource-induced failures (minion deaths,
  worker-handshake timeouts, truncated HTTP-stub connections)

#### Scenario: Small machine degrades to serial heavy tasks
- **WHEN** the build runs on a machine where the formula yields zero or negative slots
- **THEN** heavy JVMs run one at a time
- **AND** the build still configures and executes

#### Scenario: Light tasks keep full parallelism
- **WHEN** the heavy-JVM budget is saturated
- **THEN** compilation, static-analysis, and formatting tasks continue to run in parallel at
  Gradle's default worker count

### Requirement: No committed per-machine worker cap
The repository SHALL NOT commit a machine-specific global worker limit
(`org.gradle.workers.max`); a fresh clone SHALL build green with default Gradle settings on any
machine meeting the documented hardware minimum, with no edits to committed files.
<!-- implements FR3 of adapt-build-load-to-hardware -->

#### Scenario: Fresh clone needs no tuning
- **WHEN** a developer clones the repository onto a machine meeting the documented minimum and
  runs `./gradlew build`
- **THEN** the build passes without modifying any committed configuration
- **AND** `git grep org.gradle.workers.max` finds no committed occurrence

### Requirement: Heavy-JVM budget override
The build SHALL accept a Gradle property that overrides the computed heavy-JVM budget; when
set (via command line, user-level Gradle properties, or CI environment) it SHALL take
precedence over the hardware formula.
<!-- implements FR4 of adapt-build-load-to-hardware -->

#### Scenario: Override takes precedence
- **WHEN** the build runs with the override property set to K
- **THEN** at most K heavy JVMs execute concurrently regardless of detected hardware

### Requirement: Budget decision is logged
The build SHALL log, once per build, the effective heavy-JVM budget together with its inputs —
detected RAM, detected processor count, and the override property when one is set — so an
operator can see why a given concurrency was chosen and detect a stale override.
<!-- implements NFR-O1, UX2 of adapt-build-load-to-hardware -->

#### Scenario: Computed budget is visible
- **WHEN** the build runs without the override property
- **THEN** the log names the computed budget, the detected RAM, and the detected core count

#### Scenario: Override is discoverable
- **WHEN** the build runs with the override property set
- **THEN** the log names the override property and its value as the source of the budget

### Requirement: Delegating decorator completeness gate
A named ArchUnit rule SHALL fail the build when a production class implements an interface and holds a same-type delegate — a field, constructor parameter, or record component of the interface type, including `Supplier` of it — without overriding every `default` method that interface declares (superinterfaces included). Leaf implementers (no same-type delegate) are out of scope: for a leaf the constant default is a truthful "I don't have this"; only for a delegator is it a silent replacement of the delegate's real behavior. Justified exemptions — self-delegating overloads whose inherited body is the decorator's documented intent — SHALL live in a named allowlist beside the rule, one entry per class and method with the reason. The rule's own spec SHALL seed a violating class and assert the rule fails it.
<!-- implements FR9 of fix-denial-attribution-durability -->

#### Scenario: An unforwarded default fails the build
- **WHEN** a production class holds a delegate of the interface it implements and leaves one of that interface's default methods unoverridden, and it is not in the allowlist
- **THEN** the architecture rule fails the build naming the class and the method

#### Scenario: A leaf implementer is untouched
- **WHEN** a production class implements an interface with defaults but holds no same-type delegate
- **THEN** the rule does not constrain it

#### Scenario: An allowlisted self-delegating overload passes with its reason recorded
- **WHEN** a delegating class inherits a default whose body delegates to an abstract method the class does override, and the class is allowlisted with a reason
- **THEN** the rule passes and the allowlist entry names the class, the method, and the reason

### Requirement: Dependency license gate
CI SHALL run a dependency-license gate over the runtime classpath of every distributed
module (the factory boot jar's module and the plugin-contract module) on every push and on
fork pull requests, in a workflow of its own, separate from `./gradlew check`.

The gate SHALL read every license each resolved module declares, normalize license-name
variants to one canonical name, and fail when a module declares no license the allowlist
accepts. A module declaring several licenses SHALL pass when at least one is accepted.

One repository file SHALL be the only source of accepted licenses. It SHALL accept the
licenses known under the SPDX identifiers Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause,
EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1 and GPL-2.0 with Classpath Exception — spelled
as the gate's normalizer names them — and SHALL NOT accept LGPL, GPL or AGPL in any version.
Module-scoped exceptions SHALL not be needed for the shipped inventory; adding one SHALL
state the reason beside it.

A failing gate SHALL name each violating module with its coordinates and every license it
declares in the job log, SHALL attach the violation report to the run, and every run SHALL
attach the full generated license inventory of the distributed modules. The gate SHALL be
covered by a functional build test over a miniature project with an offline repository. The
project SHALL document one command that reproduces the CI verdict locally.

The gate's build plugin SHALL enter the build through the version catalog and the committed
lockfiles and verification metadata, so the dependency-verification and OSV gates cover it
like every other plugin.
<!-- implements FR4, FR5, FR6, FR7, FR8, FR12, NFR-R1, NFR-R2, NFR-O1, NFR-O2, NFR-S2, NFR-C1, UX1 of add-project-license -->

#### Scenario: Copyleft-only dependency fails CI
- **WHEN** a resolved runtime module declares only LGPL-2.1 (or GPL, or AGPL)
- **THEN** the license job fails naming the module's coordinates and its declared licenses
- **AND** the violation report is attached to the run

#### Scenario: Dual-licensed dependency passes without an exception
- **WHEN** a resolved runtime module declares `EPL-2.0` and `LGPL-2.1-only`
- **THEN** the license job passes for that module
- **AND** the allowlist contains no entry naming that module

#### Scenario: Spelling variant is not a violation
- **WHEN** a resolved runtime module declares `The Apache Software License, Version 2.0`
- **THEN** the gate normalizes it to the canonical Apache-2.0 name and passes

#### Scenario: Test-only dependency is outside the gate
- **WHEN** a test-scope dependency declares a license the allowlist does not accept
- **THEN** the license job is unaffected, because only runtime classpaths are evaluated

#### Scenario: Version bump needs no allowlist edit
- **WHEN** a dependency version is bumped and its declared licenses are unchanged
- **THEN** the license job passes with the allowlist unchanged

#### Scenario: Functional test pins the semantics
- **WHEN** the build-logic functional test suite runs
- **THEN** a miniature module declaring only LGPL fails the gate
- **AND** a miniature module declaring EPL-2.0 and LGPL-2.1 passes
- **AND** a miniature module declaring an Apache spelling variant passes

#### Scenario: The gate plugin is locked and verified
- **WHEN** the gate's plugin version is bumped
- **THEN** the bump lands in the version catalog, the buildscript lockfile and the
  verification metadata in one diff
- **AND** a plugin jar missing from the verification metadata fails the build before any
  task of it runs

#### Scenario: Local reproduction
- **WHEN** a maintainer runs the documented command on the same lock state CI evaluated
- **THEN** the verdict and the report equal CI's

### Requirement: Branch-scoped mutation target
Each module's `pitest` SHALL target a mutation scope the build computes from one
owner. With no `pitScope` property the scope is the branch's changes: the
module's production classes whose source files differ, in the working tree
(committed, staged, unstaged and untracked) against the scope base. `-PpitScope=all`
or requesting `pitestAll` selects the module's whole production tree; an explicit
comma-separated glob list keeps the subset the module owns. A module whose
resolved scope is empty skips its gate cleanly — its mutation task, its verdict
task and its report finalizer together, so a report an earlier run left in the
module's build directory is never judged. The scope is obtained through a
configuration-cache-validated read, so an edit between two builds re-resolves it;
its computation runs `git` only — never the test suite, never class files.
<!-- implements FR1, FR4, FR5, NFR-P1, NFR-R2, NFR-R3, NFR-R4, UX1 of scope-pit-locally -->
<!-- implements FR1, FR5 of scope-pit-to-changed-files -->
<!-- implements FR11 of split-into-modules -->

#### Scenario: Default run mutates the branch's changed classes
- **WHEN** `./gradlew check` runs with no `pitScope` property on a branch that
  changed production files in some modules
- **THEN** each of those modules' PIT run targets exactly the classes derived
  from its changed files, uncommitted edits included
- **AND** every module the branch did not touch skips its mutation task
- **AND** the 100% mutation-score gate and `pitestVerifyAllKilled` apply to the
  scoped set

#### Scenario: Whole tree on request
- **WHEN** `./gradlew check -PpitScope=all` or `./gradlew pitestAll` runs
- **THEN** every module mutates its full production package tree
- **AND** the union of module scopes equals the full production tree

#### Scenario: Property narrows the mutation scope
- **WHEN** a module's `check` runs with `pitScope` set to one or more class globs
- **THEN** PIT mutates only the classes matching those globs that the module owns
- **AND** the module skips its gate when none of them is mutable there

#### Scenario: A property that names no glob is refused
- **WHEN** `pitScope` is set but holds no class glob (`-PpitScope=`, a blank
  `pitScope=` in a `gradle.properties`, only commas)
- **THEN** the build fails naming the empty property, rather than skipping
  every module's mutation task

#### Scenario: An abbreviated pitestAll is refused
- **WHEN** `pitestAll` is requested by an abbreviation Gradle accepts (`pA`)
  and `pitScope` is not `all`
- **THEN** the build fails before any task runs, asking for the full name or
  `-PpitScope=all`, rather than mutating the branch scope

#### Scenario: No resolvable base preserves full-project mutation
- **WHEN** `./gradlew check` runs without the `pitScope` property and no scope
  base can be resolved (no default-branch ref, a root commit, not a git checkout)
- **THEN** every module mutates its full production package tree — the fallback
  is the whole tree, never an empty scope

#### Scenario: Empty scope is a clean pass
- **WHEN** a module's resolved scope holds no mutable production class
- **THEN** that module's mutation task is skipped and the build succeeds
- **AND** the build does NOT fail with PIT's "No mutations found" error
- **AND** a `mutations.xml` left in that module's build directory by an earlier
  run is not re-judged: the verdict task and the report finalizer skip with the
  mutation task

#### Scenario: An edit between two builds re-resolves the scope
- **WHEN** `./gradlew check` runs twice with no edit in between, then a
  production file is edited and it runs a third time, with the configuration
  cache on
- **THEN** the second build reuses the cached configuration and the same scope
- **AND** the third build re-obtains the scope and the edited class is in it

#### Scenario: Scope computation is git-only
- **WHEN** the scope is computed in branch mode
- **THEN** at most three `git` processes run — four when the base comes from
  `origin/main` because no local `main` exists — and no test task or class file
  is read; in whole-tree mode via the property, none runs

#### Scenario: The operator's git configuration cannot narrow the scope
- **WHEN** the operator's global git configuration ignores a path pattern that
  matches an untracked production class, or quotes non-ASCII paths
- **THEN** the untracked class is still scoped and the non-ASCII path is mapped
  to its class
- **AND** the build's `git` processes take no optional index lock, so a
  concurrent `git status` on the same checkout is not refused

### Requirement: Mutation scope base
The scope base SHALL be the merge base of `HEAD` with the default branch
(`main`, else `origin/main`). When the build runs on the branch `main` itself,
the base SHALL be `HEAD`'s first parent; on any other branch a merge base equal
to `HEAD` SHALL stay the base. When no base can be resolved — no default-branch
ref, a root commit, not a git checkout — the scope SHALL be the whole tree.
Resolution failures SHALL never narrow the scope.
<!-- implements FR2, NFR-R1 of scope-pit-locally -->

#### Scenario: Feature branch diffs against its merge base
- **WHEN** the build runs on a branch that forked from `main` and `main` has
  since moved
- **THEN** the scope base is the merge base, so classes changed only on `main`
  are not scoped in

#### Scenario: The default branch diffs against its first parent
- **WHEN** the build runs with `HEAD` on the branch `main`
- **THEN** the scope base is `HEAD^`, so a squash or merge commit's own changes
  are mutated

#### Scenario: A fresh branch scopes only its uncommitted work
- **WHEN** the build runs on a branch other than `main` that has no commits of
  its own (its merge base with `main` is `HEAD`) and carries uncommitted edits
- **THEN** the scope base is `HEAD`, so only the uncommitted and untracked
  changes are mutated — not the last commit of `main`

#### Scenario: No resolvable base widens to the whole tree
- **WHEN** neither `main` nor `origin/main` exists, or `HEAD` has no parent, or
  the project directory is not a git checkout
- **THEN** every module mutates its whole production tree
- **AND** the scope line names the fallback reason

### Requirement: Test changes widen the mutation scope
A changed file under a module's test source tree SHALL widen that module's scope
to its whole production tree. A changed file under the shared test-fixtures
module's sources SHALL widen every module's scope to its whole production tree.
A deleted or renamed-away production file SHALL contribute no class.
<!-- implements FR3 of scope-pit-locally -->

#### Scenario: A weakened spec is caught
- **WHEN** a branch changes only a test file in one module (for example deletes
  an assertion from a killing spec)
- **THEN** that module's whole production tree is mutated and the mutant the
  assertion killed survives, failing the gate

#### Scenario: A shared fixture change mutates everything
- **WHEN** a branch changes a file under the shared test-fixtures module
- **THEN** every module mutates its whole production tree

#### Scenario: A deleted class contributes nothing
- **WHEN** a branch deletes a production source file and changes nothing else in
  that module
- **THEN** that module's mutation task is skipped

### Requirement: Mutation scope is announced
The build SHALL log one lifecycle line per build naming the scope mode
(`branch`, `all`, `explicit`), the resolved base commit, the changed-class
count per module, and any widening or fallback reason. The line SHALL be logged
even when the scope leaves every module's mutation task skipped.
<!-- implements NFR-O1, UX2 of scope-pit-locally -->

#### Scenario: A run that mutates nothing says so
- **WHEN** a scoped run resolves its base and no module has a changed class
- **THEN** the log still carries the scope line, naming the base and that every
  module was skipped

#### Scenario: A branch run explains itself
- **WHEN** a scoped run resolves its base
- **THEN** the log carries the base commit, the per-module class counts, and the
  widening reason for any module widened by a test change

#### Scenario: A fallback is visible
- **WHEN** the base could not be resolved
- **THEN** the log line says the whole tree is mutated and why

#### Scenario: A one-module run speaks only of its own modules
- **WHEN** a run schedules the mutation task of some modules only
  (`./gradlew :domain:check`)
- **THEN** the scope line counts and names only those modules, not the
  branch's changes in modules the run does not mutate

### Requirement: Scheduled whole-tree mutation run
A scheduled CI workflow SHALL run the whole-tree gate on `main` nightly and on
manual dispatch, SHALL upload the PIT reports, and on failure SHALL create a
tracker issue — or comment on the still-open issue from a previous run — naming
the failing modules and linking the run. It SHALL skip when `main` is unchanged
since the previous successful scheduled run, through a mechanism that reads no
other run's artifacts. Its token SHALL carry only `contents: read` and
`issues: write`.
<!-- implements FR6, NFR-S1, NFR-C1, UX4 of scope-pit-locally -->

#### Scenario: Cross-file mutation rot is caught within a day
- **WHEN** a mutant in an unchanged class stops dying because of a change
  merged elsewhere
- **THEN** the next scheduled run fails on that module and an issue names it
  with a link to the run and its PIT report artifact

#### Scenario: A repeat failure updates the open issue
- **WHEN** the scheduled run fails while the issue from a previous failure is
  still open
- **THEN** a comment is added to that issue instead of a second issue being
  opened

#### Scenario: An unchanged main is not re-mutated
- **WHEN** the scheduled run starts and `main` is at the commit the previous
  successful scheduled run verified
- **THEN** the run skips the gate and reports success
