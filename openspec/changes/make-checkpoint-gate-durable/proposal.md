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

A third defect, found on 2026-10-07 by the implementation of this change, shares the same
cause and is made reachable by it. In container mode the gnome's question has a second copy
beside the round record: the decision file `.gnomish-task/decisions/<stage>-a<attempt>.json`,
which the round's snapshot commit carries onto the branch and nothing removes until the
`Completed` cleanup. The human's answer resets the stage to attempt 0, so the next round has
the same name; the next box clones a tip that still carries the old file, reads it as a new
question, and the task parks again — after every answer, forever. The name was meant to make
stale files "self-excluding", but the pair (stage, attempt) is reset by design: by an answer,
by advancement, by a start of stage, by a killed round's retry; a stage re-entered through
pipeline routing will repeat it routinely. The reader asks "is the file there?" of a medium
that keeps everything — the discriminator that a per-round temp directory supplied by being
deleted was lost when the transport moved onto the branch. The canon's answer is one
mechanism: the request carries an identity minted by the orchestrator that no later round
repeats (Step Functions' task token, EIP's Correlation Identifier, Kleppmann's fencing token),
the receiver accepts only the current identity, and deletion is hygiene, never the judge.

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
- **ADDED**: the *round token* — a round's identity, minted by the factory when the round
  opens and repeated by no later round of the task; in container mode the decision request is
  named by it and read only under it.
- **MODIFIED**: the decision-file protocol in git modes — the path the gnome is given carries
  the round token; the adapter reads exactly that path and nothing else, so a request carried
  over on the branch from any earlier round is not a request.
- **MODIFIED**: the three outcome-clearing writes (decision, approval, resumed) remove the
  `decisions/` directory in the same commit — the consumed request leaves the tip with the
  transition that consumed it.
- **MODIFIED**: an interrupted verification resumed from a snapshot that carries the round's
  decision request re-raises that request instead of reporting the round `Completed`.
- **MODIFIED** (added 2026-10-07, same defect class as the round token — found by the sibling
  audit of the #83 escalation): the recorded denial position is offered to a container round
  environment **as part of building it**, read from the branch tip at that moment by the one
  component that builds environments — not by a separate `restoreDenials` step whose ordering
  against the resume's reattach is nobody's. Today the reattached box of a resumed task never
  receives it and replays the guard's whole denial log.
- **MODIFIED** (added 2026-10-07): the container resume preparation — pending-snapshot check,
  discard or reattach, salvage — has one implementation shared by `run` and `take`, in place
  of two copies.
- **ADDED**: ADR 0003 gains the principle and its corollary for readers of a medium that keeps
  the past (liveness by identity, never by presence); `crash-consistency.md` gains a checklist
  item; the glossary gains *gate*, *awaiting approval*, *approval*, *resumed write*, *round
  token*.

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
  protocol in task.json" (three writers of `outcome: null`; each removes `decisions/`),
  "State-file JSON contract v1" (new tokens, version stays 1), "One logical transition, one
  commit" (the gate and the approval as transitions), "Denial cursor rides the escalation
  write" (own report only), "State directory with one writer per file" (the decision path
  carries the round token), "Gnome commits within a round" (the carve-out names that path).
- `agent-executor`: "Decision-file protocol" (the path carries the round token; only that
  path is read; a resumed interrupted verification re-raises the snapshot's request).
- `tracker-take`: "Escalation parks and exits" (a returned checkpoint is approved before the
  run continues; a return without a reply writes the reset), "Any instance can pick up a
  returned task" (the approval is on the branch, not in the picker's memory).
- `manual-run`: "Resume invocation" (as `make-run-headless` left it): paused → approve, then
  continue; escalated without `--decision` → resumed write, then continue.
- `status-report`: "JSON contract v1, state-derived" (as `make-run-headless` left it): the
  `awaitingApproval(stage)` position and the attempt `stop` object; a consumed outcome reads
  null.
- `execution-environment`: "Denial read position survives the process" (the offer is made as
  the environment is built, on every path that builds one; no later offer exists).

## Goals

- G1: no kill window between a round commit and a park commit can skip a `manual` checkpoint,
  deliver a task past one, or lose an escalation's content — on either medium.
- G2: a consumed outcome never survives the transition that consumed it; the attempt budget a
  resume grants is granted once, durably.
- G3: the compiler, not a convention, keeps every reader of a position aware of the gate.
- G4: a decision request written by an earlier round is never read as the current round's,
  on any medium and however the attempt counter moves (answer, advancement, retry,
  re-entry).

## Non-Goals

- NG1: the tracker-side receipt defects found by the same audit (a park re-driven after a
  fresh claim; a finished task whose cleanup never converges; `run --resume` of a completed
  task that never cleans) — `fix-terminal-receipt-convergence`.
- NG2: giving recorded rounds a stage identity (FR9 of `harden-task-branch-contract`); the gate
  position names its stage, which is all this change needs.
- NG3: a human-facing approval UI beyond the existing return-to-ready and `--resume`.
- NG4: changing how `AttemptsExhausted` or `CannotExecute` recover — both are correct today.
- NG5: a monotonic round sequence number persisted in `state.json` — it would need a commit
  at round open, a new durable step with its own kill windows; the branch tip at round open
  is already an identity no later round repeats.
- NG6: giving the trace path `attempts/<stage>/<round>/trace.jsonl` the round's identity — a
  repeated round overwrites the previous trace at the tip, but git history keeps every
  version and no reader at the tip exists; the javadoc claim is corrected here, the path is
  left for `add-round-identity` if a tip reader ever appears.

## Users & Scenarios

- U1: **A pipeline author** marks a release stage `manual`. The factory dies right after the
  stage passes. On restart the task is still waiting for them — not deployed.
- U2: **An operator** answers a gnome's question a day later. The question is on the branch
  and in the tracker report whether or not the process that raised it survived to park.
- U3: **A maintainer** reads `status` of a task that crashed twice mid-stage: the attempt
  counter shows the attempts actually spent, and the outcome field is null, not a park from
  two visits ago.
- U4: **An operator** answers a container-mode task's question. The next round runs with the
  answer; it does not park again on the question just answered.

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
- FR13: every container-mode round SHALL be identified by a *round token* minted by the
  factory when the round opens — the task branch's tip commit at that moment — that no later
  round of the task repeats; the decision path handed to the gnome SHALL be
  `.gnomish-task/decisions/<stage>-a<attempt>-<token>.json`, and the adapter SHALL read
  exactly that path after the round: a file under any other name, however it came to be on
  the tip, SHALL change nothing. A resumed round SHALL reuse the token its snapshot recorded
  and SHALL be checked against it exactly as a live round would be; no token is minted on
  resume, and no step of a round SHALL re-read the branch tip to recover a token it was handed.
- FR14: the three outcome-clearing writes (`appendDecision`, the approval, the resumed write)
  SHALL remove `.gnomish-task/decisions/` in the same commit, on both media (a no-op where the
  directory is absent).
- FR15: an interrupted verification resumed from a snapshot commit SHALL re-raise the
  decision request that snapshot carries under the round's token as `DecisionNeeded`, read
  from the snapshot's tree, never from a working copy; a snapshot carrying none SHALL resume
  as `Completed`, as today.
- FR16: the harvested boundary carve-out SHALL name the token path and nothing else under
  `.gnomish-task/`.
- FR17: the denial position the branch tip records SHALL be offered to a container round
  environment as part of building it — read from the tip at that moment by the one component
  that builds environments — on every path that builds one: the first round's open, a segment
  boundary, and a resume's reattach. No separate offering step SHALL exist, and the run
  support port SHALL expose no way to offer a position after an environment is built.
- FR18: the container resume preparation — pending-snapshot check, discard or reattach,
  salvage of leftovers — SHALL have one implementation, used by both `run` and `take`.

### Non-Functional Reliability

- NFR-R1: every kill window of the gate, the approval and the resumed write SHALL freeze a
  named shape with one recovery owner; the kill-point matrix SHALL gain rows for the gate
  (host and container, `Paused` and `DecisionNeeded`, with a barrier **before** the park
  commit asserting that the next stage never ran and that the second pickup is a no-op), the
  approval, and the resumed write, including a `manual` last stage.
- NFR-R2: the approval and the resumed write SHALL be idempotent: repeated on a tip that no
  longer matches, they refuse and change nothing.
- NFR-R3: PIT stays at 100% in every touched module.
- NFR-R4: a decision file of any name left on the tip by an earlier round SHALL change
  nothing on any pickup — answered, returned without a reply, retried after a kill, or
  re-entered — and the kill window "after the snapshot carrying a request, before the state
  commit" SHALL re-raise that request without re-running the round.

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
- M5: `ContainerModeResumeE2ESpec` and `SandboxLifecycleZombieE2ESpec` green through the
  `--decision` answer path, with the stale-file scenario the fake agent plays today.
- M6: `grep -rn "decisionPath(\|DECISIONS_DIR" */src/main adapters/*/src/main` returns the
  allowlisted owner files only; the identity spec shows the request's name, the snapshot
  subject and the round's open tip to be one value.
- M7: `grep -rn "restoreDenials" */src/main adapters/*/src/main sandbox/*/src/main` returns
  the environment port, its adapters and the environment builder only — no run-support port,
  no drive, no engine execution; a resumed container task whose guard survived with a
  recorded cursor reports no denial twice.

## Impact

- `:domain`: `Position`, `Advancement`, `Engine` (preflight), `StageAttemptLoop`,
  `AttemptRecord` (stop payload), `TaskState`, `StageResult`, `BranchShapeClassifier`/
  `BranchShape`/`BranchTipFacts`.
- `adapters/git`: `GitTaskRepository`, `GitObjectsTaskRepository`,
  `PushBestEffortTaskLifecycleStore`, `StateJsonMapper`/`StatePositionDto`/`StateAttemptDto`,
  `BranchTipFactsReader`, `TaskBranchLister`, `TaskJsonMapper` (outcome clearing); for the
  round token: `BranchDecisionFile`, `HarvestedBoundaryCheck`, `SandboxRoundEnvironmentSource`,
  `EnvironmentRoundSnapshot`, `EnvironmentAttemptPersistence`, `ServiceCommitMessages`,
  `SnapshotTipCheck`, `PendingVerification` (`:application`, port), both repositories
  (`decisions/` removal); `RoundToken`, `ClosedRound` and the per-run `CurrentRound` cell in
  `app/port/git` (`:application`), replacing `AttemptCommitRef`;
  `RecordedAttemptCommitWorkspace` (`:application`) reads the cell.
- `adapters/agent`: `ResumeVerificationStageExecutor` (re-raises the snapshot's request and
  restores the round's identity from it).
- `:bootstrap` wiring: `ContainerRunSupport` (one cell instead of two refs; the lease's
  environment factory reads the recorded denials at build time; `restoreDenials()` removed),
  `ContainerRunSupportFactory`, `ContainerTipReader` (the read becomes a supplier),
  `ExecutorAdapterSelector` (hands the cell to the resume executor).
- `sandbox/docker`: `ContainerEnvironments` (the `restoreDenials` setter and field removed;
  `roundEnvironment()` takes the offer from a supplier given at construction).
- `:application`, denial restoration and resume preparation: `SandboxRunSupport` port
  (`restoreDenials()` removed), `ContainerTerminalDrive`, `TakeContainerEngineExecution` (the
  call removed), new `ContainerResumePreparation` used by `ContainerResumeOutcomes` and
  `TakeContainerResumeRunner`.
- `:test-fixtures`: the fake agent's `decision-then-plain` scenario (reads the path from
  `$GNOMISH_DECISION_FILE`, never a spelled name).
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
  `TakeResumeRunner ↔ TakeContainerResumeRunner`, the host/container lifecycle repositories,
  `DecisionFileTransport ↔ BranchDecisionFile` (read semantics diverge by design: a per-round
  temp file on the host, a token-named branch path in the container).
- Active changes whose deltas this change layers under: `add-pipeline-entry-precondition`
  (also MODIFIES "State directory with one writer per file" — sequenced after this change,
  its delta gains the layered preamble), `add-decision-arbiter` (changes the request's
  content schema, not its path — informational note).

## Open Questions

- Q1: should the approval be keyed by a round number or claim epoch in addition to the gate
  stage, so an approval meant for one visit cannot open the gate after a later retry of the
  same stage? Deferred: a `manual` stage that passed does not retry, so the stage name is the
  key today; revisit with `add-stage-iteration`.
- Q2: should the host transport adopt the round token too, so both media share one
  decision-file contract? Deferred: the host's per-round temp file is deleted after the read
  and has no defect; unifying is a transport change of its own.
