## Review Resolution: own-git-invocation-policy

Source: temporary-docs/gnomish/github-oinsio-gnomish-factory-102/review-artifacts.md

### 1 — applied
- Recommendation: CRITICAL — Deleting `harden` deletes the reftable-backend precondition
- Re-verified: `adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitTaskBranches.java:80-85` — `harden` runs `refFormat.require(cloneDir)` ("The backend first: a clone the factory refuses is not written to") and then `hardening.harden(cloneDir)`; `TakeFreshClaim.java:64` and `GitModeRunner.java:101` say "The backend check inside harden first: a refused clone gets no worktree-registry write"; `FactoryRegisterOwnerSpec.groovy:119-122` pins `new FactoryCloneRefFormat(` to `GitTaskBranches.java` and `TouchpointFetch.java` (constructed at `GitTaskBranches.java:63`).
- Edit: `design.md` D4 — "Removed" now names only the two hardening classes and the write inside `harden`; new "Kept, renamed" bullet: the port method becomes `TaskBranchGit.requireRefFormat(Path)`, `GitTaskBranches` keeps only `refFormat.require`, the decorator passes it through, the seven call sites call it at the same position, `FactoryRegisterOwnerSpec` unchanged. Name chosen as `requireRefFormat` (the review's `requireBackend` was "for example") to match `FactoryCloneRefFormat.require`. Reconciled: `design.md` D9 (pairs keep the call under the new name; markers reworded to that step); `design.md` D10 row 2 (previous writers: renamed / write removed; "Old way removed": the config write deleted, the port method keeps only the precondition); `tasks.md` 3.2 (rewritten: delete the classes and their spec, rename the port method, keep each call at its position, rewrite `RunnerStartHardeningSpec` to assert the backend check still runs before the first write and no configuration is written, `FactoryRegisterOwnerSpec` stays green unedited); `tasks.md` 3.3 (markers replace "harden" by the ref-backend check); `proposal.md` REMOVED bullet, FR6 ("The clone hardening write SHALL be removed; the ref-backend check … keeps its place ahead of the first write"), M1 (count of `harden` still → 0, the sites remaining under the check's own name) and Impact (`:adapters:git`, `:application`). Task 3.4's greps keep their expectations (`harden(` is still empty after the rename).

### 2 — applied
- Recommendation: WARNING — D2 does not say how one entry point avoids prefixing a transfer twice
- Re-verified: `adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitProcessRunner.java:198-199,223-227,303-310,431` — the string entry, `run(Path, GitTransfer)` (via `runClassified`) and `run(Path, RefTransaction)` all reach one `execute`, which builds `new ProcessBuilder(commandLine(...))`; `design.md` D2 said both "the single entry point" and "the typed transfer entry does not prefix the pairs a second time".
- Edit: `design.md` D2 — `commandLine` is idempotent on the policy: an `args` already opening with the owner's pairs (every `GitTransfer.argv()`) gets nothing added, so all three runner entries reach the same `commandLine`, the owner has one producer, and a transfer carries the pairs once. `tasks.md` 1.1 gains the spec case "an argv that already opens with the pairs is returned with them once"; `tasks.md` 2.1's "runs the value's argv as-is, without prefixing again" becomes "through the same `commandLine`, which adds no second copy of the pairs (D2)".

### 3 — applied
- Recommendation: WARNING — The spec and D6 disagree on who plants the replace ref
- Re-verified: `specs/git/git-invocation-policy/spec.md:115-120` (before the edit) listed "a replace ref" in the fixture's default posture; `design.md:167-168` says "The replace-ref vector is planted by the identity spec itself; the fixture cannot know which commit to target"; `tasks.md` 4.2 plants four vectors, 5.1 plants the replace ref.
- Edit: `specs/git/git-invocation-policy/spec.md` requirement "The policy holds on an adversarial local configuration" — the fixture's posture lists the four configuration vectors; "The identity spec SHALL plant the replace ref on the commit it reads, and SHALL assert that none of the vectors ran"; scenario "The posture is in force" THEN reads "every planted configuration vector". Reconciled `proposal.md` M4: 4 configuration vectors in the shared fixture across the existing suites, 5 with the identity spec's replace ref, 0 executed. FR8 already describes the identity spec's five vectors and stays.

### 4 — applied
- Recommendation: SUGGESTION — Task 2.3 runs two of the three old-way patterns D10 row 1 names
- Re-verified: `design.md` D10 row 1 names three old-way greps including `"core.hooksPath=`; `tasks.md` 2.3 ran two. The quoted form hits only `gitobjects/.../GitExec.java:131`; the unquoted form also hits `EnvironmentAttemptPersistence.java:93`, `EnvironmentRoundSnapshot.java:55`, `EnvironmentSalvage.java:90,94` (in-box `git -c core.hooksPath= commit|checkout` scripts).
- Edit: `tasks.md` 2.3 — third command `grep -rn '"core.hooksPath=' --include=*.java . | grep /src/main/` (expected empty after 2.2; the in-box unquoted hits are the box's git, NG6), "Record the three commands". `design.md` D10 row 1 "Old way removed" — one sentence classifying the three `Environment*` in-box scripts as the box's git (NG6). Trivially safe: it aligns the task with the sweep the design already names; no behaviour or scope changes.

### 5 — applied
- Recommendation: SUGGESTION — The Why and one scenario describe a `git commit` the factory no longer runs
- Re-verified: `adapters/git/.../WorktreeClosingCommit.java:84` and `gitobjects/.../CommitBuilder.java:125` run `commit-tree`; `grep '"commit"'` over production Java finds no factory `git commit`; `FactoryRegisterOwnerSpec.groovy:130` "FR13: no production source runs a factory-side git commit".
- Edit: `proposal.md` Why — the signing bullet names `git commit-tree`; the maintenance bullet says `fetch`, and any future factory `git commit`, would start it; the hooks bullet that said a planted hook runs "during the factory's `git commit`" is merged into the next one, which names the runner's `update-ref` and `push` (the same stale `git commit` claim the recommendation's title covers). `specs/git/git-invocation-policy/spec.md` scenario retitled "Factory git starts no background maintenance", WHEN "the factory writes a commit object and moves a ref in a clone whose loose-object count exceeds the automatic maintenance threshold", THEN "…started by those commands" (unarchived ADDED scenario, no marker exists yet). `proposal.md` Impact "Layered on" now names `make-pipe-reads-non-parking` (archived 2026-10-20; `GitExec`/`CaptureRunner` read through `ChildOutput` on lines this change does not edit).

