## Artifacts Review: own-git-invocation-policy

### Summary
| Dimension            | Result                                                                 |
|----------------------|------------------------------------------------------------------------|
| Freshness            | 1 stale claim ❌ (the hardening entry carries the ref-backend check), 4 ⚠️ |
| Completeness         | 14/15 requirements fully covered (UX2 is a hand-off with no spec, by design) |
| Internal consistency | 3 contradictions (D2 entry point vs typed transfer entry; replace-ref planter; D7 seed-script claim) |
| External consistency | 0 conflicts with stable specs or active changes; 1 with code (`FactoryCloneRefFormat` reached through `harden`) |

Reviewed against the working copy at `50f1a0c8`; `origin/main` holds nothing ahead of it.
`openspec validate own-git-invocation-policy --strict`: valid, no INFO lines.

### Landed since

The change was last revised in `1a49a350` (2026-10-20, the squash merge of #99). No change was archived after that commit. One change was archived *in* it and is not named in the proposal's "Layered on" sentence:

- **`make-pipe-reads-non-parking`** (archived 2026-10-20, same commit) — overlaps on `GitExec` (`:gitobjects`) and `CaptureRunner` (`:subprocess`): it moved both readers onto `ChildOutput`. Its proposal names this change and states "either order; whichever lands second is rebased" (`archive/2026/10/2026-10-20-make-pipe-reads-non-parking/proposal.md:272-274`). Checked: the set of production classes depending on `ProcessBuilder` is unchanged by it and equals D7's allowlist of nine (grep over `*/src/main`: `GitProcessRunner`, `GitExec`, `DockerCli`, `HostTaskExecutionEnvironment`, `StderrPolicy`, `CaptureRunner`, `ProcessLaunch`, `GroupedLaunch`, `StdinSource`; `CommandProcessRunner`, `GitTransfer` and the `environment` `package-info` mention the type in javadoc only). `GitExec.run` still builds its line through the private `commandLine` at `GitExec.java:100,122-134`. Nothing in the design is stale because of it; the "Layered on" sentence may name it (folded into R6's wording edit, not a separate item).

Earlier archives the change already layers on were re-read for the claims it rests on: `tighten-spec-contract` (`GitPush`, the reftable floor, FR13 "no factory-side `git commit`") and `fix-terminal-receipt-convergence` (`TaskBranchAdoption`, `ProcessLaunch`, D18/D19). Their effect on the artifacts is in Freshness below.

### Freshness

