# Proposal: make-run-headless

Sequenced after `remove-interactive-console`, which removes the human-plays-a-role adapters;
this change removes the two prompts that remain in `gnomish run`.

## Why

The real user of `gnomish run` is an AI agent debugging a pipeline stage: a human asks for a
stage to be configured, the agent edits `.gnomish/`, runs the task, reads the outcome, fixes
and re-runs. That agent drives `run` as a subprocess. Two places in `run` still block on
stdin waiting for a human: the escalation decision prompt ("Decision (empty to resume
without one):") and the checkpoint confirmation ("Press Enter to continue:"). For a
subprocess-driving agent a blocking prompt is a hang until its tool timeout, or a stdin
script it must write in advance for a question it has not seen yet. `take` and `serve`
already have the right shape — an escalation parks the task and the process exits with a
return-path message; there is no in-run decision wait (`tracker-take`, "Escalation parks and
exits"). `run` has no tracker, so its branch and its exit code must play that role.

What the two prompts drag along: `DialogConsole.prompt`/`ask`, the `status` / `status --json`
meta-command intercepted below the prompts, the `awaitingInput` activity in the live status
report, the two EOF exceptions (`EscalationEofException`, `CheckpointEofException`) that are
today the only way exit codes 10 and 11 are ever produced, and five hand-synchronized copies
of the checkpoint line — three with a prompt (`RunnerOutcomeLoop`, `GitResumeContinuation`,
`ContainerResumeOutcomes`) and two without, in `take`'s park reports (`TakePauseExit`,
`TakeOutcomeMapper`) — that no registry row declares.

One fact the headless shape depends on and today's `run` does not provide: a park is never
written to the branch. `GitModeRunner`, `GitResumeContinuation` and `ContainerTerminalDrive`
record only `Completed`/`Aborted`; an EOF at a prompt deliberately records nothing, leaving
the task as an interrupted run. Once `Escalated`/`Paused` end the process, the branch must
carry the outcome the way `take`'s park does, or `--resume` has nothing to read.

## What Changes

- **MODIFIED** **BREAKING**: an `Escalated` outcome in `run` ends the process: the report is
  printed, the outcome is recorded on the task branch (git mode) as a park, the process exits
  10 with a return-path line naming the resume command. No decision prompt.
- **MODIFIED** **BREAKING**: a `Paused` outcome in `run` ends the process: the outcome is
  recorded on the task branch as a park, checkpoint line, exit 11. `--resume` continues from
  the advanced position with no confirmation.
- **ADDED**: the `run` terminal boundary — host fresh run, host resume, container run and
  resume — records a park outcome (`escalated` with `lastEscalation`, or `paused`) through the
  task repository before the process exits, keeping the worktree or the stopped box for the
  resume, exactly as `take` records its parks.
- **ADDED**: `gnomish run --resume <task> --decision="<text>"` appends the operator decision
  to the branch (author `operator`, current stage, now), resets attempts and continues —
  exactly what a non-empty answer at the prompt did. `--resume` without `--decision` on an
  escalated task continues with no decision — what the empty answer did — except for a
  recorded `DecisionNeeded`, which restates the question and exits 10 again without burning
  an attempt, mirroring `take`.
- **REMOVED**: `DialogConsole.prompt` and `ask`, the in-dialog `status` meta-command, the
  `awaitingInput` activity variant, `EscalationEofException`, `CheckpointEofException`, and
  the `ActivityTracker.markAwaitingInput` seam. `ConsoleIO.readLine` keeps one production
  reader, `ConsoleTakeoverConfirmation`.
- **MODIFIED**: exit codes 10 and 11 are produced from the terminal outcome itself, the way
  `take` maps its result, not from an EOF exception.
- **MODIFIED**: the five checkpoint/escalation terminal renders collapse into one owner used
  by the in-process loop, both resume continuations and `take`'s two park reports.
- **MODIFIED**: in-place mode's start reminder states that an escalation or checkpoint ends
  the task for good, since there is no branch to resume from.
