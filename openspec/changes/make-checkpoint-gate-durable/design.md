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
decision file into the host round — a second medium for the same fact, container-only today
(`decisions/<stage>-a<n>.json` rides the snapshot), with no reader on either medium.

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
- `GitAttemptPersistence ↔ EnvironmentAttemptPersistence`: untouched — `state.json`'s mapping
  is the shared `StateJsonMapper`, one owner for the new tokens.

**D7 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `Advancement.positionAfter(definition, stage)` — the one decision of what a pass leaves as position | `Position` (sealed; `AwaitingApproval` for `MANUAL`) | `StageAttemptLoop.java:157` (the round commit); `Engine.runStages` MANUAL arm (returns the persisted state, no in-memory advance); the approval's "position after the gate" (the AUTO branch, `Advancement.afterGate`, reached only through the public `TaskState.approveGate(definition)`), computed by the four callers that hold the pinned definition: `GitResumeContinuation.resumePaused`, `ContainerResumeOutcomes.resumePaused` (`run`), `HostResumeMechanics.approveCheckpoint`, `ContainerResumeMechanics.approveCheckpoint` (`take`), and handed to the repository as `approved`; `add-stage-finished-event`'s `StagePassed.advancedTo` (reads the same value) | `Advancement.nextPosition(next)` call in `Engine.java:185-187` — deleted; the mode-blind javadoc — rewritten | the sealed `Position` switch (22 readers fail to compile until they name the gate); `AdvancementSpec` mode table; `:bootstrap` grep gate `PositionConstructionGateSpec`: `new Position.AwaitingApproval(` appears in `*/src/main` only in `Advancement.java` and `StateJsonMapper.java` (the wire reader; the repositories' refusal checks pattern-match the variant, they do not construct it) |
| `TaskRepository.approveCheckpoint` — the only writer past a gate | one commit; refuses as `CheckpointApprovalRefusedException` | `GitResumeContinuation.resumePaused`, `ContainerResumeOutcomes.resumePaused` (`run`); `HostResumeMechanics.approveCheckpoint`, `ContainerResumeMechanics.approveCheckpoint` ← `TakeLoadedBranchRoutes` Paused arm (`take`) | "continue with `finalState`" in both `resumePaused` arms and in `TakeLoadedBranchRoutes` → `resumeWithoutDecision` for a `Paused` tip — deleted | the Engine refuses to run from `AwaitingApproval` (D1), so a path that skips the approval cannot make progress; `PositionConstructionGateSpec` above; identity spec `GateApprovalIdentitySpec` (`:bootstrap`, bare origin, both media): after any approval the tip's position is past the gate **iff** its `outcome` is null, in the same commit, and that position is the one following the gate in the pinned definition |
| `TaskRepository.resumeFrom` — the only way a consumed outcome is cleared besides `appendDecision` / `approveCheckpoint` | one commit; refuses when `outcome` already null | `EscalationResume.decide` null-decision path (`run`); `TakeDecisionResume` bare-return arm; `HostResumeMechanics.resumeFrom`, `ContainerResumeMechanics.resumeFrom` ← `TakeLoadedBranchRoutes.resumeWithoutDecision` when the tip's `outcome` is recorded | in-memory `finalState.resetAttempts()` with no write: `TakeDecisionResume.java:69`, `EscalationResume` (formerly `EscalationResumeDialog.java:83`) — replaced by the write; `TakeResumeRunner.resumeWithoutDecision` / `TakeContainerResumeRunner.resumeWithoutDecision` continuing over a recorded outcome — routed through `resumeFrom` first | `:bootstrap` grep gate `OutcomeConsumptionGateSpec`: `resetAttempts()` appears in `*/src/main` only in `TaskState` and the call sites that hand the result to one of the three writers (allowlisted by file); identity spec `ConsumedOutcomeIdentitySpec` (bare origin, both media): after any continuation, `task.json outcome == null` **iff** `state.json` carries the continuation's reset/approved state, one commit apart from the park |
| `AttemptRecord.stop` — the one durable copy of a round's stop | `Stop` (sealed) | writer: `StageAttemptLoop` (DECISION_NEEDED / CANNOT_VERIFY arms); readers: `Engine.preflight`, `StatusReport`/`AttemptMapper` (render) | the in-memory-only `EscalationReport` as the sole carrier of the question — the report is now rebuilt from the record | the type: `AttemptRecord`'s constructor requires a `Stop`; `StageAttemptLoopSpec` asserts the stop on the recorded round for both results |
| `BranchShapeClassifier` with the position among its facts — the one classification of a gate | `BranchShape.AwaitingApproval` | every shape reader (`TakeDispositionResume`, `BranchRepairLog`, `status`, `GitResumeRunner`/`ContainerResumeRunner` routing) | `InProgress` for a tip at a gate — the classifier now reads `BranchTipFacts.position` | the sealed `BranchShape` switch; `BranchShapeClassifierPropertySpec` generates positions |

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

Constructive before destructive: no step here deletes anything. Idempotence: a second pickup
over any frozen state above lands exactly where the first did (the Engine's answer is a
function of the tip; `approveCheckpoint`/`resumeFrom` refuse on a tip they already moved).
Atomicity per medium: every write above is one commit through the medium's existing writer
(worktree commit on host, bare-object ref update in the container). Readers name their medium:
every decision reads the tip (`EnvelopeMediumBoundarySpec`). Kill-point rows added to
`TransitionKillPointSpec`: `GateKillPoints` (host + container; `Paused` and `DecisionNeeded`;
steps 1–4 with a barrier **before** step 2; the recording executor asserts the next stage never
ran; the `manual`-last-stage variant asserts no `Completed` is ever recorded),
`ApprovalKillPoints` (one step; second pickup is a no-op), `ResumedKillPoints` (one step;
second pickup resets nothing). *Shape decision:* a new shape `AwaitingApproval` rather than
"`InProgress` refined by position" — the recovery owners differ (the stage engine *runs* an
`InProgress` tip and *pauses* a gate), and `crash-consistency.md` item 3 forbids two owners for
one shape; `BranchTipFacts` gains the position to make the classification total.

**D9 — `add-stage-iteration` review note.** Its D6 overflow decision request, the window
"cursor exhausted, no stage verdict", and the D8 repair-item adoption after a stage-end failure
are three new instances of the pattern this change closes. The rule lands here (ADR 0003,
`crash-consistency.md` item 12); that change applies it before its apply — a review note is
written into its `design.md` as a task of this change, not a silent assumption.

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
- [Scope: D1–D5 plus D7/D8 is one to two weeks] → cut line: D4 (the resumed write) and its gate
  split into `clear-consumed-outcome` if the estimate overruns; D1–D3 cannot be split without
  leaving the lost-park defect half-fixed.

## Migration Plan

1. Domain first: `Position.AwaitingApproval`, `Stop`, `Advancement`, `Engine.preflight`,
   `StageAttemptLoop`, `TaskState` — every `Position` reader fixed to compile (tasks §1).
2. Wire and repositories: `StateJsonMapper` tokens, `approveCheckpoint`, `resumeFrom`,
   refusal rules, the denial-cursor fix, round-trip specs (§2).
3. Classifier: `BranchTipFacts.position`, `BranchShape.AwaitingApproval`, every shape reader
   (§3).
4. Continuations: `ResumeMechanics` approve/resumeFrom, `run` and `take` arms, terminal
   boundaries re-delivering a lost park, `status` render (§4).
5. Gates and kill-point rows, identity specs (§5); ADR, rule, glossary, review note (§6).
