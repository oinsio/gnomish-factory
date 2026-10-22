# quality-gates

## Purpose

Defines the automated quality gates that guard the codebase: a single `./gradlew check` command that runs the test suite, coverage, and mutation testing, plus format, static-analysis, and dependency-hygiene gates, all enforced in CI alongside security scanning, on a reproducible build.

## Requirements

### Requirement: Single verification command
The build SHALL expose one command — `./gradlew check` — that compiles
production and test code, runs the full Spock suite, produces the JaCoCo
report, and runs PIT mutation testing. With the module tree in place, root
`check` SHALL aggregate every module's `check`, each running that module's PIT.
**Implements**: FR11 of split-into-modules

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
**Implements**: FR1 of scope-pit-to-changed-files

#### Scenario: Surviving mutant fails the build
- **WHEN** a production class in scope contains logic not killed by any test
- **THEN** `./gradlew check` fails
- **AND** the failure output names the mutation threshold and links to the HTML report

#### Scenario: Only Java production code is mutated
- **WHEN** PIT runs
- **THEN** its target classes include only Java production packages

### Requirement: Coverage reporting
JaCoCo SHALL produce XML and HTML coverage reports on every test run.
**Implements**: FR6, NFR-O1 of add-project-skeleton

#### Scenario: Reports generated
- **WHEN** the test task completes
- **THEN** JaCoCo XML and HTML reports exist under the build directory

### Requirement: Code format gate
The build SHALL enforce a consistent code format via a Spotless check wired into `./gradlew check`; formatting violations SHALL fail the build and be auto-fixable with `spotlessApply`.
**Implements**: FR8 of add-project-skeleton

#### Scenario: Misformatted code fails the gate
- **WHEN** a source file violates the configured format
- **THEN** `./gradlew check` fails naming the file
- **AND** running `spotlessApply` fixes the violation

### Requirement: Static analysis gate
Compilation SHALL fail on Error Prone bug patterns and NullAway null-safety violations within the production base package; Error Prone's unused-code checks (`UnusedMethod`, `UnusedVariable`) SHALL be treated as errors.
**Implements**: FR9 of add-project-skeleton

#### Scenario: Null-safety violation fails compilation
- **WHEN** production code dereferences a value that NullAway cannot prove non-null
- **THEN** compilation fails naming the violation

#### Scenario: Dead private code fails compilation
- **WHEN** production code contains an unused private method or variable
- **THEN** compilation fails with the unused-code check named

### Requirement: Dependency hygiene
The build SHALL detect unused and misdeclared dependencies and fail the gate on violations.
**Implements**: FR10 of add-project-skeleton

#### Scenario: Unused dependency fails the gate
- **WHEN** a declared dependency is not used by any source set
- **THEN** the dependency-analysis task fails naming the dependency

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every branch push and pull request
and SHALL publish JaCoCo and PIT reports and a Gradle profile report as build
artifacts.
**Implements**: FR5, FR7 of scope-pit-locally; FR2, NFR-C1 of add-release-pipeline; FR1, FR2, NFR-C1 of remove-ci-build-timeout; FR2, FR3, FR4, NFR-C1 of scope-pit-to-changed-files; FR11 of split-into-modules
**Notes**: A tag push starts no check run because the release workflow judges the run the tagged commit already has on `main`; a second run would waste the budget and shadow that verdict. CI passes no mutation scope because the build's own scope owner narrows the gate as a local run does; the whole-tree gate is the scheduled run's. The build job runs to completion under GitHub's 6-hour default.

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
- **WHEN** the CI build job, or the scheduled whole-tree mutation job, runs longer than 30 minutes
- **THEN** it keeps running and reports its real pass/fail verdict rather than being cancelled by a per-job timeout

#### Scenario: A superseded build run is cancelled
- **WHEN** a new commit is pushed to a ref whose CI build job is still running
- **THEN** the in-flight run for that ref is cancelled by workflow concurrency

#### Scenario: A hung job is bounded by the timeout
- **WHEN** a CI job other than those two, one that supports a per-job timeout, runs longer than its bound — 30 minutes, or 10 for the Pages deploy job
- **THEN** GitHub cancels the job with its standard timeout error rather than letting it run to the 6-hour default

#### Scenario: A reusable-workflow job is exempt from the timeout
- **WHEN** a CI job is a call of a reusable workflow, whose form forbids a per-job timeout
- **THEN** the job declares no `timeout-minutes` and is not held to the 30-minute bound

### Requirement: Security scanning
CI SHALL run security scanning: OSV-Scanner failing the run on known-vulnerable dependency versions and Gitleaks failing the run on committed secrets on every branch push and pull request, never on a tag push; CodeQL analysis of the codebase on every pull request and on pushes to `main`. An advisory on an artifact version resolved onto a production runtime classpath SHALL be fixed by a version pin, never allowlisted.
**Implements**: FR2, NFR-C1 of add-release-pipeline; FR1, FR2, FR6, FR7, FR8, FR9, NFR-S1, NFR-S2, NFR-S3 of fix-osv-dependency-gate
**Notes**: A superseded conflict candidate, the case the allowlist entry of `A production-scope advisory is fixed, never suppressed` names, is a version whose metadata `gradle/verification-metadata.xml` records although no committed lockfile resolves it.
**Notes**: Buildscript classpaths take their security overrides from `gradle.properties` because they cannot read the version catalog.

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
- **WHEN** an advisory affects an artifact version resolved onto a production runtime
  classpath
