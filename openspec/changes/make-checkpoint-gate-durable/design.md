# Design: make-checkpoint-gate-durable

## Context

See `proposal.md` — Why. Facts the approach rests on (verified in code on 2026-10-06):

- `StageAttemptLoop.java:157` records a passing round with
  `state.recordPassAndAdvance(record, Advancement.positionAfter(definition, stage))`;
  `Advancement.positionAfter` (`Advancement.java:53-67`) is mode-blind by design — "AUTO and
  MANUAL move the position identically" — so the round commit of a `manual` pass already says
  `AtStage(next)` (or `PipelineEnd` after the last stage). The `Engine`'s MANUAL arm
  (`Engine.java:185-187`) re-applies `advanceTo(nextPosition(next))` in memory only.
- The park is a later commit: `GitOutcomeRecorder.recordIntent` (host) and
  `ContainerRunTermination.recordPark` (container) write `task.json` only; `state.json` is
  untouched by any lifecycle write except `appendDecision`.
- `AttemptRecord` (`AttemptRecord.java:67-74`) carries `round, result, startedAt, checkResults,
  executorUsage, judgeUsage, denials` — no payload for `DECISION_NEEDED` or `CANNOT_VERIFY`;
  nothing in `*/src/main` reads `DECISION_NEEDED` back for recovery. The host round commit does
  not stage the decision file; the question exists only in the in-memory `EscalationReport`.
- `Engine.preflight` (`Engine.java:110-122`) already re-escalates `AttemptsExhausted` from the
  recorded counter — the precedent for "the stop is reproduced from the record".
- `task.json`'s `outcome` is cleared only by `appendDecision` (`GitTaskRepository.java:132-144`,
  `GitObjectsTaskRepository.java:175-185`). `TakeDecisionResume.java:68-69` and the blank-answer
  path reset attempts in memory only. `ContainerResumeOutcomes.java:115,141` and
  `TakeContainerResumeRunner.java:74` compute `pendingVerification` only on the null-outcome arm.
- `GitObjectsTaskRepository.recordOutcome:205-220` feeds the *carried* `lastEscalation` into
  `egressCursors.forEscalation`, so a `Paused`/`Completed`/`Aborted` write after an earlier
  `CannotExecute` reads the live cursor.
- `BranchTipFacts` (`BranchTipFacts.java:25-31`) carries envelope status, outcome, two booleans
  and the cleanup flag — not the position; `BranchShapeClassifier.java:94-113` maps
  "rounds recorded, outcome null" to `InProgress`.
- `StateFileVersionGate.readGated` demands `version == supportedVersion` exactly.
- Readers that switch on `Position` today (22 files): `Engine`, `TaskState`, `Advancement`,
  `StageResult` (`:domain`); `StatusReport`, `SummaryAccumulatorListener`, `MdcEventListener`,
  `TaskSummaryAssembler`, `HeartbeatProgress`, `ResumeDecisionCommit`, `GitModeRunner`,
  `EscalationResumeDialog` (→ `EscalationResume` after `make-run-headless`),
  `ContainerResumeOutcomes`, `TakeContainerResumeRunner`, `TakeFinishReport`,
  `status/json/PositionDto`, `StatusReportJsonMapper` (`:application`); `StateJsonMapper`,
  `StatePositionDto`, `TaskBranchLister` (`adapters/git`); `RunCheckRunContext` (`:bootstrap`).
- `ResumeMechanics<B>` with `HostResumeMechanics` / `ContainerResumeMechanics` is the existing
  shared abstraction over the two `take` resume media (`manual-sync-pairs.md`, preference 1).

Facts behind the round token (D10), verified in code on 2026-10-07 after task 4.3 turned the
two container E2E specs red:

- `BranchDecisionFile.Handle.read()` (`BranchDecisionFile.java:84-86`) answers "is the file at
  `decisions/<stage>-a<attempt>.json` present in the box's working copy", through
  `environment.readFile`, after `closeRound()` (`ExecutorRoundExecution.java:111-118`). Nothing
  asks whether this round wrote it. The javadoc's "stale files are self-excluding" (`:24-27`)
  and `HarvestedBoundaryCheck.decisionPath`'s "stage and attempt in the name make stale files
  self-excluding" (`:121-123`) are the premise; the premise is false.
- The snapshot commit is `git add -A` (`EnvironmentRoundSnapshot.java:33`), so the request
  lands on the branch; the only remover is `CleanupCommit` at `Completed`. `appendDecision`
  (`GitObjectsTaskRepository.java:169-185`) rewrites `task.json`/`state.json` and leaves the
  file.
