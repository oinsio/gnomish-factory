# Design: fix-terminal-receipt-convergence

## Context

See `proposal.md` — Why. Facts the approach rests on (verified in code on 2026-10-06, re-verified
2026-10-14):

- `GuardedPark.observeAtTarget` (`take/GuardedPark.java:133-145`) answers LANDED iff
  `fetchTask(ref).state() instanceof AwaitingHuman`; `FinishEffect.observeAtTarget`
  (`take/FinishEffect.java:64-78`) iff `finished()` or state `Finished`. Both are
  `TerminalEffect` implementations driven by `TerminalEffectDrive`, and both deliver under
  `ClaimGuard.stillOurs` (`TakeFinishReport.java:57`).
- Every `take` pickup runs after `tracker.claim` succeeded (`TakeReconcile` javadoc, lines
  36-50), so the state is `Working(this)` at probe time; the javadoc claims the branch's pending
  marker distinguishes "never landed" from "landed" — which fails in the window after the
  tracker write and before `confirmTerminalWrite`.
- `TakeDisposition.dispose` (`:71-85`): a `Ready` task with `finished()` is declined
  (`refuseFinished`), a `Finished` task is `Skipped` — no path reaches a `CompletedUncleaned`
  branch once the finish landed. `TakeDisposition` holds no git collaborator; the clone is
  reachable as `order.run().cloneDir()`. `TakeReconcileFinish.finishUncleaned` (`:90-99`) is
  only reached from a claimed pickup, and receives its cleanup as a `Runnable` built in
  `TakeLoadedBranchRoutes` (`:96-97`, `mechanics.finishCleanup(cloneDir, branch)`).
- `GitResumeContinuation.reportCompleted` (`:137-142`) and
  `ContainerResumeOutcomes.reportCompleted` (`:143-148`) print and return; neither cleans.
- The cleanup commit has one writer per store (`TaskLifecycleStore.finishCleanup`, pushed by
  `PushBestEffortTaskLifecycleStore`) but five callers in `application/src/main` and
  `bootstrap/src/main`: `TakeEngineExecution:183` (fresh finish), `HostResumeMechanics:73` and
  `ContainerResumeMechanics:51` → `ContainerRunTermination:84` (the `ResumeMechanics.finishCleanup`
  strategies behind `TakeLoadedBranchRoutes`), `GitOutcomeRecorder:77` and
  `ContainerTerminalDrive:78` (manual `run`, no tracker). `FinishTransition.cleanup()` is the
  Runnable each take path hands in.
- `GithubHistoryFactReader` derives `returned`/`finished` from the **whole** marker thread
  (`hasKind`, `:45-54`). The boundary fact that exists, `TrackerFacts.latestBoundary`, is
  **claim-scoped on purpose** (`TrackerFacts.java:13-16`): `GithubTrackerFacts.latestBoundary`
  (`:76-91`) and `TrackedTaskFacts.latestBoundary` (`:61-72`) return null when a CLAIM follows
  the boundary — which is exactly the state at probe time. `GithubCommentBoundary.latestBoundaryIndex`
  (`:46`) is the index of the latest CLAIM-or-ABORT marker, not a park/finish boundary.
  `GithubCommentUpsert` makes every factory comment a find-then-upsert on a
  `GithubCommentIdentity` — a re-drive is idempotent at the comment level, so the harm of 4c is
  the *state* change (re-parking a returned task), not a duplicate comment.
- `TrackerTask` (`gnomish-plugin-api`) carries `ref, snapshot, state, abortFacts, finished,
  designators` — no boundary. Both adapters pass the shared port contract suite.
- Kill-point pickups: `ParkKillPoints.pickup` (`:114-128`) and `FinishKillPoints.pickup`
  (`:187-201`) drive the effect with `world.takeOrder()` — the seeding instance's claim, tracker
  still `Working(kp0001)`/`AwaitingHuman`; no return, no second claim. `TakeKillPointWorld`
  (used by `ResumedKillPoints`, `GateKillPoints`, `ApprovalKillPoints`) already has a
  production-route `pickup()` (`routes.call(takeOrder())`, `:51-57`, behind a precheck on
  `AwaitingHuman` + the pending marker) and `returnedAndReclaimed()` (`:64-70`: return to
  `Ready`, reclaim by the **same** instance, epoch issued).
- `WorktreeJanitor` (`serve`) is the per-clone hygiene thread (worktrees by age, hourly); it
  holds no tracker and no git (`:60-65`), and its javadoc (`:36-38`) and the `factory-serve`
  scenario "The cleaner consults no tracker status" fix that policy for the age pass.
