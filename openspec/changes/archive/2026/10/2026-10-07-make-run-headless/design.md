# Design: make-run-headless

## Context

See `proposal.md` — Why. Facts the approach rests on (state after `remove-interactive-console`):

- `RunnerOutcomeLoop.dispatch` is an exhaustive switch over `TaskOutcome`: `Completed`
  returns, `Aborted` throws `AbortedException` (exit 12), `Paused` and `Escalated` prompt and
  loop. Exit 10/11 exist only as `EscalationEofException` / `CheckpointEofException`, thrown
  from the prompts on EOF and mapped by `RunExitCodeMapper`.
- The resume side already does the durable half headlessly: `GitResumeContinuation.
  resumeEscalated` rebuilds the `Escalated` outcome from `task.json`, runs the same dialog,
  and `recordDecisionIfAppended` writes the decision plus the attempts reset in one commit
  (`TaskRepository.appendDecision`, FR5 of `add-git-workflow`); `ContainerResumeOutcomes`
  calls `appendDecision` directly with the same shape. `take` resets attempts in
  `TakeDecisionResume` (over `ResumeMechanics`), and `TakeResumeRunner` /
  `TakeContainerResumeRunner` share `ResumeDecisionCommit` for the commit itself.
- A park is never recorded by `run` today. `GitModeRunner.run`, `GitResumeContinuation.
  runToTerminalBoundary` and `ContainerTerminalDrive.run` treat a normal return of
  `RunnerOutcomeLoop.run` as `Completed` (read `state.json` back, record, clean up — in the
  container also dispose the box); an EOF exception leaves `task.json` without an outcome on
  purpose. The `escalated`/`paused` records the resume routers switch on are written only by
  `take` (`TakeEngineExecution` through `GitOutcomeRecorder.recordIntent`,
  `TakeContainerEngineExecution` through `ContainerRunTermination.recordPark`) and by spec
  fixtures.
- `take` has the headless precedent: "Escalation parks and exits" with a return-path message;
  a `DecisionNeeded` resumed without a reply parks again restating the question.
