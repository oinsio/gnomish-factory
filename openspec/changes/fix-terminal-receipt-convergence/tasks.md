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

## Follow-ups from tighten-spec-contract

- F67 (closed by task 3.4): move the marker of `GitResumeOutcomeSpec` "run() with outcome completed reports and exits without further touching the worktree or branch" (a lost-cleanup tip) to `Completed task with a lost cleanup converges`, asserting the cleanup, and write a test of the narrowed `Completed task resumes to a no-op` on a cleaned-up branch.
