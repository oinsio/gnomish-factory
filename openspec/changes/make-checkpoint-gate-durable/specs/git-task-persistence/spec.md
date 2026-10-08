# Spec Delta: git-task-persistence

## MODIFIED Requirements

### Requirement: Task lifecycle port
A `TaskRepository` application-layer port SHALL own task-scoped lifecycle writes: create the task branch and record the task context at start, append a `Decision` on resume, **approve a checkpoint**, **record a resume that consumed an outcome**, and record the `TaskOutcome`/escalation at completion or parking. The approval write SHALL, in one commit, move `state.json`'s position from `AwaitingApproval(stage)` to the position after that stage (the next stage, or the pipeline end after the last), set `task.json`'s `outcome` to null and its pending marker to false, and leave the attempt history untouched; it SHALL refuse without writing when the tip's position is not `AwaitingApproval` of the stage named. The resumed write SHALL, in one commit, replace `state.json` with the reset state handed to it, set `outcome` to null and the pending marker to false; it SHALL refuse without writing when the tip's `outcome` is already null. The STARTED commit SHALL carry both `.gnomish-task/task.json` and an initial `.gnomish-task/state.json` positioned at the first stage with zero attempts, so a resume after a first-round crash finds a readable state. A branch whose tip predates this contract — `task.json` present, `state.json` absent — SHALL classify as a legal shape that resumes the first stage from scratch, never as corrupt. The engine's `AttemptPersistence` port SHALL remain unchanged; the git adapter SHALL implement both ports over the same task branch. In sandboxed mode these lifecycle commits SHALL be created factory-side over bare git objects in the factory clone — no working copy, no checkout, no hook execution; the branch ref SHALL advance atomically only if the tip is unchanged, and a stale tip fails the write without force. Recording an outcome SHALL NOT require a live environment. On resume, the decision, approval or resumed commit SHALL be created before the environment is materialized, so the working copy contains it from the start. Host mode keeps its worktree commits unchanged.
<!-- implements FR3, FR7, FR8, NFR-R2 of make-checkpoint-gate-durable -->
<!-- implements FR1, FR2 of add-git-workflow; FR25 of add-sandbox-core; FR3 of harden-task-branch-contract -->

#### Scenario: Start creates the branch with the task context
- **WHEN** a git-mode run starts for a new task
- **THEN** the task branch is created and its first commit adds both `.gnomish-task/task.json` with the task context and an initial `.gnomish-task/state.json` at the first stage with zero attempts

#### Scenario: Approval is one commit past the gate
- **WHEN** the approval write is called for a tip whose position is `AwaitingApproval(stage)`
- **THEN** one commit lands whose `state.json` position is the stage after it (or the pipeline end), whose `task.json` outcome is null and marker false, and whose attempt history equals the tip's

#### Scenario: Approval of a non-gate tip refuses
- **WHEN** the approval write is called for a tip whose position is `AtStage`, the pipeline end, or a gate of another stage
- **THEN** no commit lands and the refusal names the tip's actual position

#### Scenario: Resumed write clears the outcome with the reset
- **WHEN** a continuation consumes a recorded `escalated` outcome without a decision
- **THEN** one commit lands carrying the reset `state.json` and `task.json` with `outcome` null and marker false, and no tip exists with one but not the other

#### Scenario: A first-round crash resumes from the initial state
- **WHEN** a run dies infrastructurally before any round completes and the task is resumed
- **THEN** resume reads the initial `state.json` from the STARTED commit and continues the first stage, with no crash loop and no unreturnable park

#### Scenario: A pre-contract tip is a legal shape
- **WHEN** resume finds a branch tip holding `task.json` but no `state.json`
- **THEN** the tip classifies as a legal pre-contract shape and the run resumes the first stage from scratch

#### Scenario: Parking records the outcome
- **WHEN** a run ends with `Escalated`
- **THEN** the outcome and escalation report are committed to `task.json` before the process exits

#### Scenario: Decision commit precedes materialization
- **WHEN** a sandboxed task is resumed with a human decision, an approval, or a consumed outcome
- **THEN** the lifecycle commit is created factory-side on the harvested tip and the environment is materialized after it, so the in-box clone already contains it