- ❌ `design.md:107-109`, `tasks.md:82-85` — "Removed: … `GitTaskBranches.harden` and the port method `TaskBranchGit.harden`". Reality: `GitTaskBranches.harden` is two steps, and only the second is the configuration write: `GitTaskBranches.java:80-85` runs `refFormat.require(cloneDir)` (the reftable-backend precondition of `tighten-spec-contract` NFR-S2, `FactoryCloneRefFormat.java:11-30`) *before* `hardening.harden(cloneDir)`. Every call site says so in its own comment ("The backend check inside harden first: a refused clone gets no worktree-registry write", `TakeFreshClaim.java:64-66`, `GitModeRunner.java:101-103`), and `FactoryRegisterOwnerSpec.groovy:119-122` pins `new FactoryCloneRefFormat(` to exactly `GitTaskBranches.java` and `TouchpointFetch.java`. Deleting the method as written deletes the precondition from the seven run paths and fails that gate. → R1.
- ⚠️ `proposal.md:23-26` ("`commit.gpgSign` … runs an arbitrary program during the factory's `git commit`"; "every factory `git commit` starts a detached `git maintenance run --auto`") and the scenario "Factory commits start no background maintenance" (`specs/git/git-invocation-policy/spec.md:30-33`). Reality: since `tighten-spec-contract` design D13 the factory runs no factory-side `git commit` — every closing commit is `commit-tree` (`WorktreeClosingCommit.java:84`, `CommitBuilder.java:125`), pinned by `FactoryRegisterOwnerSpec` "FR13: no production source runs a factory-side git commit". `commit-tree` honours `commit.gpgSign`, so the signing vector stands; `commit-tree` and `update-ref` start no automatic maintenance, so the maintenance sentence describes a command the factory no longer runs, and the scenario's test cannot go red on the factory's own commit path (the key stays useful as a pre-emption, D3). → R6.
- ⚠️ `design.md:262` (D10 row 1, old-way grep: "`"core.hooksPath=` literals outside it — all expected empty"). Reality: three production files carry the literal inside in-box shell scripts — `EnvironmentAttemptPersistence.java:93`, `EnvironmentRoundSnapshot.java:55`, `EnvironmentSalvage.java:90,94` (`git -c core.hooksPath= commit` run by the box's git, NG6). The sweep as written finds three unlisted survivors. → R5.
- ⚠️ `tasks.md:54-55` ("`GitObjectsBoundarySpec`'s permitted set admits `gittransfer`"). Reality: `GitObjectsBoundarySpec.groovy:30-48` is a forbidden-package list (`com.github.oinsio.gnomish` exact, `..adapter..`, `..app..`, `..domain..`, `..status..`, `..usage..`, Spring, Jackson) with no permitted set; `com.github.oinsio.gnomish.gittransfer` matches none of them, so the spec needs no edit. → R8.
- ⚠️ `design.md:210-212` ("[the seed helper's] only git command is the owner's `GitTransfer.clone`"). Reality: the rendered script runs `git config --global --add safe.directory` twice, `git remote remove origin`, `git config user.name|user.email|gc.auto` and `git reset --hard` beside the clone (`DockerSeedCloneCommand.java:77-88`) — which D10 row 2 itself lists as the NG6 exemption. → R10.
- `tasks.md:120-122` ("about 70 calls in about 44 files on 2026-10-18"): the task's own grep gives 57 hits in 39 files today. Approximate by its wording; no edit needed.

Verified fresh (file:line): the seven `git.branches().harden(cloneDir)` call sites, the decorator method `RemoteOutageSignalingTaskBranchGit.java:50`, the port method `TaskBranchGit.java:32`, the two classes (M1's 7+1+1+2); `GitExec`'s one literal `GitExec.java:131` and its `GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM` isolation `:103-104`; `GitTransfer.CONFIG_KEYS` with `maintenance.auto=false`/`gc.auto=0` and `STRIPPED` opening with the two configuration variables (`GitTransfer.java:63-82`); the public compact constructor `:85-88`; `GitPush.closing`/`branchOnly` through `start()` (`GitPush.java:43-67`); `GitNetworkCommands.subcommandIndex` skipping leading `-c` pairs (`:109`); the runner's three entries (`GitProcessRunner.java:175,198,223`) all reaching `execute` and its private `commandLine` (`:303-310,431`); `TaskBranchAdoption` as one `update-ref --stdin` transaction (`:13-16`) behind `ContainerResumeBranch.ensureLocalBranch` (`:60`); the `@Retry` and its `TEMPORARY` comment (`TakeSlotRunnerContainerConcurrencySpec.groovy:168-172`); `LocalBoxEnvironment.groovy:66` (`config gc.auto 0`); `BareGitRepoFixture.initWorkingRepo` (`:42`); `RunnerStartHardeningSpec` (five "hardens … at run start" features); the four "harden" sync markers in three pairs; layering allowlists — `:adapters`, `:adapters:agent`, `:bootstrap`, `:sandbox:docker` list `:gittransfer`, `:application` and `:gitobjects` do not (`gitobjects/build.gradle:44`); `docs/adr/0012` free and `0011` taken; threat-registry rows end at 49; no glossary entry yet; `add-subprocess-access-log` carries no `module-layering` delta and records the re-layering (`proposal.md:70-87`); `fix-docker-exec-env-argv` still plans ADR 0011 with its own F69 noting the renumber; `add-doctor-command` has no UX2 check yet (task 7.3 hands it off). Both MODIFIED blocks restate the main-spec text verbatim with additions only (diffed; `git-task-persistence` keeps its one scenario "Reading a gnome branch runs nothing"). The `git-transfer` main spec already promises the stripping for transfers (`spec.md:139`); the ADDED "for every command" requirement generalizes it without contradiction.

### Coverage matrix

| ID     | proposal | delta spec | task |
|--------|----------|------------|------|
| FR1    | ✅ | ✅ `git-invocation-policy` "Every factory git process…"; `module-layering` | ✅ 1.1, 1.2, 2.1, 2.2 |
| FR2    | ✅ | ✅ same | ✅ 1.1, 1.2 |
| FR3    | ✅ | ✅ "Inherited per-process configuration is stripped…" | ✅ 1.1, 2.1, 2.2 |
| FR4    | ✅ | ✅ "Transfers layer on the invocation policy" | ✅ 1.3 |
| FR5    | ✅ | ✅ "The factory writes no repository configuration" | ✅ 3.1 |
| FR6    | ✅ | ✅ same; `git-task-persistence` MODIFIED | ✅ 3.2 (see R1) |
| FR7    | ✅ | ✅ "The invocation policy has one owner and no bypass"; `module-layering` | ✅ 6.1 |
| FR8    | ✅ | ✅ "The policy holds on an adversarial local configuration" | ✅ 4.2, 5.1 (see R3) |
| NFR-R1 | ✅ | ✅ "Concurrent work on one clone…" | ✅ 5.3, 5.4 |
| NFR-R2 | ✅ | ✅ "…writes no repository configuration" (Implements) | ✅ 5.2 |
| NFR-S1 | ✅ | ✅ `git-task-persistence` (Implements) | ✅ 5.1 |
| NFR-S2 | ✅ | ✅ "Every factory git process…" (Implements) | ✅ 1.1 |
| NFR-O1 | ✅ | ✅ "…writes no repository configuration" (Implements) | ✅ 3.1 |
| UX1    | ✅ | ✅ `git-task-persistence` "The clone's configuration is not written" | ✅ 5.1, 5.3 (byte-identical assertions) |
| UX2    | ✅ | — (release note and a doctor hand-off; no behaviour of this change) | ✅ 7.3 |
| Q3     | ✅ | — | ✅ 7.3 |

### Completeness

- `proposal.md`: every required section present; IDs follow `traceability.md`; NFR-P and NFR-C considered explicitly; Q1/Q2 resolved in design, Q3 left open with its owner named. Non-goals NG1 and NG6 name the change that picks them up (`add-sandbox-hardening`; the box's trust domain) — conditions, not times.
- Delta specs: ADDED/MODIFIED structure correct; every requirement has scenarios; both MODIFIED blocks carry the `Layered on` preamble and the current text.
- `design.md`: decisions D1–D10 with rationale and rejected alternatives; "Sync surfaces" (D9) and "Single-owner mechanisms" (D10) present. The D10 rows name the invariant and the enforcement, not construction sites. Row 2's "Old way removed" is incomplete against reality (R1: the port method also carries the backend precondition) and row 1's old-way grep expectation is wrong (R5).
- `tasks.md`: every FR/NFR/UX maps to a task; verification is automated. Task 3.2's "root `./gradlew check` green" is read per `verification-scope.md` as the task's own scope.

### Internal consistency

- `design.md:56-59` vs `design.md:62-63` and `tasks.md:42-45` — D2 says `GitInvocation.commandLine(gitBinary, args)` is "the single entry point" and "both launchers pass what it returns straight to `ProcessBuilder`", then says the runner's typed transfer entry "does not prefix the pairs a second time". In the code both the string entry and the typed entry reach one `execute` (`GitProcessRunner.java:303-310`). The design does not say how one entry point yields two outcomes. → R2.
- `specs/git/git-invocation-policy/spec.md:115-120` vs `design.md:166-168` — the requirement makes the shared fixture plant "a replace ref" among its default vectors; D6 says "the replace-ref vector is planted by the identity spec itself; the fixture cannot know which commit to target". → R3.
- `design.md:210-212` vs `design.md:263` — D7 says the seed helper's "only git command" is the clone; D10 row 2 lists its five `git config` lines as the exemption. → R10.
- `specs/git/git-invocation-policy/spec.md:104-107` vs `design.md:186-209` — the requirement says the gate "SHALL assert that it reached every production source root"; D7 is an ArchUnit rule over imported classes, which has no source roots to reach. → R9.
- Terminology is stable ("invocation policy", "transfer set", "the owner"); the glossary entry is task 7.2's.

### External consistency

- **Vs implemented code:** removing `TaskBranchGit.harden` removes the reftable-backend precondition from every fresh claim and manual run, undeclared (Freshness ❌; R1). The proposal's "REMOVED (code)" bullet and FR6 name only the configuration write as the thing to stop, so the precondition's survival is inside the change's intent.
- **Vs stable specs:** `git-transfer` "Inherited per-process configuration is stripped" (transfers) and the ADDED "for every command" requirement do not contradict. The `git-task-persistence` MODIFIED block replaces "SHALL have `core.hooksPath` pointed at an empty directory" with a per-invocation promise plus "SHALL persist no setting" — a widened promise, recorded in D4.
- **Vs narrowed scenarios:** none. Both MODIFIED requirements only add scenarios and clauses (diffed against `openspec/specs/`).
- **Vs other active changes, MODIFIED overlap:** no other active delta MODIFIES "Layered Gradle module tree", "Enforced acyclic dependency direction" or "Factory git executes no untrusted content" (grep over `openspec/changes/*/specs`). `add-subprocess-access-log` records the removal of its `module-layering` delta and the sequencing after this change (`proposal.md:84-87`). `fix-docker-exec-env-argv` still names ADR 0011 in three artifacts, with its own F69 pending; task 7.3 carries the renumber.
- **Vs rules and ADRs:** ADR 0008 is extended, not contradicted (credential/transport keys stay ambient, NFR-S2). No lock is added (`lock-scope.md`); `CloneMutationLock` is untouched. One initiative, within the 1–4 week scope. Only `docs/` files are referenced.

### Deferrals

- `own-git-transfer-argv` NG1 (`archive/2026/09/2026-09-23-own-git-transfer-argv/proposal.md:102-104`) — `--negotiation-tip`, "deferred to a later change" (⚠️ a time, not a condition). This change edits `GitTransfer` but does not meet it: fetch-negotiation disclosure is not an invocation-configuration concern. Not taken up, correctly; no re-deferral owed.
- `own-git-transfer-argv` NG5 (pushes, `:111`) — already taken up by `tighten-spec-contract` (`GitPush`); this change layers on it.
- `fix-terminal-receipt-convergence` NG15 (bounding `GitExec`'s wait, `:279`) — this change touches `GitExec`'s command line only, not its wait; not met.
- `add-sandbox-core` FR17 ("SHALL set an empty `core.hooksPath` on every factory-managed copy", `archive/2026/08/2026-08-13-add-sandbox-core/proposal.md:281`) — not a deferral; its main-spec requirement is the one this change MODIFIES.

### Recommendations, ordered

1. **CRITICAL — Deleting `harden` deletes the reftable-backend precondition** (`openspec/changes/own-git-invocation-policy/design.md:107`)
   Problem: D4 and task 3.2 remove `GitTaskBranches.harden` and the port method `TaskBranchGit.harden` as "the escape hatch", but `GitTaskBranches.java:80-85` runs `FactoryCloneRefFormat.require(cloneDir)` first and the configuration write second; the seven call sites rely on that order ("a refused clone gets no worktree-registry write", `TakeFreshClaim.java:64`, `GitModeRunner.java:101`), and `FactoryRegisterOwnerSpec.groovy:119-122` pins `new FactoryCloneRefFormat(` to `GitTaskBranches.java` and `TouchpointFetch.java`. No artifact names where the backend check goes.
   Impact: implemented as written, a fresh claim or a manual run on a files-backend clone writes its worktree registry and branch before anything refuses it (NFR-S2 of `tighten-spec-contract` silently lost on the paths that do not fetch first), and `FactoryRegisterOwnerSpec` goes red with no task that owns the fix.
   Fix: in D4 and D10 row 2, keep the port entry as the backend precondition under a name that says what it does (for example `TaskBranchGit.requireBackend(Path)`, with `GitTaskBranches` keeping `refFormat.require` there and the decorator passing it through), and delete only the hardening write; task 3.2 replaces each `harden(cloneDir)` call with the renamed call and rewrites `RunnerStartHardeningSpec` to assert the backend check still runs before the first write; task 3.3 rewords the markers to the new name; FR6 says "the clone hardening write SHALL be removed" (not "and its port method"); M1 counts `harden` occurrences only.
   Fix risk: the proposal's REMOVED bullet and M1 wording change; the port keeps one method with a pass-through in `RemoteOutageSignalingTaskBranchGit`, which is what the ref-format check needs and the write does not.

2. **WARNING — D2 does not say how one entry point avoids prefixing a transfer twice** (`openspec/changes/own-git-invocation-policy/design.md:56`)
   Problem: D2 makes `GitInvocation.commandLine(gitBinary, args)` the single entry point both launchers pass straight to `ProcessBuilder`, and in the same decision says the runner's typed transfer entry "does not prefix the pairs a second time" because `GitTransfer.argv()` already opens with them; `GitProcessRunner.java:303-310` routes the string entry, the typed entry and the ref-transaction entry through one `execute` and one `commandLine`.
   Impact: the implementer has three readings and no rule to choose by — a second owner method that skips the pairs (the escape hatch `implementation.md` item 3 forbids), a prefix check inside `commandLine`, or pairs carried twice (which task 1.3's "no key has two values" spec may or may not treat as a defect); each builds a different `GitInvocation` and a different gate item 2.
   Fix: state the mechanism in D2 — `commandLine` is idempotent on the policy: when `args` already opens with the owner's pairs (true of every `GitTransfer.argv()` by the compact-constructor invariant) it adds nothing, so the owner has one producer and no bypass; add that case to task 1.1's spec list ("an argv that already opens with the pairs is returned with them once") and drop "without prefixing again" from task 2.1 in favour of "through the same `commandLine`".
   Fix risk: a caller that hand-spells the eight pairs ahead of its arguments is accepted as already carrying the policy, which is the policy; the construction gate keeps `GitTransfer` the only such producer.

3. **WARNING — The spec and D6 disagree on who plants the replace ref** (`openspec/changes/own-git-invocation-policy/specs/git/git-invocation-policy/spec.md:115`)
   Problem: the requirement "The policy holds on an adversarial local configuration" makes the shared fixture plant "a replace ref" as part of its default posture, and its scenario asserts the clone "holds every planted vector"; D6 (`design.md:166-168`) says the fixture plants four vectors and the identity spec plants the replace ref itself, since the fixture cannot know which commit to target.
   Impact: task 4.2's fixture spec and the requirement's scenario are written against different vector sets; the implementer either plants a replace ref in the fixture (contradicting D6 and the "local configuration" wording — a replace ref is a ref, not a configuration key) or leaves the scenario asserting a vector the fixture does not plant.
   Fix: in the requirement, list the four configuration vectors as the fixture's posture and add one sentence: "The identity spec plants the replace ref on the commit it reads"; keep M4's count of five by saying so in the scenario's THEN ("every planted configuration vector").
   Fix risk: none.

4. **WARNING — The host-mode gnome stand-in commits under the planted posture and the sweep does not name it** (`openspec/changes/own-git-invocation-policy/tasks.md:137`)
   Problem: `GitModeMidRoundPushSpec.groovy:52,75` builds its clone with `initWorkingRepo` and runs the committed stand-in `gnome-mid-round-commit.sh`, whose `git commit` (`steps/gnome-mid-round-commit.sh:9`) is the gnome's own git in the worktree: it reads the shared `.git/config`, so D6's `pre-commit` hook (exit 1) and `gpg.program` (exit 1) refuse the commit. Task 4.3's starting set — files matching `ProcessBuilder|.execute()` in test sources — does not reach a stand-in step script, and D6's "Fixture writes" paragraph counts only writes through the runner.
   Impact: the spec goes red at the root check with no task naming it; the nearest "fix" is to weaken the posture or to give the stand-in a `-c core.hooksPath=` of its own, both of which hide that a gnome's commit meets the operator's hooks by design (NG6).
   Fix: in D6 and task 4.3, name `GitModeMidRoundPushSpec` (and any other spec whose stand-in runs git in the clone: grep `\bgit\b` under `test-fixtures/src/main/resources/stand-in/steps/`) as `initPlainWorkingRepo` uses with the reason "the gnome's git is outside the policy (NG6); the posture is the factory's to meet", and add that grep to task 4.3's starting set.
   Fix risk: none; the host gnome's commits are not a path FR8 promises to cover.

5. **WARNING — D10 row 1's old-way grep expects empty but three in-box scripts carry the literal** (`openspec/changes/own-git-invocation-policy/design.md:262`)
   Problem: row 1 says the grep for `"core.hooksPath=` literals outside `GitInvocation` is "expected empty"; `EnvironmentAttemptPersistence.java:93`, `EnvironmentRoundSnapshot.java:55` and `EnvironmentSalvage.java:90,94` spell `git -c core.hooksPath= commit` inside shell scripts the box's git runs (NG6), and task 2.3's grep omits that literal, so the row and the task disagree with each other and with the tree.
   Impact: per `implementation.md` item 2 an unlisted survivor of the old-way sweep is the defect the rule exists for; the implementer who runs the row's grep finds three and must either strip the in-box policy (wrong: that git is the box's, under its own `GIT_CONFIG_*`) or stop and ask.
   Fix: list the three files in row 1's "Old way removed" as exemptions with the reason (in-box git, NG6, the box's own hooks policy), and add the `core.hooksPath=` literal to task 2.3's grep with those three as the expected hits.
   Fix risk: none.

6. **SUGGESTION — The Why and one scenario describe a `git commit` the factory no longer runs** (`openspec/changes/own-git-invocation-policy/proposal.md:24`)
   Problem: "runs an arbitrary program during the factory's `git commit`" and "every factory `git commit` starts a detached `git maintenance run --auto`" predate `tighten-spec-contract` FR13: every factory-side commit is `commit-tree` (`WorktreeClosingCommit.java:84`, `CommitBuilder.java:125`), pinned by `FactoryRegisterOwnerSpec`; `commit-tree` honours `commit.gpgSign` but starts no maintenance, so the scenario "Factory commits start no background maintenance" (`spec.md:30-33`) cannot go red on the factory's own path.
   Impact: wording only — D3 keeps `maintenance.auto`/`gc.auto` as pre-emption and the transfer set already carried them; the scenario's test is a pin that never fails.
   Fix: in the Why, say `commit-tree` for the signing vector and move the maintenance sentence to "`fetch`, and any future `git commit`, would start it"; retitle the scenario while the change is unarchived to "Factory git starts no background maintenance" with WHEN "the factory writes a commit object and moves a ref in a clone over the maintenance threshold"; while editing the proposal, add `make-pipe-reads-non-parking` to the "Layered on" sentence (it edits `GitExec` on other lines).
   Fix risk: none.

7. **SUGGESTION — Task 1.3's verify list omits the spec the constructor invariant breaks** (`openspec/changes/own-git-invocation-policy/tasks.md:33`)
   Problem: `ScriptedGitSpec.groovy:87-93` hand-builds `new GitTransfer(['-c', 'fetch.prune=false', 'fetch', …])`, which the D2 compact-constructor invariant refuses; task 1.3 names `GitTransferSpec`, `GitPushSpec`, the `SeedTransferFixture` specs and `DockerSeedCloneCommandSpec`, not this one (`TransferAdversaryFixture.seedTransfer` and `DockerSeedCloneCommandSpec:161,181` derive from `owner.argv()` and still open with the pairs).
   Impact: one red feature in `:adapters:git` found at task 2.1's check; the fix is obvious (build the value through `GitTransfer.fetch`), so this costs minutes, not a wrong build.
   Fix: add `ScriptedGitSpec` ("the transfer owner's value is matched and logged by its argv") to task 1.3's verify list with "rebuilt through the owner's `fetch` factory".
   Fix risk: none.

8. **SUGGESTION — Task 2.2 edits a permitted set that does not exist** (`openspec/changes/own-git-invocation-policy/tasks.md:54`)
   Problem: "`GitObjectsBoundarySpec`'s permitted set admits `gittransfer`" — `GitObjectsBoundarySpec.groovy:30-48` is a forbidden-package list; `com.github.oinsio.gnomish.gittransfer` matches none of its entries (`com.github.oinsio.gnomish` is an exact-package pattern), so the spec needs no change.
   Impact: the implementer looks for a set to edit and finds none; a minute lost, or a spurious edit.
   Fix: replace the bullet with "`GitObjectsBoundarySpec` needs no edit (its forbidden list does not name the leaf); the Gradle layering allowlist of `:gitobjects` gains `:gittransfer` with the comment, and `gitobjects/build.gradle`'s header comment ('never a factory module') names the second JDK-only leaf".
   Fix risk: none.

9. **SUGGESTION — The gate requirement promises a source-root reach an ArchUnit rule has no notion of** (`openspec/changes/own-git-invocation-policy/specs/git/git-invocation-policy/spec.md:106`)
   Problem: "The gate SHALL assert that it reached every production source root" — D7's `GitLaunchBoundarySpec` imports classes (`ProcessBuilder` dependency equality over an allowlist, item 1; a method-call check, item 2); the "reached every root" assertion belongs to source-scan gates (`LaunchOwnerGateSpec`, `GitTransferBoundarySpec`), and the scenario's "fails naming the file" is a class name under ArchUnit.
   Impact: wording; the implementer following D7 writes the right gate and leaves one sentence of the requirement unmet.
   Fix: reword to "The gate SHALL assert that its allowlist is matched exactly, so a new spawn site fails until it is classified" and the scenario's THEN to "fails naming the class".
   Fix risk: none.

10. **SUGGESTION — D7 says the seed helper runs one git command; the script runs seven** (`openspec/changes/own-git-invocation-policy/design.md:210`)
    Problem: "its only git command is the owner's `GitTransfer.clone`" — `DockerSeedCloneCommand.java:77-88` also runs two `git config --global --add safe.directory`, `git remote remove origin`, three `git config` writes and `git reset --hard`, all in the box; D10 row 2 lists five of them as the NG6 exemption.
    Impact: wording; the gate conclusion (transfer literals forbidden, the clone carries the pairs by the constructor invariant) still holds, since the other commands are the box's git under NG6.
    Fix: "its only transfer is the owner's `GitTransfer.clone` …; the box-side `config`, `remote remove` and `reset` lines are the box's git (NG6, D10 row 2)".
    Fix risk: none.

### Verdict

needs revision — one CRITICAL (R1: the port entry being deleted carries the reftable-backend precondition, pinned by `FactoryRegisterOwnerSpec`) and four WARNINGs (R2 D2's one-entry-point mechanism; R3 the replace-ref planter; R4 the host gnome stand-in under the posture; R5 the D10 row 1 grep expectation). The premise of the change stands and nothing of it is implemented: `harden` and its seven call sites, `GitExec`'s lone literal, the private `commandLine` builders and the `@Retry` are all in the tree as the proposal describes them.

Nothing in the project was modified by this review; the human decides what to apply.