- `DialogConsole` is the console owner (`operator-console`, "No direct process-stream writes
  in production code"); its output side (`print`, `printMachine`) is used by every command.
  Its input side (`prompt`, `ask`, `readLine`, the `status` meta-command, the
  `ActivityTracker` hook) is used by the two prompts only.
- `Activity.AwaitingInput` is a sealed variant with a JSON form (`awaitingInput(prompt)`) and
  a text form; the canonical reference document (`status-report-v1.reference.json`) carries
  `verifying`, and the escalations corpus is independent of activity, so no reference file
  changes. `SnapshotActivityTracker` exists only to implement `ActivityTracker`, and
  `ConsoleStatusRenderer` / the `StatusRenderer` port exist only to feed the `status`
  meta-command; both are constructed in `:bootstrap` (`RunAssembler`,
  `ResumeDialogConsoleFactory`).
- The checkpoint line `Stage '<s>' passed. Manual checkpoint reached.` is spelled in five
  production files: the three prompt sites above and `take`'s two park reports
  (`TakePauseExit`, `TakeOutcomeMapper`).

## Goals / Non-Goals

**Goals:**
- One terminal shape for `run`: print, exit by outcome; resume by flags.
- One owner of the terminal render.

**Non-Goals:**
- Multi-line decisions (proposal Q1).
- The pre-park kill window and the stale `outcome` field (see "Crash consistency of the park"
  and Risks): owned by `make-checkpoint-gate-durable`, sequenced after this change.
- Any change to how `take` consumes tracker replies.

## Decisions

**D1 — Terminal outcomes exit by outcome, not by EOF.** `RunnerOutcomeLoop.run` returns
the terminal `TaskOutcome` (`Completed`, `Paused`, `Escalated`); `Aborted` keeps throwing
(its stderr summary and exit 12 are unchanged). The loop itself never exits the process: the
terminal boundary (D8) records the park and then throws one carrier,
`RunParkedException(TaskOutcome)`, the `run` twin of `TakeExitCodeException`;
`RunExitCodeMapper` — an `ExitCodeExceptionMapper`, so it can only read exceptions — maps it
to 11 for `Paused` and 10 for `Escalated`, and `RunExceptionReporting` lists it under
"already reported" (the render went to stdout before the throw). `EscalationEofException` and
`CheckpointEofException` are deleted. *Rationale:* the outcome is data and the boundary
needs it as data (to record the park); the exception exists only to cross the Spring exit
boundary, which is the same shape `take` uses. *Alternative rejected:* two exception types
(`EscalatedException`/`PausedException`) thrown from inside the loop — the loop would then
skip the boundary's park recording, and the types would carry nothing but a code.

**D2 — The resume flags carry what the prompt carried.** `RunArguments` gains
`@Nullable String decision`; `RunArgumentsParser` accepts `--decision=<text>` only with
`--resume` and rejects it with `--mode=in-place` (exit 2). The value travels as a third
argument of `ManualRunners.resume(order, taskId, decision)` into `GitResumeRunner` /
`ContainerResumeRunner` — not as a `RunOrder` field, which `ServeArguments` and
`TakeDispatcher` also build and would have to fill with `null`. `GitResumeContinuation.
resumeEscalated` and `ContainerResumeOutcomes` call the dialog-free successor of
`EscalationResumeDialog` — `EscalationResume.decide(context, escalated, decision)` — which
returns the same `Resumption` for a non-null decision (append + reset) and for a null one
(reset only), and throws `DecisionRequiredException` (mapped to exit 10, the report restated)
for a null decision over a `DecisionNeeded` report (FR4). A `--decision` on a task whose
outcome is not `escalated` is a `UsageException` raised by the resume router after reading
`task.json`, before any branch write. *Rationale:* `take` already treats "returned without an answer" as "park again";
`run`'s branch is its tracker. Two points settled at apply time (2026-10-06): the decision
travels as a `@Nullable String` beside `taskId` on the three resume signatures — the
transposition hazard `process-invariants.md` names is accepted here because the parser is the
value's only producer and the chain has one consumer (D7 row 3); and a `--decision` resume does
not echo the escalation report before the engine runs — the operator has just supplied the
answer, and the restatement render is reserved for the refused `DecisionNeeded` case (FR4).
*Alternative rejected:* resume a `DecisionNeeded` with no
decision and let the gnome ask again — burns a round and a paid call to learn what the
branch already knows.

**D3 — One terminal render.** `TerminalOutcomeRender` (`:application`, `app`) renders the two
stops: `Escalated` → `renderEscalation(report, plane)` (moved here from
`EscalationResumeDialog`; `TakeOutcomeMapper` and `TakeDecisionResume` update their import)
plus the return-path line; `Paused` → `checkpointLine(passedStage)` plus the return-path line.
The return-path line is built from `RunOrder.cloneDir()` and the task id and, for an
escalation, shows `--decision="..."` as an optional suffix. Consumers of the stop render:
`RunnerOutcomeLoop` (in-process), `GitResumeContinuation.resumeEscalated` (host resume),
`ContainerResumeOutcomes` (container resume). Consumers of `checkpointLine` alone:
`TakePauseExit` and `TakeOutcomeMapper`, whose park reports carry the same sentence without a
return-path line for `run`. *Rationale:* the checkpoint sentence has five copies, two of them
in `take`; the rule of three makes the shape an abstraction, and an owner that `take` does
not use would leave two copies outside the gate. *Alternative rejected:* three renders with
`Kept in sync with` markers — the rule forbids it at three; a `run`-only owner with the
`take` copies exempted — the gate would then pin a literal the exempted files still spell.

**D4 — Delete the console's input side.** `DialogConsole` keeps `print` and `printMachine`
with one constructor, `DialogConsole(ConsoleIO)`; `prompt`, `ask`, `readLine`, the
`STATUS_COMMAND` interception, the `StatusRenderer` and `ActivityTracker` collaborators go.
With them go the classes that existed only to feed them: the `ActivityTracker` port and
`SnapshotActivityTracker`, `ConsoleStatusRenderer` and the `StatusRenderer` port (the
`status` subcommand renders through `StatusTextRenderer` directly), and
`ResumeDialogConsoleFactory` in `:bootstrap` (a resume console is `new DialogConsole(io)`).
`Activity.AwaitingInput` is deleted with its JSON (`ActivityDto.AwaitingInput`) and text
arms, the `UntrustedTextGateSpec` allowlist row that names it, and the javadoc mentions in
`StatusEventListener` / `LiveActivity`. The live report goes with them (added 2026-10-06
after task 3.2 found it orphaned): `StatusSnapshotHolder.current(TaskContext)` built the
event-side report only for the `status` meta-command, so it and every holder member that fed
nothing else (the attempt limit and its initial resolution in `RunAssembler`, the held
escalation and outcome) are deleted; the holder keeps the live `TaskState` and activity its
production readers use. The `status-report` requirement "Fields partitioned by derivability" is
restated over the live state instead of a live report — the property it protects (the live
fold never disagrees with the persisted file at a boundary) survives the report's removal.
Taken one step further on 2026-10-06 (task 3.7), after a consumer sweep showed the live side
feeds nothing at all: the held activity is written by `StatusEventListener` and
`AgentActivityEnricher` and read only by the enricher itself, and every production
`StatusReport.build` passes a null attempt limit (the JSON render then emitted `"attemptLimit":
0`). So `Activity`, the activity fold, `AgentActivityEnricher` and the report's `activity` /
`attemptLimit` fields are deleted, the holder keeps only the `TaskState` that
`RunCheckRunContext` reads, and contract v1 drops the two fields as a pre-release amendment (no
release has shipped it). "Fields partitioned by derivability" is removed rather than restated —
no live-only field remains.
*Rationale:* the input choke point existed to intercept
`status` mid-prompt; with no prompt, the `status` subcommand is the only status path, which
the `operator-console` scenario retargets. *Alternative rejected:* leave `prompt` for "a
future dialog" — an escape hatch back to a blocking `run`.

**D5 — In-place mode states the loss.** `ManualRunRunner.IN_PLACE_REMINDER` (`:bootstrap`, printed by `ManualRunDrive`) says that an
escalation or checkpoint ends the task for good in this mode. No in-place resume is added.
*Rationale:* in-place is a dry-run of a stage's config; a stage that escalates in a dry-run
has told the author what they needed. *Alternative rejected:* an in-memory resume loop for
in-place only — that is the prompt again.

**D6 — Sync surfaces: two declared pairs touched, no new pair added.**

- `GitResumeContinuation` ↔ `ContainerResumeOutcomes` (declared at both ends, `Kept in sync
  with`): both `resumeEscalated` arms switch to `EscalationResume.decide` and both
  `resumePaused` arms drop the checkpoint prompt, in the same task (2.3). The marker text on
  both ends is rewritten from "runs the same `EscalationResumeDialog` … prints the same
  checkpoint prompt" to "both resolve the escalation through `EscalationResume` and continue a
  pause without a prompt", so the stated invariant matches the new one. One medium-specific
  step is part of the container arms and not of the host ones: before any branch write or
  round, the container resume disposes the kept box (`support.disposeExistingEnvironment()`),
  because its in-box clone is behind the park commit the factory wrote and the fast-forward-only
  harvest would refuse the next round; the precedent is `TakeContainerResumeRunner.appendDecision`
  (FR17 / D12 of `harden-task-branch-contract`). The host worktree is the branch, so it has no
  equivalent (found and fixed in task 1.5, 2026-10-06). *Decision:* keep the
  declared pair — the arms differ by medium (worktree vs. box) and the shared rule now lives in
  `EscalationResume` and `TerminalOutcomeRender`, so the pair's own content shrinks rather
  than grows.
- `GitModeRunner` ↔ `ContainerGitModeRunner` (declared), and with them `GitResumeContinuation.
  runToTerminalBoundary` ↔ `ContainerTerminalDrive.run` — the terminal boundaries that gain
  the park recording (D8). Both media add the same arm in the same task (1.4); the markers
  gain one line: "both record a park outcome at the terminal boundary and keep the workspace
  for the resume". *Decision:* keep the declared pairs — the recording call differs by medium
  (`GitOutcomeRecorder.recordAndCleanUp` keeps the worktree; `ContainerRunTermination.
  recordPark` + `keepStopped` keeps the box), which is exactly what the pair declares.
- The undeclared five-copy checkpoint sentence is dissolved into `TerminalOutcomeRender`
  (D3) rather than declared. The `TakeResumeRunner` / `TakeContainerResumeRunner` pair is not
  edited: `take` keeps its tracker-reply path.

*Alternative rejected:* a shared `TerminalBoundary<M>` abstraction over the four boundaries —
`GitOutcomeRecorder` and `ContainerRunTermination` already are the per-medium owners, and
the arm added here is one call into each; the abstraction would be the rule of three applied
to two.

**D7 — Single-owner mechanisms.**

| Owner                                                                                                                                                                 | Value (type)                                                                                                                                                                                                           | Consumers                                                                                                                                                                                                                                                       | Old way removed                                                                                                                                                                                                                                                                           | Enforced by                                                                                                                                                                                                                                                         |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `TerminalOutcomeRender` — the one render of an `Escalated`/`Paused` stop, its return-path line, and the checkpoint sentence                                           | `String` block written through `DialogConsole.print` (stop render) or embedded in a park report (`checkpointLine`); a value, not a prompt                                                                              | stop render: `RunnerOutcomeLoop.handlePaused` / `handleEscalated` (`:application`), `GitResumeContinuation.resumeEscalated`, `ContainerResumeOutcomes` escalated arm; `checkpointLine`: `TakePauseExit.finish`, `TakeOutcomeMapper` paused arm                  | the three `console.prompt("Press Enter to continue: ")` sites, `EscalationResumeDialog.handleResumable`'s print + prompt, and the two hand-spelled checkpoint strings in `TakePauseExit` / `TakeOutcomeMapper` — deleted                                                                  | `DialogConsole` has no `prompt`; a `:bootstrap` architecture spec (`TerminalRenderOwnerSpec`) scans `*/src/main` for the literal `Manual checkpoint reached` and the return-path prefix and asserts each appears in exactly one file, `TerminalOutcomeRender.java`  |
| `EscalationResume.decide` — the one place a `run` resume decision becomes a `Resumption` (decision appended, attempts reset, `DecisionNeeded`-without-answer refused) | `RunnerOutcomeLoop.Resumption`                                                                                                                                                                                         | `GitResumeContinuation.resumeEscalated`, `ContainerResumeOutcomes` escalated arm                                                                                                                                                                                | `EscalationResumeDialog.handle` / `handleResumable` (prompt-driven) — deleted. Exemption: `TakeDecisionResume` keeps its own `resetAttempts()` calls — `take`'s decision comes from a tracker reply with an acknowledge protocol (FR12 of `harden-task-branch-contract`), not from a flag | the parameter type: the method takes `@Nullable String decision`, there is no console to ask; `grep -rn "resetAttempts()" */src/main` returns `EscalationResume` and `TakeDecisionResume` only                                                                      |
| `RunArguments.decision()` — the only source of an operator decision in `run`                                                                                          | `@Nullable String` (a primitive; acceptable because the value's only producer is the argument parser and its only consumer is `EscalationResume` through the resume routers — no second source exists to diverge from) | `ManualRunDrive` → `ManualRunners.resume(order, taskId, decision)` → `GitResumeRunner` → `GitResumeContinuation`, and → `ContainerResumeRunner` → `ContainerResumeOutcomes`                                                                                     | stdin (`console.prompt("Decision (empty to resume without one): ")`) — deleted                                                                                                                                                                                                            | `ConsoleIO.readLine` has one production caller; `ConsoleOwnerGateSpec` (existing) plus a new assertion in it that `readLine(` appears in `src/main` only in `SystemConsoleIO` and `ConsoleTakeoverConfirmation`                                                     |
| The `run` terminal boundary — the one place a `run` park becomes durable (D8)                                                                                         | `TaskOutcome.Escalated` / `TaskOutcome.Paused`, recorded through `TaskRepository.recordOutcome`                                                                                                                        | `GitModeRunner.run`, `GitResumeContinuation.runToTerminalBoundary` (host: `GitOutcomeRecorder.recordAndCleanUp`), `ContainerTerminalDrive.run` for both the fresh container run and the container resume (`ContainerRunTermination.recordPark` + `keepStopped`) | "normal return means `Completed`" in all three — replaced by a switch over the returned outcome; the EOF exceptions' deliberate non-recording — deleted with them                                                                                                                         | the return type of `RunnerOutcomeLoop.run` (a boundary that ignores it does not compile under Error Prone's unused-return check); `RunParkRecordingSpec` on a bare origin: after exit 10/11 `task.json` on the branch tip carries the park, for all four boundaries |

**D8 — The `run` terminal boundary records the park.** When `RunnerOutcomeLoop.run` returns
`Escalated` or `Paused`, each boundary records it before exiting: host (`GitModeRunner.run`,
`GitResumeContinuation.runToTerminalBoundary`) through `GitOutcomeRecorder.recordAndCleanUp`,
whose outcome-driven disposal already keeps the worktree for a park (FR6 of
`add-git-workflow`) and whose `recordIntent` already skips the remote reconciliation for one;
container (`ContainerTerminalDrive.run`, reached by the fresh run and by every resume arm)
through `ContainerRunTermination.recordPark` followed by `keepStopped`, the pair `take`
already uses — and never `completeAndDispose` / `finishCleanup`, which belong to `Completed`
alone. Then the boundary throws `RunParkedException(outcome)` (D1). In-place mode records
nothing (D5). `run` has no tracker, so no external write follows the park and none is owed:
the park record carries **no** `trackerWritePending` marker. `TaskRepository.recordOutcome`
takes the expectation as a parameter (`TrackerWrite.OWED` from `take`, which drives the
intent → effect → receipt protocol; `TrackerWrite.NONE` from the two `run` boundaries), so the
marker is never set and then cleared in a second commit — a record that announces a tracker
write nobody will make is the shape the follow-up `make-checkpoint-gate-durable` exists to
remove (revised 2026-10-06; the first implementation of task 1.4 recorded with the marker and
cleared it at once, which left one more kill window and a tip that lied in it). *Rationale:* `--resume` switches on `task.json`'s recorded
outcome; without this record an exit-10 run resumes as an interrupted one — attempts not
reset, `--decision` refused as "not escalated". *Alternative rejected:* leave `task.json`
without an outcome and have `--resume` infer the park from the last attempt's result — a
second reader of the attempt history with its own idea of "escalated", diverging from
`take`'s branch shape.

## Crash consistency of the park (D8; `crash-consistency.md`)

Durable steps of a `run` that parks, in order, after the engine's last round commit (already
pushed by the mid-round push on host, by the in-box protocol in the container):

