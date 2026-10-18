# Tasks

Sequenced after `make-checkpoint-gate-durable` (archived 2026-10-09) and `tighten-spec-contract`
(archived 2026-10-14): the files they shared with this change (`TakeLoadedBranchRoutes`, the
`run` completed arms, `ParkKillPoints`) and the three MODIFIED requirements are in their landed
form. Root `./gradlew check` green after §2, §4 and §6.

## 0. The spec-contract gate reads unarchived deltas

Runs first, although added after task 1.1 was done: task 1.1's six `tracker-port` markers turned
`checkSpecContract` red (2026-10-14), and every later task writes more. Task 0.3 was added after
task 3.5, whose REMOVED requirement left two scenarios the gate still demanded a link for; it runs
next, before the remaining tasks.

- [x] 0.1 `SpecContractCheck` / `ScenarioLinkRule` (`build-logic`; design D7, FR6): the set a
      marker resolves against becomes the main-spec scenarios plus the scenarios under ADDED and
      MODIFIED requirements of every delta in `openspec/changes/*` (never `archive/`), read
      whether or not `--changes` is set — a task input, so the cacheable task reruns when a delta
      changes; REMOVED requirements contribute nothing; the unlinked-scenario finding keeps
      reading the main specs only. Verify functional specs in `build-logic/src/functionalTest`
      for `quality-gates` "A marker naming an unarchived delta's scenario is not dangling",
      "A marker naming a removed or archived scenario is dangling" and the refined "A dangling
      marker fails the gate" (markers written above the features), and that "Scenarios of
      unarchived deltas are not linked" still holds. Gate: `./gradlew checkSpecContract` green
      with task 1.1's markers in place, none removed (M4).
- [x] 0.2 Docs: the `SpecContractCheck` class javadoc (deltas are "never linked" → never
      required to be linked, but resolvable by markers; D7) and `.claude/rules/testing.md`,
      "Every scenario has a test": a marker may name a scenario of an unarchived delta's ADDED
      or MODIFIED requirement and needs no edit at archive; a title only under REMOVED or only
      in `archive/` is dangling.
- [x] 0.3 `ScenarioLinkRule` / `SpecContractCheck` (`build-logic`; design D7 extension, D5 row,
      FR6): a REMOVED requirement of an unarchived delta retires its main-spec scenarios, and
      retirement only relaxes the gate. Current signatures: `ScenarioLinkRule.judge(List<SpecFile>
      mains, List<SpecFile> deltas, Map<String, ScenarioMarkerReader.Markers> markers,
      ScenarioExemptions exemptions, Set<String> scope)`, `ScenarioExemptions.assertReached(Set<String>
      specIds, Set<String> existing)`, `SpecContractCheck.getDeltaSpecs()`, and the call
      `ScenarioLinkRule.judge(mains, deltas, markers, exemptionList, scope)` in `SpecContractCheck`.
      No signature change: the retired set is computed inside `judge` from `deltas` — for every
      delta (they already exclude `archive/`), each `Requirement` whose `section` starts with
      `REMOVED` retires the main-spec requirement with the same `Requirement.name` and the same
      `specId`, and that requirement's scenarios are retired; `getDeltaSpecs()` already carries the
      REMOVED sections, so no new task input, and `SpecContractCheck` changes only its javadoc.
      Read whether or not `--changes` is set: (1) the unlinked-scenario finding skips retired
      scenarios — the change that removes them is the record of their retirement; (2) the
      resolvable set stays as task 0.1 left it (main-spec scenarios ∪ scenarios under
      ADDED/MODIFIED requirements of unarchived deltas), so a marker naming a retired scenario is
      not dangling; (3) an exemption line naming a retired scenario is neither required nor stale —
      `assertReached` keeps receiving all main-spec scenarios, and its message is unchanged. Update
      the `ScenarioLinkRule` and `SpecContractCheck` class javadocs and `.claude/rules/testing.md`,
      "Every scenario has a test", in the same task: a removed requirement's scenarios need no
      link from the change that removes them; that change deletes its leftover markers and
      exemption lines itself, and the archive's own gate run reports any it forgot. Verify
      functional specs in `build-logic/src/functionalTest`, markers written above the features,
      for the `quality-gates` scenarios "A scenario of a requirement removed by an unarchived
      delta need not be linked" (including that a delta under `archive/` retires nothing), "A
      marker or exemption naming a retired scenario is accepted", and the refined "A scenario with
      no marker fails the gate"; run the existing scenario-link functional specs by name to
      confirm task 0.1's scenarios, "A dangling marker fails the gate" and "A stale exemption
      fails the gate" among them, still hold. Then remove the interim patch task 3.5 left, by
      explicit edit — the gate no longer forces it: delete the exemption line of "A converged
      staged removal is visible in the log" in
      `config/spec-contract-exemptions/git-git-task-persistence.txt` and the file with it (it
      holds no other entry; the file is new in this change, and a header-only file would declare
      a capability with no exception, so it goes), and delete the marker
      `// Scenario: git/git-task-persistence / Kill between the staged removal and its commit converges on the same worktree`
      from `bootstrap/src/test/groovy/com/github/oinsio/gnomish/app/killpoint/TransitionKillPointSpec.groovy`,
      keeping the marker of "A host worktree already missing .gnomish-task/ does not affect the
      cleanup". Gate: `./gradlew checkSpecContract` green with both gone and task 1.1's markers in
      place (M4). Sweep (`implementation.md` item 2): `grep -rn "removed-by:\|Kill between the
      staged removal" config/ */src/test */*/src/test build-logic/src/functionalTest` returns
      nothing; report the hits and what happened to each.

## 1. The tenure boundary as a port fact

- [x] 1.1 `TrackerTask` (`gnomish-plugin-api`) gains `@Nullable BoundaryKind tenureBoundary`
      (design D1, FR1): the last PARK / FINISH / ABORT / STALE_CLAIM_REMOVED marker on the thread
      in posting order, whatever claim markers follow it; `Tracker.fetchTask` javadoc names it and
      says it is not the claim-scoped `TrackerFacts.latestBoundary`. GitHub adapter: derive it in
      the issue-detail read from the parsed marker thread already fetched, beside
      `GithubHistoryFactReader`'s whole-thread `returned`/`finished`, no extra call (NFR-C1) —
      not through `GithubTrackerFacts.latestBoundary` or `GithubCommentBoundary.latestBoundaryIndex`.
      In-memory adapter: `InMemoryTracker.fetchTask` reads the last such entry of
      `TrackedTask.thread()`; no new recording (every write op already records one). Verify the
      port contract suite gains the features of `tracker-port` "Tenure boundary survives a claim
      on every adapter" (park / return / claim, then a second return and claim) and "Every tenure
      boundary kind is reported on every adapter", run against both adapters (WireMock for
      GitHub).
- [x] 1.2 Adapter author guide (`docs/guides/adapter-author-guide.md`): the new fact, how to
      derive it structurally, and why it is not the claim-scoped boundary of the shape facts.
- [x] 1.3 `docs/glossary.md`: entry **Tenure boundary** — the thread's last boundary marker,
      *Not:* the claim-scoped boundary the shape facts carry for the reaper (design D1).

## 2. Receipt probes read history

- [x] 2.1 `GuardedPark.observeAtTarget` (FR2): LANDED iff `tenureBoundary == PARK`, ABSENT iff
      `STALE_CLAIM_REMOVED` or null, UNDETERMINED on a tracker failure (unchanged WARN); one DEBUG
      line when a park is confirmed from history (NFR-O1). Delete the
      `TrackerTaskState.AwaitingHuman` read. Rewrite the `TakeReconcile` class javadoc paragraph
      that claims the marker alone distinguishes the two cases. Verify `GuardedParkSpec` data
      table over the four boundary values; `TakeLoadedBranchRoutesSpec`: a `Parked`+marker branch
      whose tracker boundary is `PARK` clears the marker and continues with the reply.
- [x] 2.2 `FinishEffect.observeAtTarget` (FR3): LANDED iff `FINISH` or `finished()`. Verify
      `FinishEffectSpec`.
- [x] 2.3 `ReceiptProbeGateSpec` (`:bootstrap`; design D5): `TrackerTaskState.AwaitingHuman` and
      `latestBoundary` absent from `take/GuardedPark.java` and `take/FinishEffect.java` (M2).
      Gate: `./gradlew check` green.

## 3. Delivered cleanup converges without a claim

