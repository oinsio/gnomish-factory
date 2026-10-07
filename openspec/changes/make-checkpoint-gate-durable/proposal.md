# Proposal: make-checkpoint-gate-durable

Sequenced after `make-run-headless`, whose design names this change as the owner of the kill
window before a park's outcome commit, and before `add-stage-iteration` is applied, whose
item-level verdicts must follow the rule this change introduces.

## Why

A task branch today can say "continue" before anyone has allowed it to. When a stage marked
`manual` passes, the engine's round commit already records the position **past** that stage;
the fact "stop here and wait for a human" is written only afterwards, as a separate park
commit. If the process dies between the two — a window of milliseconds, but a real one — the
next pickup reads an ordinary interrupted run and runs the next stage. On a `manual` **last**
stage it reads `PipelineEnd`, records `Completed` and, in `take`, posts the tracker finish:
the task is delivered without the review its author asked for. The same shape loses a
`DecisionNeeded`: the round commit records that the gnome asked, but not *what* it asked;
the question lives only in the in-memory report that the lost park would have carried, so
the pickup re-runs the round and pays for it again. The defect is older than
`make-run-headless`, is present in `take` and `serve` today, and no kill-point row covers the
window: `ParkKillPoints` starts at the outcome commit. The 2026-10-06 audit
(`crash-consistency.md`, items 2 and 4) found the window classifies as `InProgress` — a
shape whose recovery owner is right for every history that produces it except these.

A second, quieter defect shares the cause. `task.json`'s `outcome` is cleared only by
`appendDecision`. Every other way of continuing a parked task — a checkpoint, a blank
answer, a return without a reply, an infrastructure retry — leaves the old `paused(X)` or
`escalated` on the tip until the next terminal write happens to overwrite it. Meanwhile the
attempt reset those paths imply exists only in memory, so a kill mid-run makes the next pickup
walk the decision path again and reset the counter again: **every crash grants a fresh
attempt budget**. The stale record also hides an interrupted container verification behind
the resume arms that skip it, and makes `status` and the shape classifier report a live park
that is not one.

The canon is unanimous about the rule being broken — write-ahead logging, Kleppmann's dual
write, Helland, the saga pivot — and every comparable orchestrator (Temporal, Argo, GitLab,
GitHub Actions) keeps the wait inside the same write as the completion, or makes it a property
of the next step. None of them has a post-mortem of a gate skipped after a restart; systems
that write it this way do not produce the bug class.

## What Changes

- **ADDED**: a third pipeline position, *awaiting approval* — the passing round of a `manual`
  stage records the position **at the gate**, not past it. A run starting from a gate returns
  `Paused` without executing anything.
- **ADDED**: the *approval* write — the only way a task moves past a gate: one commit that
  advances the position and clears the recorded outcome. `run --resume` of a paused task and
  `take`'s pickup of a returned checkpoint perform it before continuing.
- **ADDED**: the stop a round produces rides the round record — a `DecisionNeeded` round
  carries its question and options, a `CannotVerify` round its check, reason and details — and
  the engine re-raises the escalation from the record on the next run, exactly as it already
  re-raises `AttemptsExhausted` from the counter. The park commit becomes pure tracker
  delivery; losing it loses nothing.
- **ADDED**: the *resumed* write — one commit that clears a consumed outcome and persists the
  attempt reset it implies, for every continuation that today resets in memory.
- **MODIFIED** **BREAKING**: the `stage-engine` advancement contract — `manual` no longer
  "returns Paused with the position already advanced past the paused stage"; a resume from a
  paused state no longer "starts at the next stage" by itself, the approval does.
- **MODIFIED**: the branch-shape set gains `AwaitingApproval` (a `manual` stage passed, the
  gate not opened); `InProgress` no longer covers that history.
- **MODIFIED**: `state.json` v1 gains the `awaitingApproval` position token and a per-attempt
  `stop` object; `task.json` is unchanged in shape. The version stays 1 (pre-release
  amendment).
- **MODIFIED**: the container `recordOutcome` derives its denial-cursor decision from the
  outcome being recorded, never from the `lastEscalation` carried over.
- **MODIFIED**: `status` renders the gate ("awaiting approval after `<stage>`") and never a
  stale park.