1. **outcome commit** — `task.json` gains `outcome: escalated|paused` (+ `lastEscalation`),
   on the branch tip, by `TaskRepository.recordOutcome(…, TrackerWrite.NONE)` — no pending
   marker, so there is no receipt step and no receipt window;
2. **push** of that commit by the repository's push decorator (best effort, FR1/FR6 of
   `fix-lifecycle-push`);
3. **workspace keep** — host: nothing to do (the worktree stays); container:
   `keepStopped` (stop the box, dispose judge boxes, reconcile the remote).

| Kill window                        | Branch shape (`task-branch-contract`)                                              | Recovery owner                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Direction                                                             |
|------------------------------------|------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| after the round commit, before 1   | rounds present, no outcome — classifies as `InProgress`, the interrupted-run shape | `GitResumeContinuation.resumeFromRecordedPosition` / `ContainerResumeOutcomes.resumeFromRecordedPosition`: salvage leftovers, continue from the recorded position. **Converges only for `AttemptsExhausted`** — the limit is in the recorded state, so the engine re-parks at once. For `Paused`, `DecisionNeeded` and `CannotVerify` the stop is lost: the round commit already persisted the advanced position (a `MANUAL` pass) or a result whose stop nothing reads back (the question lives only in the in-memory report), so the pickup runs the next stage or re-runs the round — on a `MANUAL` last stage it delivers the task unreviewed. This is a defect of the branch contract older than this change and present in `take` today; it is **owned by the follow-up `make-checkpoint-gate-durable`** ("the verdict rides the round record"), which this change depends on and does not fix | roll forward (`AttemptsExhausted`); **lost** otherwise — out of scope |
| after 1, before 2                  | parked locally, origin behind                                                      | the next resume's bootstrap reconciliation (local ahead of origin continues from local, FR8 of `add-git-workflow`); the terminal-boundary reconciliation of the next completed run delivers the tip                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | roll forward                                                          |
| after 2, before 3 (container only) | parked on origin, box still running                                                | `ContainerRunTermination.sweepOrphans` on the next `run` start (age-governed, `mode=manual` objects); on resume the arm disposes the kept box before continuing (see D6 — `reattach` would reuse a running box as-is, with its in-box clone behind the park commit); the stop itself is `keepStopped`, idempotent on an already-stopped box (corrected 2026-10-06 while writing task 1.5)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | roll forward                                                          |