- The attempt number is `state.attempts().size()` (`RoundExecution.java:102`,
  `StageAttemptLoop.java:106`) and restarts at 0 on `resetAttempts()` (`TaskState.java:165`),
  `advanceTo` (`:98`), `startOfStage` (`:144`), and on a killed round's retry (the state is
  unchanged). `AttemptKey`'s javadoc calls it "monotonic"; it is monotonic within one visit of
  a stage. `add-pipeline-routing` and `add-stage-iteration` will make `(stage, a0)` repeat
  routinely.
- The host transport (`DecisionFileTransport.java:81-86, 176`) is a per-round temp directory
  deleted after the read — the deletion was the discriminator; it did not move with the
  transport onto the branch.
- `HarvestedBoundaryCheck.verify` diffs `previousTip..snapshot` — a correct "written this
  round" test — but runs in `persist`, after the decision read, in another class.
- `ResumeVerificationStageExecutor.execute` (`:55-73`) resumes an interrupted verification
  from `PendingVerification(attemptCommit, stage, round)` and returns `Completed` without
  reading any decision: a kill after the snapshot that carried a request and before the state
  commit silently drops the question (the lost-park class of this change, on another step).
- `AttemptCommitRef` (`app/port/git`) is the precedent for a per-run value one adapter
  component records and another reads: the snapshot records the attempt commit, persistence
  reads it. `EnvironmentAttemptPersistence.previousTip` (`:118, :140`) is the tip at round
  open under another name.
- External canon (research of 2026-10-07): the owner of "is this request live" is a
  receiver-side identity match — AWS Step Functions mints one task token per wait, invalid
  after use, re-minted on re-entry; EIP's Correlation Identifier; Kleppmann's fencing token
  ("reject any write on which the token has gone backwards"). Deletion after processing is
  hygiene with an at-least-once caveat (SQS: an undeleted message becomes visible again, so
  the consumer is idempotent). Retries clear the prior attempt's outputs (Tekton unsets
  `status.Results`, Airflow clears XCom). Stale pid files are the file-as-message precedent:
  robust designs record boot id or process start time — an identity that cannot recur —
  never presence.
- Changes touching the same regions: `make-run-headless` (archived; its
  `EscalationResume.decide` is the `run` caller of the resumed write), `add-stage-iteration`
  (D2 relies on "trailing PASSED means stage done"; D6/D8 introduce new stops), `add-stage-
  finished-event` (`StagePassed(advancedTo)` payload reads `Advancement.nextPosition`),
  `enforce-artifact-contracts` (MODIFIES "Outcome and report model" — not touched here).

## Goals / Non-Goals

**Goals:**
- No tip on a task branch permits what no commit has yet allowed (G1).
- A consumed outcome and the reset it implies land together, once (G2).
- The compiler enforces the gate on every reader (G3).

**Non-Goals:**
- Tracker-side receipt convergence (`fix-terminal-receipt-convergence`).
- Stage identity on rounds (FR9 of `harden-task-branch-contract`).
- Any change to `AttemptsExhausted` / `CannotExecute` recovery.

## Decisions