#### Scenario: Abort outcome needs no environment
- **WHEN** a sandboxed task aborts after a boundary violation
- **THEN** the aborted outcome is committed factory-side on the last harvested tip and the kept environment is not touched

#### Scenario: Concurrent tip movement fails the write
- **WHEN** another factory instance moves the task branch between the tip read and the ref update
- **THEN** the lifecycle write fails without force and no existing commit is lost

### Requirement: Outcome protocol in task.json
`outcome` SHALL be null while a visit is in progress and SHALL be reset to null by the commit that begins each resumed visit — the decision commit, the approval commit, or the resumed commit — never by a later round's commit and never left to the next terminal write. These three SHALL be the only writers of `outcome: null`. Each of the three SHALL also remove `.gnomish-task/decisions/` in that same commit (a no-op where the directory is absent), so a consumed decision request leaves the tip with the transition that consumed it; the removal is hygiene — no reader SHALL depend on it, since a request is live only under its round's token (see the decision-file protocol). `lastEscalation` SHALL be kept separately from `outcome` so the last question/answer stays visible after resume; it is display data and SHALL drive no recovery decision.
<!-- implements FR7, FR8, FR14 of make-checkpoint-gate-durable -->
<!-- implements FR5 of add-git-workflow -->

#### Scenario: Parked and interrupted are distinguishable
- **WHEN** a resumed task's process dies mid-stage
- **THEN** `task.json` shows outcome null (interrupted) while a parked task shows its recorded outcome

#### Scenario: A checkpoint continuation clears the park
- **WHEN** a `paused` task is approved and the process dies before the next round commits
- **THEN** the tip's `task.json` outcome is null and its position is past the gate

#### Scenario: A reply-less return clears the park
- **WHEN** a task parked as `escalated(AttemptsExhausted)` is returned without a reply, picked up, and the process dies before the next round commits
- **THEN** the tip's `task.json` outcome is null and `state.json` carries the reset attempt counter, so the next pickup resets nothing a second time