- **MODIFIED**: `operator-guide-run.md` gains the agent-facing protocol: exit code → next
  command, with `--decision`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `manual-run`: "Outcome loop with in-process resume", "Resume invocation", "Agent-raised
  decisions reach the operator unchanged", "Status command and auto-summaries", "English
  dialog with forgiving input", "EOF semantics without a TTY" (layered on
  `remove-interactive-console`'s delta), "Run modes" (in-place reminder), "Single-task dialog
  invocation" (the `--decision` flag joins the flag matrix).
- `status-report`: "JSON contract v1" loses the `awaitingInput` activity variant; version
  stays 1 (pre-release amendment, nothing ever emitted it outside a prompt).
- `operator-console`: "Machine-readable console output is verbatim" loses the in-dialog
  meta-command scenario; the `--json` command path is the only machine path left.

## Goals

- G1: every `gnomish run` process terminates without reading stdin.
- G2: the escalate → decide → resume loop is expressible as three process invocations an
  agent can script from the exit code alone.
- G3: one render of "the task stopped here and this is how to continue", shared by the
  in-process loop, both resume continuations and `take`'s park reports.

## Non-Goals

- NG1: changing `take`/`serve` behavior or the takeover confirmation.
- NG2: a `--decision` for `take` (decisions arrive through the tracker there).
- NG3: removing `DialogConsole` as the console owner for output; only its input side goes.
- NG4: renumbering exit codes.

## Users & Scenarios

- U1: **An AI agent iterating on a stage** runs the task, gets exit 10 with the question on
  stdout, edits the stage or forms an answer, runs `--resume --decision=...`, gets 11 at the
  checkpoint, inspects the branch, runs `--resume`, gets 0.
- U2: **A pipeline author at a terminal** reads the same lines; the resume command is printed
  ready to paste.
- U3: **A maintainer** edits one terminal render instead of five copies.

## Requirements

### Functional

- FR1: `Escalated` in `run` SHALL print the escalation report and a return-path line naming
  `gnomish run --dir <dir> --resume <task> [--decision="..."]`, then exit 10; in git mode
  the run SHALL record the `Escalated` outcome on the task branch before exiting, so the
  branch carries `escalated` and `lastEscalation` for the resume; in in-place mode the run is
  lost (stated up front).
- FR2: `Paused` in `run` SHALL print the checkpoint line naming the passed stage and the
  resume command, then exit 11; in git mode the run SHALL record the `Paused` outcome on the
  task branch before exiting.
- FR3: `--resume <task> --decision="<text>"` SHALL be accepted only with `--resume`, only for
  a task whose recorded outcome is `escalated`, and SHALL append the decision to the branch
  and reset `attemptsUsed` in the same commit before the engine continues, as the prompt path
  does today.
- FR4: `--resume <task>` without `--decision` on an escalated task SHALL continue with
  attempts reset and no decision appended, except when the recorded report is
  `DecisionNeeded`: then the question is restated, no attempt is burned, and the process
  exits 10.
- FR5: `--resume <task>` on a paused task SHALL continue from the advanced position with no
  prompt; on a completed task it SHALL report and exit 0 as today.
- FR6: no production code SHALL call `ConsoleIO.readLine` except the takeover confirmation;
  `DialogConsole.prompt`, `ask`, the `status` meta-command, `ActivityTracker.markAwaitingInput`
  and `Activity.AwaitingInput` are deleted; with them the live status side that fed only the
  meta-command — the live report, the activity fold, `AgentActivityEnricher`, and the report's
  `activity` / `attemptLimit` fields (contract v1, pre-release amendment).
- FR7: exit codes 10 and 11 SHALL be derived from the terminal `TaskOutcome` the run ended on;
  the two EOF exception types are deleted.
- FR8: the terminal render (report + return-path line) SHALL have one owner used by the
  in-process loop, the host resume continuation, the container resume continuation, and —
  for the checkpoint line — `take`'s pause park and its outcome mapper.
