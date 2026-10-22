# Spec Delta

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Test changes widen the mutation scope
**Reason**: Removed as a temporary measure by narrow-branch-mutation-scope (NG1 names the pickup condition). On this project every code-bearing change edits the shared test fixtures, so the shared-fixture rule widened every branch run to the whole tree (3 h 08 min on the CI runner, 2026-10-21) and the branch scope was no scope. The whole-tree verdict on a weakened test or fixture is now owned by the scheduled whole-tree run alone; the scenario "A deleted class contributes nothing" moves to "Branch-scoped mutation target" unchanged.
**Migration**: The markers of "A weakened spec is caught" and "A shared fixture change mutates everything" are deleted with their features; the deleted-class feature keeps its marker, which now resolves under the branch-scope requirement.