- **ADDED**: ADR 0003 gains the principle; `crash-consistency.md` gains a checklist item; the
  glossary gains *gate*, *awaiting approval*, *approval*, *resumed write*.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stage-engine`: "Advancement modes" (manual pause parks at the gate), "Resume from any valid
  state" (a gate returns Paused; a pass-recorded resume is the `auto` case only), new
  requirement "The stop rides the round record".
- `lifecycle/task-branch-contract`: "Total branch-shape classification" (eleventh shape
  `AwaitingApproval`), "One recovery owner per shape" (its owner), new requirement "A recorded
  position never implies an authorisation not yet recorded".
- `git-task-persistence`: "Task lifecycle port" (approval and resumed writes), "Outcome
  protocol in task.json" (three writers of `outcome: null`), "State-file JSON contract v1"
  (new tokens, version stays 1), "One logical transition, one commit" (the gate and the
  approval as transitions), "Denial cursor rides the escalation write" (own report only).
- `tracker-take`: "Escalation parks and exits" (a returned checkpoint is approved before the
  run continues; a return without a reply writes the reset), "Any instance can pick up a
  returned task" (the approval is on the branch, not in the picker's memory).
- `manual-run`: "Resume invocation" (as `make-run-headless` left it): paused → approve, then
  continue; escalated without `--decision` → resumed write, then continue.
- `status-report`: "JSON contract v1, state-derived" (as `make-run-headless` left it): the
  `awaitingApproval(stage)` position and the attempt `stop` object; a consumed outcome reads
  null.

## Goals

- G1: no kill window between a round commit and a park commit can skip a `manual` checkpoint,
  deliver a task past one, or lose an escalation's content — on either medium.
- G2: a consumed outcome never survives the transition that consumed it; the attempt budget a
  resume grants is granted once, durably.
- G3: the compiler, not a convention, keeps every reader of a position aware of the gate.

## Non-Goals

- NG1: the tracker-side receipt defects found by the same audit (a park re-driven after a
  fresh claim; a finished task whose cleanup never converges; `run --resume` of a completed
  task that never cleans) — `fix-terminal-receipt-convergence`.
- NG2: giving recorded rounds a stage identity (FR9 of `harden-task-branch-contract`); the gate
  position names its stage, which is all this change needs.
- NG3: a human-facing approval UI beyond the existing return-to-ready and `--resume`.
- NG4: changing how `AttemptsExhausted` or `CannotExecute` recover — both are correct today.

## Users & Scenarios

- U1: **A pipeline author** marks a release stage `manual`. The factory dies right after the
  stage passes. On restart the task is still waiting for them — not deployed.
- U2: **An operator** answers a gnome's question a day later. The question is on the branch
  and in the tracker report whether or not the process that raised it survived to park.
- U3: **A maintainer** reads `status` of a task that crashed twice mid-stage: the attempt
  counter shows the attempts actually spent, and the outcome field is null, not a park from
  two visits ago.

## Requirements

### Functional

- FR1: a passing round of a stage with `manual` advancement SHALL record the position
  `AwaitingApproval(stage)` in the round commit, never `AtStage(next)` or `PipelineEnd`.
- FR2: a run starting from `AwaitingApproval(stage)` SHALL return `Paused(stage)` without
  invoking any execution, verification or persistence port, on every medium and every entry
  point (fresh run, `run --resume`, `take` pickup).
- FR3: the task repository SHALL offer an *approval* write that, in one commit, moves the
  position from `AwaitingApproval(stage)` to the position after that stage, clears `outcome`,
  clears the pending marker and leaves the attempt history untouched; it SHALL refuse when the
  tip's position is not `AwaitingApproval(stage)` for the stage named, writing nothing.
- FR4: the approval SHALL be performed by `run --resume` of a paused task and by `take`'s
  pickup of a returned checkpoint before the engine continues; no other path SHALL write a
  position past a `manual` stage.
- FR5: a round recorded as `DECISION_NEEDED` SHALL carry the question and options; a round
  recorded as `CANNOT_VERIFY` SHALL carry the check, reason and details — in `state.json`, as
  untrusted-text carriers.
- FR6: a run starting from a state whose last recorded round carries a stop SHALL return the
  matching `Escalated` outcome from the record, without invoking execution or verification
  ports, exactly as it does today for a spent attempt limit.
- FR7: the task repository SHALL offer a *resumed* write that, in one commit, clears `outcome`,
  clears the pending marker and persists the reset `TaskState`; every continuation that today
  consumes a recorded outcome without `appendDecision` SHALL perform it before the engine
  continues: `run --resume` without `--decision` over an escalation, `take`'s return without a
  reply, and the infrastructure-class returns.
- FR8: `appendDecision`, the approval write and the resumed write SHALL be the only writers of
  `outcome: null`; no component SHALL reset attempts in memory without one of them having
  landed.
- FR9: the container-medium `recordOutcome` SHALL decide the denial-cursor read from the report
  of the outcome being recorded, never from the `lastEscalation` carried from the previous tip.
- FR10: `state.json` SHALL serialize the new position as `awaitingApproval(stage)` and the
  per-attempt stop as a `stop` object with a `type` discriminator; the round-trip spec SHALL
  iterate every `Position` and every stop variant; `"version"` stays 1.
- FR11: the branch-shape set SHALL gain `AwaitingApproval` — a tip whose `state.json` position
  is a gate and whose `outcome` is null or stale — with the stage engine as its recovery owner
  and "re-deliver the park" as its recovery; the classifier's facts SHALL carry the position.
- FR12: `status` (text and JSON) SHALL render a gate as "awaiting approval after `<stage>`"
  and SHALL show `outcome: null` for a task whose park was consumed.

### Non-Functional Reliability

- NFR-R1: every kill window of the gate, the approval and the resumed write SHALL freeze a
  named shape with one recovery owner; the kill-point matrix SHALL gain rows for the gate
  (host and container, `Paused` and `DecisionNeeded`, with a barrier **before** the park
  commit asserting that the next stage never ran and that the second pickup is a no-op), the
  approval, and the resumed write, including a `manual` last stage.
- NFR-R2: the approval and the resumed write SHALL be idempotent: repeated on a tip that no
  longer matches, they refuse and change nothing.
- NFR-R3: PIT stays at 100% in every touched module.

### Non-Functional Observability

- NFR-O1: the approval and the resumed write SHALL each be one INFO line with the task id and
  the stage; a refused approval SHALL be one WARN with its catalog code naming the tip's actual
  position.

### Non-Functional Cost

- NFR-C1: no path SHALL re-pay an executor round to recover a stop the record already carries
  (the cost the lost-park re-run spends today).

## Operator Experience Criteria

- UX1: a task waiting at a gate reads the same everywhere — `status`, the `run` stop render,
  the `take` park report: "Stage '<s>' passed. Awaiting approval." with the resume path.
- UX2: a human who approves once is never asked to approve the same gate again unless they
  rejected it.

## Success Metrics

- M1: `TransitionKillPointSpec` green with the new rows on the bare-origin fixtures, including
  the `manual`-last-stage case.
- M2: `grep -rn "new Position.AwaitingApproval(" */src/main` returns `Advancement.java` and
  `StateJsonMapper.java` only; `grep -rn "resetAttempts()" */src/main` returns the
  three outcome-clearing writers' call sites only.
- M3: `ResumeMatrixSpec`'s "a post-pause resume starts at the next stage" is rewritten to pass
  through the approval; `TakeResumeRunnerWithoutDecisionSpec` asserts the resumed commit.
- M4: a `status --json` document for a resumed-then-killed task shows `outcome: null`.

## Impact

- `:domain`: `Position`, `Advancement`, `Engine` (preflight), `StageAttemptLoop`,
  `AttemptRecord` (stop payload), `TaskState`, `StageResult`, `BranchShapeClassifier`/
  `BranchShape`/`BranchTipFacts`.
- `adapters/git`: `GitTaskRepository`, `GitObjectsTaskRepository`,
  `PushBestEffortTaskLifecycleStore`, `StateJsonMapper`/`StatePositionDto`/`StateAttemptDto`,
  `BranchTipFactsReader`, `TaskBranchLister`, `TaskJsonMapper` (outcome clearing).
- `:application`: `TaskRepository` port, `ResumeMechanics` and both mechanics,
  `GitResumeContinuation`, `ContainerResumeOutcomes`, `TakeResumeRunner`,
  `TakeContainerResumeRunner`, `TakeDecisionResume`, `TakeLoadedBranchRoutes`,
  `TakeReconcile`, `EscalationResume` (from `make-run-headless`), `TerminalOutcomeRender`,
  `StatusReport`/`StatusTextRenderer`/`StatusReportJsonMapper`, `SummaryAccumulatorListener`,
  `TaskSummaryAssembler`, `HeartbeatProgress`, `MdcEventListener`, `ResumeDecisionCommit`,
  `TakeFinishReport`, `status/json/PositionDto`.
- `:bootstrap`: `RunCheckRunContext`, `ContainerRunTermination`, kill-point worlds and rows,
  the two new grep gates, `UntrustedTextGateSpec` allowlist.
- Docs: `docs/adr/0003-crash-consistency.md`, `.claude/rules/crash-consistency.md`,
  `docs/glossary.md`, `openspec/changes/add-stage-iteration/design.md` (review note).
- Declared sync pairs touched: `GitResumeContinuation ↔ ContainerResumeOutcomes`,
  `TakeResumeRunner ↔ TakeContainerResumeRunner`, the host/container lifecycle repositories.

## Open Questions

- Q1: should the approval be keyed by a round number or claim epoch in addition to the gate
  stage, so an approval meant for one visit cannot open the gate after a later retry of the
  same stage? Deferred: a `manual` stage that passed does not retry, so the stage name is the
  key today; revisit with `add-stage-iteration`.