#### Scenario: The consumed request leaves with the answer
- **WHEN** a container-mode task parked on a decision request is answered
- **THEN** the decision commit carries the answer, the reset state and no `.gnomish-task/decisions/` entry, and the tip before it still carries the request

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation, whose `cannotExecute` kind additionally carries the denials of the round that could not execute), `state.json` (its initial version written once by `TaskRepository` as part of the STARTED commit; afterwards written by exactly two factory-side components with disjoint windows — the git `AttemptPersistence` for every round write, and `TaskRepository` for the lifecycle transitions of the Task lifecycle port (the approval write moves the position past the gate; the resumed write replaces the state with the reset one), each in its own commit outside any round: version, position, attemptsUsed, attempts[] {round, result, startedAt, stop, checks[], denials[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1), `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the path names the round within one visit of the stage — a repeated visit overwrites it at the tip and git history keeps every version), and — in git modes — `decisions/<stage>-a<attempt>-<token>.json` (written only by the gnome; the single gnome-writable path under `.gnomish-task/`, named by the round token per the decision-file protocol). The gnome writes `state.json` never.
<!-- implements FR13 of make-checkpoint-gate-durable -->
<!-- implements FR3 of add-git-workflow -->
<!-- implements FR23 of add-sandbox-core -->
<!-- implements FR4 of fix-denial-report-attachment -->
<!-- implements FR3 of harden-task-branch-contract -->
<!-- implements FR2 of fix-denial-attribution-durability -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

#### Scenario: The request is named by its round
- **WHEN** two rounds of the same stage and attempt number run in one task — an answered question restarts the stage, or a killed round is retried
- **THEN** their decision paths differ by the round token, and the later round's path is not present on the tip it opens on

### Requirement: Gnome commits within a round
Gnome commits inside a round SHALL be allowed (encouraged via stage instructions, using plain git); the adapter's commit closes the round. Boundary verification SHALL run factory-side against harvested refs in sandboxed mode: history rewrite is refused by the fast-forward-only harvest itself; `.gnomish-task/` SHALL be untouched by the gnome between tips — with exactly one carve-out, the current round's token path `decisions/<stage>-a<attempt>-<token>.json` (FR23 of add-sandbox-core); a decision file under any other name is a violation like any other `.gnomish-task/` write; the in-box HEAD check before the snapshot commit is advisory only. In host mode the existing worktree checks (HEAD on the task branch, previous tip an ancestor, `.gnomish-task/` untouched) remain. A violation breaks durability: persist SHALL throw, aborting the task, with the evidence kept on the branch and in the kept environment.

Boundary verification SHALL distinguish three outcomes, never two: clean,
violated, and **cannot-verify**. A git invocation that fails while producing
the evidence (non-zero exit, unreadable revs) SHALL classify as
cannot-verify — an infrastructure failure that aborts the round without
burning a stage attempt and without attributing a violation to the gnome —
and SHALL never be read as clean. Both boundary-check media (worktree diff
and harvested-ref check) SHALL implement the same three-outcome rule.
<!-- implements FR13, FR16 of make-checkpoint-gate-durable -->
<!-- implements FR12 of add-git-workflow -->
<!-- implements FR21, FR23 of add-sandbox-core -->
<!-- implements FR13 of harden-logging-observability -->

#### Scenario: Fine-grained gnome history is preserved
- **WHEN** the gnome makes three commits during a round
- **THEN** the round-closing commit builds on them and all four commits reach the branch

#### Scenario: History rewrite aborts
- **WHEN** at the round boundary the previous tip is no longer an ancestor of the branch
- **THEN** persist throws (host) or the ff-only harvest refuses (sandboxed) and the task ends Aborted

#### Scenario: Decision request is the one permitted state-directory write
- **WHEN** a gnome commit adds the current round's token path under `.gnomish-task/decisions/` and touches nothing else under `.gnomish-task/`
- **THEN** boundary verification passes; any other `.gnomish-task/` change still aborts

#### Scenario: A failed boundary probe never passes as clean
- **WHEN** the git invocation backing the boundary check exits non-zero
  (damaged repo, bad rev) while its output stream is empty
- **THEN** the check reports cannot-verify, the round aborts as an
  infrastructure failure with the git failure as evidence, no stage attempt
  is burned, and no boundary violation is attributed to the gnome

#### Scenario: A request under another round's name is a violation
- **WHEN** a gnome commit adds a file under `.gnomish-task/decisions/` whose name is not the current round's token path
- **THEN** boundary verification fails as a `.gnomish-task/` modification

#### Scenario: A resumed round is checked against its recorded token
- **WHEN** an interrupted verification is resumed from a snapshot commit and the resuming instance lands the round's state commit
- **THEN** the boundary check's carve-out and diff base are the token the snapshot's subject records — not a re-read of the tip, which on this path is the snapshot itself — and a persist with no round identity in hand throws rather than falling back to the tip

### Requirement: State-file JSON contract v1
`task.json` and `state.json` SHALL carry `"version": 1` and follow status-report v1 conventions (camelCase, ISO-8601 UTC, millisecond durations, sealed types via `"type"`). `state.json`'s position SHALL be `atStage(stage)` | `awaitingApproval(stage)` | `pipelineEnd`; each recorded attempt SHALL carry a `stop` object — `none` | `decisionNeeded(question, options)` | `cannotVerify(check, reason, details)` — with the field absent read as `none`. Both additions are pre-release amendments under version 1: no released branch carries either token, and a build older than this amendment reading a newer tip fails closed as an unreadable envelope rather than resuming past the gate. Readers SHALL ignore unknown fields; an unknown version SHALL refuse resume and the inspection commands (`status`/`usage`) alike, with a clear error naming the file and the unsupported version. Status-report DTOs SHALL NOT be reused; a contract test SHALL hold the StatusReport rendered from state files equivalent to one rendered from live events, anchored by `status-report-v1.reference.json`.
<!-- implements FR10 of make-checkpoint-gate-durable -->
<!-- implements FR4 of add-git-workflow -->

#### Scenario: Unknown version refuses resume
- **WHEN** `state.json` carries `"version": 2`
- **THEN** resume stops with an error naming the file and the unsupported version

#### Scenario: Every position and stop variant round-trips
- **WHEN** each `Position` variant and each attempt stop variant is written to `state.json` and read back
- **THEN** the value read equals the value written, for every variant the sealed hierarchies permit, with no hand-listed subset

#### Scenario: Equivalence with the live report
- **WHEN** the same task history is rendered from events and from the persisted files
- **THEN** the two StatusReports are equivalent per the reference contract

### Requirement: One logical transition, one commit
Every logical transition of a task SHALL become durable as exactly one commit on the task branch; mutually-implied fields SHALL never split across commits. In particular: a human decision lands in the same commit as the attempt-counter reset it implies; a passing round lands in the same commit as the position it leaves the task at — the next stage after an `auto` pass, the gate after a `manual` pass; a stop a round produces lands in the same commit as the round that produced it; the approval that opens a gate lands in the same commit as the position past it and the cleared outcome; a consumed outcome is cleared in the same commit as the reset it implies; a container park's outcome lands in the same commit as its pending marker. A kill between any two commits therefore never freezes a half-applied transition, and never a tip that permits what no commit has yet allowed.
<!-- implements FR1, FR3, FR5, FR7 of make-checkpoint-gate-durable -->
<!-- implements FR4 of harden-task-branch-contract -->

#### Scenario: Decision and attempt reset are one commit
- **WHEN** a resume decision is recorded
- **THEN** the commit carrying the decision also carries the reset attempt counter, and no tip exists with one but not the other

#### Scenario: Pass and advancement are one commit
- **WHEN** a round's verification passes
- **THEN** the commit recording the passing round also records the position the pass leaves the task at — the following stage for `auto`, the gate for `manual` — so a kill after it never re-runs the green stage and never runs past an unopened gate

#### Scenario: Stop and round are one commit
- **WHEN** a round ends in `DecisionNeeded` or `CannotVerify`
- **THEN** the commit recording the round carries the stop's content, and a tip holding the round without its stop does not exist

#### Scenario: Park outcome and marker are one commit
- **WHEN** a container-mode task parks
- **THEN** its outcome and the pending marker land in one commit, and no tip shows the outcome without the marker or the marker without the outcome

### Requirement: Denial cursor rides the escalation write
`task.json` SHALL carry the environment's denial read position as it stood after an escalation's denials were drained — the same opaque-position-plus-source-identity shape `state.json` records with an attempt — written in the same lifecycle commit as the escalation it belongs to, through the shared atomic writer, so the durable position can lag the record carrying its denials but never lead it. Whether a lifecycle write reads the environment's cursor SHALL be decided from the report of the outcome being recorded alone — never from the `lastEscalation` carried over from the previous tip, which a later `Paused`, `Completed` or `Aborted` write preserves for display only. Like `state.json`'s cursor, the field is environment bookkeeping, not task state: it SHALL influence no reconstructed field and never appear in `status.json`. It is additive under contract v1: absent means "no cursor to resume from". Writing it is best-effort: an environment that cannot answer its cursor at park time writes none and never fails the park. An instance resuming the task SHALL be offered the newest source-matching committed position across `state.json` and `task.json` at the branch tip.
<!-- implements FR9 of make-checkpoint-gate-durable -->
<!-- implements FR3, FR5, NFR-R1 of fix-denial-attribution-durability -->

#### Scenario: The drained position is committed with the escalation
- **WHEN** a round dies before its close, its denials are drained onto a `cannotExecute` escalation, and the task is parked
- **THEN** `task.json` carries the position advanced by that drain, written together with the escalation and its denials

#### Scenario: A carried-over escalation does not move the cursor
- **WHEN** a tip carries a `cannotExecute` escalation from an earlier visit and a later visit records `Paused`, `Completed` or `Aborted`
- **THEN** the write reads no environment cursor, preserves the tip's committed cursor unchanged, and logs no cursor warning

#### Scenario: Resume prefers the newest committed position
- **WHEN** a resumed task's branch tip carries an attempt cursor in `state.json` and a newer escalation cursor in `task.json`, both naming the surviving guard container
- **THEN** the restore offers the escalation's position, and the first read after the resume starts past the drained denials

#### Scenario: An unanswerable cursor never fails the park
- **WHEN** the environment cannot answer its denial cursor while a `cannotExecute` escalation is being recorded
- **THEN** `task.json` records the escalation and its denials without a cursor and the park succeeds