### 6 — applied
- Recommendation: SUGGESTION — Task 1.3's verify list omits the spec the constructor invariant breaks
- Re-verified: `adapters/git/src/test/.../ScriptedGitSpec.groovy:87-93` builds `new GitTransfer(['-c', 'fetch.prune=false', 'fetch', '--no-tags', 'origin'], new TreeMap())`, which D2's compact-constructor invariant refuses.
- Edit: `tasks.md` 1.3 Verify — adds `ScriptedGitSpec` ("the transfer owner's value is matched and logged by its argv"), rebuilt through the owner's `fetch` factory and green. A reference fix to the verify list; without it task 1.3 hands a red spec to task 2.1.

### 7 — applied
- Recommendation: SUGGESTION — Task 2.2 edits a permitted set that does not exist
- Re-verified: `bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/GitObjectsBoundarySpec.groovy:28-48` is a forbidden-package list (`com.github.oinsio.gnomish` as an exact package, `..adapter..`, `..app..`, `..domain..`, `..status..`, `..usage..`, Spring, Jackson); `com.github.oinsio.gnomish.gittransfer` matches none. `gitobjects/build.gradle:39` says "dependency-free `:subprocess` leaf only — never a factory module".
- Edit: `tasks.md` 2.2 — the bullet now says `GitObjectsBoundarySpec` needs no edit (its forbidden list does not name the leaf) and that `gitobjects/build.gradle`'s header comment names the second JDK-only leaf. The comment edit is part of adding the edge the task already adds.

### 8 — applied
- Recommendation: SUGGESTION — The gate requirement promises a source-root reach an ArchUnit rule has no notion of
- Re-verified: `specs/git/git-invocation-policy/spec.md:106-107` (before) "The gate SHALL assert that it reached every production source root"; `design.md` D7 is an ArchUnit spec over imported classes asserting `ProcessBuilder` dependency equality with a nine-class allowlist; the `:bootstrap` ArchUnit specs import `com.github.oinsio.gnomish` with `ClassFileImporter` (`TypedExceptionMessageSpec.groovy:60`), with no source roots.
- Edit: `specs/git/git-invocation-policy/spec.md` requirement "The invocation policy has one owner and no bypass" — "The gate SHALL assert that its allowlist is matched exactly, so a new spawn site fails until it is classified"; scenario "A third launcher fails the build" THEN "fails naming the class". The guarantee the old sentence aimed at survives: equality fails when an allowlisted class from any of the five modules is missing from the import.

### 9 — applied
- Recommendation: SUGGESTION — D7 says the seed helper runs one git command; the script runs seven
- Re-verified: `sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/DockerSeedCloneCommand.java:77-88` — two `git config --global --add safe.directory`, the rendered clone, `git remote remove origin`, `git config user.name|user.email|gc.auto 0`, `git reset --hard`.
- Edit: `design.md` D7 closing paragraph — "its only transfer is the owner's `GitTransfer.clone` …; the box-side `config`, `remote remove` and `reset` lines are the box's git (NG6, D10 row 2)".

### 10 — rejected
- Recommendation: SUGGESTION — Task 4.3's starting set misses the stand-in step scripts that run git in the clone
- Re-verified: `bootstrap/src/test/.../GitModeMidRoundPushSpec.groovy:52` builds its clone with `initWorkingRepo`, and `test-fixtures/src/main/resources/stand-in/steps/gnome-mid-round-commit.sh:9` runs `git … commit` in it; the planted `pre-commit`/`gpg.program` would refuse it.
- Evidence: fails "Worth fixing" test 2 (Impact). The review itself says "clarity of the task text only — the implementer meets this spec at the root check … then applies the sweep the task already prescribes". `tasks.md` 4.3 already prescribes that sweep ("route them through the fixture helpers, or switch them to `initPlainWorkingRepo` with a comment giving the reason … Verify: root `./gradlew check` green"), and `design.md` Risks covers it ("will now hit a failing planted hook → the task's sweep routes them through the fixture helpers or `initPlainWorkingRepo`"). Naming `GitModeMidRoundPushSpec` as an expected `initPlainWorkingRepo` use would also decide in advance the route-or-plain choice the task leaves to the sweep, which is a task change rather than a trivially safe wording fix.

### Other edits
None.

### Validation
- `openspec validate own-git-invocation-policy --strict`: Change 'own-git-invocation-policy' is valid