**D1 — The position is the gate.** `Position` gains a third sealed variant,
`AwaitingApproval(String stage)`, naming the `manual` stage that passed. `Advancement.positionAfter`
becomes mode-aware: `MANUAL` → `AwaitingApproval(stage)`, `AUTO` → `AtStage(next)` /
`PipelineEnd` as today; its javadoc sentence is reversed, with this change as the reason.
`Engine.run` on `AwaitingApproval(stage)` returns `TaskOutcome.Paused(state, stage)` from
`preflight`, invoking no port — fail-closed and idempotent by data: however many times a pickup
runs, it pauses. The Engine's MANUAL arm stops re-advancing in memory and returns the state the
round persisted. A `manual` last stage therefore never writes `PipelineEnd`; the approval does.
*Rationale:* the canon is unanimous (ARIES/WAL "log the intent before the action", Kleppmann's
dual write, Helland, the saga pivot and its `*_PENDING` semantic lock), and every comparable
orchestrator keeps the wait in the same write as the completion or makes it a property of the
next step (Temporal `UpdateWorkflowExecution`, Argo's suspend node in the same CR update,
GitLab's `manual` job → derived `blocked`, GitHub Actions environments gating the *next* job);
none has a post-mortem of a gate skipped after a restart. A sealed variant makes every reader
fail to compile until it names the gate (G3). *Alternative rejected — derive the owed stop at
pickup:* a detector that reads definition + position + last round and re-parks. Steelman: no
contract change, all facts already on the branch, the project's `PendingVerification` precedent.
Where it breaks: it is a second implementation of the gate rule beside the Engine's MANUAL arm
— a manual-sync twin the compiler cannot keep in step (`manual-sync-pairs.md`); it fails open
(a detector error continues silently, the class of failure being fixed); rounds carry no stage
identity, so the stage is inferred from pipeline order; and `PendingVerification` is in fact
an *explicit* record (a snapshot commit with a distinctive subject), not an inference. Borrowed
from it anyway: level-triggered re-delivery — the pickup re-reads the tip and re-delivers the
stop, but from an explicit fact.

**D2 — The approval is the pivot write.** `TaskRepository.approveCheckpoint(taskId,
TaskState approved)` lands one commit: `state.json` position `AwaitingApproval(s)` → the
position after `s`, `task.json` `outcome` null, `trackerWritePending` false, attempt history
untouched (a checkpoint resets nothing). The approved state is computed by the caller that
holds the pinned definition, through the public `TaskState.approveGate(PipelineDefinition)`,
which delegates to the package-private `Advancement.afterGate` (the AUTO branch of
`positionAfter`) — one owner for "what follows a stage"; the repositories hold no pipeline
definition and do not recompute it. It refuses, writing nothing, on what the tip alone shows:
the tip's position is not `AwaitingApproval(s)` for the stage named, or `approved.position()`
is itself a gate — the refusal is `CheckpointApprovalRefusedException` carrying the tip's actual position,
reported once at WARN with a catalog code (NFR-O1). Keyed by the gate stage only: a `manual`
stage that passed does not retry, so no later visit of the same stage can be confused with the
approved one (proposal Q1 deferred to `add-stage-iteration`). Callers: `run`
`GitResumeContinuation.resumePaused` / `ContainerResumeOutcomes.resumePaused` (which after
`make-run-headless` §2 continue without a prompt — they now approve, then continue), and
`take`'s pickup of a returned checkpoint through `ResumeMechanics.approveCheckpoint(order,
branch)` implemented by both mechanics. *Rationale:* Richardson's pivot — the one go/no-go
write — with a single owner and a type, not a flag. *Alternative rejected:* let the engine
advance past the gate in memory on a "confirmed" resume and persist with the next round — the
in-memory reset defect (4b of the audit) in a new place; a kill before the first round commit
re-asks the human.

**D3 — The stop rides the round record.** `AttemptRecord` gains `Stop stop`, a sealed
hierarchy `Stop.None | Stop.DecisionNeeded(UntrustedText question, List<UntrustedText>
options) | Stop.CannotVerify(CheckRef check, UntrustedText reason, UntrustedText details)`,
set by `StageAttemptLoop` when it records a `DECISION_NEEDED` or `CANNOT_VERIFY` round, and
persisted by `StateJsonMapper` as the attempt's `stop` object (absent = `None`, FR10).
`Engine.preflight` gains one rule beside the attempt-limit check: if the current stage's last
recorded round carries a stop and the attempt history has not been reset since, return
`Escalated` with the report rebuilt from the record (`EscalationReport.DecisionNeeded` /
`CannotVerify`), invoking no port. `TaskState.startOfStage()` / `resetAttempts()` already
empty the history, so a consumed stop is not re-raised. The park commit's `lastEscalation`
stays for `status` and the tracker render. *Rationale:* the same shape `AttemptsExhausted`
already has; the question is the one fact a lost park destroys today (3a of the audit), and
the host round commit has no other durable copy of it. *Alternative rejected:* commit the
decision file into the host round — a second medium for the same fact. Container mode already
has that second medium (`decisions/<stage>-a<n>.json` rides the snapshot) and it *does* have a
reader — `BranchDecisionFile.read` at every round close — which is the defect D10 closes: the
record is the one durable copy of the stop; the file is the round's transport, live only
under the round's own token.

**D4 — A consumed outcome is cleared with the reset it implies.**
`TaskRepository.resumeFrom(taskId, TaskState reset)` — the *resumed write* — lands one commit:
`state.json` = `reset`, `task.json` `outcome` null, `trackerWritePending` false; it refuses when
the tip's `outcome` is already null (nothing to consume). Performed, before the engine runs, by
every continuation that today consumes a recorded outcome without `appendDecision`: `run`
`EscalationResume.decide` with a null decision (`make-run-headless` §2), `take`
`TakeDecisionResume` on a bare `AttemptsExhausted` return (`:68-69`), and
`TakeLoadedBranchRoutes` → `resumeWithoutDecision` when the tip carries a recorded outcome (the
infrastructure-class returns). `appendDecision`, `approveCheckpoint` and `resumeFrom` become
the only three writers of `outcome: null` (FR8). Consequences: the attempt budget a return
grants is granted once (4b); the container resume arms compute `pendingVerification` on every
path because no stale outcome can route around it (6a); `status` and the classifier read a
consumed park as what it is (12). *Rationale:* `crash-consistency.md` item 4 — "attempts
reset" and "outcome consumed" are only true together. *Alternative rejected:* have the next
round commit clear `outcome` — a kill before it leaves the window open, which is the defect.

**D5 — Wire and version.** `state.json` v1 gains the position token `awaitingApproval(stage)`
and the attempt `stop` object; `task.json` is unchanged in shape. The version stays 1 (a
pre-release amendment, the precedent set by `status-report` v1 amendments): no released branch
carries either token, and a build older than this change reading a newer tip fails closed
(unknown subtype → `Corrupt`, quarantined) rather than resuming past a gate. The round-trip
spec iterates `Position`'s and `Stop`'s permitted subclasses — no hand-listed subset
(`testing.md`, "Every wire vocabulary has a round-trip spec"). *Alternative rejected:* bump to
version 2 — every existing branch becomes `UnsupportedVersion` for one additive token, and the
gate demands equality, so no mixed-version grace is possible anyway.

**D6 — Sync surfaces.** Three declared pairs are touched; no new pair is added.

- `GitResumeContinuation ↔ ContainerResumeOutcomes` (declared at both ends): both
  `resumePaused` arms call `approveCheckpoint` before continuing, in the same task; the
  `Kept in sync with` text is rewritten from "continue a pause without a prompt" to "both open
  the gate through `TaskRepository.approveCheckpoint` before continuing". *Decision:* keep the
  declared pair — the arms differ by medium, the rule lives in the repository write.
- `TakeResumeRunner ↔ TakeContainerResumeRunner` (declared): the checkpoint and the
  reply-less-return arms route through `ResumeMechanics.approveCheckpoint` / `resumeFrom`,
  implemented once per medium in `HostResumeMechanics` / `ContainerResumeMechanics`. *Decision:*
  the rule goes into the **existing shared abstraction** `ResumeMechanics<B>` (preference 1 of
  `manual-sync-pairs.md`); the runners' pair content shrinks, the markers are updated to say so.
- `GitTaskRepository ↔ GitObjectsTaskRepository` (the lifecycle writers; their terminal-commit
  helpers `TerminalWriteMarker ↔ GitObjectsTerminalCommits` are declared): both gain
  `approveCheckpoint` and `resumeFrom` in the same task with mirrored refusal rules. *Decision:*
  keep the pair already declared at both ends (`GitTaskRepository.java:57`,
  `GitObjectsTaskRepository.java:63`) and extend its `Kept in sync with` sentence with the
  invariant "the three outcome-clearing writes land the same `task.json`/`state.json` fields in
  one commit and refuse on the same tip conditions";
  the identity spec of D7(c) pins it on both media. *Alternative rejected:* a shared
  `LifecycleWrites` abstraction — the two media differ in the whole write mechanics (worktree
  commit vs. bare-object commit), and the rule-of-three is not reached.
- `GitAttemptPersistence ↔ EnvironmentAttemptPersistence`: the state-file mapping is untouched
  (`StateJsonMapper` is the one owner of the new tokens). D10 changes the environment end
  only: the harvested carve-out names the token path. No mirrored change on the host end —
  host mode has no carve-out (`RoundBoundaryCheck` treats any `.gnomish-task/` change as a
  violation; the host request never enters the working copy), so the synchronized invariant
  ("attempt commit + state-file write sequence") is not touched; the javadoc of the
  environment end says so.
- `DecisionFileTransport ↔ BranchDecisionFile` (declared at both ends, no shared classpath;
  registry row "env var name, read semantics, size cap"): D10 changes the branch end's
  read semantics — the path carries the round token and only that path is read — while the
  host end keeps its per-round temp file. *Decision:* keep the declared pair; the two media
  differ in where the request lives and what makes it stale, and the env var name and size
  cap stay identical. The registry row's invariant text is updated to "env var name, size
  cap; each end's own staleness rule: deletion after the read (host), the round token
  (branch)" — a `.claude/rules/` edit, performed by the human with task 6.2. *Alternative
  rejected:* unify both media on the token (proposal Q2) — a transport change of its own.

**D7 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `Advancement.positionAfter(definition, stage)` — the one decision of what a pass leaves as position | `Position` (sealed; `AwaitingApproval` for `MANUAL`) | `StageAttemptLoop.java:157` (the round commit); `Engine.runStages` MANUAL arm (returns the persisted state, no in-memory advance); the approval's "position after the gate" (the AUTO branch, `Advancement.afterGate`, reached only through the public `TaskState.approveGate(definition)`), computed by the four callers that hold the pinned definition: `GitResumeContinuation.resumePaused`, `ContainerResumeOutcomes.resumePaused` (`run`), `HostResumeMechanics.approveCheckpoint`, `ContainerResumeMechanics.approveCheckpoint` (`take`), and handed to the repository as `approved`; `add-stage-finished-event`'s `StagePassed.advancedTo` (reads the same value) | `Advancement.nextPosition(next)` call in `Engine.java:185-187` — deleted; the mode-blind javadoc — rewritten | the sealed `Position` switch (22 readers fail to compile until they name the gate); `AdvancementSpec` mode table; `:bootstrap` grep gate `PositionConstructionGateSpec`: `new Position.AwaitingApproval(` appears in `*/src/main` only in `Advancement.java` and `StateJsonMapper.java` (the wire reader; the repositories' refusal checks pattern-match the variant, they do not construct it) |
| `TaskRepository.approveCheckpoint` — the only writer past a gate | one commit; refuses as `CheckpointApprovalRefusedException` | `GitResumeContinuation.resumePaused`, `ContainerResumeOutcomes.resumePaused` (`run`); `HostResumeMechanics.approveCheckpoint`, `ContainerResumeMechanics.approveCheckpoint` ← `TakeLoadedBranchRoutes` Paused arm (`take`) | "continue with `finalState`" in both `resumePaused` arms and in `TakeLoadedBranchRoutes` → `resumeWithoutDecision` for a `Paused` tip — deleted | the Engine refuses to run from `AwaitingApproval` (D1), so a path that skips the approval cannot make progress; `PositionConstructionGateSpec` above; identity spec `GateApprovalIdentitySpec` (`:bootstrap`, bare origin, both media): after any approval the tip's position is past the gate **iff** its `outcome` is null, in the same commit, and that position is the one following the gate in the pinned definition |
| `TaskRepository.resumeFrom` — the only way a consumed outcome is cleared besides `appendDecision` / `approveCheckpoint` | one commit; refuses when `outcome` already null | `EscalationResume.decide` null-decision path (`run`); `TakeDecisionResume` bare-return arm; `HostResumeMechanics.resumeFrom`, `ContainerResumeMechanics.resumeFrom` ← `TakeLoadedBranchRoutes.resumeWithoutDecision` when the tip's `outcome` is recorded | in-memory `finalState.resetAttempts()` with no write: `TakeDecisionResume.java:69`, `EscalationResume` (formerly `EscalationResumeDialog.java:83`) — replaced by the write; `TakeResumeRunner.resumeWithoutDecision` / `TakeContainerResumeRunner.resumeWithoutDecision` continuing over a recorded outcome — routed through `resumeFrom` first | `:bootstrap` grep gate `OutcomeConsumptionGateSpec`: `resetAttempts()` appears in `*/src/main` only in `TaskState` and the call sites that hand the result to one of the three writers (allowlisted by file); identity spec `ConsumedOutcomeIdentitySpec` (bare origin, both media): after any continuation, `task.json outcome == null` **iff** `state.json` carries the continuation's reset/approved state, one commit apart from the park |
| `AttemptRecord.stop` — the one durable copy of a round's stop | `Stop` (sealed) | writer: `StageAttemptLoop` (DECISION_NEEDED / CANNOT_VERIFY arms); readers: `Engine.preflight`, `StatusReport`/`AttemptMapper` (render) | the in-memory-only `EscalationReport` as the sole carrier of the question — the report is now rebuilt from the record | the type: `AttemptRecord`'s constructor requires a `Stop`; `StageAttemptLoopSpec` asserts the stop on the recorded round for both results |
| `BranchShapeClassifier` with the position among its facts — the one classification of a gate | `BranchShape.AwaitingApproval` | every shape reader (`TakeDispositionResume`, `BranchRepairLog`, `status`, `GitResumeRunner`/`ContainerResumeRunner` routing) | `InProgress` for a tip at a gate — the classifier now reads `BranchTipFacts.position` | the sealed `BranchShape` switch; `BranchShapeClassifierPropertySpec` generates positions |
| `SandboxRoundEnvironmentSource.openRound` — the one minting of a round's identity (D10) | `RoundToken` (value type over the open tip's commit id), recorded in the per-run `RoundTokenRef` | `BranchDecisionFile.open(environment, key, token)` (the path the gnome is given and the one path read); `HarvestedBoundaryCheck.decisionPath(key, token)` (the one spelling, used by the handle and by `verify`'s carve-out); `EnvironmentRoundSnapshot` (subject); `EnvironmentAttemptPersistence` (carve-out and diff base, from the ref); `SnapshotTipCheck` (parses the token from the subject on the resume path and reads the request from the snapshot tree into `PendingVerification`) | `decisionPath(AttemptKey)` without a token — deleted; `BranchDecisionFile.open(environment, key)` — deleted; `EnvironmentAttemptPersistence.previousTip` and its `currentTip()` read at construction — removed in favour of the ref; the presence read "file at the key exists" as the liveness rule — replaced by the exact-path read | the parameter type (`RoundToken`, not `String`); `:bootstrap` grep gate `DecisionPathOwnerSpec`: `DECISIONS_DIR` and `decisionPath(` appear in `*/src/main` only in `EnvelopePaths`, `HarvestedBoundaryCheck`, `BranchDecisionFile`, `FactoryOwnedPaths`, `SnapshotTipCheck`, allowlisted by file, asserted reached; identity spec `RoundTokenIdentitySpec` (`:bootstrap`, bare origin, real adapters): after a round that asked, the request's file name, the snapshot subject's token and the commit the round opened on are one value, and after `appendDecision` the next round's handle names a path the tip does not hold |
| The three outcome-clearing writes — the one place a consumed request leaves the tip (D10 hygiene) | the commit removing `.gnomish-task/decisions/` | `GitTaskRepository` / `GitObjectsTaskRepository` `appendDecision`, `approveCheckpoint`, `resumeFrom` | `CleanupCommit` as the only remover — stays (the terminal sweep), no longer the only one | `ConsumedOutcomeIdentitySpec` (5.3) gains the assertion: after each continuation the tip holds no `decisions/` entry; the pair invariant of 2.5 names the removal |

**D8 — Shapes and crash consistency** (`crash-consistency.md`, items 1–11).

Durable steps of a `manual` pass that parks, in order, both media:

1. **round commit** — `state.json` position `AwaitingApproval(s)`, the passing round recorded
   (one commit with the pass, FR4 of `harden-task-branch-contract`);
2. **park outcome commit** — `task.json` `outcome: paused(s)` (+ marker in `take`, none in `run`
   after `make-run-headless` 1.6);
3. **tracker park** (`take` only);
4. **receipt** — marker cleared (`take` only).

| Kill window | Branch shape | Recovery owner | Direction |
|-------------|--------------|----------------|-----------|
| after 1, before 2 | `AwaitingApproval`, outcome null | the Engine's run entry on any pickup: returns `Paused(s)` with no port call; the terminal boundary records the park it finds missing (step 2) and delivers it (`run`: print + exit 11; `take`: park on the tracker) | roll forward — the park is reproduced |
| after 2, before 3 | `AwaitingApproval`, outcome `paused(s)`, marker set | `take`: `TakeReconcile.deliverPark` as today (the pending-write marker is the same sub-state as for any park) | roll forward |
| after 3, before 4 | as above, tracker parked | `confirmTerminalWrite` on the next pickup (existing) | roll forward |

Approval (one commit): before it, `AwaitingApproval` + whatever the park left; after it, the
approved position with `outcome` null — `InProgress` or `CompletedUncleaned`-to-be at
`PipelineEnd`. A kill right after the approval commit, before the next round, freezes
`InProgress` at the following stage: the ordinary interrupted-run shape, correct. Resumed
write (one commit): before it, `Parked`; after it, `InProgress` at the reset state. Stop on
the record: the round commit carries it; a kill before the park commit freezes `InProgress`
whose next run re-escalates from the record (D3) and records the park — roll forward.

Round token (D10), container medium, a round that asks: 1. **snapshot commit** — the request
at the token path, the token in the subject; 2. **state commit** — the round recorded with its
stop (D3). A kill after 1, before 2 freezes the existing `PendingVerification` shape (a tip
whose subject is a snapshot); its recovery owner stays `ResumeVerificationStageExecutor`,
which now reads the request from the snapshot's tree and re-raises `DecisionNeeded` — roll
forward, no agent re-run, no attempt burned. After 2 the stop is on the record and D3 governs.
A stale request file on the tip, under any name, is not a shape input: no reader looks for
anything but the current round's path, so the three outcome-clearing commits may remove
`decisions/` or die before pushing it with no change in recovery.

Constructive before destructive: the only deletion added, `decisions/` removal, rides the
outcome-clearing commit — after the answer it consumes is durable in that same commit, and
after the stop was recorded (D3) one or more commits earlier. Idempotence: a second pickup
over any frozen state above lands exactly where the first did (the Engine's answer is a
function of the tip; `approveCheckpoint`/`resumeFrom` refuse on a tip they already moved).
Atomicity per medium: every write above is one commit through the medium's existing writer
(worktree commit on host, bare-object ref update in the container). Readers name their medium:
every decision reads the tip (`EnvelopeMediumBoundarySpec`). Kill-point rows added to
`TransitionKillPointSpec`: `GateKillPoints` (host + container; `Paused` and `DecisionNeeded`;
steps 1–4 with a barrier **before** step 2; the recording executor asserts the next stage never
ran; the `manual`-last-stage variant asserts no `Completed` is ever recorded),
`ApprovalKillPoints` (one step; second pickup is a no-op), `ResumedKillPoints` (one step;
second pickup resets nothing), `RequestSnapshotKillPoints` (container; kill between the
snapshot carrying a request and the state commit; the pickup re-raises the request's
content; the second pickup is a no-op; then an answer, and the next round's read is empty).
*Shape decision:* a new shape `AwaitingApproval` rather than
"`InProgress` refined by position" — the recovery owners differ (the stage engine *runs* an
`InProgress` tip and *pauses* a gate), and `crash-consistency.md` item 3 forbids two owners for
one shape; `BranchTipFacts` gains the position to make the classification total.

**D9 — `add-stage-iteration` review note.** Its D6 overflow decision request, the window
"cursor exhausted, no stage verdict", and the D8 repair-item adoption after a stage-end failure
are three new instances of the pattern this change closes. The rule lands here (ADR 0003,
`crash-consistency.md` item 12); that change applies it before its apply — a review note is
written into its `design.md` as a task of this change, not a silent assumption.

**D10 — The round token: a request is live only under the identity of the round that owns
it.** A container-mode round has an identity no later round of the task repeats — the
**round token**, the task branch's tip commit at the moment the round opens. It is unique by
construction: every round lands at least its snapshot commit (`--allow-empty`), and every
reset rides a lifecycle commit of its own (`appendDecision`, `resumeFrom`,
`approveCheckpoint`), so no two rounds open on the same tip. One owner mints it:
`SandboxRoundEnvironmentSource.openRound` reads `refs/heads/<branch>` (it already holds the
runner, the clone and the branch) and records it in a per-run `RoundTokenRef` (the
`AttemptCommitRef` shape), from which every consumer takes it as a `RoundToken` value — never
re-reading the tip. The decision path becomes `decisions/<stage>-a<attempt>-<token>.json`:
`BranchDecisionFile.open(environment, key, token)` names it, hands it to the gnome in
`$GNOMISH_DECISION_FILE`, and `read()` reads exactly it — a file under any other name is not
read, whether it was carried over on the tip, left by a killed round, or written by the gnome
beside the real one. `HarvestedBoundaryCheck.decisionPath(key, token)` is the one spelling of
the path; the token-less overload is deleted, so no caller can build the old name. The
snapshot subject carries the token (`gnomish: snapshot <stage>#<round> <token>`), which is
what makes the crash path recoverable: `SnapshotTipCheck` parses it, reads the request —
if any — from the snapshot's tree at the token path (`git show <snapshot>:<path>`, a read of
the durable medium), and `PendingVerification` carries the raw content; `ResumeVerification-
StageExecutor` maps it through the shared `DecisionFileReader` to `DecisionNeeded`, or to
`Completed` when the snapshot holds none. `EnvironmentAttemptPersistence` takes the token from
the ref for the carve-out and for its diff base — its own `previousTip` field, the same value
under another name, is removed (one owner). **Hygiene, not judgement:** the three
outcome-clearing writes also remove `decisions/` in their commit (FR14) — the consumed request
leaves the tip with the transition that consumed it, and a PR under escalation does not
accumulate answered questions — but no reader relies on that removal; correctness is the
token match alone, so a kill between an answer and its push changes nothing. *Rationale:* the
canon makes the receiver's identity match the single owner of "is this request live" (Step
Functions' task token, EIP's Correlation Identifier, Kleppmann's fencing token) and treats
deletion as hygiene with an at-least-once caveat (SQS); two independent liveness judgements
would be two recovery owners for one shape (`crash-consistency.md` item 3). The tip at round
open is the one identity the medium already provides, available to the one component that
opens rounds, with no new durable step. *Alternatives rejected:*
- **Delete-before-open as the judge** — the round removes any file at its key in the box's
  working copy before launching the agent, and reads by presence at close (the implementer's
  proposal; Tekton's "unset results before retry"). Steelman: no new concept, no path change,
  the delete needs no durability (a round killed before its snapshot leaves the tip as it was,
  and the next open deletes again), and the kill-after-snapshot path reads the right file. Where
  it breaks: the judge is still presence, so correctness rests on *every* opener remembering
  to clear — a second round source, salvage, `add-stage-iteration`'s per-item passes — and
  neither the compiler nor a spec across a reset catches the one that forgets; it leaves
  `AttemptKey` lying and the resume path unable to tell a dead round's request from an older
  one with the same key. Borrowed: the removal, as hygiene in the consuming commit.
- **Diff-based read** — accept the file only if it appears in `openTip..snapshot`. Lamport-
  correct, but a request rewritten with identical content is invisible to it, it makes the
  reader depend on a diff another class runs later, and it is a second liveness judgement
  beside the name.
- **A monotonic `roundSeq` in `state.json`** — the fencing token proper. It needs a commit at
  round open (today a round's first durable step is its snapshot), a new kill window and a
  new shape; the tip SHA gives uniqueness, which is all a receiver-side match needs, without
  ordering and without a step (proposal NG5).
- **A monotonic attempt number in the name** — breaks the contract that an answer restarts
  the stage's budget at zero (`resetAttempts`, FR4 of `harden-task-branch-contract`).

## Risks / Trade-offs

- [22 `Position` readers must change at once] → the compiler lists them; most are render or
  MDC arms with an obvious text; the tasks name every file.
- [`ResumeMatrixSpec` "a post-pause resume starts at the next stage" and the `stage-engine`
  spec text encode the old contract] → both are rewritten in this change (M3); the archived
  `add-stage-engine` D4 rationale ("`Completed` directly from a manual last stage would silently
  skip the checkpoint") is exactly what the new contract delivers.
- [`add-stage-finished-event` reads `Advancement.nextPosition` for `StagePassed.advancedTo`] →
  it reads `positionAfter` instead; a `manual` pass emits `advancedTo = AwaitingApproval(s)`,
  which is the truth. Sequencing note added to that change.
- [A `manual` stage that is later made iterating (`add-stage-iteration`) may need an approval
  key beyond the stage name] → Q1 deferred there explicitly.
- [Older build reads a newer tip] → fails closed as `Corrupt` (D5), quarantined; pre-release.
- [Scope: D1–D5 plus D7/D8 is one to two weeks; D10 adds three to five days] → cut line: D4
  (the resumed write) and its gate split into `clear-consumed-outcome` if the estimate
  overruns; D1–D3 cannot be split without leaving the lost-park defect half-fixed, and D10
  cannot be split from D3, which is what makes the stale request reachable (the answer path
  is now the only continuation of a recorded stop).
- [The token lengthens the decision path the gnome is told to write] → a 40-character hex
  suffix is well inside any path limit; the gnome never spells the name — it reads
  `$GNOMISH_DECISION_FILE` — and the fake agent is checked to do the same.
- [Two readers of "the tip at round open" could drift — the round source and
  persistence] → there is one: persistence takes the token from the ref the round source
  records; `RoundTokenIdentitySpec` pins the name, the subject and the open commit to one
  value.
- [`PendingVerification` grows a payload read from the snapshot tree] → the read is a
  `git show` on the factory clone, the medium ADR 0003 names; the executor only maps it.

## Migration Plan

1. Domain first: `Position.AwaitingApproval`, `Stop`, `Advancement`, `Engine.preflight`,
   `StageAttemptLoop`, `TaskState` — every `Position` reader fixed to compile (tasks §1).
2. Wire and repositories: `StateJsonMapper` tokens, `approveCheckpoint`, `resumeFrom`,
   refusal rules, the denial-cursor fix, round-trip specs (§2).
3. Classifier: `BranchTipFacts.position`, `BranchShape.AwaitingApproval`, every shape reader
   (§3).
4. Continuations: `ResumeMechanics` approve/resumeFrom, `run` and `take` arms, terminal
   boundaries re-delivering a lost park, `status` render (§4).
5. Round token (§7, D10): the value and its minting, the token path and the exact read, the
   snapshot subject and the resume path, the `decisions/` removal, the fake agent and the two
   container E2E specs — before task 4.3 is ticked, since its container features depend on
   it.
6. Gates and kill-point rows, identity specs (§5); ADR, rule, glossary, review note (§6).