- **THEN** the build pins a non-vulnerable version of that artifact, and no committed lockfile
  resolves the vulnerable version
- **AND** an allowlist entry for that advisory exists only for a superseded conflict candidate:
  its reason names the candidate's version and states that it is metadata-only, and no
  committed lockfile resolves that version

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

#### Scenario: Every committed lockfile is scanned
- **WHEN** the OSV-Scanner job runs
- **THEN** it evaluates every committed Gradle lockfile in the repository — the root's and each module's, project and buildscript — against the one repository-root allowlist
- **AND** no lockfile is skipped or judged differently for the directory it lives in

#### Scenario: A security override names its advisory and its exit
- **WHEN** a security version override is added
- **THEN** it is declared in `gradle/libs.versions.toml`, or in `gradle.properties` for a buildscript classpath, naming the advisory it clears and the condition for dropping it
- **AND** the committed lock state is regenerated in the same diff, so the scanned lockfiles carry the overridden version

### Requirement: Reproducible build
The build SHALL be reproducible: the Gradle version is fixed by the wrapper, the Java toolchain is pinned to 25, and dependency versions are declared in a single location. Its outputs SHALL be reproducible as well as its inputs: two builds of the same commit with the same version SHALL produce byte-identical archives.
**Implements**: NFR-R1 of add-release-pipeline; FR3, FR4, FR5, NFR-R1, NFR-R2 of fix-osv-dependency-gate
**Notes**: The override scenarios concern a security override of a BOM-managed or `strictly`-constrained transitive; its effect is read from the committed lock state, not inferred from what the build script intends.

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

#### Scenario: Archives carry no order, time or mode of the build machine
- **WHEN** any archive task of any module runs — a jar, the boot jar, a distribution archive
- **THEN** it writes its entries in a fixed order, with no file timestamps and with fixed file modes

#### Scenario: No archive records when it was built
- **WHEN** the boot jar and the other archives are built
- **THEN** none embeds a generated file that records when it was made: the boot jar's build info carries no build time, and the SBOM stays outside the jar

### Requirement: Hardware-derived heavy-JVM budget
The build SHALL limit how many memory-heavy forked JVMs (test JVMs and the mutation engine)
run concurrently to a budget computed from the host's total RAM and processor count, reserving
fixed allowances for the build daemon and OS headroom. On hardware where the formula yields
less than one slot, the budget SHALL clamp to 1 so heavy tasks degrade to serial execution
instead of failing.
**Implements**: FR1, FR2, NFR-R1 of adapt-build-load-to-hardware

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
**Implements**: FR3 of adapt-build-load-to-hardware

#### Scenario: Fresh clone needs no tuning
- **WHEN** a developer clones the repository onto a machine meeting the documented minimum and
  runs `./gradlew build`
- **THEN** the build passes without modifying any committed configuration
- **AND** no committed `gradle.properties` file sets `org.gradle.workers.max`

### Requirement: Heavy-JVM budget override
The build SHALL accept a Gradle property that overrides the computed heavy-JVM budget; when
set (via command line, user-level Gradle properties, or CI environment) it SHALL take
precedence over the hardware formula.
**Implements**: FR4 of adapt-build-load-to-hardware

#### Scenario: Override takes precedence
- **WHEN** the build runs with the override property set to K
- **THEN** at most K heavy JVMs execute concurrently regardless of detected hardware

### Requirement: Budget decision is logged
The build SHALL log, once per build, the effective heavy-JVM budget together with its inputs —
detected RAM, detected processor count, and the override property when one is set — so an
operator can see why a given concurrency was chosen and detect a stale override.
**Implements**: NFR-O1, UX2 of adapt-build-load-to-hardware

#### Scenario: Computed budget is visible
- **WHEN** the build runs without the override property
- **THEN** the log names the computed budget, the detected RAM, and the detected core count

#### Scenario: Override is discoverable
- **WHEN** the build runs with the override property set
- **THEN** the log names the override property and its value as the source of the budget

### Requirement: Delegating decorator completeness gate
A named ArchUnit rule SHALL fail the build when a production class implements an interface and holds a same-type delegate — a field, constructor parameter, or record component of the interface type, including `Supplier` of it — without overriding every `default` method that interface declares (superinterfaces included).
**Implements**: FR9 of fix-denial-attribution-durability
**Notes**: A leaf implementer is out of scope because for a leaf the constant default is a truthful "I don't have this"; only for a delegator is it a silent replacement of the delegate's real behavior.
**Notes**: The allowlist of justified exemptions sits beside the rule, one entry per class and method; a justified exemption is a self-delegating overload whose inherited body is the decorator's documented intent.

#### Scenario: An unforwarded default fails the build
- **WHEN** a production class holds a delegate of the interface it implements and leaves one of that interface's default methods unoverridden, and it is not in the allowlist
- **THEN** the architecture rule fails the build naming the class and the method

#### Scenario: A leaf implementer is untouched
- **WHEN** a production class implements an interface with defaults but holds no same-type delegate
- **THEN** the rule does not constrain it

#### Scenario: An allowlisted self-delegating overload passes with its reason recorded
- **WHEN** a delegating class inherits a default whose body delegates to an abstract method the class does override, and the class is allowlisted with a reason
- **THEN** the rule passes and the allowlist entry names the class, the method, and the reason