Constructive before destructive: the outcome commit (1) precedes the box stop (3); nothing
is deleted on a park. Idempotence: `recordOutcome` on a tip already carrying the same park is
a no-op commit-wise (same content) — enforced by the adapters themselves, which make no
commit when `task.json` is unchanged instead of surfacing git's "nothing to commit" (task 2.6,
added 2026-10-06 when a reset-only `--resume` re-parking identically hit that error) — and
`keepStopped` on a stopped box is a no-op — a second
recovery pass over the *same* frozen state changes nothing. "A second `--resume` is a no-op on
the branch" holds only for a `DecisionNeeded` resumed without `--decision` (FR4: restate, no
write); a `--resume` over `AttemptsExhausted` or `CannotVerify` resets attempts and runs a
round by design (FR3, FR4), and a `--resume` over `paused` continues (FR5) — those are the
transition itself, not its recovery. Kill-point specs: `RunParkKillPointSpec` kills after
step 1 for the host (which has no step 3, so that is its only post-commit window), and
`RunParkKillPointContainerE2ESpec` after step 1 and after step 2 for the container, with an
`AttemptsExhausted` escalation and a `paused` park, runs `--resume`, asserts the shape and the
convergence, and that the recovery pass before the transition is a no-op. The window **before**
step 1 is not specced here: for `AttemptsExhausted` it converges today (asserted as a
precondition of `RunParkRecordingSpec`'s resume features), for the other stops it is the
follow-up change's kill-point row. Readers on the recovery path read the branch tip, never the
worktree (`EnvelopeMediumBoundarySpec`).

## Risks / Trade-offs

- [An agent reads the return-path line from stdout but the process wrote the report to the
  log only] → FR1 puts both on stdout; the E2E journey asserts the resume command appears in
  stdout of the exit-10 and exit-11 runs.
- [Removing `Activity.AwaitingInput` changes the sealed hierarchy the JSON reader-side
  specs enumerate] → `StatusReportJsonMapperSpec` gains a variant round-trip that iterates
  `permits` (none existed; added in task 3.2); the reference document carries `verifying`, so no fixture changes; the spec's
  "JSON contract v1" text drops the variant explicitly.
- [`ScriptedConsoleIO` fixtures that fed decision answers] → they become `--decision`
  arguments of a second `resume` call; `RunChainFakes` and the resume routing specs are listed
  in tasks.
- [Deleting the input side leaves `ConsoleStatusRenderer`, the `StatusRenderer` port and
  `SnapshotActivityTracker` without a caller, which the unused-code gate reports as an error]
  → D4 deletes them with the meta-command; `StatusCommand` keeps its own renderer path.
- [A boundary records the park but a spec fixture still plants `task.json` by hand] → the
  fixtures keep working (same record); the new boundary spec drives the real run.
- [In-place escalation loses the run] → D5 states it in the reminder and the guide; the
  agent use case runs git mode by default.

- [A `MANUAL` checkpoint or a `DecisionNeeded` killed between the round commit and the park
  outcome commit is lost: `--resume` reads `InProgress` and continues] → known, older than this
  change, present in `take`; this change narrows its own kill-point claims to the windows it
  owns and names `make-checkpoint-gate-durable` as the owner (design table above).
- [`task.json`'s `outcome` is cleared only by `appendDecision`, so a `paused`/`escalated`
  record survives a resume that continued without a decision until the next terminal write;
  `status` and the shape classifier read it as a live park] → out of scope here; the same
  follow-up change owns it (a "resumed" write that clears the consumed outcome with the reset).

## Migration Plan

1. Land the new shape behind the existing one: `TerminalOutcomeRender`, `EscalationResume`,
   `--decision`, outcome-based exit with the park recorded at every boundary — with the
   prompts still compiled (tasks §1–§2).
2. Switch the loop and both continuations, delete the input side (tasks §3).
3. Docs and the E2E three-process journey (tasks §4).