- FR10: the park recording of FR1/FR2 SHALL run at every `run` terminal boundary — host
  fresh run, host resume, container fresh run, container resume — keeping the worktree (host)
  or the stopped box (container) for the resume, and SHALL never run the `Completed`
  cleanup for a park.
- FR9: `--decision` without `--resume`, or with `--mode=in-place`, or on a task whose outcome
  is not `escalated`, SHALL be a usage error (exit 2) naming the conflict.

### Non-Functional Reliability

- NFR-R1: the decision commit and the attempts reset land in one commit (crash-consistency
  rule item 4), unchanged from today's `appendDecision`.
- NFR-R2: PIT stays at 100%; deleting the prompts leaves no orphaned guard.
- NFR-R3: the park recording is crash-consistent for the windows this change introduces: every
  kill window between the park's outcome commit and the process exit freezes a branch shape the
  `task-branch-contract` capability already names, with one recovery owner, and recording a park
  twice is a no-op (`crash-consistency.md`, items 1–3, 8, 10). The window between the last round
  commit and the park's outcome commit converges only for `AttemptsExhausted`; for a checkpoint
  and the other escalation kinds it is a pre-existing defect of the branch contract (also in
  `take`), owned by the follow-up change `make-checkpoint-gate-durable`, which this change
  depends on for the full claim.

### Non-Functional Observability

- NFR-O1: the return-path line is on stdout and in the log at INFO with the task id, so an
  agent reading either can find the resume command.

## Operator Experience Criteria

- UX1: the last lines of an escalated or paused run are self-sufficient: what stopped, why,
  and the exact command to continue.
- UX2: the guide has a section "Driving `run` from a script or an agent" with the exit-code →
  next-command table.

## Success Metrics

- M1: the console is read in production only by `ConsoleTakeoverConfirmation` and its owner
  `SystemConsoleIO` — the `readLine` sites `ConsoleOwnerGateSpec` allows, the others being the
  port's declaration and subprocess-output readers.
- M2: `grep -rn "Press Enter" */src/main` returns nothing; `grep -rln "Manual checkpoint
  reached" */src/main` returns one file.
- M3: the packaged-jar reference journey runs as three processes with empty stdin, exits
  10, 11, 0 in order.

## Impact

- `:application`: `RunnerOutcomeLoop`, `EscalationResumeDialog`, `GitResumeContinuation`,
  `ContainerResumeOutcomes`, `GitModeRunner`, `ContainerGitModeRunner`,
  `ContainerTerminalDrive`, `GitResumeRunner`, `ContainerResumeRunner`, `TakePauseExit`,
  `TakeOutcomeMapper`, `TakeDecisionResume` (import), `DialogConsole`, `ActivityTracker`
  (deleted), `SnapshotActivityTracker` (deleted), `ConsoleStatusRenderer` and the
  `StatusRenderer` port (deleted — they served the meta-command only), `Activity`,
  `ActivityDto`, `StatusLineFormatter`, `StatusReportJsonMapper`, `StatusEventListener` and
  `LiveActivity` (javadoc), `RunArguments(Parser)`, `RunExitCodeMapper`,
  `RunExceptionReporting`; `:bootstrap`: `ManualRunRunner` (in-place reminder),
  `ManualRunDrive` and `ManualRunners` (decision plumbing), `RunAssembler` and
  `ResumeDialogConsoleFactory` (console construction without a tracker),
  `ContainerRunTermination`/`SandboxRunSupport` (park recording for `run`),
  `UntrustedTextGateSpec` (allowlist row), E2E journeys; `:test-fixtures`:
  `ScriptedConsoleIO` stays for output-asserting specs.
- Sequenced after `remove-interactive-console`; its delta on "EOF semantics without a TTY"
  is the base this change's delta is written over.

## Open Questions

- Q1: Should `--decision` also accept `--decision-file=<path>` for multi-line answers? Deferred;
  a one-line flag matches the tracker-comment shape `take` consumes.