- The cleanup commit has two writers, one per store. The host one, `CleanupCommit`
  (`adapters/git`), runs `git rm -r .gnomish-task` in the task's **shared worktree**
  (`ensureWorktree` hands every process of one factory home the same path) before
  `WorktreeClosingCommit` builds the commit and lands it under the register's compare-and-swap.
  The container one, `GitObjectsTerminalCommits.cleanUp`, edits the tip's tree in a private
  index (`GIT_INDEX_FILE`) and lands the same transaction; it never touches a worktree. The
  `GitObjects` it writes through is opened by `ContainerRunSupport` over `cloneDir/.git` — the
  host clone — with a temp dir: nothing in it is container-specific, although three designs
  (`fix-envelope-medium` NG1, `tighten-spec-contract` D13, this change's D2 as first written)
  named it "the container medium's writer". Task 3.3 (2026-10-14) raced two host cleanups and
  found the loser failing on the worktree's `index.lock` during `git rm` — before either reached
  the compare-and-swap — then re-reading a tip still `CompletedUncleaned` and rethrowing. Git
  retries ref locks (`core.filesRefLockTimeout`, `reftable.lockTimeout`) but never the index
  lock, and a kill leaves `index.lock` behind for an operator.
- `make-checkpoint-gate-durable` (archived 2026-10-09) and `tighten-spec-contract` (archived
  2026-10-14) rewrote `TakeLoadedBranchRoutes`, the `ParkKillPoints` rows and the `run`
  completed/paused arms; the text this change layers on is the stable spec. Active neighbours:
  `add-claim-return` (tracker-take return verb; not touched here), `add-stage-iteration`.
- Branch adoption (verified 2026-10-14, after the implementation audit). Readers resolve the
  branch through one owner, `TaskBranchLocator.locate` (`:101-131`): `Local`
  (`refs/heads/gnomish/<b>`) or `RemoteTracking` (`refs/remotes/origin/gnomish/<b>`, refreshed
  by the touchpoint fetch whenever no local branch exists, the register landing in the clone's
  register itself — `FactoryRegister.fetchedInPlace:84-87`); `classifyShape:100-102`,
  `BranchStateReader:90`, `DeliveredBranchReader:101`, `TaskBranchLister:80-83` read either.
  The bare-object writers derive `refs/heads/` from the bare task id
  (`GitObjectsTaskRepository.refFor:342`, a duplicate of `FactoryRegister.branchRef:204`), and
  the two prefixes are spelled by hand in `TaskBranchLocator:102-103`,
  `ReplicaPairReconciler:116-117`, `TaskBranchLister:59-60`, `BaseRefresh:118`. The step between
  — making the branch local — exists three times: the host bootstrap (`TakeResumeBootstrap:84`)
  hands `worktree add` a short name (`TaskWorktreeManager:69`) and git creates the branch itself
  (its "exactly one remote-tracking of that name" rule); the container bootstraps call the port's
  `ensureLocalTaskBranch` (`TaskBranchGit:49`, `ContainerResumeBranch:67-80`: `git branch <b>
  <tracking>`, no compare-and-swap, the loser fails "already exists"); the claimless path has
  none, so `DeliveredCleanup` classified `CompletedUncleaned` from the tracking ref and
  `finishCleanup` threw "no branch exists" — the audit's CRITICAL 1. `CommitBuilder.checkPrecondition:63-71`
  already lands a ref that must not exist (`create` in the same `update-ref --stdin`
  transaction); `GitPush` pushes explicit refspecs only (`:21`), and no production code reads
  `@{u}` or runs `pull`, so a branch needs no upstream configuration.

Facts the 2026-10-15 revision (D13) rests on, verified in code that day:

- `TaskBranchLister.list` (`adapters/git`, `:78-88`) enumerates `refs/heads/gnomish/*` and
  `refs/remotes/origin/gnomish/*` with `for-each-ref` and runs no fetch; each ref is classified
  through `RefTipSource.at` → `FactoryRegisterRead.at` (`:59-65`), which reads only the clone's
  own `refs/gnomish-factory/<t>`. No register over an envelope is `TipRelation.NoRegister`, and
  `BranchShapeClassifier` (`:100-104`) answers `Unknown("no factory register … names a commit of
  the branch tip …")`.
- `DeliveredBranchPass.run` (`app/serve`, `:75-77`) keeps `instanceof CompletedUncleaned` rows
  only; the rest are skipped with no log line. Its `converge` then runs `fetchTask` and
  `ClaimlessDeliveredCleanup.convergeIfFinished` (`:86`), whose `locate` *does* run the touchpoint
  fetch — after the decision of which rows to visit, never before it.
- The factory clone is an ordinary clone: `--single-branch` is on the container seed clone alone
  (`GitTransfer.clone`, ADR 0008's `SeedPath` row). The only persistent write to the factory
  clone's configuration is `core.hooksPath` (`FactoryCloneHardening:62-66`); nothing writes
  `remote.origin.fetch`, and every register transfer is an explicit one-task refspec
  (`FactoryRegisterRefs.fetchedInPlace` / `fetchedTracking`, used by `TouchpointFetch.fetch` only).
  So an operator's `git fetch`, a `git pull`, or an IDE's periodic fetch lands every `gnomish/*`
  branch as a tracking ref with no register.
- The janitor's branch pass is the only daemon path to `ClaimlessDeliveredCleanup`: the feed
  declines finished tasks (`FinishedDecline`), the reaper reads the tracker only, the sweep reads
  containers only. A finished task's lost cleanup on another instance's clone converges in the
  background through this pass or not at all.
- The same half-pair exists on a local head: `TaskBranchLocator.locate` returns `Local` with no
  fetch (`:105-106`), so a head an operator checked out by hand classifies `Unknown` on a claimed
  take and is parked `AwaitingHuman(INFRA)` (`TakeDispositionResume:82` → `TakeQuarantinePark`);
  `ReplicaPairReconciler.followOriginRegister` (`:185-206`) runs later in the resume bootstrap,
  after that decision. Deferred (proposal NG5).

## Goals / Non-Goals

**Goals:** a lost receipt never undoes a human's return (G1); `CompletedUncleaned` always
converges (G2); the kill-point matrix picks up like production (G3).

**Non-Goals:** new tracker writes; the fenced return; the pre-park windows.

## Decisions

**D1 — The receipt is confirmed from the tenure boundary, a whole-thread fact.** `TrackerTask`
gains `@Nullable BoundaryKind tenureBoundary` — the last PARK / FINISH / ABORT /
STALE_CLAIM_REMOVED marker on the task's thread in posting order, or null when none, **whatever
claim markers follow it**. GitHub derives it from the parsed marker thread `fetchTask` already
reads, beside `GithubHistoryFactReader`'s whole-thread `returned`/`finished` (no extra call,
NFR-C1); the in-memory adapter reads the last such entry of `TrackedTask.thread()` (every write
op already records one; nothing new is recorded). It is **not** `TrackerFacts.latestBoundary`
and is not derived through `GithubTrackerFacts.latestBoundary`, `TrackedTaskFacts.latestBoundary`
or `GithubCommentBoundary.latestBoundaryIndex`: those are claim-scoped by design (the reaper's
view of the current tenure) and null at probe time; reusing them reproduces the defect. The
glossary gets the term (**tenure boundary**, *Not:* the claim-scoped boundary of the shape
facts). `GuardedPark.observeAtTarget` answers LANDED iff `tenureBoundary == PARK`, ABSENT iff
`STALE_CLAIM_REMOVED` or null, UNDETERMINED on a tracker failure as today;
`FinishEffect.observeAtTarget` answers LANDED iff `FINISH` or `finished()`. *Rationale:* the
state is overwritten by the pickup's own claim; the thread's last boundary is not. After a
landed park it is PARK whether the human returned the task or not; after a lost park the dead
claim is reaped, leaving STALE_CLAIM_REMOVED — exactly the two cases 4c conflates. *Alternative
rejected — stamp the claim epoch into the park comment and compare:* a second structural marker
and a parse on the hot path for a fact the boundary already states.

**D2 — One owner of the claimless delivered cleanup, over one cleanup commit writer (D8).**
`DeliveredCleanup` (`:application`): given a clone, a task id and a tip, when the tip is
`CompletedUncleaned` and the tracker reports the task finished (`finished()` or boundary
`FINISH`), it runs the cleanup commit through `TaskLifecycleStore.finishCleanup` (pushed
best-effort by the store's decorator, as today; on both media the store's `finishCleanup` is
the bare-object writer of D8) and then the medium's disposal (host: the
worktree, as `GitOutcomeRecorder.disposeWorkspace` does; container: the sandbox support's
disposal); a `Delivered` tip is a no-op; a ref update that loses a race logs DEBUG and returns.
It is **not** a `TerminalEffect`: it writes no tracker effect, so there is nothing to probe and
no `ClaimGuard` — no claim is written or checked. It replaces the per-medium
`ResumeMechanics.finishCleanup` strategies (`HostResumeMechanics`, `ContainerResumeMechanics`),
which are deleted, and the cleanup half of `TakeEngineExecution`'s fresh-finish lambda. Consumers
by file: `TakeLoadedBranchRoutes` (the `Runnable` handed to `TakeReconcileFinish.finishUncleaned`),
`TakeEngineExecution` and `TakeContainerEngineExecution` (`FinishTransition.Fresh`'s cleanup), `TakeDisposition`'s `Finished` and
`Ready+finished` arms (after the decline/skip message, when the clone at `order.run().cloneDir()`
holds the task branch; `TakeDisposition` reaches it through one field, the slot's
`ClaimlessDeliveredCleanup.forSlot(wiring)`), `GitResumeContinuation.reportCompleted` /
`ContainerResumeOutcomes.reportCompleted` (before printing), and `WorktreeJanitor`'s tick — a
new branch pass over the clone's `gnomish/*` branches on every tick (proposal Q1: every tick, a
local listing); the janitor gains the bound tracker, a branch lister over the clone and
`DeliveredCleanup`, and its "no tracker check" policy narrows to the age pass (javadoc and the
`factory-serve` delta say so). *Amended 2026-10-15 (D13):* the pass forwards a branch by the
domain's forwarding table — `CompletedUncleaned` and `Unregistered` — never by its own
`instanceof`, and the claimless owner reads the branch through the fetching locate **before** it
classifies: locate (the touchpoint fetch lands the branch and its register) → classify at the
register → `fetchTask` only for a `CompletedUncleaned` tip → adopt → clean. The listing stays
local and free; the fetch is paid once per forwarded branch, and a branch it does not forward
costs nothing. Exemptions, with
reason: `GitOutcomeRecorder.recordAndCleanUp` and `ContainerTerminalDrive` — manual `run` has no
tracker, so the cleanup follows its intent immediately (FR10 of harden-task-branch-contract) and
there is no "finished" to check; they keep calling the store directly. *Rationale:*
`crash-consistency.md` item 3 — the shape has writers today, but no route reaches them after the
finish lands; three new entry points need one owner, not three copies. *Alternative rejected —
re-claim finished tasks for cleanup:* contradicts `enforce-finish-terminality` (a finished task is
never claimed) and would re-open the task in the tracker for a git-only step.

**D3 — Kill-point pickups go through the production route, on the world that already has it.**
`TakeKillPointWorld` gains `claimAsSecondInstance()` (a new `InstanceId` with its own
`ClaimEpochBook`, the real `tracker.claim`, and a `TakeOrder` for it) and `reap()` (the reaper's
`removeStaleClaim`); `returnedAndReclaimed()` stays for the same-instance rows. The
`ParkKillPoints` and `FinishKillPoints` rows move onto `TakeKillPointWorld`
(`TakeKillPointWorlds`), and their pickup is the row's own sequence — return as the human (park
landed) or reap (park lost), claim as the second instance, then the real
`TakeLoadedBranchRoutes.route` / `TakeDisposition.dispose` under that order — instead of driving
the effect directly. `TakeKillPointWorld.pickup()`'s precheck on `AwaitingHuman` + the pending
marker (the heuristic this change removes) goes; a row that wants an empty pickup says so. The
two windows this change fixes become assertions: after the park's tracker write the
human-returned pickup must not re-park; after the finish's tracker write the finished-task
disposition must land the cleanup. *Rationale:* the matrix passed while production failed
because the pickup skipped the step that changes the state the probe reads (`testing.md`,
"Fixtures assemble through production owners"); a second world with its own production-route
pickup would be two fixtures of one rule. *Alternative rejected — keep direct effect drives and
add a tracker-state stub:* tests the stub.

**D4 — Sync surfaces.** One declared pair is touched and collapsed: `CleanupCommit` ↔
`GitObjectsTerminalCommits.cleanUp` (both ends carried the marker; kept by `fix-envelope-medium`
as "different media: subprocess vs bare objects"). `DeliveredCleanup` is a new consumer of that
pair with a condition neither design had asked of it — many writers from different processes,
under no claim — and the host end fails it (Context; D8). Preference 1 of
`manual-sync-pairs.md`: the host store's `finishCleanup` takes the bare-object end, the host end
is deleted, and no pair remains. `GuardedPark`/`FinishEffect` are two `TerminalEffect`
implementations over different facts, not two implementations of one rule.
`ResumeMechanics<B>` is the shared abstraction of the two resume media, not a pair: removing its
`finishCleanup` from both strategies in favour of `DeliveredCleanup` keeps it one abstraction with
one method fewer. The new fact is a port contract: both adapters pass the extended suite, which
is the mechanism `tracker-port` already uses instead of a pair. The kill-point harness keeps one
production-route pickup (`TakeKillPointWorld`), not two. The step "make the task branch local"
was an undeclared triple — the host's name resolution inside `worktree add` (git's rule, no
project code), the container's `git branch` in `ContainerResumeBranch`, nothing on the claimless
path (Context); preference 1 again: it collapses into `TaskBranchAdoption` behind the port's
existing `ensureLocalTaskBranch` (D9), and the other two are deleted (D11).

The test build's Docker environment (D14) is one owner, not a pair: the build-logic class
`TestDockerEnvironment` is the only place that names the committed configuration and sets the
four variables, and the `:bootstrap` gate asserts their effect (what the test JVM's
configuration holds, which endpoint it carries), not a second spelling of the path — unlike
`AdversarialGitConfig`, no fixture needs the constants, so no "no shared classpath" row is added.

