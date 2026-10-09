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
- Found while implementing 7.3 (escalation of 2026-10-07, issue #83): the resume path is a
  **second producer of a round**. `ResumeVerificationStageExecutor.execute` returns without
  `openRound`, so it fills `AttemptCommitRef` (`attemptCommit.record(p.attemptCommit())`,
  `:67`) and nothing fills the `RoundTokenRef` task 7.1 added; the engine still calls
  `persist` for that round (`StageAttemptLoop.java:111,123` → `AttemptJournal.java:62`).
  `SnapshotTipCheck.java:111` parses the token from the subject and drops it;
  `PendingVerification` carries no token. The `previousTip` diff base compared the snapshot
  with itself on that path (the tip *is* the snapshot) — a guard that cannot fire. Two
  per-run cells filled by different steps make a half-populated round representable; that,
  not the missing `record` call, is the defect (D10, amended).
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
Position.AwaitingApproval gate, TaskState approved)` lands one commit: `state.json` position `AwaitingApproval(s)` → the
position after `s`, `task.json` `outcome` null, `trackerWritePending` false, attempt history
untouched (a checkpoint resets nothing). The approved state is computed by the caller that
holds the pinned definition, through the public `TaskState.approveGate(PipelineDefinition)`,
which delegates to the package-private `Advancement.afterGate` (the AUTO branch of
`positionAfter`) — one owner for "what follows a stage"; the repositories hold no pipeline
definition and do not recompute it. It refuses, writing nothing, on what the tip alone shows:
the tip's position is not `gate`, or `approved.position()` is itself a gate — the refusal is
`CheckpointApprovalRefusedException` carrying the tip's actual position, reported once at WARN
with a catalog code (NFR-O1). `gate` is the variant the caller pattern-matched off the tip
state (`CheckpointApproval.approve`, the shared seam of D7), never constructed. Naming the gate
is what makes a stale approval harmless: an approval computed from one gate can never open
another the tip has since reached, and a repeated approval finds a tip it already moved and
refuses (NFR-R2). Keyed by the gate stage only: a `manual`
stage that passed does not retry, so no later visit of the same stage can be confused with the
approved one (proposal Q1 deferred to `add-stage-iteration`). Callers: `run`
`GitResumeContinuation.resumePaused` / `ContainerResumeOutcomes.resumePaused` (which after
`make-run-headless` §2 continue without a prompt — they now approve, then continue), and
`take`'s pickup of a returned checkpoint through `ResumeMechanics.approveCheckpoint(order,
branch)` implemented by both mechanics — all four through `CheckpointApproval.approve`
(`:application`, package-private): compute `approveGate`, run the medium's pre-write hook (the
container medium disposes its kept box there), hand the tip's gate and the approved state to
the repository. *Rationale:* Richardson's pivot — the one go/no-go
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

**D6 — Sync surfaces.** Three declared pairs are touched; no new pair is added; two
**undeclared** copies are dissolved (added 2026-10-07 and 2026-10-08).

- `EnvironmentLease` (`sandbox/docker`) and `FreshJudgeEnvironments` (`adapters/agent`) hold
  the same rule — one live box per role, built from a supplier on demand, kept while its key
  (the stage segment; the attempt commit) is unchanged, disposed when it changes — with the
  same fields and the same `synchronized` pair of methods, and no marker at either end
  (`manual-sync-pairs.md`, preference 3). One end documents its lock as the resource-serializing
  exception, the other documents nothing: the two ends already disagree. *Decision:* **one
  owner** (preference 1): `LiveBox<K>` in `sandbox/core` carries the rule and the three-phase
  lock shape (D13); both classes become thin owners of their key — the segment index over the
  plan, the attempt commit — and delegate. *Alternative rejected:* declare the pair and fix the
  lock twice — the lock shape is the hard part, and two copies of a concurrency protocol are
  two places for the next review to miss.

- `ContainerResumeOutcomes.resumeFromRecordedPosition` (`:54-67`) and
  `TakeContainerResumeRunner.resumeWithoutDecision` (`:81-92`) hold the same resume
  preparation — pending-snapshot check, discard or reattach, salvage when no snapshot is
  pending — shared only through `stageToReattach`; the javadoc's "exact sequence, reused here"
  is reuse by copy, with no marker (`manual-sync-pairs.md`, preference 3 — an audit finding).
  The denial-restoration ordering defect of D11 sits in both copies, which is the harm the rule
  names. *Decision:* **one owner** (preference 1): `ContainerResumePreparation.prepare(support,
  discardWork, position, taskId)` in `:application` returns the pending verification and
  performs the discard/reattach/salvage sequence; `stageToReattach` moves into it; both callers
  shrink to one call (FR18). *Alternative rejected:* declare the pair — a third caller is
  already foreseeable (`add-stage-iteration`'s per-item resume), and the sequence has no
  medium-specific half to justify two bodies.

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
| `Advancement.positionAfter(definition, stage)` — the one decision of what a pass leaves as position | `Position` (sealed; `AwaitingApproval` for `MANUAL`) | `StageAttemptLoop.java:157` (the round commit); `Engine.runStages` MANUAL arm (returns the persisted state, no in-memory advance); the approval's "position after the gate" (the AUTO branch, `Advancement.afterGate`, reached only through the public `TaskState.approveGate(definition)`), computed in `CheckpointApproval.approve` — the shared seam of the four callers that hold the pinned definition: `GitResumeContinuation.resumePaused`, `ContainerResumeOutcomes.resumePaused` (`run`), `HostResumeMechanics.approveCheckpoint`, `ContainerResumeMechanics.approveCheckpoint` (`take`) — and handed to the repository as `approved` together with the tip's gate; `add-stage-finished-event`'s `StagePassed.advancedTo` (reads the same value) | `Advancement.nextPosition(next)` call in `Engine.java:185-187` — deleted; the mode-blind javadoc — rewritten | the sealed `Position` switch (22 readers fail to compile until they name the gate); `AdvancementSpec` mode table; `:bootstrap` grep gate `PositionConstructionGateSpec`: `new Position.AwaitingApproval(` appears in `*/src/main` only in `Advancement.java` and `StateJsonMapper.java` (the wire reader; the repositories' refusal checks pattern-match the variant, they do not construct it) |
| `TaskRepository.approveCheckpoint(taskId, gate, approved)` — the only writer past a gate | one commit; refuses as `CheckpointApprovalRefusedException` | `CheckpointApproval.approve` (`:application`) — the one call site, which pattern-matches `gate` off the tip state and never constructs it; reached from `GitResumeContinuation.resumePaused`, `ContainerResumeOutcomes.resumePaused` (`run`, via `CheckpointApproval.continuePause`); `HostResumeMechanics.approveCheckpoint`, `ContainerResumeMechanics.approveCheckpoint` ← `TakeLoadedBranchRoutes` Paused arm (`take`) | "continue with `finalState`" in both `resumePaused` arms and in `TakeLoadedBranchRoutes` → `resumeWithoutDecision` for a `Paused` tip — deleted | the Engine refuses to run from `AwaitingApproval` (D1), so a path that skips the approval cannot make progress; `PositionConstructionGateSpec` above; identity spec `GateApprovalIdentitySpec` (`:bootstrap`, bare origin, both media): after any approval the tip's position is past the gate **iff** its `outcome` is null, in the same commit, and that position is the one following the gate in the pinned definition |
| `TaskRepository.resumeFrom` — the only way a consumed outcome is cleared besides `appendDecision` / `approveCheckpoint` | one commit; refuses when `outcome` already null | `EscalationResume.decide` null-decision path (`run`); `TakeDecisionResume` bare-return arm; `HostResumeMechanics.resumeFrom`, `ContainerResumeMechanics.resumeFrom` ← `TakeLoadedBranchRoutes.resumeWithoutDecision` when the tip's `outcome` is recorded | in-memory `finalState.resetAttempts()` with no write: `TakeDecisionResume.java:69`, `EscalationResume` (formerly `EscalationResumeDialog.java:83`) — replaced by the write; `TakeResumeRunner.resumeWithoutDecision` / `TakeContainerResumeRunner.resumeWithoutDecision` continuing over a recorded outcome — routed through `resumeFrom` first | `:bootstrap` grep gate `OutcomeConsumptionGateSpec`: `resetAttempts()` appears in `*/src/main` only in `TaskState` and the call sites that hand the result to one of the three writers (allowlisted by file); identity spec `ConsumedOutcomeIdentitySpec` (bare origin, both media): after any continuation, `task.json outcome == null` **iff** `state.json` carries the continuation's reset/approved state, one commit apart from the park |
| `AttemptRecord.stop` — the one durable copy of a round's stop | `Stop` (sealed) | writer: `StageAttemptLoop` (DECISION_NEEDED / CANNOT_VERIFY arms); readers: `Engine.preflight`, `StatusReport`/`AttemptMapper` (render) | the in-memory-only `EscalationReport` as the sole carrier of the question — the report is now rebuilt from the record | the type: `AttemptRecord`'s constructor requires a `Stop`; `StageAttemptLoopSpec` asserts the stop on the recorded round for both results |
| `BranchShapeClassifier` with the position among its facts — the one classification of a gate | `BranchShape.AwaitingApproval` | every shape reader (`TakeDispositionResume`, `BranchRepairLog`, `status`, `GitResumeRunner`/`ContainerResumeRunner` routing) | `InProgress` for a tip at a gate — the classifier now reads `BranchTipFacts.position` | the sealed `BranchShape` switch; `BranchShapeClassifierPropertySpec` generates positions |
| `RoundToken.of(commitId)` (`app/port/git`) — the one parse of a commit id into a round's identity (D10); called by exactly two producers: `SandboxRoundEnvironmentSource.openRound` over the open tip (a fresh round mints) and `SnapshotTipCheck` over the snapshot subject (a resumed round reuses — never mints) | `RoundToken` (value type; blank or non-hex refused) and `ClosedRound(token, attemptCommit)` (a round that has its snapshot; neither half nullable) | the per-run cell `CurrentRound` (`app/port/git`, replaces `RoundTokenRef` and `AttemptCommitRef`): written by `openRound` (`open(token)`), `EnvironmentRoundSnapshot` (`snapshotted(commit)`), `ResumeVerificationStageExecutor` (`restore(PendingVerification)` — the same two transitions, run together); read as `opened()` by `BranchDecisionFile.open(environment, key, token)` (the path the gnome is given and the one path read), `HarvestedBoundaryCheck.decisionPath(key, token)` (the one spelling), `EnvironmentRoundSnapshot` (subject); read as `closed()` by `EnvironmentAttemptPersistence` (carve-out, diff base, parent check), `RecordedAttemptCommitWorkspace` (the check runners' attempt commit); `PendingVerification` carries the typed token from `SnapshotTipCheck` to the executor | `decisionPath(AttemptKey)` without a token — deleted; `BranchDecisionFile.open(environment, key)` — deleted; `RoundTokenRef` and `AttemptCommitRef` — deleted (two cells that could hold half a round); `EnvironmentAttemptPersistence.previousTip` and its `currentTip()` read at construction — removed (the diff base is the recorded token; the post-harvest `currentTip()` stays, it compares the record to a fresh observation); `SnapshotTipCheck`'s parse-and-drop of the token — the token travels; the presence read "file at the key exists" as the liveness rule — replaced by the exact-path read | the parameter types (`RoundToken`, `ClosedRound`, not `String`); `opened()`/`closed()` return distinct types, so a consumer of the closed round cannot compile against an open one; `:bootstrap` grep gate `DecisionPathOwnerSpec`: `DECISIONS_DIR` and `decisionPath(` appear in `*/src/main` only in `EnvelopePaths`, `HarvestedBoundaryCheck`, `BranchDecisionFile`, `FactoryOwnedPaths`, `SnapshotTipCheck`, allowlisted by file, asserted reached; `:bootstrap` grep gate `CurrentRoundWriterSpec`: `RoundToken.of(` appears in `*/src/main` only in `SandboxRoundEnvironmentSource` and `SnapshotTipCheck`, and the cell's three writers appear only in the three files above, allowlisted, asserted reached; identity spec `RoundTokenIdentitySpec` (`:bootstrap`, bare origin, real adapters): (live) after a round that asked, the request's file name, the snapshot subject's token and the commit the round opened on are one value, and after `appendDecision` the next round's handle names a path the tip does not hold; (resumed) after a kill between the snapshot and the state commit, the pickup's state commit lands with a boundary check whose carve-out and diff base are the snapshot's recorded token — no re-read tip — and the same three values are still one |
| `ContainerEnvironments.roundEnvironment()` — the one place a round environment is built, and therefore the one place the tip's recorded denial position is offered to it (D11) | `DenialRestoration`, produced by the `Supplier<DenialRestoration>` the seam receives as a per-task argument of `ContainerEnvironmentFactory.forTask(...)` (D12; `ContainerTipReader.restorable(...)` over the branch tip, evaluated at build time, in the unlocked phase of the live box — D13) | `EnvironmentLease.environmentFor` (first open, segment boundary, `reattachFor` on resume) — the only caller of `roundEnvironment()`; every box it hands out already carries the offer | `ContainerEnvironments.restoreDenials(…)` setter and its field — deleted; `SandboxRunSupport.restoreDenials()` and `ContainerRunSupport.restoreDenials()` — deleted; the calls in `ContainerTerminalDrive.run` and `TakeContainerEngineExecution.run` — deleted; `ContainerTipReader.restoreDenials(support)` — becomes the supplier | the constructor parameter (no setter exists to call late); the port has no method to call out of order; `:bootstrap` grep gate `DenialRestorationOwnerSpec`: `restoreDenials(` in `*/src/main` only in the environment port and its adapters (`TaskExecutionEnvironment`, `SelfCheckedEnvironment`, `LeasedEnvironment`, `EgressGuard`) and `ContainerEnvironments`, allowlisted by file, asserted reached; `ContainerRunSupportSpec`: a box obtained through `reattachFor` on a tip with a recorded cursor received the offer (red with the supplier wired to `DenialRestoration.none()`); E2E `ContainerModeResumeE2ESpec`: resume onto a surviving guard with a recorded cursor reports no denial twice |
| The three outcome-clearing writes — the one place a consumed request leaves the tip (D10 hygiene) | the commit removing `.gnomish-task/decisions/` | `GitTaskRepository` / `GitObjectsTaskRepository` `appendDecision`, `approveCheckpoint`, `resumeFrom` | `CleanupCommit` as the only remover — stays (the terminal sweep), no longer the only one | `ConsumedOutcomeIdentitySpec` (5.3) gains the assertion: after each continuation the tip holds no `decisions/` entry; the pair invariant of 2.5 names the removal |
| `ContainerEnvironmentFactory` (`sandbox/docker`, D12) — the one place the installation's box equipment meets a task's inputs; a facade with one return type (ADR 0010) | `ContainerEnvironments`, from `forTask(baseKey, link, allowlist, projectId, restoration)` | `ContainerRunSupportFactory.create` — the only caller; the factory is a component of that record, built once per ownership mode by `ContainerSupports.supportFactory(mode)` | `ContainerEnvironments.forTask(...)` static with seven parameters — deleted; `new BoxTiming(new SystemClock(), new ThreadSleeper(), …)` and the guard-root `Path.of(tmpdir, "gnomish-guard")` per call in `ContainerRunSupportFactory.create` — moved into the factory's construction; `SandboxProperties`/`FactoryProperties` as parameters of `ContainerSupportFactory.create` and as fields of `ContainerGitModeRunner`, `ContainerResumeRunner`, `ContainerTakeSupport` (relayed by `TakeContainerResumeBootstrap`, `TakeWorkRouter`, `TakeContainerFreshClaim`) — deleted; `SandboxLifecyclePassFactory.create(sandbox, factory, clock)` per run — called once per mode | the compile-time parameter gate (an eighth per-task input has a home in the method, an installation input in the constructor — neither pressures the other); the `ContainerSupportFactory` port's five-parameter `create`, so a runner cannot pass a property set; `ContainerEnvironmentsSeamSpec` asserts the credential scrub through the factory-built seam; `ContainerRunSupportSpec` builds through the factory |
| `LiveBox<K>` (`sandbox/core`, D13) — the one implementation of "one live box per role, rebuilt when its key changes" and of the lock that guards it | `TaskExecutionEnvironment`, materialized, for a key; `Optional<TaskExecutionEnvironment> current()` as the last recorded box | `EnvironmentLease.environmentFor` / `current` / `currentIfLeased` / `dispose` (over the segment index); `FreshJudgeEnvironments.environmentFor` / `disposeCurrent` (over the attempt commit) | the two `synchronized` method sets in `EnvironmentLease` and `FreshJudgeEnvironments` — deleted; `FreshJudgeEnvironments`' javadoc claim of the resource-serializing exception — retired (waiting now happens on the build's future, not on the monitor) | `LiveBoxConcurrencySpec` (real threads, a materializer blocked on a latch: a reader returns promptly; a same-key request joins the one build; a failed build releases every waiter; dispose after a build waits for the build, then disposes with nothing held); `:bootstrap` architecture spec `LockScopeOwnerSpec`: `synchronized` appears in `sandbox/*/src/main` and `adapters/agent/src/main` only in `LiveBox` and `GuardDenialReads`, allowlisted by file, asserted reached |

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
write (one commit): before it, `Parked`; after it, the written state with `outcome` null —
`InProgress` where the write keeps the recorded rounds (an `Aborted` or legacy `Paused`
return with rounds on record), `Created` where it carries `resetAttempts()` (an `Escalated`
return: the reset empties the stage's round history, so the tip classifies as a stage not yet
run), or `Answered` when that reset tip's `task.json` already records a decision — same owner
(the stage engine), same direction (roll forward) for all three; `ResumedKillPoints` pins the
`Created` row. Stop on
the record: the round commit carries it; a kill before the park commit freezes `InProgress`
whose next run re-escalates from the record (D3) and records the park — roll forward.

Round token (D10), container medium, a round that asks: 1. **snapshot commit** — the request
at the token path, the token in the subject; 2. **state commit** — the round recorded with its
stop (D3). A kill after 1, before 2 freezes the existing `PendingVerification` shape (a tip
whose subject is a snapshot); its recovery owner stays `ResumeVerificationStageExecutor`,
which now reads the request from the snapshot's tree and re-raises `DecisionNeeded` — roll
forward, no agent re-run, no attempt burned. The pickup rebuilds the round's in-memory
identity from that record alone (`CurrentRound.restore`, D10): the state commit it then lands
is checked against the recorded token, exactly as the live path would have checked it, so
the resumed round is not a weaker round. After 2 the stop is on the record and D3 governs.
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
`approveCheckpoint`), so no two rounds open on the same tip. A fresh round mints it:
`SandboxRoundEnvironmentSource.openRound` reads `refs/heads/<branch>` (it already holds the
runner, the clone and the branch) and parses it through `RoundToken.of`, the one parse of a
commit id into a round identity; a resumed round never mints — it reuses the token its
snapshot recorded, parsed by `SnapshotTipCheck` through that same function (amended
2026-10-07; the facts above). Every consumer takes the identity as a value from one per-run
cell, `CurrentRound` (`app/port/git`), which replaces both `RoundTokenRef` and
`AttemptCommitRef`: it is written by `open(token)` at round open, `snapshotted(commit)` at
the snapshot, and `restore(PendingVerification)` on the resume path — implemented as those
same two transitions run together, so there is one code path for the round's identity with
two input sources (the live tip, the durable record), not two code paths. Its readers get
distinct types: `opened()` yields the `RoundToken` (the decision path, the snapshot subject),
`closed()` yields a `ClosedRound(token, attemptCommit)` with neither half nullable (the
persistence's carve-out, diff base and parent check; the check runners' attempt commit
through `RecordedAttemptCommitWorkspace`). A round with a snapshot and no token is not a
value this type can hold. The cell itself is a **bridge at a port boundary, and the design
names which:** `AttemptPersistence.persist(taskId, state, trace)` in `:domain` and the
published SPI `AttemptCommitWorkspace` cannot carry a round identity without teaching the
engine about commits, which D15 of `add-sandbox-core` deliberately refused ("the sequence
hides in adapters"), and host mode has no such identity to carry. A per-run cell is allowed
only there, and only holding a whole identity, never a fragment. The decision path becomes
`decisions/<stage>-a<attempt>-<token>.json`:
`BranchDecisionFile.open(environment, key, token)` names it, hands it to the gnome in
`$GNOMISH_DECISION_FILE`, and `read()` reads exactly it — a file under any other name is not
read, whether it was carried over on the tip, left by a killed round, or written by the gnome
beside the real one. `HarvestedBoundaryCheck.decisionPath(key, token)` is the one spelling of
the path; the token-less overload is deleted, so no caller can build the old name. The
snapshot subject carries the token (`gnomish: snapshot <stage>#<round> <token>`), which is
what makes the crash path recoverable: `SnapshotTipCheck` parses it, reads the request —
if any — from the snapshot's tree at the token path (`git show <snapshot>:<path>`, a read of
the durable medium), and `PendingVerification` carries the typed token and the raw content;
`ResumeVerification-StageExecutor` restores the round into the cell from it and maps the
content through the shared `DecisionFileReader` to `DecisionNeeded`, or to `Completed` when
the snapshot holds none. `EnvironmentAttemptPersistence` takes the closed round from the cell
for the carve-out, the diff base and the parent check — its own `previousTip` field is
removed: on the live path it was the open tip under another name, on the resume path it was
the snapshot itself, so the boundary diff compared a value with its own re-read (one owner;
the post-harvest `currentTip()` stays, since it compares the record to a fresh observation of
the branch, the shape of check the canon keeps). **Hygiene, not judgement:** the three
outcome-clearing writes also remove `decisions/` in their commit (FR14) — the consumed request
leaves the tip with the transition that consumed it, and a PR under escalation does not
accumulate answered questions — but no reader relies on that removal; correctness is the
token match alone, so a kill between an answer and its push changes nothing. *Rationale:* the
canon makes the receiver's identity match the single owner of "is this request live" (Step
Functions' task token, EIP's Correlation Identifier, Kleppmann's fencing token) and treats
deletion as hygiene with an at-least-once caveat (SQS); two independent liveness judgements
would be two recovery owners for one shape (`crash-consistency.md` item 3). The tip at round
open is the one identity the medium already provides, available to the one component that
opens rounds, with no new durable step. For the amendment: recovery rebuilds in-memory
context from the durable record and nothing else (ARIES's analysis pass rebuilds the
transaction table from the log; Temporal replays the *same* workflow code against recorded
history — one code path, recorded inputs; Argo and Tekton continue from `status`), the
record carries the input that pinned the attempt (Tekton stores the spec, GitHub Actions
re-runs the same SHA — the token is that input), and a holder filled by `record()` and read
by `required()` is textbook hidden temporal coupling (DevIQ: "no mechanism to detect or
prevent incorrect ordering"; Seemann's Ambient Context anti-pattern; Go's `context`: pass it,
do not store it) — so the cell is tolerated only as a named bridge and only whole ("make
illegal states unrepresentable"). *Alternatives rejected:*
- **Two cells, token moved, the executor records both** (the implementer's option A). Steelman:
  the smallest edit, the `AttemptCommitRef` precedent, one new `record` line. Where it breaks:
  a half-populated round stays representable; the two `record` lines on the resume path are a
  hand-synchronized pair with no marker (`manual-sync-pairs.md`, preference 3); and the
  persistence constructor reaches eight parameters, so the two refs get grouped anyway —
  grouped without the invariant that is the whole point. Borrowed: the executor as the one
  recording site of the resume path; the types relocated to `app/port/git`.
- **Thread the round through the engine's ports** — `ExecutionResult` carries an opaque
  receipt, `persist` and the check workspace take it; no cell at all. Steelman: the canon's
  first preference (pass the context), compile-time enforced end to end. Where it breaks: a
  `:domain` port change (`StageExecutor`, `AttemptPersistence`) and a published-SPI change
  (`AttemptCommitWorkspace`) for one medium — host mode would carry an empty opaque value —
  against D15's standing decision; beyond this change's scope. Borrowed: the obligation to name
  the port that forces the bridge, and a gate pinning the cell's writers to the two producers.
- **Persistence falls back to re-reading the tip when no round opened on this run** (option
  C). The self-comparing guard this amendment removes, kept on purpose; a second reader of the
  open tip that task 7.4 exists to delete.
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

**D11 — The recorded denial position is an input of building a box, never a step after it**
(added 2026-10-07; FR17). Found by the sibling audit of the #83 escalation, in the class D10's
amendment names: a value one step produces, another consumes, with a recovery path that runs
the steps in another order. `ContainerEnvironments.restoreDenials(restoration)` (`:176`) only
stores a field; `roundEnvironment()` (`:103-111`) applies it to the environment it builds *if
the field is already set*; `environment(key)` (`:180`) builds a fresh `SelfCheckedEnvironment`
each call. A fresh start calls `support.restoreDenials()` before the first `openRound`, so the
first box gets the offer. A resume calls `support.reattachFor(stage)` — which builds the box
through the lease — **before** `support.restoreDenials()` (`ContainerResumeOutcomes.java:62`
then `ContainerTerminalDrive.java:47`; `TakeContainerResumeRunner.java:87` then
`TakeContainerEngineExecution.java:111`), so the reattached box's guard never receives the
recorded cursor and identities: its next read replays the container's whole denial log, the
duplicate FR5/FR7 of `fix-denial-attribution-durability` exist to prevent. Green today
because the routing specs drive a stubbed `SandboxRunSupport` and `ContainerEnvironmentsSpec`
sets the field first. *Decision:* the offer is read **as the box is built**. `ContainerEnvironments`
is constructed with a `Supplier<DenialRestoration>`; `roundEnvironment()` evaluates it and
offers the result to the environment it returns; the supplier is `ContainerTipReader`'s read
of the branch tip (`ContainerRunSupport` wires it, since it owns the runner, the clone and the
branch). The setter, the field, `SandboxRunSupport.restoreDenials()`, its implementation and
its two callers are deleted — there is then no step to order, and no API through which a
late offer could be made. Reading the tip at build time is also what the fresh-start path
meant: the position the tip records when the box is born, a read of the durable medium
(`crash-consistency.md` item 11). *Rationale:* the same canon as D10's amendment — recovery
rebuilds context from the record on the path that needs it, not from a step the normal path
happened to run first (ARIES, Temporal); a setter applied only to objects built after it is
hidden temporal coupling (DevIQ), and the project's own "Immutable after construction" clause
(`process-invariants.md`) forbids the attach-after shape without a cycle that forces it — none
does here. *Alternatives rejected:*
- **Forward the late offer to the leased box** — `ContainerRunSupport.restoreDenials()` also
  calls `lease.currentIfLeased().ifPresent(e -> e.restoreDenials(offer))` (`LeasedEnvironment`
  already forwards, `:94`). Two lines, one spec. Where it breaks: it keeps the ordering
  dependency between two port methods and fixes the one caller that got it wrong today; the
  next caller (a per-item resume, a third medium) can get it wrong again, and nothing but a
  review notices. The patch, not the mechanism.
- **Move `support.restoreDenials()` before `reattachFor`** in both callers. Same objection,
  and it leaves the setter whose semantics ("applies to boxes built later") caused the defect.
- **Read the restoration once at `ContainerRunSupport` construction.** The tip moves between
  construction and the first box (`appendDecision`, `resumeFrom`, `approveCheckpoint` land
  first and may carry a newer escalation-side cursor); a value read early is the stale baseline
  D10's `previousTip` was.

*Amendment 2026-10-08 (the #83 escalation of task 8.1).* "Constructed with a supplier" left
two things unsaid, and the implementer stopped on both. How the seam receives an eighth input
when `forTask` already has seven: D12 — the supplier is a per-task argument of the factory's
one method; the seven never existed as one lifetime. Where the tip read runs: inside
`EnvironmentLease.environmentFor`, which was `synchronized` across Docker work already — D13
moves the build, and this read with it, into the unlocked phase of the live box. Neither is
recorded as debt: both are in this change.

**D12 — The container-environment seam is split by lifetime, not bundled** (added
2026-10-08; FR19, FR20, M8). `ContainerEnvironments.forTask(baseKey, link, sandbox, timing,
allowlist, guardConfigRoot, ownership)` mixes two lifetimes: `sandbox`, `timing`,
`guardConfigRoot` and the ownership *mode* are fixed for the process; `baseKey`, `link`,
`allowlist`, the project identity and now the denial restoration read vary per task. The same
mixing sits one level up — `ContainerSupportFactory.create` takes `SandboxProperties` and
`FactoryProperties` on every call although `ContainerSupports`, which builds that factory,
holds both as fields; the two container runners and `ContainerTakeSupport` carry them for no
other reason than to relay them; `SandboxLifecyclePassFactory.create` is re-evaluated per run
over the same two sets. The parameter gate fired on the eighth argument; it was right about the
pressure and said nothing about the cause.

*Canon.* Hevery (Google testability guide, "To new or not to new"): never mix injectables and
newables in one constructor — a factory's constructor takes the injectables, its method the
newables. Van Deursen and Seemann (*DI Principles, Practices, and Patterns*): configuration
known at startup is a dependency, per-request values are runtime data and travel through method
calls; constructor over-injection is a smell that points at a missing facade, and a parameter
object that "only moves the parameters to a common root" is not that facade. The project's own
fields-not-parameters clause (`process-invariants.md`) and ADR 0010 ("a factory with one return
type is a facade shape, and is named as one") say the same.

*Decision.* A public `ContainerEnvironmentFactory` in `sandbox/docker`, constructed once per
ownership mode with the installation half — `SandboxProperties`, `BoxTiming` (system clock,
thread sleeper, the docker command bound), the guard config root, `OwnershipMode` — whose one
method `forTask(baseKey, link, allowlist, projectId, Supplier<DenialRestoration>)` builds a
`ContainerEnvironments` (five parameters). The `DockerCli` stays per task, created by the
factory from its timing, as today. `ContainerEnvironmentBuilder` stays the per-task relay
beneath, built by the factory. The static `forTask` is deleted; the package-private
`ContainerEnvironments` constructor stays for daemon-free specs. `ContainerSupports.
supportFactory(mode)` builds the factory and the sandbox lifecycle pass once and hands both,
with the two property sets, to `ContainerRunSupportFactory` as record components; `create`
shrinks to `(cloneDir, taskId, segments, definition, credentialEnvVarsToScrub)` and the
runners and `ContainerTakeSupport` drop the fields they only relayed. ADR 0010's three
questions for the facade: the four members are used together in exactly one construction;
the construction is the behavior (the builder it assembles, the docker seam it bounds); the
result already has a name. Membership sentence: *what the installation decides about every
box before any task exists.*

*Alternatives rejected* — each steelmanned, each with where it breaks here:
- **A parameter object over the five box-build inputs** (the implementer's recommendation).
  For: the five do travel together, and one record closes the gate in one step. Against: they
  recur in exactly two signatures, `forTask` and the `ContainerEnvironmentBuilder`
  constructor — and the builder *is* that object, in field form; the record would exist only to
  be unpacked into it. Fails test 1 of the record criterion (`process-invariants.md`) and is
  the "moves the parameters to a common root" shape. Borrowed: the group's membership, which is
  the factory's field set.
- **An immutable wither `forTask(...).offering(supplier)`.** For: no new type, the restoration
  stays a construction input. Against: the seven-parameter static survives, and the next
  per-task input pressures it again; the design said "at construction" because the offer must
  have no later step, which the wither honours but the lifetime does not.
- **The first production `@ParameterLimitExemption`.** For: honest and greppable. Against: the
  reason it would carry — "two lifetimes in one signature" — is the defect, not a justification.
- **The supplier on `BoxGitLink`.** Against: three box roles share the link and two never
  restore denials; a value two of three holders ignore is a bag component.
- **Compose the offer in `ContainerRunSupport`**, in the lambda the lease already receives as
  its box factory. For: zero new parameters, `sandbox/docker` stays ignorant of the branch.
  Against: the single owner moves into a lambda in `:bootstrap`, enforceable only by a grep for
  `roundEnvironment()`; and the mixed-lifetime static is untouched until the next eighth input.

**D13 — One live box per role, in the three-phase lock shape** (added 2026-10-08; FR21, M9).
`EnvironmentLease.environmentFor` is `synchronized` and runs harvest, dispose and materialize —
Docker work bounded by minutes — under the monitor that `current()` and `currentIfLeased()` take
to read a field. After D11 the branch-tip read joins it. By `lock-scope.md` this is a
state-guarding lock held across blocking work, not the exception: a waiter that only wants the
field disqualifies the exception, and `currentIfLeased` is exactly that waiter (the park's
denial-position read). Today every caller runs on the slot's own thread — the revocation salvage
and the park's cursor read both run after the engine returns — so no thread waits in production;
the shape is wrong before the harm is observable, which is the moment the rule asks to fix it.
`FreshJudgeEnvironments` has the same shape and claims the exception in its javadoc; its claim
holds only because its one other waiter, `disposeCurrent`, wants the same box.

*Canon.* CERT LCK09-J and *JCIP* §11.4.1 (no blocking I/O under a lock). The borrowed shape is
*JCIP*'s `Memoizer` (§5.6) — the claim is a `Future` placed under the lock, the computation runs
unlocked, later callers for the same key wait on the future rather than on the lock — and the
kubelet pod workers' read side, where a reader gets the last recorded status, never the sync in
flight.

*Decision.* `LiveBox<K>` in `sandbox/core`, over the `TaskExecutionEnvironment` port, with a
`Materializer<K>` strategy (the lease's `materialize(branch, null)`; the judge's
`materialize(branch, sha)`): phase 1 under the lock — if the recorded key equals the requested
one, return the box; if a build is in flight for that key, take its future; otherwise record a
new future as the in-flight claim and take the previous box out of the record; phase 2 with
nothing held — dispose the previous box if any (after its harvest, which the lease's caller
performs), build and materialize the new one, complete the future; on any failure complete it
exceptionally and clear the claim, so every waiter fails and the next request may try again;
phase 3 under the lock — record the box and the key, clear the claim. `current()` returns the
last *recorded* box and never waits; it is empty (or throws, as today) while the first build is
in flight. `dispose()` takes the in-flight future under the lock, waits for it unlocked, then
swaps the record out under the lock and disposes with nothing held — constructive before
destructive. Re-entry needs no revalidation beyond the claim: only the claim's owner records,
and `dispose` waits for the claim before it clears, so the record cannot have moved under a
builder. The two classes keep their public surface and lose their monitors; the
`FreshJudgeEnvironments` javadoc's exception claim is retired — waiting for the same box is now
waiting on its future, which is what the exception was for. *Alternative rejected:* leave the
lease's lock as debt for a follow-up change — the D11 read would land under it, a change that
widens a lock classifies it in its own artifacts (`lock-scope.md`, "How this is checked"), and
the pair in D6 means the fix is written once either way.

**D14 — Test processes never inherit the operator's environment** (added 2026-10-08; FR22,
M10). The implementer found a stray `{"question": "Refactor or patch?"}` in its round's decision
file. The chain: the gnome's agent process holds `GNOMISH_DECISION_FILE` for the round; the
Gradle build it ran inherited it; Gradle's `Test` task hands the forked JVM the daemon's
environment by default; `LocalBoxEnvironment` in `:test-fixtures` builds the fake agent's
`ProcessBuilder` on that inherited environment and only layers the command's fragment on top;
`fake-agent.sh` copies a scenario's `decision.json` to whatever `$GNOMISH_DECISION_FILE` names.
The production host adapter clears the environment and composes it through `ChildEnvAllowlist`
(FR9 of `add-sandbox-core`) — the fixture box is laxer than the owner it stands in for, the
shape `testing.md` ("Fixtures assemble through production owners") exists to forbid. The same
inheritance sits in `FakeAgentInvocation` (sets the variable only when a spec passes a path) and
`E2eProcessHarness` (inherits, adds `GNOMISH_HOME`).

*Canon.* Bazel's test encyclopedia: a test gets a fixed baseline environment and "should not
depend on the presence, absence, or value of any environment variable not listed"; Gradle's
`Test.environment` "defaults to the environment of this process"; hermetic tests. The build
already pins `GIT_CONFIG_GLOBAL` for every test JVM and PIT minion for the same reason (design
D11 of `own-git-transfer-argv`).

*Decision.* Two layers, each at its owner. At the spawn sites, an allowlist over a cleared
environment: `LocalBoxEnvironment.exec` composes through the production
`ChildEnvAllowlist.compose(HostTaskExecutionEnvironment.BASE_ENV_NAMES, command.env())` over
`environment().clear()`; `FakeAgentInvocation` and `E2eProcessHarness` clear and put the host
base set, the git test configuration (`GIT_CONFIG_GLOBAL`, `GIT_CONFIG_COUNT/KEY_0/VALUE_0`),
`JAVA_HOME`, the Docker client variables, and the test-owned `GNOMISH_FAKE_*` / `GNOMISH_HOME`
they set themselves. At the build, subtractive: `TestEnvironmentHygiene.applyTo(project, task)`
in `build-logic`, applied by `test-conventions` beside `AdversarialGitConfig.applyTo` to every
`Test` task and to `pitest`, removes every `GNOMISH_*` variable the build did not set from the
forked JVM — subtractive because the test JVM legitimately needs the Docker, Gradle and
Testcontainers variables of the machine, and an allowlist there would be a maintenance list of
the operator's tooling (the posture `fix-docker-exec-secret-argv` chose for the same reason).
Enforcement: `TestEnvironmentHygieneSpec` (`:bootstrap`) asserts `System.getenv()` of the test
JVM holds no `GNOMISH_*` key, and that a fake-agent round run through the fixture box with
`GNOMISH_DECISION_FILE` planted in the fixture's own parent environment leaves that file
untouched; `:bootstrap` architecture spec `ProcessEnvironmentOwnerSpec`: `environment().putAll(`
and `environment().put(` in `*/src/test` and `test-fixtures/src/main` appear only in files that
call `environment().clear()` first, allowlisted by file, asserted reached. Landing: `testing.md`
gains the rule (a human edit, task 6.8, as 6.2 was). *Alternative rejected:* fix
`FakeAgentInvocation` alone — the fixture box is the path the stray write took, and the forked
JVM is where the variable entered; a fix at one of three doors is the patch, not the mechanism.

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
  persistence] → there is one: persistence takes the closed round from the cell the two
  producers fill; `RoundTokenIdentitySpec` pins the name, the subject and the open commit to
  one value on both the live and the resumed path.
- [D11 reads the tip once per box built — one more `git` read on a segment boundary and on
  reattach] → the same read the deleted `restoreDenials()` step made once per run; a segment
  boundary is rare and already harvests and materializes, so the read is noise against it.
- [D11 and D6's dissolved copy widen a change that is already at its scope line] → both touch
  files this change edits anyway (`ContainerResumeOutcomes`, `TakeContainerResumeRunner`,
  `ContainerRunSupport`, the two drives); §8 is three tasks; the cut line stays D4 — if the
  estimate overruns, D4 leaves first, never §8, whose defect is live in production today.
- [D12–D14 (2026-10-08) add three more sections to a change past its scope line] → the human
  chose to take all four findings here rather than open three changes against the same files
  (`ContainerRunSupport`, `ContainerRunSupportFactory`, `EnvironmentLease`, the fake agent).
  Cut order if the estimate overruns, after D4: §11 (D13, the live box) leaves first — the
  lock has no waiter today and the D11 read can land under it with the classification recorded
  as debt; §10 (D12's upper half, the support chain) second — `forTask`'s own split is what
  8.1 needs and stays; §9 (D14) never leaves — it protects the gnome implementing this very
  change from its own test runs.
- [`LiveBox` changes when a judge box is disposed relative to its build] → `dispose` waits for
  the in-flight build and then disposes, the same order the monitor imposed; the concurrency
  spec pins it.
- [A subtractive `GNOMISH_*` strip at the build hides a test that needed one] → the only
  `GNOMISH_*` variables a test legitimately sees are the ones its own spawner sets on the child,
  never on the test JVM; `TestEnvironmentHygieneSpec` turns the assumption into an assertion.
- [The resume path is a second producer the first design missed; a third could appear
  (salvage, `add-stage-iteration`'s per-item passes)] → `CurrentRoundWriterSpec` allowlists
  the cell's writers and `RoundToken.of`'s callers by file, so a new producer fails the build
  until it is added to the D7 row with its own identity assertion.
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
6. Denial restoration at build time (§8, D11) and the one resume preparation (4.7, D6): after
   §7, since both edit the same resume files; before the final gate.
7. Gates and kill-point rows, identity specs (§5); ADR, rule, glossary, review note (§6).
8. (2026-10-08) Test environment hygiene (§9, D14) — right after §8, before any further
   round runs the build inside a gnome: fixture box, fake-agent invocation, E2E harness,
   build-side strip, the two specs.
9. The container support chain's lifetimes (§10, D12 upper half): the factory and the lifecycle
   pass fixed in `ContainerRunSupportFactory`, `create` to five parameters, the relays removed.
10. The live box (§11, D13): `LiveBox<K>`, both leases over it, the concurrency spec, the
    lock-scope owner spec; then 4.4–4.6, §5, §6.