- [x] 3.1 `DeliveredCleanup` (`:application`; design D2, FR4): given a clone, a task id and a
      `CompletedUncleaned` tip whose tracker reports finished, run the cleanup commit through
      `TaskLifecycleStore.finishCleanup` (pushed by the store's decorator) and the medium's
      disposal; a `Delivered` tip is a no-op; a ref update that loses a race logs DEBUG and
      returns; one INFO line with the task id on a performed cleanup (NFR-O1). Not a
      `TerminalEffect`, no `ClaimGuard`. Delete `ResumeMechanics.finishCleanup` with its
      `HostResumeMechanics` and `ContainerResumeMechanics` implementations; `TakeLoadedBranchRoutes`
      hands `DeliveredCleanup` to `TakeReconcileFinish.finishUncleaned` as the cleanup `Runnable`,
      and `TakeEngineExecution`'s `FinishTransition.Fresh` cleanup is built from it.
- [x] 3.2 Consumers, by file (FR4): `TakeDisposition` — reaches the cleanup through one field,
      the slot's `ClaimlessDeliveredCleanup.forSlot(wiring)`; the `Finished` arm and the
      `Ready+finished` arm run it when the clone at `order.run().cloneDir()` holds the task
      branch, then report as today;
      `GitResumeContinuation.reportCompleted` and `ContainerResumeOutcomes.reportCompleted` run it
      before printing; `WorktreeJanitor.tick` gains a branch pass (the janitor gains the bound
      tracker, a branch lister over the clone and `DeliveredCleanup`): list `gnomish/*` branches
      of the clone, for each `CompletedUncleaned` tip one `fetchTask` and `DeliveredCleanup` when
      finished (proposal Q1: every tick); the javadoc's "no tracker check" policy narrows to the
      age pass. Old-way sweep: `grep -rn "finishCleanup(" application/src/main bootstrap/src/main`
      shows calls only in `DeliveredCleanup`, `GitOutcomeRecorder`, `ContainerTerminalDrive` and
      `ContainerRunTermination` (M3) — record the result. Verify `TakeDispositionSpec`,
      `GitResumeRoutingSpec` / `ContainerResumeRoutingSpec` (completed arm cleans when the
      envelope is present, writes nothing when it is not), `WorktreeJanitorSpec` (branch pass;
      the age pass still reads no tracker — `factory-serve` "The cleaner consults no tracker
      status" and "A finished task's branch is cleaned on the branch pass").
- [x] 3.3 `DeliveredCleanupIdentitySpec` (`:bootstrap`, bare origin, host and container): for each
      of the seven consumers (D5 row), a `CompletedUncleaned` tip with a finished tracker converges to
      `Delivered` with the workspace gone, origin carries the cleanup, and a second run writes
      nothing (NFR-R1); two instances racing on one branch land one cleanup commit.
      `ReceiptProbeGateSpec` gains the `finishCleanup(` allowlist of design D5, every file
      asserted reached.

- [x] 3.4 `run --resume` on a `Delivered` tip (FR4; `manual-run` "Completed task resumes to a
      no-op", and the "second `--resume` writes nothing" of "Completed task with a lost cleanup
      converges"): the resume bootstrap of both media routes a branch whose cleanup already
      landed (no `.gnomish-task/` at the registered commit, completed) to the done report — writes
      nothing, exit 0 — instead of `AbsentEnvelope`. Found by task 3.2 (2026-10-14). Closes F67:
      `GitResumeOutcomeSpec` asserts the second `--resume` writes nothing, and a test of the
      narrowed no-op scenario runs on a cleaned-up branch, host and container.
- [x] 3.5 The host cleanup commit onto the bare-object writer (design D8, FR4, NFR-R1; found by
      task 3.3, 2026-10-14): `GitTaskRepository.finishCleanup` delegates to
      `GitObjectsTerminalCommits.cleanUp` through a `TaskLifecycleCommitWriter` (the factory
      `CommitIdentity`, the store's clock and epochs) over a `GitObjects` that `GitTaskRepository`
      opens over its own clone's `.git` (JVM temp dir for the private index), so it cannot name
      another; `GitTaskStore` passes nothing new; delete
      `CleanupCommit`; `GitObjects`' class javadoc says it works over any `.git` — the host clone
      included — and that nothing in it is container-specific. Old-way sweep, recorded in the
      report: `grep -rn 'CleanupCommit\|"rm", "-r"' adapters/git/src/main` shows no hit for the
      envelope directory (`ConsumedRequestRemoval`'s `rm` of the gnome-writable path is not the
      cleanup and stays). `ReceiptProbeGateSpec` gains that gate (M5). Remove `@PendingFeature`
      from `DeliveredCleanupRaceSpec`'s host feature; both features green over their rounds. List
      any spec that asserted the host cleanup commit's committer and move it to the factory
      identity. Verify by name: `DeliveredCleanupRaceSpec`, `DeliveredCleanupIdentitySpec`,
      `GitTaskRepositorySpec` (cleanup features), the `CleanupCommit` specs rehomed onto
      `GitTaskRepository.finishCleanup` (the staged-removal matrix rows are deleted with the
      worktree path: a tip that carries no envelope is the one no-op); scoped PIT on
      `GitTaskRepository`, `GitTaskStore`, `GitObjectsTerminalCommits`. Also done here:
      `CommitMetadata.gitDate` in `:gitobjects` writes `@<epoch> +0000` — git read a bare number
      below 100000000 as a calendar field, so commits stamped near the epoch by `VirtualClock`
      failed; pinned by a feature in `GitObjectsCommitSpec`. The change gained a
      `git/git-task-persistence` delta (design D8): "Cleanup on completion" removed and replaced
      by "Cleanup commit on completion" without the two staged-removal scenarios, "The cleanup
      commit is built per mode" now one writer on both media (second marker on
      `GitTaskRepositoryCleanupSpec`), and the new "A host worktree already missing
      .gnomish-task/ does not affect the cleanup" (marker on `TransitionKillPointSpec`). The
      staged-removal marker left on `TransitionKillPointSpec` and the interim exemption line for
      the log scenario in `config/spec-contract-exemptions/git-git-task-persistence.txt` are
      retired by task 0.3 (design D7 extension).

## 4. Kill-point pickups through the production route

- [x] 4.1 `TakeKillPointWorld` gains `claimAsSecondInstance()` and `reap()` (design D3): a second
      `InstanceId` with its own `ClaimEpochBook`, the real `tracker.claim`, and a `TakeOrder` for
      it; `returnedAndReclaimed()` stays for the same-instance rows; the `pickup()` precheck on
      `AwaitingHuman` + the pending marker is removed and the `Resumed`/`Gate`/`Approval` rows
      that expect an empty pickup say so per row. The `ParkKillPoints` and `FinishKillPoints`
      rows move onto `TakeKillPointWorld` (`TakeKillPointWorlds`): their pickup returns as the
      human after the tracker park landed, reaps when it did not, claims as the second instance,
      and routes through `TakeLoadedBranchRoutes.route` / `TakeDisposition.dispose` under that
      order. Assertions added per row: after the tracker park, the human's return is not undone
      (tracker stays `Working(second)`, one park comment); after the tracker finish, the
      finished-task disposition lands the cleanup. Record in this task that the pre-change code
      fails both (M1).
      M1 (2026-10-14): with `ParkLanding.landed` reading `state() instanceof AwaitingHuman` again,
      every returned park window fails — the host, container and denial park rows on "the park the
      human answered was posted again" (thread: the answered park, the second claim, a re-posted
      park, no round), the gate rows on the missing approval; with `TakeDisposition`'s finished
      arms not calling `ClaimlessDeliveredCleanup`, both finish rows fail "killed after the tracker
      finish: … left the CompletedUncleaned shape, expected Delivered". Restored, 22/22 green.
      "Tracker stays `Working(second)`" cannot hold literally: the return's continuation runs its
      round, which parks again, so the row asserts the park count before and after the takeover
      (`[1, rounds]`) instead.
- [x] 4.2 Gate: `./gradlew check` green; PIT 100% on the touched modules (NFR-R2).

## 5. Durable record

- [x] 5.1 `docs/adr/0003-crash-consistency.md`, mechanism 2 ("intent → effect → receipt"): the
      probe reads the target's durable history, never the state a later step rewrote; one
      sentence naming this change.
- [x] 5.2 Operator guides: a finished task's branch is cleaned by the next `take <ref>`,
      `run --resume`, or `serve` pass — no manual cleanup.
- [x] 5.3 Four rule edits, each with a "the failure this exists for" paragraph naming this
      change's task 3.3 finding (design D8): `.claude/rules/manual-sync-pairs.md` — a change
      adding a consumer to a declared pair chooses the end in `design.md` with its reason and
      states whether both ends hold under the consumer's conditions (its writers, its medium, the
      claim it holds); "different media" is no reason when the condition is about something else;
      an end that fails the condition triggers preference 1 in the same change.
      `.claude/rules/design-decisions.md` — the single-owner table gains a **Requires** column
      (a worktree index, a working copy, a live box, the network, a claim, or none), so a
      mechanism named by where it was first wired is told apart from one bound to that medium.
      `.claude/rules/lock-scope.md` — a section "Writers from different processes": a step
      sequence a multi-process writer runs is listed with the lock each step takes; the first
      step that takes a lock with no compare-and-swap behind it is the race; an NFR "idempotent
      across instances" is designed only with that table in `design.md` and a real-thread race
      spec named in the single-owner table; a lock in the JVM is no answer.
      `.claude/rules/proposal-format.md` — a non-goal that defers a known gap names the condition
      that must pick it up (the kind of change, not "a later change"), and `/review-artifacts`
      and `/opsx:propose-checked` check a new change against the deferrals of the archived
      proposals whose files it touches — add that step to `.claude/commands/review-artifacts.md`
      and `.claude/commands/opsx/propose-checked.md` (project-owned), so the rule names a check
      that exists. Written in this change, not deferred: `fix-envelope-medium` NG1 deferred this
      unification "to a later change" and nothing picked it up.

## 6. The task branch is adopted by one ref transaction

Added 2026-10-14 after the implementation audit (CRITICAL 1: the claimless cleanup classified a
remote-tracking-only branch and wrote to a head that did not exist) and the design session that
followed (design D9–D12). Current signatures the tasks change: `boolean
TaskBranchGit.ensureLocalTaskBranch(Path cloneDir, String taskId)` (`TaskBranchGit:49`,
implemented by `GitTaskBranches:85` over `ContainerResumeBranch.ensureLocalBranch`); `Path
TaskWorktreeManager.ensureWorktree(String taskId, String branchName)` and the port's
`TaskWorktreeGit.ensureWorktree(RegisteredClone, String taskId, String branchName)`; `void
DeliveredCleanup.cleanUp(Path cloneDir, String taskId)` and `cleanUpIfFinished(Path, String,
TrackerTask)`; `static String GitObjectsTaskRepository.refFor(String taskId)`.

- [x] 6.1 `TaskBranchRefs` (`adapters/git`; design D5 row, FR8): the one spelling of
      `refs/heads/gnomish/<b>` and `refs/remotes/origin/gnomish/<b>`, two named methods over
      `TaskIdSanitizer.branchName`. Consumers by file: `TaskBranchLocator` (`:102-103`),
      `ReplicaPairReconciler` (`:116-117`), `TaskBranchLister` (`:59-60`), `BaseRefresh` (`:118`),
      `FactoryRegister.branchRef` (`:204`). Delete `GitObjectsTaskRepository.refFor` and
      `REF_PREFIX`; its eight writes and `GitTaskRepository.finishCleanup` take
      `FactoryRegister.branchRef`. Old-way sweep, recorded in the report (M7):
      `grep -rn '"refs/heads/\|"refs/remotes/origin/' adapters/git/src/main application/src/main bootstrap/src/main`
      — every hit is the owner, or listed with its reason (`ManualResumeLawBinding:55` and
      `RemoteBaseRef` spell the *base* ref, not the task branch: keep, say so). Verify by name:
      `ReceiptProbeGateSpec` gains the prefix gate, every allowlisted file asserted reached; the
      specs of the five consumers; scoped PIT on the owner.
- [x] 6.2 `TaskBranchAdoption` (`adapters/git`; design D9, D10, FR7): `ensureLocalTaskBranch`
      returns the sealed `BranchAdoption` — `Adopted(ObjectId tip)` / `AlreadyLocal` /
      `Nowhere` — with `LocalTaskBranch` as the view the writers' entries accept; `Adopted` by
      one `update-ref --stdin` transaction, `create <head> <tracking tip>`, register untouched;
      a lost `create` re-reads through `TaskBranchLocator.locate` and answers `AlreadyLocal`;
      `Unavailable`/`Refused` propagate as today. `undoAdoption(Adopted)`: one transaction,
      `delete <head>` under compare-and-swap on the commit it holds, register back to the
      adopted tip. `ContainerResumeBranch` keeps its `Local` reconcile and delegates
      `RemoteTracking` to the owner; its `git branch` arm and INFO line go (the adoption logs
      its own INFO anchor once: "adopted branch … from …"). Port javadoc and
      `RemoteOutageSignalingTaskBranchGit` follow the signature. Verify: `TaskBranchAdoptionSpec`
      (`adapters/git`, `ScriptedGit`: the create line, a lost create, nowhere, the undo and a
      refused undo), `BranchAdoptionRaceSpec` (`:bootstrap`, real threads, two adopters on one
      clone over a bare origin: one `create`, both answer a local branch at one tip, neither
      fails; `lock-scope.md`, "Specs"); scoped PIT.
- [x] 6.3 Consumers, by file (design D11, FR8): `TakeResumeBootstrap` (`:69-84`) and
      `GitResumeRunner`'s bootstrap (host) adopt after `locate` and before the worktree;
      `TaskWorktreeGit.ensureWorktree(RegisteredClone, LocalTaskBranch)` and
      `TaskWorktreeManager.ensureWorktree(LocalTaskBranch)` name an existing branch — git resolves
      nothing; `TakeContainerResumeBootstrap:53` and `ContainerResumeRunner:100` switch on the
      typed answer; `DeliveredCleanup.cleanUp(Path, LocalTaskBranch)` and `cleanUpIfFinished`
      take the adopted branch, `ClaimlessDeliveredCleanup` adopts over the location it holds
      (`Local` or `RemoteTracking`) and skips the rest. Old-way sweep, recorded (M6):
      `grep -rn '"branch", \|"worktree", "add"' adapters/git/src/main` — `"branch"` only in
      `TaskBranchCreator` (fresh task), `worktree add` only in `TaskWorktreeManager` on a
      `LocalTaskBranch`. Verify: the four bootstrap specs (a remote-tracking-only branch is
      adopted, then materialized), `ClaimlessDeliveredCleanupSpec` (the `RemoteTracking` row
      now adopts first), `DeliveredCleanupSpec`, `TaskWorktreeManagerSpec` (an absent branch
      fails, never created); `ReceiptProbeGateSpec` branch-command gate; scoped PIT.
- [x] 6.4 No undo (design D12 as revised 2026-10-14, FR9, NG4): delete
      `TaskBranchAdoption.undoAdoption` and `FactoryRegister.deleteAdoptedHead` (built by 6.2
      under the first D12; no consumer, `implementation.md`), with their `TaskBranchAdoptionSpec`
      rows (the undo, the refused undo) and the "undo" sentences of the `TaskBranchAdoption`,
      `BranchAdoption` and `FactoryRegister` javadoc; `Adopted` loses `tip` and becomes
      `Adopted(String taskId)` (the INFO anchor logs the tip from its own local). `DeliveredCleanup`
      is unchanged: no origin read, no undo arm. Old-way sweep, recorded: `grep -rn
      "undoAdoption\|deleteAdoptedHead\|adopted.tip()\|\.tip()" adapters/git/src application/src
      bootstrap/src` — no production or test hit survives. Verify: `DeliveredCleanupCrossCloneSpec`
      (`:bootstrap`, bare origin, two clones, real adapters: B adopts and cleans after A
      delivered, B's push is rejected; B's head and register stay at B's cleanup commit, origin
      holds A's one cleanup commit; then the three readers of D12 — B's next claimless pass reads
      `Delivered`, writes nothing and runs no push; B's touchpoint reconciliation declines the
      catch-up push with its "not an ancestor" line; `run --resume` on B refuses with
      `DivergedBranchException`; and a claimed take on B after the tracker lost the finish reports the task done and leaves B's
      head at B's cleanup commit — D12 as amended 2026-10-15), plus the kill-point rows for windows
      (a)–(c) of D12 in the same spec's table (kill after create, after the commit, after the
      push; the second run converges and the third writes nothing); `TaskBranchAdoptionSpec` and
      `BranchAdoptionRaceSpec` still green; scoped PIT on `TaskBranchAdoption`, `FactoryRegister`.
- [x] 6.5 The `Unregistered` shape (`:domain`; design D13, FR10; architect review 2026-10-15):
      `BranchShape.Unregistered(String register, String tip)` joins the sealed set;
      `BranchShapeClassifier` maps `TipRelation.NoRegister` over an envelope to it (the `Bare`
      arm for a tip without an envelope stays); a new `RecoveryOwner.TOUCHPOINT_FETCH`; disposition
      `QUARANTINE` (D13: what a reader does after its own read — so `TaskListRenderer.outcomeCell`,
      `BranchShapeDiagnosis.diagnosisFor` and `BranchRepairAction.phrase`, which decide by
      disposition and which no compiler flags, render it as a quarantine with no edit of their
      own; assert it in their specs); `tipCarriesState()` false; `isClean()` false; `BranchShape.forwardedByBranchPass()` — true for
      `CompletedUncleaned` and `Unregistered`, the one table the janitor's pass reads (D5 row).
      Every exhaustive switch names the new arm — list them in the report: `BranchShapeDiagnosis`
      ("fetched without its factory register — `gnomish status <task>` fetches it"),
      `BranchRepairLog`, `TakeDispositionResume` (throws `BranchQuarantineException`, as for
      `Unknown`) and whatever the compiler finds (`task-branch-contract` "New shape cannot be
      silently ignored"); `BranchQuarantineReport.nextStep`, an `instanceof` reader no compiler
      flags, gets the `Unregistered` remedy (`gnomish status <task>` fetches the register; a
      branch still `Unregistered` after it has no factory register at origin and is inspected
      before the task returns to ready). Glossary: **Unregistered** (a tip with a factory envelope and no
      register in this clone; *Not:* `Unknown`, which is a present and diverged pair; *Not:*
      `Bare`). Verify `BranchShapeClassifierSpec` (the two new scenarios of "Total branch-shape
      classification" and "A missing register is a state of the clone, not of the branch", with
      "A register that is not an ancestor of the tip quarantines" narrowed to the diverged case),
      the `status` list row (`task-inspection` "A branch fetched without its register lists as
      Unregistered with its remedy"), the locate's offline fallback (`git-task-persistence` "An
      unreachable origin falls back to the tracking ref", now `Unregistered`), the quarantine report's
      and the repair line's specs (the `Unregistered` remedy; "parking for a human"); scoped PIT on
      `BranchShape*`, `BranchShapeDiagnosis`, `BranchQuarantineReport`, `TakeDispositionResume`.
- [x] 6.6 The branch pass forwards by the table and the owner fetches first (`:application`, `:adapters:git`;
      design D2 as amended, D13, FR11, FR12, NFR-C2, NFR-O2): `DeliveredBranchPass.run` keeps a
      row iff `row.shape().forwardedByBranchPass()` — no `instanceof BranchShape.` in the file
      (M8) — logs one DEBUG line per row it leaves alone, and hands the owner a `TaskRef`;
      `ClaimlessDeliveredCleanup` gains the sequence `converge(Path, TaskRef, PipelineDefinition)`:
      `locate` (the touchpoint fetch lands branch and register in place) → classification at the
      register over that location → `fetchTask` only for `CompletedUncleaned` → `adopt` →
      `cleanUpIfFinished`. The classification is a new read of the `TaskBranchGit` port,
      `classifyShape(Path, String taskId, BranchLocation)` (D13 as amended for this task):
      `GitTaskBranches` moves its switch over the location into it and its two-argument
      `classifyShape` becomes the new read over its own `locate`; `RemoteOutageSignalingTaskBranchGit`
      wraps it like the others; adapter and decorator specs cover it; the two
      `TakeDisposition` finished arms keep handing the task they already observed through an
      overload that skips the tracker read; an `Unregistered` tip after the owner's read is returned
      to the pass with the location the locate answered, and the pass logs one aggregate line per
      pass naming them with the location kind of each (a local head — not fetched, NG5; a tracking
      ref — origin delivered no register or could not be reached), never asserting which cause
      holds (a new `OperatorEvent` code, WARN), and writes nothing. Old-way sweep: `grep -rn "instanceof BranchShape.CompletedUncleaned"
      application/src/main` — only `DeliveredCleanup`'s own guard survives; `grep -rn
      "fetchTask(" application/src/main/java/com/github/oinsio/gnomish/app/serve` — none in the
      pass. Verify `DeliveredBranchPassSpec` (`factory-serve`: "A branch fetched without its
      register is read through the fetch before it is judged", "A branch the pass does not
      forward costs nothing", "A branch still unregistered after the owner's read is named, not
      skipped";
      `git-task-persistence` "A third party's fetch brings the branch without its register"),
      `ClaimlessDeliveredCleanupSpec` (order of the sequence, no tracker read for a non-completed
      tip; one `locate` before `adopt`, and the classification over its answer); scoped PIT on `DeliveredBranchPass`, `ClaimlessDeliveredCleanup`,
      `GitTaskBranches`, `RemoteOutageSignalingTaskBranchGit`.
- [x] 6.7 Identity and gates (design D5, D13; M9): `DeliveredCleanupIdentitySpec`'s eight
      "second clone" rows already exist (the two `take` finished arms, `run --resume` and the
      janitor's branch pass, host and container), seeded by `atOriginOnly` through `git clone` —
      the tracking ref present, no register in the clone, the posture a plain `git fetch` leaves.
      The two janitor rows drop the `located(...)` wrapper, which is deleted: it ran a `locate` of
      its own before the consumer, a posture production never has; `atOriginOnly` asserts the
      posture (`!second.holdsHead()` and the task's shape `Unregistered`). No row is added. The
      `tracker-take` scenario "A finished task's branch held only as remote-tracking converges" is
      linked from those rows; the gates (6.1: `TaskBranchRefsGateSpec`; 6.3:
      `TaskBranchCommandGateSpec`; 6.6: `BranchPassTableGateSpec`, its own spec in `:bootstrap`
      since `ReceiptProbeGateSpec` is past the file-size cap, banning `instanceof BranchShape.` in
      `serve/DeliveredBranchPass.java`) with every file asserted reached; `DeliveredCleanupRaceSpec`'s javadoc states that the reflog it
      counts exists because the clone is not bare (D9).
- [x] 6.8 Docs and the rule: `docs/glossary.md` — **Branch adoption** (making a branch a clone
      holds only as a remote-tracking ref local, by one compare-and-swap ref create; *Not:* a
      checkout, a fresh task's branch creation), **Unregistered** (6.5), and the **Branch shape**
      entry (a missing register over an envelope classifies `Unregistered`, a diverged one
      `Unknown`);
      `docs/guides/operator-guide.md`, "A finished task whose branch cleanup was cut short" —
      from any clone, whether or not it holds the branch locally and whoever fetched it (the
      IDE's periodic fetch included), and the one case an instance's own cleanup commit stays
      behind (another clone delivered first): what the leftover local branch costs, why nothing
      deletes it, and that a manual `run --resume` of that task on that clone refuses on the
      divergence, and that nothing removes it (D12 as amended 2026-10-15); `operator-guide-inspect.md`
      — the list-mode paragraph (a branch whose register no fetch brought lists as `Unregistered`,
      was `Unknown`), the single-task paragraph (a branch read without its register reports
      `Unregistered` with the `status <task>` remedy, exit code 7 as every quarantine shape) and
      the refusing-shapes paragraph (`Unregistered` joins the list; `Unknown` no longer covers a
      branch with no register). `.claude/rules/crash-consistency.md` item 15 (D13, "Rule
      landing"): an absent companion ref is a state of the reader, not a shape of the pair; an
      actor on a medium whose consistency only a fetch guarantees reads through the fetch's owner
      before a consequential act, and a reader that cannot judge says so rather than skipping —
      with "the failure this exists for" naming this change's task 6.5 finding (the janitor's
      silent skip of every branch an IDE fetch brought); `docs/adr/0003-crash-consistency.md`
      gains the `Unregistered` row of its shape table: the touchpoint fetch as owner, quarantine on
      a claimed take when the branch is still `Unregistered` after it.
- [x] 6.9 Gate: `./gradlew check` green; PIT 100% on the touched modules (NFR-R2); M6, M7, M8
      and M9 recorded.
      Root `./gradlew check` (2026-10-15): BUILD SUCCESSFUL in 1 h 39 min, every module's PIT
      gate included. M6: `"branch", ` only at `TaskBranchCreator:61`, `"worktree", "add"` only
      at `TaskWorktreeManager:105`. M7: the task-branch prefixes only in `TaskBranchRefs:24-25`;
      the other hits spell the base ref, the exemptions D5 lists (`RemoteBaseRef:28`,
      `BaseRefresh:118,127`, `ManualResumeLawBinding:55`). M8: no `instanceof BranchShape.` in
      `DeliveredBranchPass.java`; `BranchPassTableGateSpec` 8/8. M9: `DeliveredCleanupIdentitySpec`
      19/19, its janitor rows seeded by `atOriginOnly` with no `locate` in the fixture, the shape
      read through the location-taking `classifyShape`, which fetches nothing.

## 7. The test build runs under its own Docker client configuration

- [x] 7.1 `TestDockerEnvironment` (`build-logic`; design D14, D5 row; FR13–FR15, NFR-C3): the
      committed `test-fixtures/src/main/resources/docker-config/config.json`; the owner composes
      the four variables (D14 items 1–6) and is applied by `test-conventions` beside
      `AdversarialGitConfig` to every `Test` task and to `pitest`; the copy lands in the task's
      temporary directory before it runs; the endpoint resolves through a configuration-time value
      source (one `docker context inspect`, none when `DOCKER_HOST` is set); nothing
      machine-specific is declared as an input. `TestEnvironmentHygieneFunctionalSpec`
      (`:31`, `:150-158`) takes a pass-through stand-in other than `DOCKER_CONFIG`. New
      `TestDockerEnvironmentFunctionalSpec` on an inner build covers every scenario of the ADDED
      `quality-gates` requirement (the `docker` binary a stand-in preset answering `context
      inspect`; the host OS a parameter of the owner, never the real one in the spec). Old-way
      sweep: `git grep -n "DOCKER_CONFIG\|DOCKER_CONTEXT" -- build-logic test-fixtures bootstrap`
      — every hit the owner, `TestChildEnvironment`'s pass-through list, or a spec. Verify:
      the build-logic functional specs by name, `checkSpecContract`.
      As built (2026-10-15): the owner is `TestDockerEnvironment` with `DockerContextEndpoint`, the
      value source (obtained once per build, kept on the `Gradle` object's extra properties), and
      `test-docker-environment-conventions`, applied by `test-conventions`. The spec is two files:
      `TestDockerEnvironmentFunctionalSpec` (11 features, every branch of the composition, the
      owner handed the host OS and the stand-in preset `docker-context-inspect` with its answers in
      `data/stdout-docker-context`) and `TestDockerConventionsFunctionalSpec` (3 features through
      `test-conventions`: a real test JVM, PIT's minions, the shared cache entry); a red run with
      the `DOCKER_CONTEXT` removal deleted failed all three. The four convention fixtures now write
      the committed inputs through one `MiniScopeRepository.writeTestConventionInputs`. Sweep: the
      owner, `TestChildEnvironment:42` (the copy passed to a spawned jar), the preset's `record` row,
      and `TestEnvironmentHygieneFunctionalSpec`'s javadoc — whose pass-through stand-in is now
      `DOCKER_HOST` on a socket no daemon answers. `:build-logic:functionalTest` 186/186.
- [x] 7.2 Gate and rule (design D14, D5 row; M10): `TestDockerEnvironmentSpec` in `:bootstrap`
      asserts the effect in the real test JVM — its `DOCKER_CONFIG` names a directory that is not
      the operator's, whose `config.json` holds no `credsStore`, `credHelpers`, `proxies` or
      plugin hooks; `DOCKER_CONTEXT` is absent; `DOCKER_HOST` is set whenever
      `DockerClientFactory` finds a daemon; on macOS the socket override is
      `/var/run/docker.sock`. `.claude/rules/testing.md`, "Test processes never inherit the
      operator's environment": the Docker client configuration joins git's as a medium the build
      replaces, with "the failure this exists for" naming this change's task 6.9 prompt.
- [x] 7.3 Markers: the ADDED requirement's scenarios and the MODIFIED scenario "Only the factory's
      own namespace is removed" linked from the specs of 7.1 and 7.2; `checkSpecContract` green.
- [x] 7.5 Tools of build-side value sources resolve on the client's PATH (D14, amended 2026-10-18):
      `DockerContextEndpoint` and `MutationScopeSource` (`build-logic`) take the client's `PATH`
      through `providers.environmentVariable('PATH')`, resolve `docker` / `git` against it and exec the
      absolute path; an unresolved tool keeps the absent-tool value (`Endpoint.NONE`, `null`) but logs a
      build warning naming the searched PATH, never in silence. Functional-spec branches in `build-logic` for both (resolved on the client's
      PATH while the daemon's lacks it; unresolved warned with the PATH named). Old-way sweep: every
      `exec`/`commandLine` of a bare tool name in `build-logic/src/main`.
- [ ] 7.4 Gate on both systems (M10, M11; UX4): on the developer's macOS machine, with the
      operator's `"credsStore": "desktop"` left in place, the Docker suites by name
      (`ContainerFreshSegmentSpec`, `ContainerBoxBoundarySpec`, `ContainerReadOnlySurfacesSpec`,
      `EgressGuardIntegrationSpec`, a Gitea E2E spec — Ryuk started, `docker build` through
      `buildx`), then the root `./gradlew check` once, sampling `ps` for
      `docker-credential-desktop` and the TCC log during it; then the Linux CI job green. Record
      M10 and M11 here.
      macOS, 2026-10-15 (Docker Desktop, `credsStore: desktop` in place): the five Docker suites
      by name passed with none skipped, `testcontainers/ryuk:0.14.0` seen in `docker ps`, `buildx`
      v0.33.0-desktop found through `cliPluginsExtraDirs`. Root `./gradlew check
      :build-logic:functionalTest` green in 1 h 25 min (1030 tasks); `ps` sampled through the
      whole run: 0 `docker-credential-*` processes; TCC log over the run: 0 entries naming
      `docker-credential` (M10). Two earlier runs failed on PIT RUN_ERRORs from minion deaths
      under memory pressure (`TerminalPark.isPark`, `BindingTrustTable.firstParty`), both killed
      on a scoped rerun, none in the green run. Local Linux rehearsal (Ubuntu 24.04 container,
      Temurin 25, git 2.55, docker CLI 29.9 on the host daemon's socket, arm64): both build-logic
      specs 14/14, `TestDockerEnvironmentSpec` 3/3 (the macOS feature skipped),
      `GiteaContainerFixtureSmokeSpec` 2/2, `ContainerBoxBoundarySpec` 4/4, Ryuk on the plain
      `/var/run/docker.sock` path with no override. Linux CI: pending the push (M11).

## 8. Post-exit output is bounded, the launch has one owner, the host sweeps its process group

Added 2026-10-16 (design D15–D19) after task 7.4's Linux gate failed on
`ProcessSupervisorInterruptSpec`. Task 7.4 is unchanged and closes by a CI rerun; the spec it
failed on is fixed here. Stage 1 (8.1–8.9) is `:subprocess` and its specs; stage 2 (8.10–8.18)
migrates the readers; the wrapper and the sweep follow (8.19–8.23); one gate at the end (8.24).
Revised 2026-10-16 after task 8.1's review (design D15–D19 as amended): one launch form, the bound
on `ExecHandle`, the round scratch directory, `GitExec`'s stderr pump, the sweep's end rule. Two
sequencing statements live in other changes' files and are not tasks here: `extract-agent-round-runner`
(NG13) and `add-subprocess-access-log` D14 (NG14) take theirs in their own `/opsx:update`.

- [x] 8.1 Review before code (`design-decisions.md`, "A decision added during implementation"):
      run `/review-artifacts fix-terminal-receipt-convergence D15–D19` — freshness of every quoted
      signature against the code, the three D5 rows against the rule, the tasks below — and fold
      its findings in through `/opsx:update` before 8.2 starts.
- [x] 8.1a Review before code of the decision added 2026-10-17 (`design-decisions.md`, "A decision
      added during implementation"): `/review-artifacts fix-terminal-receipt-convergence D20` with
      the amended D15 (write-side amendment withdrawn), D16 (`StdinSource` in the owner's
      signature), D17 (stdin clauses removed), the D4 map's write side (five writers), the D5 rows
      `ProcessLaunch` and `StdinSource`, FR23, NG16–NG17, M13 as amended, and the delta requirement
      "A child's input is a file or nothing"; findings folded in through `/opsx:update` before 8.2.
- [x] 8.2 `Captured.truncated` and the post-exit bound (D15; FR16, FR17, NFR-P1): `Captured` gains
      `boolean truncated`; `ProcessSupervisor` exposes its kill grace; `CaptureRunner` derives
      `postExitBound = killGrace + POST_EXIT_MARGIN` and joins both drains with it on every
      termination, closing both streams when a join expires; the stdin of `run(builder, deadline,
      input)` is **not** bounded here — 8.4a routes it through the stdin source; `drainJoinBound` and
      `DEFAULT_DRAIN_JOIN_BOUND` deleted; `Drain.join(Duration)` takes no nullable;
      `ProcessSupervisor.postExitBound()` is the getter. Red first:
      a `CaptureRunnerPostExitSpec` on `exit-leaky.sh` asserting `EXITED`, `truncated`, and a
      return within the bound plus 100 ms while the holder is alive (M15, first half).
- [x] 8.3 `DrainCloseReleaseSpec` (real threads, `lock-scope.md`): a drain parked in `read()` on a
      pipe a holder keeps returns within the bound once the stream is closed, and the process's
      `onExit` future and a second `getInputStream().read()` complete — the reaper is released.
- [x] 8.4 `ProcessLaunch` (D16, D19; FR18, FR22): the role interface in `:subprocess` and its one
      production form `ProcessLaunch.grouped()` (the launcher prefix of 8.19 lands here or there,
      whichever runs first; until 8.19 the form starts plain and 8.19 makes it grouped — the gate
      below does not depend on it); `CaptureRunner` takes it by constructor, the no-argument
      constructor supplying `grouped()` — a Local Default of its own module, admissible because no
      second production form exists (D16 as amended); `FakeProcess` in `test-fixtures` gains
      latch-gated exit and `java.nio.channels.Pipe` ends with a `holdOpen()`/`release()` pair.
      `LaunchOwnerGateSpec` (`:bootstrap`): a `ProcessBuilder` is started only in the owner's file,
      `implements ProcessLaunch` appears in no production source but the owner's, the allowlist
      asserted reached (M13) — red until 8.10–8.13 route the other three sites, so it is written
      here with those files allowlisted and the allowlist shrunk by each of them.
- [x] 8.4a `StdinSource` (D20; FR23): a sealed type in `:subprocess` — `NONE` and `of(byte[])`;
      `ProcessLaunch.start(ProcessBuilder, StdinSource)` has no overload without a source, sets
      `redirectInput(/dev/null)` for `NONE`, and for content creates the file with
      `Files.createTempFile` (atomic, 0600) in the owner's input directory — `grouped(Path
      inputDirectory)`, with `grouped()` naming `java.io.tmpdir` for the short constructors — writes
      it in full,
      sets `redirectInput(file)`, starts, and deletes the file in a `finally` — on the failure path
      too; a write failure raises the owner's launch exception before any process starts (an
      infrastructure failure, as a missing launcher helper). `CaptureRunner.run(builder, deadline,
      input)` passes `of(input)` and its calling-thread write (`:74-78`) goes; `run(builder,
      deadline)` passes `NONE`. Red first: `StdinSourceSpec` on a real process — a child
      `sh -c 'sleep 30 <&0 >/dev/null 2>&1 & exit 0'` with an input of 1 MiB (larger than any pipe
      buffer) returns at the exit, not flagged truncated, with no factory thread left alive for it
      and the file gone from the spec's own input directory,
      the holder still alive before the sweep (the holder's stdin must be redirected explicitly:
      a POSIX shell gives a background job `/dev/null`; its stdout and stderr must be redirected
      away too, or the drains wait the post-exit bound of 8.2 and the spec observes the read side); a child with `NONE` reads end-of-file at
      once (`wc -c` prints 0); a child reads the whole 1 MiB after the file was deleted; an
      unwritable input directory handed to `grouped(Path)` fails before the start. `LaunchOwnerGateSpec` gains the two shapes:
      `getOutputStream()` and `redirectInput(` in no production source but the owner's (M13 as
      amended) — red until 8.10, 8.12 and 8.13 delete the other writers, allowlisted here and
      shrunk by each of them.
- [x] 8.5 `ProcessSupervisorInterruptSpec` "an interrupt landing after a clean exit" rewritten on
      `FakeProcess`: exit released first, the pipe held, the interrupt delivered by the latch, no
      `Thread.State` poll; the real-script feature of D16 kept as its own feature with the bound of
      ten post-exit bounds.
- [x] 8.6 `CaptureRunnerDrainSpec` "a normal exit waits for its output" rewritten on `FakeProcess`
      (the holder writes after the exit, then releases within the bound: the capture is complete
      and not truncated); `trailing.sh` loses its `sleep 0.5` and its header says what it is for
      now; `StandInLibrarySpec` row updated.
- [x] 8.7 `CaptureRunnerPostExitSpec` second half: the truncated capture's WARN through
      `LogCaptureSupport` with its new `OperatorEvent` code in the callers `GitProcessRunner` and
      `DockerCli` (NFR-O3); `LogContractGateSpec` green.
- [x] 8.8 Delta scenarios of "Post-exit output is bounded", "One launch owner" and "A child's
      input is a file or nothing" linked by markers from 8.2–8.7 and 8.4a; `checkSpecContract`
      green.
- [x] 8.9 Stage-1 verification (`verification-scope.md`): `:subprocess:test`, `:subprocess:
      pitestVerifyAllKilled -PpitScope=…subprocess.*`, `LaunchOwnerGateSpec`, `DrainOwnerGateSpec`
      (8.12) by name.
- [x] 8.10 `GitExec` on the owner (D17; FR19): stdout **and stderr** drained on a `Drain` (the
      stderr pump `GitExecStreams.drain` and the unbounded `stderrThread.join()` of `joinPumps`,
      `GitExec:171-175`, deleted), the cap applied to the drained stdout bytes with the same
      `truncated` answer, the stdin bytes passed to the owner as `StdinSource.of(stdin)` or `NONE`
      (`GitExecStreams.feed` and the `stdinThread` of `joinPumps` deleted, D20), `await` always
      reached,
      an interrupt landing as `GitObjectsInterruptedException` from the join;
      `GitExecStreams.readCapped` deleted or reduced to the cap; `GitExec` launches through
      `ProcessLaunch`, handed in by `GitObjects.open:50` (the record gains the component, the
      short path supplies `grouped()`). `GitExecPumpThreadsSpec` `:97-116` (the stderr feature
      that pinned the unbounded join) and `:118-152` rewritten on `FakeProcess`; the cap specs
      kept green. *Revised 2026-10-17 (D15 as amended):* a cut drain fails the command only when
      git exited on its own with code zero — the `GitObjectsException` names the bound and the
      stream; on a non-zero exit `check()` reports git's stderr as before and the cut rides its
      message; `exists()` and `check()` read the exit code unchanged. `GitExecPumpThreadsSpec` on
      `FakeProcess`: exit zero + held pipe → the typed failure; exit one + held pipe → the
      stderr-bearing failure, no second finding; a held pipe with nothing written is still a
      refusal (the flag is "no end-of-file", scenario Notes). The class javadoc names the pair:
      kept in sync with `CaptureRunner.await`'s `CutCaptureException` — same condition, same
      bound in the message.
- [x] 8.10a `CutCaptureException` (D15 as amended 2026-10-17; FR17, NFR-O3, M16): the typed
      refusal in `:subprocess`, carrying the `Captured` (prefix, stderr, which stream was cut, the
      bound), thrown by `CaptureRunner.await` when `termination == EXITED && exitCode == 0 &&
      truncated`; every other termination returns the `Captured` as today. `GitProcessRunner.
      capture` and `DockerCli.capture` catch it, log the WARN of 8.7 from `e.captured()` (the
      holder's attribution, before the rethrow — `report()` no longer sees a truncated `EXITED`
      capture, its branch at `:354` / `DockerCommandReport:45` moves into the catch) and rethrow;
      `GitCommandResult` and `DockerResult` stay flagless and their javadoc's "captured in full on
      a normal exit" becomes true. `ObjectId.of` refuses anything but 40 or 64 hex characters
      (`ObjectIdSpec`: 39, 41, a non-hex character). Red first: `CaptureRunnerPostExitSpec` on
      `FakeProcess` — exit zero + held pipe → the refusal with the prefix inside; exit one + held
      pipe → a `Captured` with `truncated` and readable streams; a timeout + held pipe → the same;
      `GitProcessRunnerSpec` and `DockerCliBoundedSpec`: the WARN through `LogCaptureSupport`,
      then the rethrown refusal, through the fake. Old-way sweep: `grep -rn "truncated()"
      --include='*.java' */src/main */*/src/main` — hits only in `CaptureRunner`, the two catch
      sites and `GitExec` (M16; pinned by `DrainOwnerGateSpec`, written in 8.12 — allowlisted here
      until then). Delta scenarios "A cut capture says so", "A failed or killed invocation keeps
      its partial output" and "A value reader asserts its frame" linked. Before 8.11: `CapturedExec`
      and `ContainerFileChannel.getFile` (8.11, 8.12) read values and inherit the rule.
- [x] 8.11 `CapturedExec` on the owner: `sandbox/core/build.gradle` takes `:subprocess` into
      `layering { allowedProjects }` with the reason (a dependency-free leaf, D9 of
      bound-subprocess-commands), the module-layering spec updated; the port `ExecHandle` gains
      `Duration postExitBound()` — `HostExecHandle` answers from its supervisor,
      `OomAnnotatedExecHandle` delegates, the five test fakes implement it
      (`RoundInterruptedWaitSpec`, `AgentRoundTeardownSpec`, `ExecutorRoundDrainTimeoutSpec`,
      `InBoxGitCommandSpec`, `CapturedExecSpec`); the `CompletableFuture` drain replaced by a
      `Drain` joined with `handle.postExitBound()`, interruptible; its three callers
      (`InBoxGitCommand`, `EgressSelfCheckProbes`, `EnvironmentSelfCheck`) unchanged in signature.
- [x] 8.12 `ContainerFileChannel` on the owner: `ExecPipeDrain` deleted, `Drain` in its place, the
      stdin pump (`pump`, `join(pump)`, `:159-172`) deleted and the file content handed to
      `DockerCli.start` as `StdinSource.of(content)` (D20) — `DockerCli.start` gains the `StdinSource`
      parameter here, `start(List<String>, boolean mergeStderr, StdinSource)`, the stderr argument
      staying `boolean` until 8.16 makes it a `StderrPolicy`; `readFile` passes `NONE`; the `-i`
      flag of both channel operations derived from the source, not spelled beside it (D20) — the
      interrupt flag re-checked after the joins so a cut read fails the channel operation;
      `ContainerFileChannelStallSpec` and the MDC spec green. `DrainOwnerGateSpec` (`:bootstrap`):
      no `.join()` on a `Thread` or a `CompletableFuture` in the six readers' files and no
      `ExecPipeDrain` anywhere (M12).
- [x] 8.13 `HostTaskExecutionEnvironment.exec` and `DockerCli.start` launch through
      `ProcessLaunch` with a `StdinSource` — `ExecCommand.stdin` (kept `@Nullable String`) becomes
      `of(bytes)` or `NONE` once at each of the two `exec` sites, the container site deriving
      `interactive` from that source (D20), and `DockerCli.start` (its `StdinSource` parameter from
      8.12) passes it on, `ChildProcessStdin` is deleted with its `@DoNotMutate` (D20; the fake-agent stdin-echo
      spec and the port contract's stdin scenario stay green on the file); `DockerCli`'s full
      constructor takes the owner and its three short constructors
      supply `grouped()`, so `ContainerEnvironmentFactory:45`, `SandboxLifecycleSweep:58` and
      `DockerRuntimeProbe:36` need no change; `HostTaskExecutionEnvironment` takes it by
      constructor; `ContainerTaskExecutionEnvironment.exec:119` reaches the owner through
      `DockerCli.start` — the `docker` client is a host process, grouped and swept like any other
      (FR22 as amended; no plain form); `ScriptedFileChannelDockerCli`'s override moves onto the
      seam; `LaunchOwnerGateSpec`'s allowlist is now the owner alone.
- [x] 8.14 `StreamDrain`: `await(Duration grace)` already closes the stream when the grace expires
      (`:106-113`, verified 2026-10-16); `close()` loses its own one-second join (`CLOSE_JOIN`) —
      the post-exit bound of the round's handle is the only wait; `LargeStreamRoundSpec` and the
      drain specs green.
      *Revised 2026-10-17 (D17 as amended):* `await` takes `launched.postExitBound()` at
      `ExecutorRoundExecution:116` and `JudgeRoundExecution:119` — the handle's bound is the wait
      after the exit as well as the only one; `DrainOwnerGateSpec`'s allowlist for `StreamDrain`
      gone.
- [x] 8.14a Retire `factory.agent-cli-tail-drain-grace` (D17 as amended 2026-10-17; FR19):
      delete `FactoryProperties.agentCliTailDrainGrace` (component, `@ConfigLevel(Level.HOST)`
      entry, constructor parameters), `FactoryPropertyDefaults.tailDrainGrace` and every caller;
      the property's row in `docs/guides/operator-guide-run.md:232`; the advice to raise it in
      `StreamDrainTimeoutException` (its message names the post-exit bound and the bytes read) and
      `StreamDrainInterruptedException`'s javadoc. Specs: `FactoryPropertiesBindingSpec`,
      `FactoryPropertiesTimeoutSpec`, `AgentSettingsValidatorSpec`, `ExecutorRoundDrainTimeoutSpec`,
      `JudgeRoundDrainTimeoutSpec`, `JudgeCannotVerifyLoggingSpec`, `AgentRoundTeardownSpec`,
      `StreamDrainSpec` — rewritten on the handle's bound (a fake handle's `postExitBound()`), never
      deleted where they pin a surviving scenario. Markers: the three retired scenarios of the
      removed requirement "Stdout is drained continuously from launch" deleted, the two renamed
      ones ("An interrupted wait is not blamed on the bound", "A drain unfinished after the
      post-exit bound is infrastructure") updated, and the line `The grace is documented for
      operators` deleted from `config/spec-contract-exemptions/executor-agent-executor.txt`.
      Old-way sweep: `grep -rn "agent-cli-tail-drain-grace\|agentCliTailDrainGrace\|tailDrainGrace"`
      outside `openspec/changes/archive/` — hits only in this change's artifacts and in `FactoryPropertiesBindingSpec`'s feature "the retired tail-drain key still binds and changes no component", which pins the premise of the `ignoreUnknownFields` risk row and must name the key.
      `checkSpecContract` and `openspec validate fix-terminal-receipt-convergence --strict` green.
- [x] 8.15 `BoundedTail` on the owner's drain, joined with `handle.postExitBound()`;
      `CommandProcessRunner.TAIL_JOIN_BOUND` deleted; `ShellCommandCheckRunnerTimeoutSpec`
      `:29-42` rewritten on `FakeProcess` (the "hung-output" line delivered by the latch, not by
      the shell's start time).
- [x] 8.16 The round's stderr file (D18; FR20): `ExecCommand.mergeStderr` becomes `StderrPolicy`
      (`Merged`, `Piped`, `ToFile(Path)`); `HostTaskExecutionEnvironment.exec:101` and
      `DockerCli.start:136` apply it (`redirectError(file)` on the host; the `docker exec` client's
      stderr redirected on the host side), `ContainerTaskExecutionEnvironment.exec:119` and
      `SelfCheckedEnvironment:108` pass it on, `ContainerFileChannel:79, 99` take `Piped`,
      `CommandProcessRunner:125`, `InBoxGitCommand:69`, `EgressSelfCheckProbes:142`,
      `EnvironmentSelfCheck:117` and `ExecCommand.of:62` take `Merged` (the five test callers of
      `of` checked), `ExecHandle.java:23` javadoc updated. `RoundScratchDirectory`
      (`adapters/agent`): a host temp directory per round, created before `exec`, removed on every
      exit path, on both media; `ExecutorRoundExecution` and `JudgeRoundExecution` pass
      `ToFile(scratch.stderrFile())` and read it after the exit into the round's diagnostics as
      `UntrustedText`. Old-way sweep: `grep -rn "mergeStderr\|redirectErrorStream\|redirectError("
      --include='*.java'` — every hit the policy, one of the consumers above, or `StreamDrain`'s
      own source.
- [x] 8.17 Delta scenarios of "The round's stderr is kept" linked from the round specs (a CLI
      stand-in writing 200 KiB to stderr: the round completes, the diagnostics carry the tail;
      `AgentRoundTeardownSpec` and the container round spec: no file of the round remains on the
      host after it ended, however it ended); `checkSpecContract` green. The round prompt reaches
      the CLI from the stdin source's file (8.4a, 8.13): the host round spec that feeds a prompt
      larger than 64 KiB through the fake agent stays green, and a fake-agent preset that keeps
      stdin open from a background job (`<&0 >/dev/null 2>&1 &` — its output redirected away, so only
      the stdin is held and the round's `StreamDrain` is not what is observed) and exits proves the
      round returns at the exit.
      *Revised 2026-10-17 (D18 as amended):* the stderr tail rides the failure's detail on every
      non-success path — `ExecutorFailure` (executor) and the `cannotVerify` details (judge) for a
      CLI that failed to start, exited without a result event, timed out or was interrupted — and
      stays a DEBUG line on success; one spec per path and side, marker "A failed round carries
      its stderr tail"; `JudgeRoundExecution` is at the 200-line cap, so the change there splits
      a responsibility out first (`process-invariants.md`).
- [x] 8.18 Stage-2 verification per module: `:gitobjects`, `:sandbox:core`, `:sandbox:docker`,
      `:adapters:agent`, `:adapters` — the changed specs by name, PIT scoped to the changed classes.
- [x] 8.19 The host launcher (D19; FR21): the launcher prefixes live in `:subprocess`'s own
      sources, since production ships them (not under the test stand-in library): the Linux form
      prefixes `setsid`, the macOS form `/usr/bin/perl -e
      'POSIX::setsid(); exec { $ARGV[0] } @ARGV or exit 127'` (the indirect-object form: a
      one-element `exec LIST` with metacharacters would go through the shell); `ProcessLaunch.grouped()`
      chooses by `os.name` and fails the invocation naming a missing helper. `HostProcessLaunchSpec`:
      the child's pid equals its group id (`ps -o pgid=`), on both platforms where the spec runs,
      and a one-element argv with a metacharacter is executed verbatim. *Revised 2026-10-17 (D19
      as amended):* before prefixing, the owner resolves argv[0] as `execvp` would — a path with a
      separator checked for existence and the execute bit, a bare name searched on the child's
      `PATH` (the builder's environment, else the JVM's), never the working directory — and throws
      `IOException` naming the binary when nothing resolves, so `GitBinaryNotFoundException`,
      `GitExec`'s "could not launch", `DockerUnavailableException` and `ProcessStartException`
      keep firing unchanged. Backstop in the owner: a launcher exit of 126 or 127 **with** the
      launcher's own stderr line is surfaced as a launch failure by the waiters that capture
      stderr whole (`CaptureRunner`, `GitExec`), never the code alone. Neither `--wait` nor
      `--fork` is passed. *Revised 2026-10-17, second (D19's second amendment):* the resolver
      follows the `#!` interpreter chain as the kernel's `binfmt_script` does — the file's head
      read, the interpreter path resolved as a path (never on `PATH`), at most four hops, each
      checked as the first was — and throws `IOException` naming the command when a hop does not
      resolve; `#!/usr/bin/env x` stops at `env`. The perl one-liner runs under `no warnings` and,
      when its exec fails, prints the project-owned sentinel `gnomish-launch: cannot exec <path>:
      <strerror>` and exits 127 for `ENOENT`, 126 otherwise; `couldNotExec` matches that sentinel
      or util-linux's `failed to execute`, and the perl-text match (`Can't exec`) is removed. No
      classifier is added to a streaming waiter: the round, the judge and the check runner keep
      their failure class from the missing result, with the launcher's line in the stderr tail or
      the output tail (the old-way sweep of `implementation.md` item 2 lists those four sites as
      verified, not routed). `HostProcessLaunchSpec` gains: a missing binary throws before any
      process exists (asserted: no child pid was ever minted); a bare name present only in the
      working directory is not found; a script whose interpreter is missing, at the first and at a
      later hop, throws before any process exists, while `#!/usr/bin/env missing` starts and exits
      127 as a result; the sentinel classifies a refused exec and a program printing the sentinel
      without the code, or the code without the sentinel, is a result; a successful launch leaves
      the launcher's stderr empty; `GitProcessRunnerSpec`'s not-found feature stays green
      **unchanged** — a change to that spec is the patch this task forbids. Scenario markers for
      the four scenarios under "The launch owner resolves the executable before the launcher"
      (the streaming-waiter scenario on the round's and the check runner's failure-tail specs).
- [x] 8.20 `ProcessGroupSweep` (D19; FR21): a round is `kill -KILL -- -<pid>`; the sweep ends when
      the zero-signal probe `kill -0 -- -<pid>` is refused, at most five rounds, one DEBUG per round
      with survivors (NFR-O3) — never a parse of `kill`'s error text; run by `CaptureRunner.await`
      after every termination and by `HostExecHandle` after both waits (the `docker` client of a
      box command included). `ProcessGroupSweepSpec` on `exit-leaky.sh`: the holder is gone after
      the sweep (M15, second half); a reparented orphan (`parent.sh` whose child outlives it) is
      gone too; an empty group ends the sweep on its first probe. *Revised 2026-10-17 (D19 as
      amended):* the sweep runs only over a `Process` that exists and asserts `pid > 1` before
      spelling `-<pid>` (a launch that threw is never swept — spec feature); its `kill` is started
      in the owner's file, ungrouped, and `LaunchOwnerGateSpec`'s wording allows exactly that
      second `start()` there and nowhere else; the javadoc names the limit (a member that changed
      its uid or called `setsid` itself). Measurement, recorded here with the decision: the
      per-launch cost of the probe on `:gitobjects` plumbing (a commit of N blobs, wall time before
      and after, and `:gitobjects:pitest` wall time); if the probe costs more than a few percent of
      the plumbing commit, the sweep is narrowed to exits that left a reason — a cut drain, a
      non-`EXITED` termination, a descendant in the snapshot — and D19 records the measurement and
      the choice.
      *Measured 2026-10-17 (macOS, developer machine; `/bin/kill` started per probe, the perl
      launcher in front of every git):* a `GitObjects.commit` of N = 50 blobs (at least 52 plumbing
      launches), median of 10 warm commits per run, three runs each — without the sweep 1128.6,
      1125.2, 1130.4 ms; with it 1299.5, 1318.8, 1335.8 ms: **+190 ms, +17 %**, about 3.5 ms per
      launch for the one refused probe of an empty group. `./gradlew :gitobjects:pitest --rerun`:
      without the sweep coverage 21 s, completed 79 s, wall 81.5 s (72 mutations, one surviving —
      the measurement's own disabling branch, which made PIT run every covering test, 485 tests);
      with it coverage 22 s, completed 59 s, wall 60.1 s (71 of 71 killed, 302 tests), so the
      coverage phase (+1 s, ~5 %) is the like-for-like figure. *Choice not taken:* the prescribed
      narrowing (sweep only after a cut drain, a non-`EXITED` termination or a descendant in the
      snapshot) breaks the scenario "A reparented orphan dies with the invocation" for a silent
      orphan — a clean exit, no pipe held, an empty snapshot (`stdin-holder.sh`;
      `GitExecGroupSweepSpec`'s alias) — so the full sweep stays and the decision is escalated
      under `spec-mismatch.md`. *Decided 2026-10-17 (user; D19's third amendment):* the full sweep
      stays — the cost is small in absolute terms (about 17 ms on a factory commit of two or three
      blobs); the narrowing is withdrawn; an in-process `kill(2)` probe is deferred with its pickup
      condition in D19.
- [x] 8.21 `ProcessSupervisorDescendantKillSpec` and `RealProcessTreeKiller`'s specs: the snapshot
      kill is kept for the cooperative phase; the sweep is observed after it; `terminateDescendants`
      is unchanged and its javadoc names the condition D19 states (every launch resolves through
      `await`, which sweeps — the shutdown kill is the backstop); `add-doctor-command`
      carries the helper check (its task 2.3 and the `Missing launcher helper` scenario, added
      2026-10-16), not implemented here.
- [x] 8.21a The shutdown step (D19, fourth amendment; FR21): `LiveGroups` in `:subprocess` — the
      child's pid registered by `GroupedLaunch` once `start()` returns, deregistered by
      `ProcessGroupSweep` on a refused probe, a launch that threw registers nothing;
      `RealProcessTreeKiller.killDescendants` runs `terminateDescendants`, then sweeps every
      registered group; `OrderedExit.onSignal` runs it before the context closes for every command
      that owns no hook, and `ServeShutdown` keeps its call. Specs: `LiveGroupsSpec`; a
      `run`-shaped hook on a real grouped `leaky.sh` whose orphan the snapshot misses — the orphan
      alive before the hook and gone after (scenario "A child in its own session is ended by the
      factory"); a `serve` shutdown while the waiter has not swept yet (scenario "A daemon's stop
      sweeps the groups no waiter has swept"); `LaunchOwnerGateSpec` names the registry's writers.
      Single-owner row `LiveGroups`; the old-way sweep over `addShutdownHook`, `killDescendants`
      and `terminateDescendants`.
- [x] 8.21b The non-atomic-write marker leaves `:atomicfile` (D20, amended 2026-10-18): `NonAtomicWrite`
      moves to a `build-checks` subproject beside `parameter-count-annotations`, put `compileOnly` on
      every module by `java-conventions`; `AtomicWriteBoundarySpec` matches its new fully qualified
      name; `GroupedLaunch` wears it with its reason (scratch handed to a subprocess);
      `EgressGuardConfig`, `HostChannelFiles` and `LedgerAppender` import it from there;
      `:sandbox:docker` drops its declared `:atomicfile` edge; its allowlist row stays as a runtime-only
      reach through `:application`, like `:baseref` (scenario "A sandbox backend
      declares only its permitted edges"). Old-way sweep: no `atomicfile.NonAtomicWrite` left.
- [x] 8.22 Delta scenarios of "Launched processes form their own process group" linked from 8.19–
      8.21; glossary entries **post-exit bound**, **launch owner**, **stdin source**, **process
      group sweep**, **live group registry**, **shutdown step**, **round scratch directory**, **cut capture** (a capture whose drain the
      post-exit bound cut; refused as a value on a clean exit, D15 as amended);
      `.claude/rules/testing.md` gains "A scheduling order is never a premise" with the four specs
      as the failure this exists for; `checkSpecContract` green.
- [x] 8.23 Rule and ADR trace (also: `implementation.md` item 2 gains one sentence — a flag or a
      verdict carried on a result is a value with consumers of its own, and its consumer list
      reaches the readers of the result, not the result's constructor; the failure named: the
      `truncated` flag of D15 died at `GitCommandResult.of` with a green build): `docs/adr/` — the post-exit bound and the group sweep are added to
      the subprocess ADR if one exists, else recorded in `bound-subprocess-commands`'s successor
      note in `design.md` only (local decision); `manual-sync-pairs.md` registry: no row, the
      seven-way pair is collapsed, stated in D4 with the class map (the five stdin writers
      removed by D20, `awaitExit`, `terminateDescendants`); `.claude/rules/lock-scope.md` or the
      subprocess ADR records the write-side fact — a child's stdin is never a pipe the factory
      writes into, with the JDK monitor as the reason — so the next pump is refused at design.
- [ ] 8.24 Gate on both systems (M12–M16; NFR-R4): on macOS, 20 runs of the four specs by name
      under two busy threads (80 of 80), the greps of M12, M13 and M16, the timings of M15 and the
      observed drain time after a clean exit (the margin the 7 s bound leaves, recorded so the bound
      is measured, not asserted); then the root `./gradlew check` once; then the Linux CI job
      green. Also: `TestDockerEnvironmentSpec` was red under a shared Gradle daemon started with
      launchd's `PATH` (`/usr/bin:/bin:/usr/sbin:/sbin`, `docker` in `/usr/local/bin`) and green
      under `--no-daemon` (2026-10-18); confirm which environment `DockerContextEndpoint`'s
      `execOperations.exec` resolves `docker` from, and if the daemon's, close the gap in
      `build-logic` under D14. Record here.
      *Recorded 2026-10-18 (macOS, developer machine; JDK 25.0.3, Gradle 9.7.0; the macOS half
      only — the root `check` and the Linux CI job are still open):* **M14 / NFR-R4 met on macOS:**
      20 runs of `:subprocess:test --rerun` (`ProcessSupervisorInterruptSpec`,
      `CaptureRunnerDrainSpec`), `:gitobjects:test --rerun` (`GitExecPumpThreadsSpec`) and
      `:adapters:test --rerun` (`ShellCommandCheckRunnerTimeoutSpec`) by name, under two
      `yes > /dev/null` busy loops for the whole series: **80 of 80** spec runs green (4 + 4 + 7 + 7
      features each, 440 feature runs, none skipped, a fresh result timestamp every run), 12–14 s per
      run. **M12 met:** the `.join()` grep shows two hits, `GroupedLaunch.signalGroup` (`:157`) and
      `ProcessSupervisor.awaitExit` (`:202`), both on a `completeOnTimeout`-bounded future, so no
      unbounded join; the `ExecPipeDrain` grep shows no production or fixture hit, only the two
      lines of `DrainOwnerGateSpec` (`:16`, `:45`) that spell the banned name as the gate's pattern —
      the grep as written is not empty, and the remaining hits are the gate itself. **M13 met:** the
      `.start()` grep shows `ProcessBuilder…start()` only in `GroupedLaunch` (`:151`, the sweep's
      ungrouped `kill`, allowed by 8.20; `:176`, the launch) — every other hit is a thread or loop
      `start()` or a `ToolCall.start()` accessor; `implements ProcessLaunch` only `GroupedLaunch:52`;
      `getOutputStream()\|redirectInput(` only in `GroupedLaunch` (`:150`, `:176`) plus one javadoc
      line of the owner's own interface, `ProcessLaunch.java:24`; `ChildProcessStdin` has no source
      file — two javadoc mentions in specs (`LaunchOwnerGateSpec:30`, `StreamDrainLogScopeSpec:15`)
      and stale reports under `sandbox/docker/build/`. **M16 met:** `truncated()` is read in
      production only in `:subprocess` (javadoc links in `CaptureRunner:17` and
      `CutCaptureException:139`; the flag itself is a local of `CaptureRunner.await:128-144`); the
      other three hits are `BoardModel.truncated()`, an unrelated record of `:application`'s board;
      `ObjectId`'s constructor refuses anything but 40 or 64 lowercase hex characters
      (`ObjectId.java:18-27`; `ObjectIdSpec` rows 39 and 41). **M15 met:** `exit-leaky.sh` through
      the production `CaptureRunner` (default kill grace 5 s, post-exit bound 7000 ms, the perl
      launcher and the real sweep), a throwaway harness outside the repo, 5 runs idle and 3 under the
      two busy loops: the call returned in 7057–7063 ms idle and 7019–7030 ms loaded (bound + 19 to
      63 ms), every time refused as `CutCaptureException` (exit 0, stdout `spoken`); the holder
      polled every 5 ms was last seen alive at 7012–7053 ms — after the bound, i.e. while the sweep
      began — and first seen dead 6–8 ms later, before the call returned; no holder left afterwards.
      **Observed drain time after a clean exit** (no holder; `sh -c 'head -c N /dev/zero'` through
      `ProcessLaunch.grouped()`, from `waitFor()` returning to `Drain.joinAll` returning, 50 runs
      per size): idle — 0 B median 9 µs (max 33 µs), 64 KiB 21 µs (128 µs), 1 MiB 182 µs
      (1.25 ms), 8 MiB 1.19 ms (2.77 ms); under the two busy loops — 8 µs (55 µs), 19 µs (168 µs),
      200 µs (1.28 ms), 1.36 ms (2.43 ms). The worst observed tail is 2.8 ms against a 7 s bound: a
      margin of about 2500×, so a clean exit on this machine is never cut by the bound.
      **`TestDockerEnvironmentSpec` and the daemon's `PATH` — confirmed, the daemon's:**
      `DockerContextEndpoint.obtain()` runs `spec.commandLine(parameters.executable.get(), …)`
      (`DockerContextEndpoint.groovy:73`) with `executable` the bare `'docker'`
      (`TestDockerEnvironment.groovy:72` builds `new Host(os.name, 'docker')`, `:148` passes it on),
      and a start failure is caught and turned into `Endpoint.NONE` without a word (`:81-85`).
      Experiment (Docker Desktop, `docker` only in `/usr/local/bin`): (A) a daemon started by
      `env -i … PATH=/usr/bin:/bin:/usr/sbin:/sbin ./gradlew help`, then the spec from a normal shell
      on that daemon — **red**, `FR14: with a daemon available the test JVM carries the endpoint as
      DOCKER_HOST` fails (`DOCKER_HOST` null) and the macOS socket-override feature is skipped;
      (B) a daemon started from a normal shell, normal client — green, 4 of 4; (C) the same normal
      daemon, the client run with the minimal `PATH` — **green**, 4 of 4. So the bare name is resolved
      against the `PATH` the daemon JVM was started with, never the client's (C's child environment
      carried the minimal `PATH` and `docker` was still found), and an IDE-started daemon with
      launchd's `PATH` silently hands the test JVMs no `DOCKER_HOST` and no socket override. Second
      instance of the class: `MutationScopeSource.git` (`:160`) runs a bare `git` the same way —
      under launchd's `PATH` it resolves to `/usr/bin/git` 2.50.1 (Apple), below the 2.51 floor,
      where the operator's shell has `/opt/homebrew/bin/git` 2.56.0; its failure is also a silent
      `null` (`:154`, `:167`). The test JVMs and their children are not this class: their
      environment is the client's. Gap **not closed here** — reported under `spec-mismatch.md` for
      the decision (options: resolve the binary by the client's `PATH`, read as a `ValueSource`
      parameter like `DOCKER_CONFIG`, and hand `exec` an absolute path; and in either case refuse
      loudly, not `Endpoint.NONE`, when no `docker` resolves while the build would otherwise
      configure Docker).
      *Recorded 2026-10-18 (macOS, root `./gradlew check --continue`):* run 1 (1 h 40 min) red on
      `LogContractGateSpec` — the WARN of `ClientPathTool` (task 7.5) carried no code; fixed by an
      in-place `log-contract-exempt` (a Gradle console line, the included build cannot reach the
      catalog) — which also failed `:bootstrap:pitest`'s green-suite precondition, and on one
      `:adapters:github` survivor (`GithubHeartbeat.patchClaimComment:137`, a class this change does
      not touch; 15 of 15 killed scoped). Run 2 (2 h 18 min): every module's PIT 100 % (bootstrap
      467/467, adapters:github 607/607), red only on two features green in run 1 and green on rerun by
      name — `GithubTrackerContractSpec` "fetchTask carries the snapshot title and body verbatim"
      (8 of 8; under load the fetch read the issue as `Gone`) and `TakeCommandStandingReaperWiringSpec`
      (3 of 3; a 5 s polling bound). Both are load-sensitive, not caused by this change's code paths;
      open: their flakiness is a finding for a follow-up. Linux CI: pending the user's push.

## Follow-ups from tighten-spec-contract

- F67 (closed by task 3.4): move the marker of `GitResumeOutcomeSpec` "run() with outcome completed reports and exits without further touching the worktree or branch" (a lost-cleanup tip) to `Completed task with a lost cleanup converges`, asserting the cleanup, and write a test of the narrowed `Completed task resumes to a no-op` on a cleaned-up branch.