**D5 — Single-owner mechanisms.**

| Owner | Value (type) | Requires | Consumers | Old way removed | Enforced by |
|-------|--------------|----------|-----------|-----------------|-------------|
| `TrackerTask.tenureBoundary` — the one fact a receipt probe reads | `@Nullable BoundaryKind`, whole-thread | the parsed marker thread `fetchTask` already reads; no extra call | `ParkLanding` (`take/`, the one park reading, added in task 2.1) for `GuardedPark.observeAtTarget` and the `Parked`+marker pre-check of `TakeLoadedBranchRoutes`; `FinishLanding` (`take/`, the one finish reading — `finished()` or `FINISH`, added in task 2.2) for `FinishEffect.observeAtTarget` and `DeliveredCleanup` (finished check) | `fetchTask(...).state() instanceof AwaitingHuman` in `GuardedPark` — deleted; `state() instanceof Finished` in `FinishEffect` — replaced; the claim-scoped `TrackerFacts.latestBoundary` stays for the reaper and is never read by a probe | the port contract suite feature (both adapters, including a boundary read after two later claims); grep gate in `ReceiptProbeGateSpec` (`:bootstrap`): `TrackerTaskState.AwaitingHuman` and `latestBoundary` do not appear in `take/GuardedPark.java`, `take/ParkLanding.java`, `take/FinishEffect.java` or `take/FinishLanding.java` |
| `DeliveredCleanup` — the one claimless cleanup of a delivered branch | the cleanup commit + push + disposal, idempotent, no `TerminalEffect` | the clone's `.git` and the tracker's finish fact; **no claim, no worktree index** — many writers from different processes (`take`, `run --resume`, the `serve` janitor) may run it at once | `TakeLoadedBranchRoutes` (cleanup `Runnable` for `finishUncleaned`), `TakeEngineExecution` (fresh-finish cleanup), `TakeContainerEngineExecution` (container fresh-finish cleanup; added by task 3.1, which found it outside this list), `TakeDisposition` (finished arms), `GitResumeContinuation.reportCompleted`, `ContainerResumeOutcomes.reportCompleted`, `WorktreeJanitor.tick` (branch pass, `DeliveredBranchPass`); the two claimless consumers (`TakeDisposition`, the branch pass) reach it through `ClaimlessDeliveredCleanup` (added by task 3.2: the branch-location check and the medium chosen by the execution-mode plan, as `TakeWorkRouter` chooses it) | `ResumeMechanics.finishCleanup` with `HostResumeMechanics:66-73` and `ContainerResumeMechanics:50-51` — deleted; `taskRepository.finishCleanup` + `disposeWorkspace` in `TakeEngineExecution:183` — routed (`GitOutcomeRecorder.disposeWorkspace` deleted); `support::finishCleanup` in `TakeContainerEngineExecution:160` — routed; `reportCompleted` printing without cleaning — replaced. Exemptions: `GitOutcomeRecorder.recordAndCleanUp`, `ContainerTerminalDrive` → `ContainerRunTermination` (manual `run`, no tracker, FR10) | grep gate in `ReceiptProbeGateSpec`: `finishCleanup(` (and the method reference `::finishCleanup`) is called in `application/src/main` and `bootstrap/src/main` only from the allowlist `DeliveredCleanup`, `GitOutcomeRecorder`, `ContainerTerminalDrive`, `ContainerRunSupport` (the support's `SandboxRunSupport.finishCleanup`, the hop between the drive and the termination; added by task 3.3, which found it calling), `ContainerRunTermination`, every allowlisted file asserted reached and the caller set equal to the allowlist; identity spec `DeliveredCleanupIdentitySpec` (bare origin, both media, each consumer through its real entry point): after any of the seven consumers runs on a `CompletedUncleaned` tip, the tip is `Delivered`, the workspace is gone and origin carries the cleanup, and a second run writes nothing — its container fresh-finish row, which needs a box that closes a round, is `DeliveredCleanupContainerE2ESpec` (Docker), and the two-writer race is `DeliveredCleanupRaceSpec` |
| `BranchShape.forwardedByBranchPass()` (`:domain`) — the one table of which shapes the janitor's branch pass hands to the claimless owner (D13) | a `boolean` per shape, beside `recoveryOwner()` and `disposition()`; the sealed switch names every shape | nothing | `DeliveredBranchPass.run` (the only reader; it forwards what the table says and logs a DEBUG line per branch it does not forward) | the pass's own `instanceof BranchShape.CompletedUncleaned` — deleted | grep gate `BranchPassTableGateSpec` (`:bootstrap`; its own spec, `ReceiptProbeGateSpec` being past the file-size cap): `instanceof BranchShape.` does not appear in `serve/DeliveredBranchPass.java`, the file asserted reached (M8); the identity spec's janitor rows on a second clone seeded as a plain `git fetch` leaves it (M9) |
| `ClaimlessDeliveredCleanup.converge` — the one claimless sequence over a branch the clone may hold with or without its register (D13) | the ordered sequence locate → classify at the register (the port's location-taking `classifyShape`, so one locate before `adopt`) → `fetchTask` (only for `CompletedUncleaned`) → adopt → `DeliveredCleanup.cleanUpIfFinished` | the clone's `.git`, origin for one touchpoint fetch per call, the tracker for one read per `CompletedUncleaned` tip; no claim | `DeliveredBranchPass` (hands a task ref, not an observed task), `TakeDisposition`'s two finished arms (hand the task they already observed; the sequence skips the tracker read it already has) | `convergeIfFinished(Path, TrackerTask, PipelineDefinition)` with the tracker read before the locate — replaced by the sequence; the pass's `fetchTask` before the owner — deleted; the two-argument `classifyShape` is not called before `adopt` (it would locate a second time); after it the head is local, and `DeliveredCleanup`'s own reads of it fetch nothing | `DeliveredCleanupIdentitySpec`'s janitor rows (M9) and `DeliveredBranchPassSpec`: a branch with no register in the clone, the register at origin, converges on one tick; a branch still `Unregistered` after the owner's read is named in the pass's aggregate line with its location kind and left for the quarantine owner (FR12); `ClaimlessDeliveredCleanupSpec`: one `locate` before `adopt`, and the classification over its answer |
| `GitObjectsTerminalCommits.cleanUp` — the one writer of the cleanup commit, on both media (D8) | the cleanup commit, landed with the register in one `update-ref --stdin` transaction under the tip's compare-and-swap | a `.git` (any clone, the host's included) and a temp dir for the private index; no worktree, no index lock, no box | `GitTaskRepository.finishCleanup` (host; the `GitObjects` is opened over the repository's own clone's `.git`, the JVM temp dir for the private index, so no other clone can be named), `GitObjectsTaskRepository.finishCleanup` (container) | `CleanupCommit` — deleted, with its `git rm -r .gnomish-task` in the task worktree and its staged-removal convergence matrix, which existed only because the worktree was the medium | the parameter type: `GitTaskRepository`'s constructor takes no `GitObjects`, only the `RegisteredClone` it opens one over; a grep gate in `ReceiptProbeGateSpec`: no `"rm"` argv with `EnvelopePaths.DIR_NAME` and no `CleanupCommit` in `adapters/git/src/main`; `DeliveredCleanupRaceSpec`'s host feature green with no `@PendingFeature` (M5) |
| `TaskBranchAdoption` (`adapters/git`), behind the port's `TaskBranchGit.ensureLocalTaskBranch` — the one way a branch held only as a remote-tracking ref becomes local (D9) | `BranchAdoption`, sealed: `Adopted(String taskId)` / `AlreadyLocal(String taskId)` / `Nowhere` (as built, task 6.2, then `tip` dropped by task 6.4 under D12 as revised: the `LocalTaskBranch` view names the task, which `ensureWorktree` and `DeliveredCleanup.cleanUp` need); its `LocalTaskBranch` view is what a writer's entry accepts | a `.git` and the tracking ref the locate just refreshed; no worktree, no index, no claim, no network of its own | `TakeResumeBootstrap` (host take, before `ensureWorktree`), `GitResumeRunner`'s bootstrap (host `run --resume`), `TakeContainerResumeBootstrap` and `ContainerResumeRunner` (as today, typed result), `DeliveredCleanup.cleanUp` (claimless; over the location `ClaimlessDeliveredCleanup` already holds) — as built in task 6.3: a caller holding a fresh lookup adopts through the port's `TaskBranchGit.adopt(Path, String taskId, BranchLocation)` (the same owner over the caller's location, no second lookup: the host bootstrap, `ClaimlessDeliveredCleanup`); the bootstraps' answer travels in `ResumedBranch.branch()` to the claimed writers of `DeliveredCleanup.cleanUp` (`TakeEngineExecution`, `TakeContainerEngineExecution`, `TakeLoadedBranchRoutes`, `GitResumeContinuation`, `ContainerResumeOutcomes`); added by task 6.3, which found them outside this list: the two fresh claims (`TakeFreshClaim`, `TakeContainerFreshClaim`), which take the created branch from the owner through `GitFreshTaskSupport.createdBranch` (lookup + `adopt`, answered already local) | the host's short name in `worktree add` — `TaskWorktreeManager.ensureWorktree` takes a `LocalTaskBranch`, so git never resolves a name; `ContainerResumeBranch`'s `git branch` arm — deleted, the `Local` reconcile stays. Exemption (task 6.3): the host lifecycle store `GitTaskRepository`, whose writes are keyed by the task id, materializes through the package-private `TaskWorktreeManager.storeWorktree(taskId)` — its STARTED write right after `TaskBranchCreator` made the head, every later write behind a bootstrap that adopted (D10's reasoning for the bare-object writes); both entries share one materialization that verifies `refs/heads/gnomish/<b>` before `worktree add` and refuses an absent head, so neither can create a branch | parameter type: `ensureWorktree(RegisteredClone, LocalTaskBranch)` and `DeliveredCleanup.cleanUp(Path, LocalTaskBranch)` cannot be built from a bare id; `TaskBranchCommandGateSpec` (`:bootstrap`; its own spec, `ReceiptProbeGateSpec` being past the file-size cap): `"branch", ` argv only in `TaskBranchCreator`, `"worktree", "add"` only in `TaskWorktreeManager`, and a `LocalTaskBranch` record constructed in production only by `TaskBranchAdoption` and `ContainerResumeBranch`'s local arm — a record's constructor is public, so the scan is what keeps the type from being built from a bare id (M6); identity rows of `DeliveredCleanupIdentitySpec` on a second clone holding no local branch; race spec `BranchAdoptionRaceSpec` (two adopters, real threads, one clone); cross-clone spec `DeliveredCleanupCrossCloneSpec` (D12: the loser's line stands, its three readers behave, and a claimed take after a lost finish leaves it standing) |
| `TaskBranchRefs` (`adapters/git`) — the one spelling of `refs/heads/gnomish/<b>` and `refs/remotes/origin/gnomish/<b>` | two `String` refs through two named methods — a primitive the git argv needs; the owner is enforced by the gate, not the type | nothing | `TaskBranchLocator`, `ReplicaPairReconciler`, `TaskBranchLister` (the two namespaces, `HEAD_NAMESPACE`/`TRACKING_NAMESPACE`), `FactoryRegister` (its transactions; the `branchRef` alias it held is deleted, task 6.2's file-size split), `HarvestAdmission`, `WorktreeClosingCommit`, `TaskBranchAdoption`; added by task 6.1's sweep: `LocalBranchTip`, `RemoteBranchTip` (both now take the `taskId`, so their callers `FirstPush`, `ParkDeliveryFence`, `OriginReconciliation`, `RemoteAttemptDelivery`, `TaskBranchReconciliation` pass it), `SandboxRoundEnvironmentSource`, `ContainerTipReader`, `ContainerRunTermination`; `GitObjectsTaskRepository.refFor` and `REF_PREFIX` — deleted, its seven writes and `GitTaskRepository.finishCleanup` take `TaskBranchRefs.head` | the inline literals in those files — deleted. Exempt, each spelling a ref that is not a task branch: `RemoteBaseRef`, `BaseRefresh` (the base: listed as a consumer before the sweep showed its ref is the base, not a task branch), `RemoteDefaultBranch` (origin's default branch), `ManualResumeLawBinding` (the base) | grep gate `TaskBranchRefsGateSpec` (`:bootstrap`; a spec of its own because `ReceiptProbeGateSpec` is past the file-size cap): neither prefix inside a string literal outside the owner and the four exemptions in `adapters/git/src/main`, `application/src/main`, `bootstrap/src/main` (M7), the allowlist exact and asserted reached; the `gittransfer` leaf's `GitPush`/`RegisterRef` spell their own, outside the scan |
| `ScenarioLinkRule` in `build-logic` — the one judge of which titles a marker may name and which scenarios must be linked | the **resolvable** set (main-spec scenarios ∪ scenarios of ADDED/MODIFIED requirements in unarchived deltas; task 0.1) and the **retired** set (main-spec scenarios under a requirement an unarchived delta REMOVES by title, same capability path; task 0.3), each computed once per run inside `judge` | the delta files of `openspec/changes/*` (never `archive/`) as a task input — `SpecContractCheck.getDeltaSpecs()` already carries the REMOVED sections | the dangling-marker finding (resolvable set) and the unlinked-scenario finding (main-spec scenarios − retired); the retired set has no other consumer — the stale-exemption finding (`ScenarioExemptions.assertReached`) keeps receiving all main-spec scenarios, so retirement only relaxes the gate | resolution against main specs only — widened in place (task 0.1), no second resolver; the link demand on a removed requirement's scenarios — waived by the retired set (task 0.3); the interim exemption line for a removed scenario and the staged-removal marker kept "until the archive" — deleted by task 0.3's own edit and sweep | the functional specs of the `quality-gates` scenarios of D7 in `build-logic/src/functionalTest` (task 0.1's three, task 0.3's two), the archive stage's own `checkSpecContract` run (`.gnomish/stages/archive/stage.yaml`), which reports any marker or exemption line left on a scenario once it leaves the main spec, and the root gate run with task 1.1's six markers in place and the interim exemption file gone (M4) |
| `TestDockerEnvironment` (`build-logic`) — the one place the test build composes the Docker client environment of a forked test JVM (D14) | four variables on the task's environment: `DOCKER_CONFIG` (a per-task copy of the committed configuration), `DOCKER_HOST` (the operator's, or the resolved context endpoint), `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (macOS, `unix://` endpoint, unless set), `DOCKER_CONTEXT` removed | the operator's environment and Docker configuration at configuration time; at most one `docker context inspect`; no daemon call | every `Test` task and the `pitest` task, through `test-conventions` (no module configures it; minions inherit the `pitest` JVM's environment); children reach it through inheritance (`DockerCli`, raw `docker` builders in fixtures, credential helpers) or `TestChildEnvironment` (the E2E jar) | the inherited operator `DOCKER_CONFIG`/`~/.docker` and context; `TestEnvironmentHygieneFunctionalSpec`'s use of `DOCKER_CONFIG` as a variable that passes through unchanged | `TestDockerEnvironmentSpec` (`:bootstrap`): the test JVM's `DOCKER_CONFIG` holds no `credsStore`, `credHelpers`, `proxies` or hooks, `DOCKER_CONTEXT` is absent, `DOCKER_HOST` is set when Docker is available; `TestDockerEnvironmentFunctionalSpec` (`build-logic`): every branch of the composition on an inner build, and a module applying `test-conventions` gets it with no configuration of its own |

**D6 — Crash consistency** (`crash-consistency.md`): no new durable step. The park sequence
(outcome+marker → tracker park → receipt) and the finish sequence (outcome+marker → tracker
finish → cleanup) keep their shapes; what changes is the recovery owner's *reading* in the window
after the effect landed: `Parked` with marker set now converges by confirming from history and
clearing the marker (roll forward, no re-drive); `CompletedUncleaned` after a landed finish now
has a reachable owner. Idempotence: `DeliveredCleanup` on a `Delivered` tip is a no-op; the
in-history confirmation writes only the receipt. Kill-point rows: the existing `ParkKillPoints`
and `FinishKillPoints` rows, picked up through D3, assert both. *Amended 2026-10-15 (D13):* the
shape set gains `Unregistered` (item 2: the half-pair a third party's fetch leaves was a window
mapping to no shape of its own); its recovery owner is the touchpoint fetch (item 3: one owner —
the `Unknown` quarantine owner no longer shares it). The fetch rolls forward: after it the branch
classifies by its content, and a second fetch changes nothing (item 8). The shape's disposition
is quarantine, since it says what a reader does once its own read is done, and a branch still
`Unregistered` then has nothing to roll forward from (D13).

**D7 — A marker resolves against unarchived deltas too.** Context: driven by FR6. The
scenario-link rule of `checkSpecContract` (FR5 of `tighten-spec-contract`) resolved a marker
against the main specs only, while `testing.md` asks for the marker to be written with its test;
the first change implemented after the gate — this one — turned it red with six markers naming
its own `tracker-port` delta. The resolvable set becomes the scenarios of the main specs plus
those under ADDED and MODIFIED requirements in the deltas of `openspec/changes/*`, never
`archive/`, read whether or not `--changes` is set (so the unarchived deltas become an input of
the cacheable task on every run). A title found only under a REMOVED requirement stays dangling.
Which scenarios must be linked does not change: the main specs' only, so proposal NG6 of
`tighten-spec-contract` stands. A MODIFIED block cannot drop a scenario (`delta-specs.md`), so a
scenario leaves the resolvable set only with a REMOVED requirement or a deleted change; the
marker that named it then fails the gate in that same run. *Rationale:* the marker is written
once, at the task that writes the test, and needs no edit at archive — the archive's own gate
run resolves it against the merged main spec. *Alternatives rejected:* remove the markers until
the archive — the link is lost between task and archive, and a typo is indistinguishable from a
deferred link; a separate change — this change cannot run its tasks on a red gate, and the fix is
one rule's resolvable set.

*Extension (task 0.3) — a REMOVED requirement retires its main-spec scenarios at once.* Task
3.5 removed "Cleanup on completion" from `git-task-persistence` and re-added its surviving
scenarios under "Cleanup commit on completion"; two scenarios of the removed requirement have no
successor. Under the rule as task 0.1 left it they stay in the main spec until the archive, so
the gate still demanded a link for each: the log scenario, whose only test was deleted with
`CleanupCommit`, could be kept green only by an exemption line naming no mechanism, and the
staged-removal marker had to stay on `TransitionKillPointSpec` above a row that no longer does
what it names — both kept alive only to be deleted at the archive. The rule therefore reads the
REMOVED sections of the unarchived deltas too. A REMOVED requirement
matches the main-spec requirement of the same title in the same capability path (`delta-specs.md`:
REMOVED names a requirement by its header; deltas under `archive/` retire nothing). Its scenarios
form the **retired** set, and retirement only relaxes the gate: (1) the unlinked-scenario
finding skips them — the change that removes the requirement is the record of their retirement;
(2) the resolvable set stays as task 0.1 left it (main-spec scenarios ∪ ADDED/MODIFIED scenarios
of unarchived deltas), so a marker naming a retired scenario is not dangling; (3) an exemption
line naming a retired scenario is neither required nor stale — `ScenarioExemptions.assertReached`
keeps receiving all main-spec scenarios. Until the archive, a retired scenario's markers and
exemption lines are therefore merely not required; the archive stage's own `checkSpecContract`
run (`.gnomish/stages/archive/stage.yaml`) turns "not required" into dangling or stale for
anything forgotten, once the scenario has left the main spec. The change that removes the
requirement deletes its own leftovers by an explicit edit and a sweep (task 0.3 for this
change). *Why not strict:* judging retired scenarios "as they will stand after the archive"
would hold for a change being implemented, but the rule applies the REMOVED sections of every
unarchived delta, and a proposed change that has not started (on 2026-10-14
`define-executor-contract`, `extract-agent-round-runner`, `migrate-verify-to-executor-contract`)
removes requirements whose tests still run over code still in production: about 65 live markers
and one exemption line would turn the gate red in modules this change does not touch, and the
only ways to green would cut the scenario↔test link early or edit other changes. *Rationale:*
the removal is already a durable, reviewed statement that the scenario goes; a second statement
in the exemption list or in a task "at the archive" duplicates it and can drift from it. *Alternative rejected:* an
exemption line per removed scenario plus a task to delete it and the old marker at the archive
(the interim patch task 3.5 left) — the line names no mechanism that verifies anything, so it
breaks the exemption form's own bar (`testing.md`), and it is kept alive only to be deleted at the
archive. A strict retirement (markers dangling, exemption lines stale before the archive) —
rejected above: it reaches every unarchived change, started or not. A removal that a later change
reverts (deleting the delta before archive) restores the requirement, and the gate asks for the
links again in that same run.

**D8 — The cleanup commit has one writer on both media, the bare-object one.** Context: driven
by FR4 and NFR-R1; the finding of task 3.3 (Context). `GitTaskRepository.finishCleanup` (host)
delegates to `GitObjectsTerminalCommits.cleanUp` over a `GitObjects` that `GitTaskRepository`
opens in its constructor on its own clone's `.git` — from the `RegisteredClone` it is already
constructed with — with the JVM temp dir for the private index, so it cannot name another clone;
`GitTaskStore` passes nothing new. A `GitObjects` constructor parameter was rejected: it would be
the escape hatch `implementation.md` item 3 forbids, since it could name another clone's `.git`;
`GitObjects.open` runs no git, so opening it is a derivation of a parameter already passed, the
way `ContainerRunSupport` opens its own in its constructor. The writer works through a
`TaskLifecycleCommitWriter` built with the factory's `CommitIdentity`, the store's clock and its `ClaimEpochSource` — so the epoch trailer and the register move are
the ones the container path already lands. `CleanupCommit` is deleted. The worktree is touched
once, after the commit, by the winner's disposal (`worktree remove --force`, as today). The
`git-task-persistence` delta records it: "Cleanup on completion" is removed and replaced by
"Cleanup commit on completion", because its two scenarios of the staged worktree removal cannot be
dropped through MODIFIED (`delta-specs.md`); the other scenarios keep their titles, "The cleanup
commit is built per mode" now stating one writer for every mode. The two dropped scenarios are
retired by the REMOVED requirement itself (D7, extension): the gate requires no link for them
from the task that implements the extension (0.3), which deletes the staged-removal marker on
`TransitionKillPointSpec` and the interim exemption file task 3.5 wrote, instead of leaving both
to the archive.

The steps of one host cleanup, with the lock each takes (`lock-scope.md`, "Writers from
different processes"):

| Step | Lock taken | Decided by |
|------|------------|------------|
| read the tip and test it for `.gnomish-task/` (`requireTip`, `exists`) | none | — |
| `read-tree` the tip into a private index, drop the entry | none shared (`GIT_INDEX_FILE`) | — |
| `write-tree`, `commit-tree` | none (objects are content-addressed; two writers write the same tree) | — |
| `update-ref --stdin`: branch under the expected tip, register alongside | reftable `tables.list.lock`, retried by `reftable.lockTimeout` (git ≥ 2.47; floor 2.51) | the compare-and-swap |
| `worktree remove --force` | git's own worktree registry writes | winner only |

The loser is decided at the one compare-and-swap, and it can lose only after the winner's ref
move landed; so `DeliveredCleanup`'s re-read of the tip after a `GitTaskRepositoryException`
always finds `Delivered` and ends in its DEBUG line — the same loser path the container medium
already has. The former host path met the worktree's `index.lock` one step earlier, where no
compare-and-swap exists and the winner has not committed yet, so the same re-read found
`CompletedUncleaned` and rethrew.

*Rationale:* git offers no retry and no compare-and-swap for the index lock, and the cleanup
needs no working copy — it removes one directory from a tree. Alternative zero
(`design-decisions.md`): delete the worktree from the step, and the writer that already does
exactly that exists and is medium-neutral, so nothing is designed. Gerrit NoteDb, JGit, libgit2,
go-git, gitoxide and GitHub's Git Data API all commit this way — object build, ref update with
the expected old value, loser retries at the operation level — and none of them locks around it.
*Alternatives rejected:* a private `GIT_INDEX_FILE` in the host code (a second implementation
of `CommitBuilder`; rule of three); a cross-process file lock around the cleanup (advisory,
stale after a kill, unreliable on network volumes, and no fencing — the compare-and-swap is the
fencing token, so the lock could only be an optimisation); a retry on `index.lock` (git cannot
tell a live lock from a crash leftover, so it is either an unbounded wait or a guess); cleanup
by the `serve` daemon only (`take` runs with no daemon, and a dead daemon leaves branches
uncleaned — the daemon's branch pass stays the sweeper, the compare-and-swap stays the
mechanism). *Sequencing:* `own-git-invocation-policy` moves the `-c` pairs `GitExec` passes
into one owner; this change keeps `GitExec` as it is (`core.hooksPath=/dev/null`, explicit
author and committer from the environment), so the two touch no common line.

**D9 — Adoption is one ref transaction: create under compare-and-swap on absence.** Context:
driven by FR7; the audit's CRITICAL 1 (Context, "Branch adoption"). The port's existing
`ensureLocalTaskBranch` becomes the one owner of making a remote-tracking-only branch local,
implemented by `TaskBranchAdoption` (`adapters/git`): one `update-ref --stdin` transaction,
`create refs/heads/gnomish/<b> <tracking tip>` — git's "create … after verifying that it does
not exist" (as built, spelled `update <head> <tip> <zero-oid>`, the documented equivalent), the same compare-and-swap on absence as JGit's `zeroId` expected-old, gitoxide's
`MustNotExist` and Gerrit NoteDb's `CREATE` command. The register is not touched: the touchpoint
fetch the locate just ran already landed origin's register in the clone's register
(`fetchedInPlace`), and the head is created at exactly the tip that register names. No `git
branch` (it sets an upstream nobody reads and has no compare-and-swap), no checkout, no name for
git to resolve. The answer is typed: `Adopted(taskId)`, `AlreadyLocal(taskId)`, `Nowhere` (the taskId, as
built in task 6.2, so the `LocalTaskBranch` view names the task for the D10 entries; the tip the
head was created at is logged, not carried — its one reader, the undo, went with D12 as revised); a `create` lost
to another adopter is read back through the same locator parse (crash-consistency item 14) and
answers `AlreadyLocal` — nothing is lost, both adopted the same tip. Reflog: the clone is not
bare, so `core.logAllRefUpdates` is on and the created head gets its reflog
(`DeliveredCleanupRaceSpec` counts the branch's moves there); the register, outside
`refs/heads/`, has none, and nothing reads one. A non-compare-and-swap refusal of `update-ref`
(`CommitBuilder:141` reports every non-zero exit as `StaleTipException`) is told from a lost
race by its effect — the shape re-read through the classifier — never by a second `rev-parse`.
*Alternative zero — no adoption; the claimless route narrows to `Local`:* nothing is designed,
but a second factory home can then never converge a finished task when the first home is gone,
against "any instance can resume" (`CLAUDE.md`); rejected, keeping its discipline — a typed
target, no re-resolution by a bare name. *Rejected — keep `git branch <b> <tracking>` and reuse
it from the claimless path:* no compare-and-swap, the loser fails "already exists", and it would
be the fourth copy of the step. *Rejected — keep the host's `worktree add` resolution and add
it to the claimless path:* a behaviour of git, not code; it engages only when the local branch
is absent, so after adoption it is inert, and inert is how it stays.

The steps of one adoption, with the lock each takes (`lock-scope.md`, "Writers from different
processes"):

| Step | Lock taken | Decided by |
|------|------------|------------|
| locate: touchpoint fetch of the branch and its register into the tracking ref and the register | the ref store's lock (reftable `tables.list.lock`, retried by `reftable.lockTimeout`) | the fetch's own force refspecs; no decision of ours |
| `update-ref --stdin`: `create refs/heads/<b> <tracking tip>` | the ref store's lock, retried | **the compare-and-swap on absence — the first and only lock behind a decision** |
| every later write | as today (D8's table): the branch is `Local` | the tip's compare-and-swap |

**D10 — The answer is typed, and the writers' entries accept nothing else.** Context: driven by
FR7, FR8; `implementation.md` item 3. `ensureLocalTaskBranch` returns `BranchAdoption` instead
of `boolean`; `TaskWorktreeManager.ensureWorktree(RegisteredClone, LocalTaskBranch)` and
`DeliveredCleanup.cleanUp(Path, LocalTaskBranch)` take the adopted-or-local view of it, so
"a clone with no local branch" is a state the writer's entry cannot be handed, and the compiler
does what the audit's unit specs (green over fakes) did not. The eight bare-object writes keep
their `taskId` entries: every caller of theirs runs behind a bootstrap that adopted, and the
gate of D5 pins that every bootstrap does. The two ref prefixes get one owner, `TaskBranchRefs`
(D5 row); `GitObjectsTaskRepository.refFor` goes, and `FactoryRegister.branchRef`, an alias of
`TaskBranchRefs.head` after task 6.1, went with task 6.2's file-size split (the register's ref
naming moved to `FactoryRegisterRefs`): every writer takes `TaskBranchRefs.head`. *Alternative rejected — a typed write
target through every bare-object write (`{head, expected, parent}` from the locator):* eight
signatures and a container writer that never sees a remote-tracking branch, for an invariant
the bootstraps already hold; the gate and the two typed entries close the hatch where it was
open.

**D11 — The three bridges collapse into the owner.** Context: driven by FR8; D4. Host:
`TakeResumeBootstrap` and `GitResumeRunner`'s bootstrap adopt right after `locate`, before
`ensureWorktree`, which now names an existing local branch (the `worktree add <path> <branch>`
form git documents for "work on an existing branch"). Container: `ContainerResumeBranch`
keeps only its `Local` arm (the reconcile) and delegates `RemoteTracking` to the owner.
Claimless: `DeliveredCleanup.cleanUp` adopts as its first step over the location
`ClaimlessDeliveredCleanup` already holds (`Local` or `RemoteTracking`), then classifies, then
writes. `TaskBranchCreator`'s `git branch <b> <base>` is a fresh task's branch, not an
adoption, and stays. *Alternative rejected — adopt inside `locate`:* `locate` is a read that
status and list commands run; a read that writes a ref is the defect `task-inspection` exists
to forbid.

**D12 — A lost cleanup race is not undone; the loser's line is a terminal shape with no
recovery owner.** Context: driven by FR9, NFR-R1, NG4; the architecture review of 2026-10-14
(after task 6.3), which asked the alternative-zero question of the undo this decision first
specified. The tracking tip was fetched at locate time; if another clone delivers the cleanup
between that fetch and ours, our cleanup commit sits on a parent origin has moved past, origin
rejects the push as non-fast-forward (one WARN, as every best-effort push), and the adopted head
is a diverged local line carrying one factory commit. Rule: **nothing undoes it.** The clone keeps
every finished local branch anyway — no code path deletes a local task branch after delivery — so
the loser's line costs exactly what a cleanup the clone had won costs: one `Delivered`-shaped ref.
Every reader already judges it by identity, never by presence (crash-consistency item 13): the
claimless cleanup classifies the registered tip, reads `Delivered` and writes nothing;
`OriginReconciliation` declines a catch-up push whose local tip origin's tip does not descend from
and says why, so no touchpoint re-pushes the line; a claimed take never reconciles it — a recorded finish declines the take before any claim
(`TakeDisposition.dispose`: `Ready` with `finished()`, and `Finished`), and when the tracker lost
the finish the `Delivered` tip is routed straight to `TakeReconcileFinish.deliverCompleted`
(`TakeDispositionResume`), which reports the task done and leaves the head where it is,
bypassing `ReplicaPairReconciler`; a manual `run --resume` on that clone fails closed with
`DivergedBranchException`, NG4's standing posture. **Recovery owner (crash-consistency item 3):
none, by design** — the shape is terminal and inert: one local ref, a manual `run --resume` on
that clone refusing, and one "not an ancestor" WARN whenever a touchpoint of that task runs on
that clone. (Amended 2026-10-15: the first text of this decision named the claim protocol's
reconcile as the owner; task 6.4's `DeliveredCleanupCrossCloneSpec` refuted it by the two routes
above.) *Why no undo (the decision this replaces):* the
undo deleted a head adoption created, under compare-and-swap, after an `ls-remote` confirmed origin
held another tip. Its premise — "the head carries nothing but our own cleanup commit" — is true
only where adoption is immediately followed by the cleanup commit, the claimless route; the same
`Adopted` value travels through every claimed bootstrap (`ResumedBranch.branch()`) to the five
claimed writers of `DeliveredCleanup.cleanUp`, which reach the cleanup after a whole engine run
has committed to that head, so an undo there on a rejected push would delete unpushed work (NG4's
data loss) — the receipt was a one-shot witness, valid until the first write, carried as a plain
value with no expiry. Confining it to the claimless route (a type consumed inside one method, a
gate on the undo's call sites) was the honest form of keeping it, and it still gave the shape a recovery owner whose premise did not hold, still
could not cover a kill between the rejection and the undo, and bought one stale ref per lost
race. Every system surveyed keeps the loser's ref: git's non-fast-forward rejection never touches
the local branch, JGit/Gerrit re-derive from the authority and delete nothing, Renovate and
Dependabot refuse to touch a branch carrying commits they did not author. *Consequences for what
task 6.2 built:* `TaskBranchAdoption.undoAdoption` and `FactoryRegister.deleteAdoptedHead` have no
consumer and are deleted (`implementation.md`: every consumer or none); `Adopted` loses its `tip`
component, whose only reader was the undo — the adoption's INFO anchor logs the tip it created at
from its own local — and becomes `Adopted(String taskId)`; `BranchAdoption`'s javadoc no longer
names a head "its owner may later undo". *Alternative rejected — the push verdict as the receipt
(un-adopt on a REJECTED push, keep on an unanswered one):* the same premise failure, one network
read cheaper. *Alternative rejected — a marker ref "adopted, unconfirmed":* a fourth ref namespace
to narrow a residual that costs one ref.

Kill windows of the claimless cleanup over an adopted head (`crash-consistency.md`): (a) after
`create`, before the cleanup commit — `Local`, `CompletedUncleaned`, the shape a `git clone` and
a checkout leave; no new shape; (b) after the cleanup commit, before its push — a closing commit
not yet pushed, as today, the next touchpoint delivers it; (c) after the push — delivered, or
rejected: the **diverged adopted head**, one clone's `Delivered`-shaped line beside origin's
`Delivered`, the shape this decision leaves standing by design; its readers are listed above and it has no
recovery owner: terminal by design. No window needs a step after the push, so there is no
window between a rejection and a repair.

**D13 — A branch without its register is `Unregistered`, and an actor reads it through the
fetching path before it decides.** Context: driven by FR10–FR12, G6, NFR-R3, NFR-C2, UX3; the
architect review of 2026-10-15 of task 6.5 (Context, the five facts above). Two decisions, one
mechanism:

1. **The half-pair is its own shape.** `BranchShapeClassifier` maps `TipRelation.NoRegister`
   over an envelope to `BranchShape.Unregistered(register, tip)` — the tip carries a factory
   envelope, the clone holds no register for the task. `Unknown` keeps the diverged pair
   (`TipRelation.Diverged`) and the legal-but-unrecognized combination. `Unregistered`'s
   recovery owner is the touchpoint fetch (`RecoveryOwner.TOUCHPOINT_FETCH`, a new constant:
   after the fetch the branch is what its content says). Its disposition is `QUARANTINE`: a
   disposition says what a reader does with the shape after its own read, and a branch still
   `Unregistered` then has no register to roll forward from. So `TakeDispositionResume` throws
   `BranchQuarantineException` for it as for `Unknown` (today's behaviour; at once for a local
   head, which the locate does not fetch, NG5), `status` lists it with its diagnosis (exit code
   7 in single-task mode, as every quarantine shape), the take's repair line reads "parking for a
   human", and `BranchQuarantineReport.nextStep` names its own remedy (`gnomish status <task>`
   fetches the register; a branch still `Unregistered` after it has no factory register at
   origin, and is inspected before the task returns to ready). *Rejected — disposition
   `ROLL_FORWARD`:* three readers decide by disposition and no compiler flags them
   (`TaskListRenderer.outcomeCell`, `BranchShapeDiagnosis.diagnosisFor`, `BranchRepairAction.phrase`);
   under it the list row shows "-" with no remedy (UX3 unmet) and a claimed take logs "resuming
   from the recorded position" just before it quarantines. Its `tipCarriesState()`
   is false (nothing is read at a register that is not there), `BranchShapeDiagnosis` renders it
   as "fetched without its factory register — `gnomish status <task>` fetches it", and every
   exhaustive switch names it (the "New shape cannot be silently ignored" scenario is what makes
   the set grow safely). *Rationale:* one label covered two states with two recoveries — GitLab
   Geo keeps "never synced" apart from "checksum mismatch", git-lfs keeps "pointer without
   object" apart from "corrupt"; `crash-consistency.md` items 2 and 3 forbid the conflation.
2. **The branch pass forwards by a domain table and the owner fetches first.**
   `BranchShape.forwardedByBranchPass()` is true for `CompletedUncleaned` and `Unregistered`;
   `DeliveredBranchPass` forwards exactly those, logs one DEBUG line per branch it does not
   forward, and hands the owner a task ref, not an observed task. `ClaimlessDeliveredCleanup`
   runs locate (the touchpoint fetch: branch and register in one transfer, register in place
   when no local head exists) → classify at the register → `fetchTask` only when the tip is
   `CompletedUncleaned` → adopt → `cleanUpIfFinished`. *Amended 2026-10-15 (task 6.6):* the
   classification takes the location the locate answered, through a new read of the
   `TaskBranchGit` port, `classifyShape(Path cloneDir, String taskId, BranchLocation location)`.
   Today the port has only `BranchShape classifyShape(Path cloneDir, String taskId)`
   (`TaskBranchGit:100`), which `GitTaskBranches.classifyShape:103-121` implements by its own
   `locator.locate(...)` followed by a switch over the answer, and which
   `RemoteOutageSignalingTaskBranchGit.classifyShape:78-79` decorates. The switch moves into the
   new read; the two-argument form becomes the new read over `locate(cloneDir, taskId)`, so a
   location has one parse (`crash-consistency.md` item 14); the decorator wraps the new read as it
   wraps the others. The sequence then costs one touchpoint fetch per forwarded branch (NFR-C2)
   and decides on the same read it adopts over and names in the FR12 line. *Rejected — the
   two-argument `classifyShape` after the locate:* it locates again — a second fetch per
   tracking-only branch, and a decision on a read independent of the one `adopt` and the FR12
   line receive (a fallback on one, `Unavailable` on the other). *Rejected — adopt before
   classifying, so the second read finds a local head and fetches nothing:* an `Unregistered`
   branch would become a local head without its register, the NG5 state no later locate
   refetches, and FR12's "writes nothing" forbids the write. A tip still `Unregistered` after the owner's
   read is returned to the pass with the location the locate answered, and the pass logs one
   aggregate line per pass naming those branches and the location kind of each — a local head,
   which the locate does not fetch (NG5), or a tracking ref for which origin delivered no
   register or could not be reached. The line never asserts which cause holds, since the locate
   cannot tell an outage's fallback from an origin without the register. The quarantine owner
   keeps them as it keeps `Unknown`. The two `take`
   finished arms hand the task they already observed, so no second tracker read. *Rationale:*
   controller-runtime reads from the cache by default and from the API server "for sensitive
   decisions" (KEP-5647 requeues on a stale cache, never skips); Argo CD resolves every revision
   by `ls-remote` before use. The listing is the cache; the locate is the authoritative read; a
   consequential act (a cleanup commit and a push) follows the authoritative read. The cost is
   one fetch per forwarded branch, and the forwarded set empties itself: the fetched register
   stays in the clone, so the next tick classifies by content.

*Alternative zero — the janitor passes over local heads only, tracking-only branches are
`take`'s:* nothing is designed; but D9 already rejected "the claimless route narrows to `Local`"
— a second factory home then never converges a finished task of a dead first home in the
background, and this change's own scenario promises it does. Kept from it: the listing's
no-fetch posture for display. *Rejected — narrow the scenario to tracking refs the factory's own
touchpoint brought (the implementer's option (a)):* it rests on "factory clones are
`--single-branch`", which is false of the factory clone; an IDE fetch makes the excluded state
the common one, and the identity spec's `located(...)` precheck would test a posture production
never has. *Rejected — write `+refs/gnomish-factory/*:refs/gnomish-factory/*` into
`remote.origin.fetch` so any fetch carries registers (proposal NG6):* outside `refs/heads` and
`refs/tags` git forces the update with or without `+`, so a register ahead of origin's — a
closing commit landed, its push not yet made, a named kill window of D13 of
`tighten-spec-contract` — is rolled back by the next IDE fetch, and `fetch.prune` (set by the
project's own adversarial configuration) deletes a register origin dropped; it also writes the
operator's clone configuration, where `harden()` already races. A fetched copy under a
remote-tracking name would be safe (bundle-uri's "never the authority" shape) but adds a second
register placement every reader must consult: not taken. *Rejected — the pass fetches every
listed branch each tick:* a fetch per branch per hour forever, for delivered branches that cannot
change; the table forwards only what can.

*Deferred with its pickup condition (proposal NG5):* the local head without a register. Its
drain needs the locate of a *local* branch to fetch the register too — the semantics every
claimed take relies on — or the register-following reconcile to run before the quarantine
decision; the change that next touches `TaskBranchLocator`'s local arm or `TakeQuarantinePark`
takes it, and `Unregistered` already names the state it will find.

*Deferred with its pickup condition (proposal NG7):* the claimed take locates twice — its
routing classifies through the two-argument `classifyShape` (`TakeWorkRouter:52`) and its resume
bootstrap locates again (`TakeResumeBootstrap:78`), two touchpoint fetches per pickup of a
tracking-only branch. The change that next touches the claimed take's routing (`TakeWorkRouter`)
or its bootstrap passes the routing's location through the location-taking read.

*Rule landing:* `.claude/rules/crash-consistency.md` gains item 15 — a companion ref that is
absent is not a shape of the pair but a state of the reader ("not yet fetched"); an actor on a
medium whose consistency only a fetch guarantees reads through the fetch's owner before a
consequential act, and a reader that cannot judge says so rather than skipping (task 6.8).

**D14 — The test build runs under its own Docker client configuration, with the operator's
endpoint handed on explicitly.** Context: driven by FR13–FR15, NFR-C3, UX4; found by this
change's final gate (task 6.9, 2026-10-15). The developer works on macOS; CI runs on Linux
(`ci.yml:37`, `ubuntu-latest`).

What the test build inherits today, verified: no build script sets `DOCKER_CONFIG`,
`DOCKER_HOST`, `DOCKER_CONTEXT` or `HOME`; `TestEnvironmentHygiene` only strips `GNOMISH_*`
(`TestEnvironmentHygiene.groovy:48-54`), and its spec promises the machine's Docker variables
reach the test JVM unchanged (main spec `quality-gates`, "Only the factory's own namespace is
removed"). So:

- Testcontainers 2.0.5 looks up registry credentials in `$DOCKER_CONFIG`, else
  `user.home/.docker` (`RegistryAuthLocator.java:82`), and runs the configured helper. docker-java
  resolves them on `createContainerCmd` as well as on pulls (`DockerClientImpl.java:309-311,
  397-399`), and the answer is cached per JVM, so every test JVM and every PIT minion runs the
  helper again. With `"credsStore": "desktop"` on macOS the helper reads Docker Desktop's group
  container and raises a system prompt; observed 2026-10-15, a minion's
  `docker-credential-desktop get` blocked for over 20 s (testcontainers-java #11713, docker/for-mac
  #7179 report the same; neither has a fix).
- `DockerCli` inherits the whole environment (`DockerCli.java:191-198`), as do the fixtures' raw
  `docker` builders (`DockerImageBuilder`, `GuardImageAvailability:44`, `FakeAgentSandboxImage`);
  the CLI therefore runs the same helper on `docker build` and on implicit pulls, applies the
  operator's plugin hooks, and injects a `proxies` block into every container it creates.
- The CLI picks its daemon from the context (`cli/command/cli.go`, `resolveContextName`:
  `DOCKER_HOST` forces the default context, then `DOCKER_CONTEXT`, then `currentContext`);
  Testcontainers ignores contexts and probes `DOCKER_HOST`, `~/.testcontainers.properties` and
  fixed sockets. The two agree on the developer's machine only because Docker Desktop links
  `/var/run/docker.sock` to `~/.docker/run/docker.sock`; on Colima or OrbStack they would not.
- On CI there is no `config.json`, no `docker login` (`.github/` has none) and one engine on the
  default socket, so CI behaves the same before and after; the defect and the risk are both on
  macOS.

Decision. `TestDockerEnvironment` in `build-logic`, applied by `test-conventions` beside
`AdversarialGitConfig` to every `Test` task and to `pitest`:

1. **Configuration.** A committed `test-fixtures/src/main/resources/docker-config/config.json`
   (`{}` plus nothing that calls out: no `credsStore`, `credHelpers`, `plugins`, `proxies`) is
   copied into the task's own temporary directory before it runs; `DOCKER_CONFIG` names the copy.
   A copy, because `buildx` writes its state into the configuration directory and the committed
   file must stay clean under parallel tasks. The committed file is the task input, by content.
2. **Plugins.** The copy's `cliPluginsExtraDirs` names `<operator configuration dir>/cli-plugins`
   when it exists — where Docker Desktop links `buildx` on macOS; on Linux the CLI finds it in the
   system plugin directories with or without it.
3. **Endpoint.** `DOCKER_HOST` is the operator's own when set; otherwise the build runs
   `docker context inspect --format '{{.Endpoints.docker.Host}}'` once under the operator's
   environment (a documented interface; it calls no daemon and no helper) and hands the answer
   on. With `DOCKER_HOST` set, the CLI uses the default context and Testcontainers its
   environment strategy, so both talk to one daemon by construction. `DOCKER_CONTEXT` is removed
   from the task's environment: the clean configuration holds no contexts.
4. **The socket Testcontainers mounts.** Under its environment strategy Testcontainers mounts the
   socket path of `DOCKER_HOST` into its Ryuk container (`DockerClientFactory.java:164-178`); the
   Docker Desktop strategy it uses today mounts `/var/run/docker.sock` instead
   (`DockerDesktopClientProviderStrategy.java:63`). On macOS the daemon runs in a Linux VM where
   the host path does not exist, so on a macOS host with a `unix://` endpoint the build sets
   `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (the in-VM path every macOS
   runtime provides; Testcontainers' Colima guide uses the same) unless the operator set it. On
   Linux the host path is the daemon's path and nothing is set.
5. **What it refuses and what it skips.** An `ssh://` endpoint or a context with TLS material
   cannot be handed on as one variable: the build fails with a message naming `DOCKER_HOST`,
   `DOCKER_CERT_PATH` and `DOCKER_TLS_VERIFY`, which the operator may set and which pass through.
   No `docker` binary or a failing `inspect`: no endpoint is set, and the Docker specs skip
   through their existing availability checks, as on a machine without Docker today.
6. **Not inputs.** The endpoint, the override and the plugin directory differ per machine and do
   not change what a test asserts; they are not declared as task inputs (Gradle does not track
   environment variables on its own), so a cache entry is shared between macOS and Linux.

The `quality-gates` promise "the machine's Docker variables reach the test JVM unchanged" is
amended, not narrowed silently: `DOCKER_CONFIG` and `DOCKER_CONTEXT` are replaced by this
decision for the reasons above; `DOCKER_HOST`, `DOCKER_CERT_PATH`, `DOCKER_TLS_VERIFY` and
`TESTCONTAINERS_*` set by the operator still pass unchanged.

*Alternative zero — no mechanism.* Every test JVM keeps the operator's configuration: on macOS a
prompt per JVM and per registry, a minion blocked until a human answers (an unattended gate
stalls), and test results that depend on the operator's `proxies` and hooks. The remedy is then
per machine, below.

*Alternative rejected — fix it on the machine* (remove `credsStore`, or `"credsStore":
"osxkeychain"`, the workaround of testcontainers-java #11713). For it: no code, one edit, and
it also quiets the developer's own `docker` use. Where it breaks: it is a per-machine rule the
build cannot enforce, Docker Desktop may rewrite the file, and it leaves hooks, proxies and the
context in the tests. Borrowed: the observation that only the helper, not the endpoint, causes
the prompt.

*Alternative rejected — `DOCKER_AUTH_CONFIG={}` alone.* For it: one variable, Testcontainers
reads it before the file (`RegistryAuthLocator.java:39`), no endpoint question at all, and it
removes the most frequent prompts (one per JVM). Where it breaks: the `docker` CLI does not read
it, so `docker build` and implicit pulls still run the helper, and hooks and proxies still reach
the tests. Not added beside `DOCKER_CONFIG` either: with a clean configuration it is a second
owner of the same fact.

*Alternative rejected — clean configuration, endpoint left to the default socket.* For it: the
simplest form; it works on Docker Desktop with the default socket enabled and on CI. Where it
breaks: on Colima, OrbStack or Docker Desktop with that option off, the CLI loses its daemon
while Testcontainers still finds one — the two disagree silently. Borrowed: honour an operator's
`DOCKER_HOST` first.

*Alternative rejected — read the context from `~/.docker/contexts/meta/<sha256>/meta.json`.* For
it: no subprocess; docker-java reads it this way. Where it breaks: the layout is the CLI's
internal store with no published schema, and TLS material lives in a second directory.
`docker context inspect` is the documented interface.

*Alternative rejected — Full Disk Access for the terminal.* The most common answer in the
reports. It grants every process started from the terminal, the gnomes included, the whole disk,
and it is per machine.

## Risks / Trade-offs

- [A diverged adopted head after a lost cleanup race (D12)] → origin is correct and nothing
  re-delivers; the line is one ref in one clone, listed by `TaskBranchLister` as `Delivered`,
  which every claimless pass skips, no touchpoint re-pushes, and nothing removes: it has no
  recovery owner by design.
  Its visible costs: a manual `run --resume` of that finished task on that clone fails closed
  with `DivergedBranchException`, and a touchpoint of that task on that clone logs one "not an
  ancestor" WARN. Accepted (NG4); the change that gives claimless paths an
  arbitration of their own removes it. `DeliveredCleanupCrossCloneSpec` pins its three readers and the
  lost-finish take.
- [`ensureWorktree` no longer lets git resolve a name] → a caller that did not adopt fails on a
  missing branch instead of silently creating one; that is the enforcement, and the bootstrap
  specs on a second clone show the adopted path.

- [`TrackerTask` is in `gnomish-plugin-api`: a plugin-contract change] → additive field with a
  null default; pre-release; the adapter author guide is updated.
- [Two boundary facts with one word in their names] → the glossary entry and the `latestBoundary`
  ban in the probe files keep them apart; the reaper keeps its claim-scoped fact untouched.
- [The janitor's branch pass adds tracker reads and fetches] → one touchpoint fetch per
  forwarded branch (`CompletedUncleaned`, `Unregistered`) and one `fetchTask` per
  `CompletedUncleaned` tip per tick, each bounded by a set this change drives to zero (the
  fetched register stays; the cleanup delivers); a branch still `Unregistered` after the owner's
  read costs at most one fetch per tick (none for a local head, NG5) until the quarantine owner
  takes it, and is named in the pass's aggregate line with its location kind; under a remote outage the existing gate bounds the fetches (D13). The age pass still reads
  no tracker, so the `factory-serve` scenario stays true.
- [`BranchShape` grows by one record] → every exhaustive switch in `:application` and
  `:bootstrap` fails to compile until it names `Unregistered` — the enforcement the contract's
  "New shape cannot be silently ignored" scenario asks for; task 6.5 lists the switches it
  touched.
- [Deleting `ResumeMechanics.finishCleanup`] → both resume media lose one strategy method; the
  identity spec runs the take recovery on both media so the replacement is observed, not assumed.
- [Moving the park/finish rows onto `TakeKillPointWorld`] → the `Resumed`/`Gate`/`Approval` rows
  share the world and its `pickup()`; removing the precheck must leave their expected empty
  pickups explicit per row.
- [Two instances cleaning the same branch] → on both media every step before the ref transaction
  takes no lock another instance can hold, and the ref transaction is the compare-and-swap (D8,
  step table); the loser re-reads `Delivered` and logs DEBUG. The host's former worktree path met
  the index lock one step before the compare-and-swap (task 3.3, 2026-10-14) and is deleted;
  "compare-and-swap on both media", as this row first read, was true of the last step only.
- [The host cleanup commit's committer changes from the clone's git configuration to the
  factory's `CommitIdentity`, as the container's lifecycle commits already carry] → the subject,
  the trailer and the register are unchanged and nothing parses the committer; task 3.5 lists
  any spec that asserted it.
- [The task worktree is stale after the ref moved: its checkout still holds `.gnomish-task/`]
  → the only reader after `Delivered` is the disposal, which runs `remove --force`; a disposal
  lost to a kill leaves the worktree to the janitor's age pass, as before.
- [`add-claim-return` edits the tracker-take return protocol concurrently] → this change touches
  neither the return requirement nor `release`; sequencing is independent, sync order noted.

- [Anonymous Docker Hub pulls on the developer's machine] → the test images are public and
  mostly cached; the limit is 100 pulls per 6 h per address. CI is anonymous already. A future
  private test image needs a test-owned `auths` entry, not the operator's helper.
- [The macOS socket override assumes the daemon sees `/var/run/docker.sock`] → true of Docker
  Desktop, Colima, OrbStack and Rancher Desktop VMs; an operator with another layout sets
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` themselves, which the build honours. Task 7.4 proves
  Ryuk starts on the developer's machine.
- [CI cannot show the macOS behaviour] → on Linux the endpoint, the plugins and the socket are
  what they were, so a green CI proves the build still works there, not the fix; M10/M11 require
  the macOS run as well.
- [A `docker context use` between builds] → the resolution is a configuration-time value source,
  re-evaluated when Gradle checks its configuration cache, so the next build follows the switch.

## Migration Plan

0. The spec-contract gate resolves markers against unarchived deltas (D7, task 0.1), so every
later task runs on a green gate; a REMOVED requirement retires its main-spec scenarios (D7
extension, task 0.3), so the interim exemption line and the staged-removal marker go. 1. Port fact + both adapters + contract suite + glossary (§1). 2. Probes read it (§2).
3. `DeliveredCleanup` and its seven consumers (§3); 3a. the host cleanup commit onto the
bare-object writer, `CleanupCommit` deleted (D8, task 3.5). 4. Kill-point pickups through the
production route; identity and gate specs (§4). 5. ADR/guides and the four rule edits (§5).
6. Branch adoption: the prefix owner, the adoption owner with its typed answer, the three
bridges collapsed, the loser's line left standing (the undo built under the first D12 deleted),
identity rows on a second clone and the gates (D9–D12, §6). 7. The `Unregistered` shape, the
forwarding table and the fetch-first claimless sequence; the identity rows re-seeded as a plain
`git fetch` leaves a clone; crash-consistency item 15 (D13, tasks 6.5–6.8).
8. The test build's own Docker client configuration (D14, §7): the build-logic owner and its
functional spec, the `:bootstrap` gate, the `testing.md` section, then the macOS and Linux runs.