#### Scenario: The rule's own spec proves it can fail
- **WHEN** the rule's own spec runs
- **THEN** it seeds a delegating class that leaves an interface default unforwarded and asserts the rule fails it

### Requirement: Dependency license gate
CI SHALL run a dependency-license gate over the runtime classpath of every distributed
module (the factory boot jar's module and the plugin-contract module) on every push and on
fork pull requests, in a workflow of its own, separate from `./gradlew check`. The gate
SHALL fail when a resolved module declares no license the allowlist accepts.
**Implements**: FR4, FR5, FR6, FR7, FR8, FR12, NFR-R1, NFR-R2, NFR-O1, NFR-O2, NFR-S2, NFR-C1, UX1 of add-project-license
**Notes**: The functional build test runs its miniature project against an offline repository. The plugin enters through the catalog, the lockfiles and the verification metadata so that the dependency-verification and OSV gates cover it like every other plugin.

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

#### Scenario: One file holds the accepted licenses
- **WHEN** the gate decides whether a declared license is accepted
- **THEN** it reads the one repository allowlist file, and no workflow, build script or plugin configuration names an accepted license of its own

#### Scenario: The allowlist accepts the permissive set and no copyleft
- **WHEN** the allowlist file is read
- **THEN** it accepts the licenses known under the SPDX identifiers Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1 and GPL-2.0 with Classpath Exception, each spelled as the gate's normalizer names it
- **AND** it accepts no LGPL, GPL or AGPL license in any version

#### Scenario: The shipped inventory needs no module exception
- **WHEN** the allowlist is read for the shipped inventory
- **THEN** it holds no rule scoped to one module
- **AND** a module-scoped exception added later states its reason beside it

#### Scenario: Every run attaches the license inventory
- **WHEN** a license-gate run completes, green or red
- **THEN** the full generated license inventory of the distributed modules is attached to the run

### Requirement: Branch-scoped mutation target
Each module's `pitest` SHALL target a mutation scope the build computes from one
owner. With no `pitScope` property the scope is the branch's changes: the
module's production classes whose source files differ, in the working tree
(committed, staged, unstaged and untracked) against the scope base, and
nothing else — a changed test source or shared test fixture SHALL not widen
any module's scope.
**Implements**: FR1, FR4, FR5, NFR-P1, NFR-R2, NFR-R3, NFR-R4, UX1 of scope-pit-locally; FR1, FR5 of scope-pit-to-changed-files; FR11 of split-into-modules; FR1, FR2, FR4, NFR-R1, NFR-R2 of narrow-branch-mutation-scope
**Notes**: The scope is obtained through a configuration-cache-validated read, which is what makes an edit between two builds re-resolve it.
**Notes**: The whole-tree verdict on a weakened test or fixture is the scheduled whole-tree run's; see NG1 of narrow-branch-mutation-scope for the condition that restores a branch-side rule.

#### Scenario: Default run mutates the branch's changed classes
- **WHEN** `./gradlew check` runs with no `pitScope` property on a branch that
  changed production files in some modules
- **THEN** each of those modules' PIT run targets exactly the classes derived
  from its changed files, uncommitted edits included
- **AND** every module the branch did not touch skips its mutation task
- **AND** the 100% mutation-score gate and `pitestVerifyAllKilled` apply to the
  scoped set

#### Scenario: A test or fixture change widens nothing
- **WHEN** a branch changes, adds or deletes files under a module's test source
  tree, or under the shared test-fixtures module's sources, and changes no
  production file in some module
- **THEN** that module's mutation task is skipped
- **AND** a module whose production files did change still targets exactly its
  changed classes, not its whole production tree

#### Scenario: A deleted class contributes nothing
- **WHEN** a branch deletes a production source file and changes nothing else in
  that module
- **THEN** that module's mutation task is skipped

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
**Implements**: FR2, NFR-R1 of scope-pit-locally

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

### Requirement: Mutation scope is announced
The build SHALL log one lifecycle line per build naming the scope mode
(`branch`, `all`, `explicit`), the resolved base commit, the changed-class
count per module, and any fallback reason. The line SHALL be logged even when
the scope leaves every module's mutation task skipped.
**Implements**: NFR-O1, UX2 of scope-pit-locally; FR3, NFR-O1, UX1 of narrow-branch-mutation-scope

#### Scenario: A run that mutates nothing says so
- **WHEN** a scoped run resolves its base and no module has a changed class
- **THEN** the log still carries the scope line, naming the base and that every
  module was skipped

#### Scenario: A branch run explains itself
- **WHEN** a scoped run resolves its base
- **THEN** the log carries the base commit, its origin, the per-module class
  counts of the mutated modules and the number of skipped modules
- **AND** the line names no module as mutated whole: the only whole-tree form is
  the `all` mode's

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
**Implements**: FR6, NFR-S1, NFR-C1, UX4 of scope-pit-locally; G3, NFR-C1 of narrow-branch-mutation-scope

#### Scenario: Cross-file mutation rot is caught within a day
- **WHEN** a mutant in an unchanged class stops dying because of a change
  merged elsewhere
- **THEN** the next scheduled run fails on that module and an issue names it
  with a link to the run and its PIT report artifact

#### Scenario: A weakened test or fixture is caught within a day
- **WHEN** a merged branch changed a test source or a shared test fixture so
  that a mutant in a production class the branch did not change now survives
- **THEN** the branch's own gate did not mutate that class, and the next
  scheduled run fails on that class's module and reports it as the previous
  scenario does

#### Scenario: A repeat failure updates the open issue
- **WHEN** the scheduled run fails while the issue from a previous failure is
  still open
- **THEN** a comment is added to that issue instead of a second issue being
  opened

#### Scenario: An unchanged main is not re-mutated
- **WHEN** the scheduled run starts and `main` is at the commit the previous
  successful scheduled run verified
- **THEN** the run skips the gate and reports success

### Requirement: Forked test JVMs carry no operator-process variables
The test build SHALL remove every `GNOMISH_*` environment variable it did not set itself from the environment of every forked test JVM — each `Test` task and the `pitest` task, whose minions inherit that JVM's environment. Apart from the Docker client configuration, which the test build replaces with its own, the strip SHALL leave every other inherited variable as it is.
**Implements**: FR22 of make-checkpoint-gate-durable; FR13 of fix-terminal-receipt-convergence
**Notes**: A test thereby observes only the factory variables its own spawner sets on a child, never those of the process that launched the build. The strip is subtractive because the test JVM legitimately depends on the machine's tooling variables; its owner is applied beside the pinned test git configuration.

#### Scenario: A variable of the launching process does not reach the test JVM
- **WHEN** a build is launched from a process whose environment holds `GNOMISH_DECISION_FILE` and runs a module's `test` task
- **THEN** `System.getenv()` inside the forked test JVM holds no `GNOMISH_*` key

#### Scenario: PIT minions are stripped the same way
- **WHEN** the same build runs a module's `pitest` task
- **THEN** no mutation minion observes the variable

#### Scenario: The machine's tooling variables survive
- **WHEN** the launching process holds `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED`
- **THEN** the forked test JVM still observes both

#### Scenario: Only the factory's own namespace is removed
- **WHEN** the launching process holds variables outside the `GNOMISH_` namespace — the machine's Gradle and Testcontainers variables and `DOCKER_HOST` among them — other than `DOCKER_CONFIG` and `DOCKER_CONTEXT`
- **THEN** the forked test JVM observes each of them unchanged

#### Scenario: A variable the build sets itself survives
- **WHEN** a build script sets a `GNOMISH_*` variable on a test task on purpose
- **THEN** the forked test JVM observes that variable

#### Scenario: Every module gets the strip from its test conventions
- **WHEN** a module applies the shared test conventions plugin
- **THEN** its `Test` tasks and its `pitest` task strip the variables through the one build-logic owner, with no configuration in the module's own build file

### Requirement: Mutation cost report
After every PIT run the build SHALL write a mutation-cost report beside the PIT
report listing each mutant whose test-execution count exceeds a documented
threshold — class, method, line, mutator, count and killing test, sorted by
count descending — and SHALL log one lifecycle line naming the module, the
threshold, the count above it and the most expensive mutant. The report SHALL
never fail the build.
**Implements**: FR3, FR4, NFR-O1, NFR-C1, UX1 of kill-expensive-mutants

#### Scenario: Hotspots are listed
- **WHEN** a module's PIT run produced `mutations.xml` with mutants above the
  threshold
- **THEN** `expensive-mutants.txt` beside it lists exactly those mutants, most
  expensive first
- **AND** the build log carries one line with the count above threshold and the
  top mutant as `class.method:line (mutator) — N tests`

#### Scenario: A cheap module reports nothing to look at
- **WHEN** no mutant exceeds the threshold
- **THEN** the report file states that and the log line says zero above threshold

#### Scenario: A skipped gate writes no report
- **WHEN** a module's `pitest` task was skipped (empty scope, no production code)
- **THEN** the report task is skipped as well and the build succeeds
- **AND** a `mutations.xml` an earlier run left in that module's build directory is
  neither read nor described by the report

#### Scenario: Cost never gates
- **WHEN** a mutant needs any number of test executions to die
- **THEN** `check` and `pitestAll` still succeed as long as every mutation was
  killed

### Requirement: Stand-in binaries are committed presets
A stand-in binary a test runs SHALL be a committed script under
`test-fixtures`; each scenario SHALL be a committed preset, a table section and
nothing else, so the library commits no links. A spec SHALL select a preset by
name, SHALL NOT write an executable file or shell text, and the only artefact
made at test time SHALL be a symbolic link to the script, made by the one
owner fixture.
**Implements**: FR24, NFR-P2, M11 of supervise-daemon-loops-and-embed-dashboard
**Notes**: Design provenance: D23, D26 of supervise-daemon-loops-and-embed-dashboard; ADR 0015.

#### Scenario: A spec selects a preset
- **WHEN** a spec needs a `git` that refuses `fetch` with a documented refusal
- **THEN** it names the `refuse-fetch` preset and hands production the link the
  owner fixture created for it in this JVM
- **AND** the spec reads the refusal text it asserts from the library's data
  file, not from a second literal

#### Scenario: A plain selection is one link per JVM, a scenario with a name one per run
- **WHEN** two specs in one JVM select the same preset without recording
- **THEN** both receive the one link the owner made for it in that JVM
- **AND** a scenario that records, reads a file the spec writes beside the
  link, or needs a name of its own (a hook, a fake-agent scenario) receives a
  per-run link to that link

#### Scenario: A recording scenario gets one link, not one script
- **WHEN** a spec must read back the arguments a stand-in received
- **THEN** the owner fixture creates one symbolic link to the preset in the
  spec's temporary directory and the script writes its log beside the link
- **AND** no file under the spec's directory is executable other than the link

#### Scenario: The library holds no link
- **WHEN** the library is listed
- **THEN** it holds the script, the tables, the data, the steps and the
  process scripts, and no symbolic link
- **AND** the library's own spec enumerates presets from the tables' sections

#### Scenario: The second test starts the stand-in in milliseconds
- **WHEN** two specs in different JVMs run the same preset
- **THEN** the operating system assesses the committed script at most once per
  build, and each start costs milliseconds on macOS as on Linux

### Requirement: Each stand-in behaviour exists once
The stand-in library SHALL hold each behaviour once: two presets that differ
in one word SHALL be one preset taking that word from its link's name; a
process-shaped fake whose whole behaviour is an exit code, a delay or a stall
SHALL be a table preset; and the library's own spec SHALL fail on a table row
that none of its features reaches.
**Implements**: FR26, M11 of supervise-daemon-loops-and-embed-dashboard
**Notes**: Design provenance: D26 of supervise-daemon-loops-and-embed-dashboard.

#### Scenario: A refusal that differs only in its text is one preset
- **WHEN** a spec needs a fetch refused with one of the refusals in
  `data/stderr-fetch`
- **THEN** it links the one `action-answer-by-name` preset under the
  refusal's name, and the script prints that refusal and exits 1

#### Scenario: A dead row fails the library spec
- **WHEN** a table row is added that no feature of the library's spec reaches
- **THEN** the library spec fails naming the preset and the row

### Requirement: Specs above a subprocess adapter fake its port in process
A spec whose subject is a class above a subprocess adapter SHALL drive an
in-process fake of the adapter's role interface, not a stand-in binary. Every
production holder of the git runner SHALL depend on the role interface, and a
gate SHALL fail on a holder typed as the class.
**Implements**: FR27, M13 of supervise-daemon-loops-and-embed-dashboard
**Notes**: Design provenance: D27 of supervise-daemon-loops-and-embed-dashboard.

#### Scenario: A first-push spec runs no process
- **WHEN** `FirstPushSpec` exercises the retry and the remote-tip re-check
- **THEN** the push, the `ls-remote` and the `rev-parse` answers come from the
  scripted in-process fake, and no git process is started

#### Scenario: The fake is as strict as the table was
- **WHEN** the subject calls the fake with an argv no row's prefix matches
- **THEN** the fake throws naming the argv, as the stand-in exits 97

#### Scenario: A process-owning class keeps its stand-in
- **WHEN** the subject is one of the classes that own a `ProcessBuilder`, an
  end-to-end suite, or a scenario that hands part of the argv to the real git
- **THEN** it runs a stand-in or the real binary, and the fake is not used

#### Scenario: A termination spec interrupts a blocked call, not a sleep
- **WHEN** a reconciler termination spec interrupts a reconciliation in flight
- **THEN** the fake's blocked call is where the interrupt lands, released by
  the spec, and the subject classifies nothing

#### Scenario: A holder typed as the class fails the gate
- **WHEN** a production class outside the adapter class and the composition
  root declares a field or parameter of the runner's concrete type
- **THEN** the boundary gate in `:bootstrap` fails naming the file

### Requirement: One owner of executable files in test sources
A build gate SHALL fail on any test-source site, outside the owner fixture and
the stand-in library, that creates an executable file or carries shebang text,
except in named exemptions each listed with its reason, and SHALL assert that
its scan reached every exempted file.
**Implements**: FR24, M11 of supervise-daemon-loops-and-embed-dashboard
**Notes**: Design provenance: D23 of supervise-daemon-loops-and-embed-dashboard.

#### Scenario: Only three kinds of file are exempt
- **WHEN** the gate lists its exemptions
- **THEN** each is a script run inside a container, a spec of a shipped
  script, or a gate's own seeded sources that hold shebang text as data for its
  detector and never write it to disk

#### Scenario: A spec that writes its own script fails the gate
- **WHEN** a test source outside the owner fixture and the named exemptions
  marks a file executable or contains shebang text
- **THEN** the owner gate in `:bootstrap` fails naming the file

#### Scenario: A moved exemption fails instead of widening the allowance
- **WHEN** an exempted file is renamed or moved without updating the gate
- **THEN** the gate fails because its scan did not reach the listed file

### Requirement: Out-of-process bootstrap suites are outside the mutation scan
The `bootstrap` suites that drive a real Docker daemon or a Gitea container and
whose production classes are all covered by in-process specs SHALL be listed
in the module's excluded test classes with the rationale the `testing.md` bar
requires; suites that run on the fake sandbox docker over a real bare
repository SHALL stay in the scan.
**Implements**: FR25 of supervise-daemon-loops-and-embed-dashboard
**Notes**: Design provenance: D24 of supervise-daemon-loops-and-embed-dashboard.

#### Scenario: The nine Docker and Gitea suites are excluded
- **WHEN** PIT runs for `:bootstrap`
- **THEN** its coverage pass runs none of `FrozenTimeEquipmentRunSpec`,
  `GiteaBestEffortPushE2ESpec`, `GiteaCrossInstanceResumeE2ESpec`,
  `RunParkKillPointContainerE2ESpec`, `RunParkRecordingContainerE2ESpec`,
  `SandboxLifecycleCrossInstanceE2ESpec`, `SandboxLifecycleLegacyIdentityE2ESpec`,
  `SandboxLifecycleRemnantReapE2ESpec`, `GiteaActionsStageVerifyE2ESpec`
- **AND** every mutant of the classes they reach is still killed by an
  in-process spec

#### Scenario: The fake-docker suites stay in the scan
- **WHEN** PIT runs for `:bootstrap`
- **THEN** `ContainerResumeEscalationSpec`, `ContainerResumeRunnerSpec` and
  `TransitionKillPointSpec` remain among its covering tests

### Requirement: Spec traceability line
Every requirement of a Requirement/Scenario spec under `openspec/specs/` or in the delta of an
unarchived change SHALL carry exactly one `**Implements**:` metadata line, naming the requirement
ids and the change that introduced them, after the requirement text and before its first
scenario. The HTML-comment traceability form (an `implements` comment) SHALL NOT appear in those trees; archived changes are
untouched.
**Implements**: FR1 of tighten-spec-contract
**Notes**: The line is metadata for OpenSpec's reader, so it does not count toward its requirement-length limit; `openspec show --json` omits it from the requirement text, which is why the project's checker reads the files rather than the CLI's output.

#### Scenario: A requirement without the line fails the gate
- **WHEN** a requirement under `openspec/specs/` has no `**Implements**:` line
- **THEN** `checkSpecContract` fails, naming the spec file, the requirement heading and the line to add

#### Scenario: A retired comment form fails the gate
- **WHEN** a spec under `openspec/specs/` or an unarchived delta contains an HTML comment opening with `implements`
- **THEN** `checkSpecContract` fails, naming the file and line and the metadata line that replaces it

#### Scenario: Two traceability lines fail the gate
- **WHEN** a requirement carries two `**Implements**:` lines
- **THEN** `checkSpecContract` fails, asking for one line with the ids joined by `;`

#### Scenario: Archived deltas are not read
- **WHEN** a delta under `openspec/changes/archive/` still carries the comment form
- **THEN** `checkSpecContract` reports nothing about it

### Requirement: Spec notes are one-line and non-normative
A `**Notes**:` metadata line of a requirement SHALL be one physical line that contains neither
`SHALL` nor `MUST`. A line of ordinary text directly after any metadata line SHALL be reported
as a wrapped note. Notes carry what explains a requirement and belongs nowhere else; a
normative statement is a requirement or a scenario.
**Implements**: FR2 of tighten-spec-contract

#### Scenario: A note with a normative word fails the gate
- **WHEN** a `**Notes**:` line contains the word `SHALL` or `MUST`
- **THEN** `checkSpecContract` fails, naming the file and line and asking for a scenario or a requirement instead

#### Scenario: A wrapped note fails the gate
- **WHEN** a `**Notes**:` line is followed by a line of ordinary text before the next scenario
- **THEN** `checkSpecContract` fails, naming the continuation line as a wrapped note

#### Scenario: Several one-line notes pass
- **WHEN** a requirement carries three consecutive `**Notes**:` lines, each one physical line without `SHALL` or `MUST`
- **THEN** `checkSpecContract` reports nothing about them

### Requirement: Scenario-to-test link
For every scenario of every requirement of a main spec, except a requirement that the delta of
an unarchived change REMOVES, at least one test source SHALL contain the marker
`// Scenario: <spec-id> / <title>` with the title verbatim, and every such marker in the test
sources SHALL name a scenario that exists under a main-spec requirement or under an ADDED or
MODIFIED requirement of the delta of an unarchived change. A scenario verified by a mechanism
that is not a test SHALL be listed in the gate's exemption list with that mechanism, and the
list is asserted reached: an entry naming no scenario of a main spec fails the gate.
**Implements**: FR5 of tighten-spec-contract; FR6 of fix-terminal-receipt-convergence
**Notes**: `<spec-id>` is the capability path OpenSpec lists (`quality-gates`, `verification/verification-hardening`); the title is the text after `#### Scenario:` with surrounding whitespace trimmed. Test sources are every module's `src/test` (`*/src/test`, `*/*/src/test`), the test trees of the `build-checks` included build (`build-checks/*/src/test`) and `build-logic/src/functionalTest`.
**Notes**: A marker written with its test resolves through the change's delta until the archive merges the scenario into the main spec; the archive's own gate run then resolves it there, with no edit to the marker.
**Notes**: A REMOVED requirement retires the main-spec requirement of the same title in the same capability path (`delta-specs.md`). Retirement only waives the link: a marker or exemption line that still names a retired scenario is neither dangling nor stale until the archive moves the scenario out of the main spec, when the archive's own gate run reports it. A delta that removes a requirement whose tests still run (a change proposed but not yet implemented) therefore turns nothing red.

#### Scenario: A scenario with no marker fails the gate
- **WHEN** a main spec holds a scenario, under a requirement no unarchived delta removes, whose title appears in no marker of any test source and in no exemption entry
- **THEN** `checkSpecContract` fails, naming the spec id, the title and the marker to write above the feature that exercises it

#### Scenario: A dangling marker fails the gate
- **WHEN** a test source carries a marker whose spec id or title names no scenario of a main spec and no scenario of an ADDED or MODIFIED requirement in the delta of an unarchived change
- **THEN** `checkSpecContract` fails, naming the test file and line and the nearest existing title

#### Scenario: A marker naming an unarchived delta's scenario is not dangling
- **WHEN** a test source carries a marker whose spec id and title name a scenario that exists only under an ADDED or MODIFIED requirement in the delta of an unarchived change
- **THEN** `checkSpecContract` reports nothing about the marker, with or without `--changes`

#### Scenario: A marker naming a removed or archived scenario is dangling
- **WHEN** a test source carries a marker whose title exists only under a REMOVED requirement of an unarchived delta, or only in a delta under `openspec/changes/archive/`
- **THEN** `checkSpecContract` fails, reporting the marker as dangling

#### Scenario: A malformed marker fails the gate
- **WHEN** a test source carries a `// Scenario:` line that is not in the `<spec-id> / <title>` form
- **THEN** `checkSpecContract` fails, naming the test file and line and the form to write

#### Scenario: A renamed scenario breaks the link on both sides
- **WHEN** a scenario title is changed in the main spec and its marker is not
- **THEN** `checkSpecContract` reports the new title as unlinked and the old marker as dangling in the same run

#### Scenario: An exempt scenario passes with its reason
- **WHEN** a scenario is verified by a CI workflow and the exemption list names the scenario and that workflow
- **THEN** `checkSpecContract` reports nothing about it

#### Scenario: A stale exemption fails the gate
- **WHEN** the exemption list names a scenario that no main spec holds
- **THEN** `checkSpecContract` fails, naming the entry to remove

#### Scenario: A scenario of a requirement removed by an unarchived delta need not be linked
- **WHEN** a main spec holds a scenario with no marker and no exemption entry, and the delta of an unarchived change removes its requirement by title
- **THEN** `checkSpecContract` reports nothing about the scenario, with or without `--changes`
- **AND** a delta under `openspec/changes/archive/` that removes a requirement of the same title retires nothing

#### Scenario: A marker or exemption naming a retired scenario is accepted
- **WHEN** a test source carries a marker, or the exemption list carries an entry, naming a scenario that a main spec holds only under a requirement an unarchived delta removes
- **THEN** `checkSpecContract` reports nothing about the marker or the entry, with or without `--changes`

#### Scenario: Scenarios of unarchived deltas are not linked
- **WHEN** a delta of an unarchived change holds a scenario with no marker anywhere
- **THEN** `checkSpecContract` reports nothing about it, with or without `--changes`

Amended by design D14 of fix-terminal-receipt-convergence: the Docker client configuration
variables are replaced by the build's own (the ADDED requirement "Forked test JVMs use the
build's own Docker client configuration"), so "Only the factory's own namespace is removed" now
excepts `DOCKER_CONFIG` and `DOCKER_CONTEXT`; every other scenario is unchanged.

### Requirement: One spec-contract checker, two launchers
The spec-contract rules SHALL be implemented once, as a Gradle task `checkSpecContract` that the
root `check` task depends on. The manual launcher `scripts/check-spec-contract.sh` and the
verification commands of the fix-artifacts and archive stages SHALL run that task rather than a
second implementation; the task SHALL read files only and produce no subprocess.
**Implements**: FR6, NFR-R1, NFR-O1, NFR-S1, NFR-C1, UX1 of tighten-spec-contract

#### Scenario: Root check runs the gate
- **WHEN** a developer runs `./gradlew check` on a tree that violates one spec-contract rule
- **THEN** the build fails in `checkSpecContract` before any module's mutation gate is consulted

#### Scenario: The manual launcher is the same gate
- **WHEN** a developer runs `scripts/check-spec-contract.sh --changes` on that tree
- **THEN** the output names the same findings as the Gradle task, plus those of the unarchived deltas for the traceability-line and notes rules

#### Scenario: Findings read like the other gates
- **WHEN** the gate fails
- **THEN** every finding names the file, the line, the rule and what to write instead, and the run ends with one summary line of scenarios checked, linked and exempt

#### Scenario: Spec and test text is never executed
- **WHEN** a spec or test file under a scanned tree contains shell-like text (`$(…)`, backquotes, a pipe) in a requirement, a note or a marker
- **THEN** `checkSpecContract` reads it as data, starts no process, and quotes it verbatim in any finding it names

#### Scenario: The gate is cacheable
- **WHEN** `./gradlew check` runs twice with no change under `openspec/` or any test tree
- **THEN** the second `checkSpecContract` is reported up to date

### Requirement: Production source files stay within the size cap
A production Java source file that a change adds or modifies SHALL stay within the 200-line
hard cap of `.claude/rules/process-invariants.md`; a file that exceeds it SHALL be split along
a responsibility seam into focused units, each within the cap, without changing observable
behaviour.
**Implements**: FR1, FR2 of fix-oversized-adapters
**Notes**: The cap is a review rule, not a build gate: the implementation audit (`/audit-implementation`, project-rule conformance) checks the files a change touches against it, so a file over the cap that no change touches is not reported.
**Notes**: The scenario `Extracted units keep the same behaviour and observability` was the acceptance of the archived change `fix-oversized-adapters` and left with the old heading `Source file size cap` (FR16 of tighten-spec-contract).

#### Scenario: An oversized adapter is split into compliant units
- **WHEN** a change adds or grows a production source file past the 200-line cap
- **THEN** the implementation audit's project-rule conformance step
  (`.claude/commands/audit-implementation.md`) reports the file against the hard cap of
  `.claude/rules/process-invariants.md`
- **AND** the remedy that rule asks for is a split along a responsibility seam, so the file and
  every unit extracted from it are ≤ 200 lines

### Requirement: App-layer specs build the assembly through one shared fixture
The test suite SHALL provide a single shared fixture (a plain Groovy
trait, no Spring test context) that constructs the standard app-layer
engine-assembly collaborator set and its `FactoryProperties` test
values; app-layer specs SHALL obtain the assembly through this fixture
instead of inlining the construction block, keeping one construction
site for the standard set in test sources, apart from the named
deviations of `One construction site for the standard assembly`.
**Implements**: FR1, FR2, FR3 of refactor-app-spec-fixtures
**Notes**: The two named deviations are the fixture's own full-graph runner builder, which builds the assembly the composition root builds, and `ManualRunAssemblyCheckClientWiringSpec`, whose subject is a collaborator the fixture does not cover.
**Notes**: The scenario `Fixture adoption preserves behavior` was the acceptance of the archived change `refactor-app-spec-fixtures` and left with the old heading `Shared app-layer assembly fixture` (FR16 of tighten-spec-contract).

#### Scenario: One construction site for the standard assembly
- **WHEN** test sources are searched for direct construction of the
  standard engine assembly (`new ManualRunAssembly`)
- **THEN** the standard set is constructed at one site — the shared fixture's
  `newAssembly`
- **AND** every other site is a named deviation: the fixture's full-graph
  runner builder and `ManualRunAssemblyCheckClientWiringSpec`

#### Scenario: A spec deviates from the fixture defaults
- **WHEN** an app-layer spec needs a non-default collaborator (custom
  console streams, agent binary, instance name)
- **THEN** it passes the deviation as an argument of the fixture factory
  method, whose parameters are positional with defaults (input, output,
  properties, time equipment), with overloads taking only the properties,
  or the properties and the time equipment
- **AND** the parts after its last deviation remain defaulted and unspelled
  at the call site; a default before a deviation may be spelled

### Requirement: Forked test JVMs use the build's own Docker client configuration
Every forked test JVM — each `Test` task and the `pitest` task, whose minions inherit its environment — SHALL run with `DOCKER_CONFIG` naming a per-task copy of one committed Docker client configuration that names no credential helper, no plugin hooks and no proxies, and with the operator's daemon endpoint handed on as `DOCKER_HOST`.
**Implements**: FR13, FR14, FR15, NFR-C3, UX4 of fix-terminal-receipt-convergence
**Notes**: The endpoint is the operator's `DOCKER_HOST` when set, else the current context's endpoint from `docker context inspect`; on a macOS host with a `unix://` endpoint `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` is `/var/run/docker.sock` unless the operator set it.

#### Scenario: The operator's credential helper is not consulted
- **WHEN** the operator's Docker configuration names a `credsStore` and the build runs a module's `test` task
- **THEN** the test JVM's `DOCKER_CONFIG` names a configuration with no `credsStore`, `credHelpers`, `proxies` or plugin hooks, and the operator's configuration directory is not it

#### Scenario: PIT minions run under the same configuration
- **WHEN** the same build runs a module's `pitest` task
- **THEN** no mutation minion observes the operator's `DOCKER_CONFIG`

#### Scenario: An operator's DOCKER_HOST is handed on unchanged
- **WHEN** the launching process holds `DOCKER_HOST`
- **THEN** the test JVM observes the same value and the build runs no `docker` process to resolve it

#### Scenario: Without DOCKER_HOST the current context's endpoint is handed on
- **WHEN** the launching process holds no `DOCKER_HOST` and the operator's current context names an endpoint
- **THEN** the test JVM observes that endpoint as `DOCKER_HOST` and observes no `DOCKER_CONTEXT`

#### Scenario: On macOS Testcontainers mounts the daemon's own socket path
- **WHEN** the host is macOS, the endpoint is a `unix://` socket and the operator set no `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
- **THEN** the test JVM observes `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` as `/var/run/docker.sock`

#### Scenario: On Linux no socket override is set
- **WHEN** the host is Linux and the operator set no `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
- **THEN** the test JVM observes no `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`

#### Scenario: The operator's CLI plugins are still found
- **WHEN** the operator's Docker configuration directory holds a `cli-plugins` directory
- **THEN** the per-task copy's `cliPluginsExtraDirs` names it

#### Scenario: An endpoint the build cannot hand on fails with the remedy
- **WHEN** the operator set no `DOCKER_HOST` and the current context's endpoint is `ssh://` or carries TLS material
- **THEN** the build fails naming `DOCKER_HOST`, `DOCKER_CERT_PATH` and `DOCKER_TLS_VERIFY`

#### Scenario: A machine without Docker still builds
- **WHEN** no `docker` binary is on the path or the current context cannot be inspected
- **THEN** the build sets no `DOCKER_HOST`, the test task runs, and the Docker specs skip through their availability checks

#### Scenario: Machine-specific values do not split the cache
- **WHEN** two machines differ only in their Docker endpoint and plugin directory
- **THEN** a test task's cache key is the same on both

#### Scenario: Every module gets the Docker configuration from its test conventions
- **WHEN** a module applies the shared test conventions plugin
- **THEN** its `Test` tasks and its `pitest` task carry the configuration through the one build-logic owner, with no configuration in the module's own build file
