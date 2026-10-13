# Spec Delta: git/git-task-persistence

## ADDED Requirements

### Requirement: Factory register ref
The factory SHALL keep, per task, the ref `refs/gnomish-factory/<task>` in origin and in its clone, naming its latest closing commit: STARTED, a round's state commit, a snapshot, an outcome, an approval, an appended decision, a salvage, the cleanup. Every closing push SHALL push the task branch and the register in one atomic push; a mid-round push or harvest SHALL move the branch alone. Every touchpoint fetch of the branch SHALL fetch the register with it, in one atomic transfer.
**Implements**: FR13, NFR-S2 of tighten-spec-contract
**Notes**: The register is the factory's trust anchor on the branch: a commit id is content-addressed, and only a factory closing push moves the ref (design D13 of tighten-spec-contract).
**Notes**: In host mode the gnome shares the clone and can move the ref; the host tier carries no integrity claim (design D19), and the register there is a detection.

#### Scenario: Branch and register land together
- **WHEN** a closing commit is pushed
- **THEN** origin's task branch and register both name the new commit, or neither moved — a refusal of either ref lands nothing

#### Scenario: A mid-round push leaves the register
- **WHEN** a mid-round push or an admitted harvest pushes the branch
- **THEN** the register at origin and in the clone still names the previous closing commit

#### Scenario: The register follows every closing commit
- **WHEN** any factory closing commit lands — STARTED, state, snapshot, outcome, approval, decision, salvage or cleanup
- **THEN** after its push the register and the branch tip are the same commit, on a bare origin, for every closing path of both media

#### Scenario: Killed between admission and push
- **WHEN** an instance dies after the admission moved the branch locally and before its push
- **THEN** the next pickup fetches the register and the branch from origin, the register names the last closing commit, and the next harvest re-admits the range as an ordinary open round

#### Scenario: The touchpoint fetch carries the register
- **WHEN** a pickup, a status read or a reconcile fetches the task branch from origin
- **THEN** the same transfer fetches the register, and a missing or non-ancestor register classifies the branch `Unknown` rather than being guessed

#### Scenario: A rejected branch update leaves the register unmoved
- **WHEN** a touchpoint fetch's branch refspec is rejected by the clone (origin's branch is not a descendant of the clone's tracking ref)
- **THEN** neither the tracking ref nor the register moves, and the fetch reports the rejection

#### Scenario: The reconcile brings the register forward
- **WHEN** the clone's task branch equals or is ahead of origin's, and origin's register is on that line while the clone holds no register or an ancestor of origin's
- **THEN** the reconcile moves the clone's register to origin's in one ref transaction that verifies the branch unmoved

#### Scenario: A register ahead of origin's is never rolled back
- **WHEN** a closing push was lost and a later branch-only push made origin's branch equal to the clone's, so the clone's register is ahead of origin's
- **THEN** the reconcile leaves the clone's register naming its own closing commit

### Requirement: Factory-side branch-tip observation
In sandboxed mode the factory SHALL observe the task branch's tip in the environment by a poll, run and rate-limited on the factory side.
**Implements**: FR5 of add-sandbox-core; FR6, NFR-S1, UX3 of own-git-transfer-argv; FR4 of tighten-spec-contract
**Notes**: Split from "Harvest protocol".
**Notes**: No event-driven tip detection exists: the poll runs on the agent's progress events only. A future detection is constrained to only wake the rate-limited poll — the event itself triggers no harvest or push — and to watch the reflog (`.git/logs/HEAD`) rather than ref files, because refs may be silently packed into `packed-refs`.

#### Scenario: Tip observation is a rate-limited factory-side poll
- **WHEN** the gnome moves the branch tip inside the environment repeatedly within one poll interval
- **THEN** the factory observes the tip by its own poll, at most once per interval

## MODIFIED Requirements

### Requirement: Host envelope reads resolve at the branch tip
In host mode, every read of a factory-owned `.gnomish-task/` file that a decision rests on — the resume bootstrap's `task.json`, the recorded `state.json` a resume or a completion reads back, and the current content a lifecycle rewrite carries forward (the pending-write marker clear, the decision append, the denial-cursor carry-forward) — SHALL resolve at the branch tip the task worktree has checked out, never at the working-tree file.
**Implements**: FR1, FR2, FR5, FR9, NFR-R2 of fix-envelope-medium
**Notes**: The working tree is where the next envelope version is staged for its commit; it is not a medium any reader consults for the current one.
**Notes**: The one spelling of the envelope's path names (the state directory, its two files, the decisions subdirectory) is `EnvelopePaths` in `:domain`, the lowest module every reader depends on.

#### Scenario: A stale worktree does not change what resume reads
- **WHEN** a previous process left the worktree without `.gnomish-task/` (a staged removal it
  never committed) while the branch tip still carries a `Completed` envelope
- **THEN** resume reads the `Completed` envelope from the tip, classifies the branch as a
  finished task awaiting cleanup, and routes to the completion finish — not to the delivered
  path

#### Scenario: A half-written worktree file is never carried forward
- **WHEN** a lifecycle rewrite runs over a worktree whose `state.json` differs from the tip's
- **THEN** the field it carries forward (the denial cursor) is the tip's value, and the
  commit that lands is what the tip plus the rewrite imply

#### Scenario: Absence and unavailability are distinct answers
- **WHEN** the tip carries no `task.json`
- **THEN** the reader reports absence and the caller routes to the delivered recovery
- **WHEN** the read is cut off before git exits
- **THEN** the reader reports unavailability and no route is taken on that answer

#### Scenario: Every medium addresses the envelope by one spelling
- **WHEN** the host tip reader, the container tip reader and the shape classifier name the
  envelope's files
- **THEN** all three resolve the same path constants, so a renamed envelope file changes one
  declaration and every reader follows

#### Scenario: A hand-typed envelope path fails the build
- **WHEN** a production source outside the owner spells `.gnomish-task`, `task.json` or
  `state.json` as a literal
- **THEN** the build fails naming the file and the owner to use instead

### Requirement: Task lifecycle port
A `TaskRepository` application-layer port SHALL own task-scoped lifecycle writes: create the task branch and record the task context at start, append a `Decision` on resume, **approve a checkpoint**, **record a resume that consumed an outcome**, and record the `TaskOutcome`/escalation at completion or parking. The engine's `AttemptPersistence` port SHALL remain unchanged; the git adapter SHALL implement both ports over the same task branch.
**Implements**: FR3, FR7, FR8, NFR-R2 of make-checkpoint-gate-durable; FR1, FR2 of add-git-workflow; FR25 of add-sandbox-core; FR3 of harden-task-branch-contract

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

#### Scenario: Resumed write on a cleared outcome refuses
- **WHEN** the resumed write is called for a tip whose `task.json` outcome is already null
- **THEN** no commit lands and the write refuses

#### Scenario: Sandboxed lifecycle commits run on bare objects
- **WHEN** a lifecycle write runs for a sandboxed task
- **THEN** its commit is created factory-side over bare git objects in the factory clone, with no working copy, no checkout and no hook executed
- **WHEN** a lifecycle write runs for a host-mode task
- **THEN** its commit is made in the task worktree

### Requirement: Round commit of the whole working tree
In host mode a round SHALL be persisted as one commit of the entire working tree: gnome changes, the updated `state.json`, and the round trace together; no separate state-only service commit exists for host-mode rounds. In sandboxed mode a round SHALL close per the snapshot-first protocol instead: a snapshot commit of the working tree inside the environment, then — after verification — a separate state commit carrying `state.json` and the round trace.
**Implements**: FR2, NFR-R1 of add-git-workflow; FR21 of add-sandbox-core

#### Scenario: Host round atomicity
- **WHEN** a host-mode round finishes and persist runs
- **THEN** exactly one new commit contains the gnome's file changes, `state.json`, and `attempts/<stage>/<round>/trace.jsonl`

#### Scenario: Sandboxed round closes as snapshot plus state
- **WHEN** a sandboxed round finishes and verification completes
- **THEN** the branch gains a snapshot commit with the gnome's file changes and a separate state commit with `state.json` and the round trace

#### Scenario: A persist failure aborts the task
- **WHEN** persisting a round fails, in host or sandboxed mode
- **THEN** the task aborts through the strict port instead of continuing past an unrecorded round

### Requirement: Task branch naming and base
The task branch SHALL be named `gnomish/` + the sanitized taskId: every character outside `[A-Za-z0-9._-]` replaced by `-`, consecutive `-` collapsed, leading/trailing `.`/`-` stripped; an empty result or `.lock` suffix rejects the taskId. The authoritative taskId lives inside `task.json` — never parsed back from the ref name. The branch SHALL be created from the base ref the resolution decision names (see base-ref-resolution).
**Implements**: FR2, FR7 of add-git-workflow; FR4, FR6, FR8, FR10, FR15 of add-base-ref-resolution; UX3 of add-base-ref-resolution; FR15 of add-base-ref-resolution; FR15 of add-base-ref-resolution; FR15, NFR-S1 of add-base-ref-resolution

#### Scenario: Unsafe characters sanitized deterministically
- **WHEN** the taskId is `PROJ 42: fix/it`
- **THEN** the branch is `gnomish/PROJ-42-fix-it` while `task.json` keeps the original id

#### Scenario: Manual run stays offline
- **WHEN** `gnomish run` starts a task without `--base` in a clone with no
  reachable remote
- **THEN** the branch is created from the local HEAD with no fetch, no
  remote query, and no new failure mode

#### Scenario: One start-point owner
- **WHEN** any of the four fresh-start paths (host/container × run/take)
  creates a task branch
- **THEN** the start point comes from the shared resolution decision, and no
  path-local default substitutes for it

#### Scenario: A stale local branch does not redirect the base
- **WHEN** the clone's local `main` is behind origin's `main` and a task
  resolving to `main` is claimed
- **THEN** the task branch's first parent, the law commit, and the pinned
  SHA are all origin's refreshed `main` tip, and the local `main` is neither
  read nor moved

#### Scenario: An origin-only branch starts the task
- **WHEN** the resolved base is a branch that exists on origin and under no
  local ref of the clone
- **THEN** the refresh delivers it and the task branch is created from the
  delivered commit — no "base ref did not resolve" failure follows a
  successful fetch

#### Scenario: A planted local tag does not redirect the base
- **WHEN** the clone holds a local tag carrying the base branch's name and
  pointing elsewhere, and a task resolving to that branch is claimed
- **THEN** the task branch starts from origin's refreshed branch tip, not
  from the tag's commit

#### Scenario: An explicit base overrides the resolution
- **WHEN** a path that accepts `--base <ref>` is started with it
- **THEN** the resolution decision names that ref and the task branch starts from its commit

#### Scenario: No path pulls
- **WHEN** any run or take path creates, resumes or finishes a task branch
- **THEN** the runner issues no `git pull`

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly `task.json`, `state.json`, the round traces `attempts/<stage>/<round>/trace.jsonl` and — in git modes — the decision requests `decisions/<stage>-a<attempt>-<token>.json`. `task.json` SHALL be written only by `TaskRepository` and `state.json` only by factory-side components; a decision request, named by the round token per the decision-file protocol, is the single gnome-writable path. The gnome writes `state.json` never.
**Implements**: FR13 of make-checkpoint-gate-durable; FR3 of add-git-workflow; FR23 of add-sandbox-core; FR4 of fix-denial-report-attachment; FR3 of harden-task-branch-contract; FR2 of fix-denial-attribution-durability

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

#### Scenario: The request is named by its round
- **WHEN** two rounds of the same stage and attempt number run in one task — an answered question restarts the stage, or a killed round is retried
- **THEN** their decision paths differ by the round token, and the later round's path is not present on the tip it opens on

#### Scenario: task.json carries the task context and the outcome
- **WHEN** `TaskRepository` writes `task.json`
- **THEN** the file carries version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation, whose `cannotExecute` kind additionally carries the denials of the round that could not execute

#### Scenario: state.json has two writers in disjoint windows
- **WHEN** `state.json` is written after the STARTED commit
- **THEN** the writer is either the git `AttemptPersistence`, for a round write, or `TaskRepository`, for a lifecycle transition (the decision commit replacing the state with the reset one beside the appended decision, the approval write moving the position past the gate, the resumed write replacing the state with the reset one) in its own commit outside any round

#### Scenario: state.json carries the position and the current attempts
- **WHEN** `state.json` is written
- **THEN** it carries version, position, attemptsUsed, attempts[] {round, result, startedAt, stop, checks[], denials[], executorUsage, judgeUsage} and totals, the inner forms as in status-report v1

#### Scenario: A repeated stage visit overwrites the round trace at the tip
- **WHEN** a stage is visited again and a round of the new visit writes `attempts/<stage>/<round>/trace.jsonl`
- **THEN** the file holds one JSON line per tool call of that round, the tip carries the latest visit's version, and git history keeps every earlier version

### Requirement: Attempt denials in the state file
Each attempt record in `state.json` SHALL carry a denials list — structured findings recorded by the environment's egress guard during that attempt's round — separate from check results. The list SHALL NOT participate in the attempt's result, the stage's overall verdict, or the prior-failure feedback of a retry.
**Implements**: FR2, FR4 of fix-denial-report-attachment; FR7 of fix-denial-attribution-durability

#### Scenario: Denials persist with the attempt
- **WHEN** a round with a guard denial is committed to the task branch
- **THEN** the attempt's entry in `state.json` carries the denial finding and its identity, and the attempt's result is unchanged by its presence

#### Scenario: Pre-existing state files stay readable
- **WHEN** a state file written before this contract addition is read
- **THEN** it parses under contract v1 with every attempt's denials read as empty

#### Scenario: Denials never feed retries
- **WHEN** an attempt with denials fails on a check and the stage retries
- **THEN** the feedback context of the retry contains only the check findings, not the denials

#### Scenario: A denial's identity is kept when known and when unknown
- **WHEN** a denial entry is recorded in an attempt's denials list
- **THEN** it carries the denial source's event timestamp paired with the source identity when the source assigned one, and an entry without an identity is kept, its identity read as unknown

### Requirement: Denial cursor in the state file
`state.json` SHALL carry the environment's denial read position at commit time — the opaque position paired with the identity of the denial source it was read from — so an instance resuming the task continues the denial delta from the newest source-matching committed position at the branch tip (`state.json`'s attempt-side cursor or `task.json`'s escalation-side cursor, whichever is newer), instead of re-reading everything the source still holds.
**Implements**: FR5 of fix-denial-report-attachment; FR3, FR5 of fix-denial-attribution-durability

#### Scenario: The cursor is committed with the attempt it delimits
- **WHEN** a sandboxed round is committed to the task branch
- **THEN** `state.json` records the environment's denial read position and the identity of the source it was read from

#### Scenario: Resume continues the delta instead of replaying it
- **WHEN** an instance resumes a task whose recorded cursor names the denial source it reattaches to
- **THEN** the first round after resume reports only its own denials, and earlier rounds' denials — already recorded in their own attempts — are not attached to it again

#### Scenario: A state file written before the cursor existed stays readable
- **WHEN** a state file with no cursor field is read
- **THEN** it parses under contract v1 and the run reads its denial source from the beginning

#### Scenario: A lifecycle rewrite preserves the cursor
- **WHEN** a RESUMED commit rewrites `state.json` on a tip whose file carries a committed cursor
- **THEN** the rewritten file carries the same cursor, and a restore from the new tip offers it

#### Scenario: The cursor changes no reconstructed field
- **WHEN** two state files differ only in their denial cursor
- **THEN** the position, attempts and usage a reader reconstructs from them are equal

#### Scenario: A writer with no denial source records no cursor
- **WHEN** a round is committed by a writer that has no denial source (host mode)
- **THEN** `state.json` carries no cursor field

#### Scenario: A decision append preserves the cursor
- **WHEN** a decision commit rewrites `state.json` on a tip whose file carries a committed cursor
- **THEN** the rewritten file carries the same cursor

### Requirement: Escalation denials in task.json
A `cannotExecute` escalation recorded in `task.json` SHALL carry the denials read from the environment of the round that could not execute, using the same finding shape as check findings and attempt denials. The denials SHALL NOT appear in the attempt history — the round they belong to was never recorded as an attempt — and SHALL influence no derived field.
**Implements**: FR2, NFR-S1 of fix-denial-attribution-durability

#### Scenario: A parked task keeps the hung round's denial
- **WHEN** a task is parked after a round was killed on its round timeout having attempted a denied egress request
- **THEN** `task.json` carries that denial under `lastEscalation`, while `state.json`'s attempt history and `attemptsUsed` are unchanged

#### Scenario: Pre-existing task files stay readable
- **WHEN** a `task.json` written before this contract addition is read
- **THEN** it parses under contract v1 with the escalation's denials read as empty

#### Scenario: Escalation denials survive the resume
- **WHEN** the parked task is resumed and the escalation is superseded by later work
- **THEN** the recorded escalation and its denials remain readable in the file's git history

#### Scenario: An escalation denial carries its identity when known
- **WHEN** a `cannotExecute` escalation records a denial
- **THEN** the entry carries its source-assigned identity when the source assigned one, and an entry without one is kept, its identity read as unknown

#### Scenario: A round with no denial records an empty list
- **WHEN** a `cannotExecute` escalation is recorded for a round that produced no denial
- **THEN** `task.json` carries the escalation's denials as an empty array

### Requirement: Denial cursor rides the escalation write
`task.json` SHALL carry the environment's denial read position as it stood after an escalation's denials were drained — the same opaque-position-plus-source-identity shape `state.json` records with an attempt — written in the same lifecycle commit as the escalation it belongs to, through the shared atomic writer, so the durable position can lag the record carrying its denials but never lead it.
**Implements**: FR9 of make-checkpoint-gate-durable; FR3, FR5, NFR-R1 of fix-denial-attribution-durability

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

#### Scenario: The escalation cursor stays out of the reconstructed state
- **WHEN** a `task.json` carrying an escalation cursor is read to reconstruct the task's state and to render `status.json`
- **THEN** no reconstructed field depends on the cursor and `status.json` does not contain it

#### Scenario: A task.json without a cursor offers none
- **WHEN** a `task.json` with no cursor field is read on resume
- **THEN** it parses under contract v1 and offers no escalation-side cursor

### Requirement: State-file JSON contract v1
`task.json` and `state.json` SHALL carry `"version": 1` and follow status-report v1 conventions (camelCase, ISO-8601 UTC, millisecond durations, sealed types via `"type"`). Readers SHALL ignore unknown fields; an unknown version SHALL refuse resume and the inspection commands (`status`/`usage`) alike, with a clear error naming the file and the unsupported version. Status-report DTOs SHALL NOT be reused.
**Implements**: FR10 of make-checkpoint-gate-durable; FR4 of add-git-workflow
**Notes**: `awaitingApproval` and the attempt `stop` are pre-release amendments under version 1: no released branch carries either token, and an older build reading a newer tip fails closed as an unreadable envelope.
**Notes**: The equivalence with the live report is anchored by `status-report-v1.reference.json`.

#### Scenario: Unknown version refuses resume
- **WHEN** `state.json` carries `"version": 2`
- **THEN** resume stops with an error naming the file and the unsupported version

#### Scenario: Every position and stop variant round-trips
- **WHEN** each `Position` variant and each attempt stop variant is written to `state.json` and read back
- **THEN** the value read equals the value written, for every variant the sealed hierarchies permit, with no hand-listed subset

#### Scenario: Equivalence with the live report
- **WHEN** the same task history is rendered from events and from the persisted files
- **THEN** the two StatusReports are equivalent per the reference contract

#### Scenario: An attempt without a stop reads as none
- **WHEN** an attempt record in `state.json` has no `stop` field
- **THEN** it reads with stop `none`

#### Scenario: Unknown version refuses the inspection commands
- **WHEN** `status` or `usage` reads a `task.json` or `state.json` carrying an unsupported version
- **THEN** the command refuses with an error naming the file and the unsupported version

### Requirement: Outcome protocol in task.json
`outcome` SHALL be null while a visit is in progress and SHALL be reset to null by the commit that begins each resumed visit — the decision commit, the approval commit, or the resumed commit — never by a later round's commit and never left to the next terminal write. These three SHALL be the only writers of `outcome: null`.
**Implements**: FR7, FR8, FR14 of make-checkpoint-gate-durable; FR5 of add-git-workflow
**Notes**: Removing the consumed requests is hygiene: a request is live only under its round's token (the decision-file protocol), so no reader depends on the removal.

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

#### Scenario: Every visit-opening commit removes the decision requests
- **WHEN** the decision, approval or resumed commit lands, on a tip with or without `.gnomish-task/decisions/`
- **THEN** that commit leaves no `.gnomish-task/decisions/` entry, and on a tip without the directory the removal changes nothing

#### Scenario: The last escalation outlives the visit and drives no recovery
- **WHEN** a task parked with an escalation is resumed and its outcome is reset to null
- **THEN** `lastEscalation`, kept apart from `outcome`, still carries the last question and answer, and no recovery decision reads it

### Requirement: Worktree lifecycle
In host mode, task worktrees SHALL live in `GNOMISH_HOME/projects/<name>/worktrees/<clone>/<sanitized-task-id>/`, outside the clone, where `<name>` is the registered project and `<clone>` the registered clone name of the `--dir` clone; two clones of one project SHALL never share a worktree folder. In sandboxed mode, the working copy SHALL be materialized and owned by the bound task environment; its location is a private adapter detail.
**Implements**: FR6 of add-git-workflow; FR1, FR2 of add-sandbox-core; FR9, NFR-R2 of add-project-registry

#### Scenario: Aborted keeps the evidence
- **WHEN** a task aborts after a persist failure
- **THEN** the working copy (worktree or environment) is left in place — it may hold the only copy of unrecorded work

#### Scenario: Kept environment is stopped, not running
- **WHEN** a sandboxed task escalates
- **THEN** its container is stopped with volume and network retained, and no gnome process keeps executing

#### Scenario: Same-named clones of different projects do not collide
- **WHEN** `~/work/api` is registered to project `billing` and `~/oss/api` to project `gateway`, and both work a task with the same id
- **THEN** their worktrees live under `projects/billing/worktrees/api/` and `projects/gateway/worktrees/api/` respectively

#### Scenario: Janitor stays in its clone
- **WHEN** the janitor runs for clone `widgets` of a project that also registers `widgets-demo`
- **THEN** it inspects only `projects/widgets/worktrees/widgets/` and leaves `widgets-demo`'s folder untouched

#### Scenario: The worktree path is shown to the operator
- **WHEN** a host-mode task starts
- **THEN** its worktree path is printed at start and `status` shows it

#### Scenario: Completion removes the working copy and keeps the branch
- **WHEN** a task ends `Completed`
- **THEN** its working copy is removed (host: `git worktree remove`; sandboxed: environment dispose) and the task branch stays

#### Scenario: A parked task keeps its working copy
- **WHEN** a task ends `Escalated` or `Paused`
- **THEN** its working copy (worktree or environment) is kept

#### Scenario: Runner start prunes worktrees and sweeps orphans
- **WHEN** the runner starts
- **THEN** it runs `git worktree prune` and the environment orphan sweep

### Requirement: Resume from the recorded branch
`--resume <task>` SHALL locate the branch — the local branch, else the touchpoint fetch of exactly `gnomish/<task>` and its register, else, only when origin cannot deliver, a tracking ref already present — then continue by `task.json` outcome: escalated → decision dialog; paused → confirmation; null → continue from the recorded position; completed → report "task done" and exit. A missing task working copy SHALL be materialized through the bound task environment from the branch state alone.
**Implements**: FR8 of add-git-workflow; FR6 of add-sandbox-core; FR7, FR12 of add-base-ref-resolution; FR13 of tighten-spec-contract
**Notes**: `git clone` brings a task branch as a remote-tracking ref without its register, so a present tracking ref does not end the locate (design D13 of tighten-spec-contract, amended 2026-10-13).

#### Scenario: Another instance resumes from origin
- **WHEN** the branch exists only on origin
- **THEN** resume fetches that single ref and materializes an environment that continues from the recorded position

#### Scenario: A tracking ref without a local branch still fetches the register
- **WHEN** no local task branch exists and a remote-tracking ref of it is already present, as a `git clone` leaves it
- **THEN** the locate runs the touchpoint fetch, and origin's register lands in the clone's register beside the refreshed tracking ref

#### Scenario: An unreachable origin falls back to the tracking ref
- **WHEN** no local task branch exists, a remote-tracking ref of it is present, and origin cannot deliver
- **THEN** the locate answers with that tracking ref, the clone's register stays as an earlier touchpoint fetch left it, and a branch with no register in the clone classifies `Unknown`

#### Scenario: Autonomous resume refreshes the pinned base for the law
- **WHEN** a tracker-driven resume finds the task branch and a pinned base ref name
- **THEN** it narrow-fetches exactly that ref name as well, binds the law from its tip (pipeline-config), and treats an unreachable remote as an infrastructure failure like the base refresh; manual resume without a reachable remote binds from the local ref tip

### Requirement: Salvage of interrupted rounds
Uncommitted leftovers of an interrupted round SHALL be salvaged by default, through the port: if the environment (or its volume) still exists, the factory SHALL commit the leftovers via `exec` inside it — as a service commit that is not a round in `state.json` — and harvest them; if the environment is gone, resume SHALL continue from the last harvested branch state, losing at most the uncommitted tail, never corrupting recorded rounds.
**Implements**: FR10 of add-git-workflow; FR6 of add-sandbox-core; FR5 of harden-task-branch-contract
**Notes**: Which files are gnome-owned is the shared factory-owned-paths policy (see execution-environment).

#### Scenario: Salvage feeds the QC loop
- **WHEN** resume finds uncommitted changes from a dead process
- **THEN** they are committed as a salvage commit and the next round starts with them in the working copy

#### Scenario: Factory files come from the tip, not the worktree
- **WHEN** the dirty worktree of a dead process holds a modified `.gnomish-task/state.json`
- **THEN** salvage commits only the gnome-owned work files, and the run continues from the `state.json` recorded at the branch tip

#### Scenario: Volume survives a dead container
- **WHEN** a factory instance dies mid-round but the task volume remains
- **THEN** a resuming instance salvages the leftovers from the volume as a salvage commit and continues

#### Scenario: Environment lost entirely
- **WHEN** both container and volume are gone
- **THEN** resume materializes a fresh environment from the branch and continues from the last recorded round without error

#### Scenario: Deliberate discard
- **WHEN** resume runs with `--discard-work` in sandboxed mode
- **THEN** the old environment is disposed and a fresh one materialized at the last round commit, with `state.json` unchanged

#### Scenario: Discard replays only the interrupted round
- **WHEN** resume runs with `--discard-work` in host mode
- **THEN** the working copy is reset to the last recorded round and the interrupted round is replayed, never the task from its start

### Requirement: Best-effort push
After every round commit, and after every task lifecycle commit a mode records, the adapter SHALL push the task branch best-effort: durability is the recorded branch state, and a failed push logs WARN and work continues. The first push of a newly created task branch is the one exception: it is load-bearing.
**Implements**: FR11 of add-git-workflow; FR5, FR21 of add-sandbox-core; FR1, FR2, FR6, NFR-O1 of fix-lifecycle-push; FR7 of harden-task-branch-contract
**Notes**: The first push's retries are an infrastructure budget, not the bounded re-attempt that bound-subprocess-commands forbids spending on an interrupted or timed-out invocation.

#### Scenario: First push of a new branch is load-bearing
- **WHEN** the first push of a newly created task branch fails and its bounded retries are exhausted
- **THEN** the take aborts before any round starts, and no work executes on a branch origin has never seen

#### Scenario: Push failure does not stop work
- **WHEN** origin is unreachable after a round commit
- **THEN** a WARN is logged and the next round starts normally

#### Scenario: Gnome commit triggers a push
- **WHEN** the gnome commits mid-round in host mode and the next tool event is observed
- **THEN** the adapter pushes the task branch best-effort

#### Scenario: Mid-round tip movement in a box is pushed
- **WHEN** the gnome commits mid-round inside the environment and the rate-limited poll observes the moved tip
- **THEN** the adapter harvests the branch through the admission gate and pushes what it admitted best-effort; the register ref is not part of that push

#### Scenario: Completion reaches origin without human involvement
- **WHEN** a task ends `Completed` with an origin remote configured, in host or sandboxed mode
- **THEN** after the outcome and cleanup commits are created the branch is pushed, and the origin tip equals the local tip with no manual push

#### Scenario: Park commit is pushed before the tracker signal
- **WHEN** a host-mode task parks (Escalated or Paused) and its outcome commit with the pending marker is recorded
- **THEN** the push attempt for that commit completes before the terminal tracker write is issued

#### Scenario: Lifecycle push failure stays best-effort
- **WHEN** origin is unreachable while a lifecycle commit after the first push is recorded
- **THEN** one WARN names the task, branch, and lifecycle event, the lifecycle operation still succeeds, and the run continues

#### Scenario: Local-only runs stay silent
- **WHEN** a task runs its whole lifecycle in a clone with no origin remote
- **THEN** no push is attempted at any lifecycle commit and no warning is logged

#### Scenario: A failed harvest skips the push
- **WHEN** a sandboxed harvest that precedes a push fails
- **THEN** the push is skipped with a WARN and work continues

#### Scenario: An undeliverable attempt commit fails an external check as infrastructure
- **WHEN** a stage declaring external checks cannot deliver its attempt commit to origin
- **THEN** delivery, a verified precondition of the poll loop (see stage-engine), fails and the check classifies as an infrastructure failure instead of expiring as a poll timeout

#### Scenario: Each lifecycle operation pushes once
- **WHEN** a mode records an appended resume decision, a terminal outcome, the `Completed` cleanup commit, or the tracker-write-confirmed commit, in host or sandboxed mode
- **THEN** exactly one best-effort push follows that lifecycle operation

#### Scenario: Completion pushes around the tracker write
- **WHEN** a task ends `Completed`
- **THEN** the outcome commit is pushed before the terminal tracker write and the cleanup commit is pushed after it — two lifecycle operations, two pushes

#### Scenario: A timed-out first push that landed counts as success
- **WHEN** the first push of a new task branch times out and the ref did land on origin
- **THEN** before any re-push the retry loop re-checks the remote tip within its own bound and treats the confirmed ref as success

#### Scenario: One owner pushes after lifecycle commits
- **WHEN** a caller records a lifecycle commit
- **THEN** the push comes from the lifecycle operation itself, and no caller issues a lifecycle push of its own

### Requirement: Origin reconciliation at task touchpoints
At resume start and at a run's terminal boundary, the factory SHALL compare the local task-branch tip with the origin branch tip (one remote-refs read) and, when origin is missing the branch or holds a strict ancestor of the local tip, push best-effort — so a push missed by an earlier crash or outage is delivered by the next instance that touches the task, whichever machine it runs on. No periodic or background reconciliation is introduced.
**Implements**: FR3, NFR-R1, NFR-O1, NFR-C1 of fix-lifecycle-push

#### Scenario: A missed terminal push is healed on resume
- **WHEN** an instance crashed after committing a terminal outcome but before its push landed, and any instance later resumes the task
- **THEN** the resume's touchpoint check finds origin behind and pushes the branch best-effort before the run proceeds

#### Scenario: Reconciliation never blocks the run
- **WHEN** the touchpoint check cannot reach origin
- **THEN** one WARN is logged and the resume or terminal path continues unchanged

#### Scenario: Up-to-date origin costs one read
- **WHEN** a touchpoint check runs and origin already holds the local tip
- **THEN** no push is attempted and the check's only remote interaction was the single refs read

#### Scenario: A parking terminal boundary spends no second read
- **WHEN** a run's terminal outcome is a park (Escalated or Paused), so the delivery fence runs over the same branch tip immediately afterwards
- **THEN** the terminal-boundary reconciliation is skipped and the fence's refs read is the boundary's only one; a `Completed` or `Aborted` boundary, which runs no fence, still reconciles

#### Scenario: Each touchpoint compares its own mode's tip
- **WHEN** the touchpoint check runs in host mode or in sandboxed mode
- **THEN** the local tip it compares is the one the touchpoint supplies from its own execution mode's reader

#### Scenario: A failed catch-up push degrades to a warning
- **WHEN** the touchpoint check finds origin behind and the catch-up push fails
- **THEN** one WARN is logged and the resume or terminal path continues unchanged

#### Scenario: A catch-up push says origin was behind
- **WHEN** the touchpoint check finds origin behind and pushes
- **THEN** the log says that origin was behind

#### Scenario: No origin remote makes the check a no-op
- **WHEN** the clone has no origin remote
- **THEN** the touchpoint check reads nothing, pushes nothing and logs nothing

### Requirement: Remote credentials are scrubbed from git's captured output
Every git command's captured stderr SHALL have URL userinfo — the `user:password@` or bare `token@` prefix a remote URL may carry — replaced by a fixed mask at the subprocess seam, before any caller can log it, attach it to an exception, or place it in a report detail the tracker publishes. The scrub SHALL be structural (any `scheme://userinfo@host` occurrence, any credential format), not a guess at particular token shapes.
**Implements**: NFR-S2 of fix-lifecycle-push

#### Scenario: A token-in-URL origin cannot leak through a push failure
- **WHEN** a push, fetch, or refs read fails in a clone whose origin URL embeds a credential, and git's stderr echoes that credential
- **THEN** the stderr the caller receives carries the mask in its place, and no WARN or report detail contains the credential

#### Scenario: Scrubbing keeps the failure diagnosable
- **WHEN** a failed git command's stderr is scrubbed
- **THEN** everything but the userinfo survives — the host, the path, and git's own message — and a URL without credentials is passed through unchanged

#### Scenario: Captured stdout is not scrubbed
- **WHEN** a git command's stdout carries a URL with userinfo, such as the configured origin URL
- **THEN** the caller receives stdout unchanged, so the adapter can read the origin URL itself

### Requirement: Delivery fence before a park's tracker write
In host mode, before the terminal tracker write of a park (Escalated or Paused), the factory SHALL verify the task branch tip is delivered to origin using the same delivery protocol external checks apply to attempt commits: a remote-tip ancestry check first, then a push with one bounded re-attempt on failure.
**Implements**: FR4, FR5, NFR-R2, NFR-O1, UX2 of fix-lifecycle-push

#### Scenario: Fence delivers before the park lands
- **WHEN** a host-mode park's first push attempt failed transiently and the fence's re-attempt succeeds
- **THEN** origin holds the park commit before the tracker's terminal write is issued and the park report carries no replication note

#### Scenario: Fence exhaustion surfaces instead of blocking
- **WHEN** every fence attempt fails against an unreachable origin
- **THEN** the park's tracker write still proceeds, the park report notes that origin is behind, and a WARN records the exhaustion

#### Scenario: No origin, no fence
- **WHEN** a host-mode task parks in a clone with no origin remote
- **THEN** the fence performs no remote interaction and the park proceeds exactly as before

#### Scenario: Fence exhaustion leaves the marker to the confirm protocol
- **WHEN** the fence exhausts its bounded attempts during a park
- **THEN** the fence neither blocks, fails nor delays the park beyond those attempts, and the pending-write marker stays governed by the existing confirm protocol alone

#### Scenario: Completed and Aborted outcomes run no fence
- **WHEN** a host-mode task ends `Completed` or `Aborted`
- **THEN** no delivery fence runs and the outcome's push stays purely best-effort

### Requirement: Push safety rules
Push SHALL be the adapter's monopoly, coded in factory logic — never expressed as rules for the gnome: credentials are not exposed to the agent and prompts contain nothing about push.
**Implements**: NFR-S1 of add-git-workflow; FR5, NFR-S1 of add-sandbox-core

#### Scenario: Non-fast-forward never escalates to force
- **WHEN** a push is rejected as non-fast-forward
- **THEN** the adapter logs WARN and does not retry with `--force`

#### Scenario: No push machinery inside the box
- **WHEN** a sandboxed round runs
- **THEN** the environment holds no push credentials and no remote address, and the only path to origin is the factory-side push after harvest

#### Scenario: Push names exactly the task branch
- **WHEN** the adapter pushes a task branch
- **THEN** it pushes exactly the task branch `gnomish/<task>` to `origin`, never with `--force`

#### Scenario: A host mid-round push checks the branch first
- **WHEN** a host-mode mid-round push is about to run
- **THEN** it runs only if HEAD is on the task branch and the old tip is an ancestor of it

#### Scenario: A refused mid-round harvest leaves the verdict to the round boundary
- **WHEN** a sandboxed mid-round harvest is refused — not fast-forward, or the admission inspection rejects a commit in the fetched range
- **THEN** the push is skipped with a WARN, the branch tip is unchanged, nothing refused becomes durable, and the authoritative verdict is the boundary violation the round close reports

### Requirement: Gnome commits within a round
Gnome commits inside a round SHALL be allowed; the adapter's commit closes the round. Every admission of gnome commits — the harvest in sandboxed mode, the round close in host mode — SHALL run one inspection of the range above the register (the previous tip an ancestor, no second parent, `.gnomish-task/` untouched but for the round's decision request, no `gnomish: ` subject) with three outcomes: clean, violated or cannot-verify. A violation SHALL abort the task.
**Implements**: FR13, FR16 of make-checkpoint-gate-durable; FR12 of add-git-workflow; FR21, FR23 of add-sandbox-core; FR13 of harden-logging-observability; FR13, NFR-S2 of tighten-spec-contract
**Notes**: Stage instructions encourage the gnome to commit with plain git as it works.
**Notes**: A boundary violation breaks durability, which is why it aborts the task instead of failing the round.
**Notes**: The one inspection replaces the host and container twins of the check (design D18 of tighten-spec-contract); the harvest runs it before the branch moves, the host round close over `previousTip..HEAD`.

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

#### Scenario: Sandboxed boundary verification runs on harvested refs
- **WHEN** a sandboxed round closes
- **THEN** boundary verification runs factory-side against the quarantine ref the harvest fetched, before the branch moves to it, and a history rewrite is refused by the fast-forward-only harvest itself

#### Scenario: A service subject in a gnome commit is a violation
- **WHEN** a commit in the inspected range carries a subject starting with `gnomish: ` — the cleanup, snapshot, round or task-event form — and the factory did not write it
- **THEN** the inspection reports a violation naming the commit and its subject, and the commit is not admitted

#### Scenario: An uncommitted envelope edit does not ride the host round commit
- **WHEN** at a host round close the working tree holds an uncommitted change under `.gnomish-task/` other than the current round's decision request
- **THEN** persist restores the factory-owned paths from the registered commit before staging, the round commit carries the factory's own envelope, and the edit is reported as a violation

#### Scenario: A violation's Aborted outcome is built from the registered envelope
- **WHEN** a boundary violation aborts the task
- **THEN** the Aborted outcome commit carries `task.json` and `state.json` derived from the registered commit's envelope, never from the tip's, so a later pickup resumes from the factory's record

#### Scenario: No in-box check decides the boundary
- **WHEN** a sandboxed round's snapshot commit is made inside the environment and the harvested refs show a boundary violation
- **THEN** the factory-side verification of the harvested refs reports the violation; it is the only boundary check — the in-box snapshot commit runs no check of its own

#### Scenario: A host round boundary checks the worktree
- **WHEN** a host-mode round closes
- **THEN** persist checks that HEAD is on the task branch, that the previous tip is an ancestor, and that `.gnomish-task/` is untouched apart from the current round's decision request — in the committed range and in the working tree alike

#### Scenario: A boundary violation keeps its evidence
- **WHEN** persist throws on a boundary violation
- **THEN** the task ends Aborted with the evidence kept on the branch and, in sandboxed mode, in the kept environment

#### Scenario: Both boundary media report cannot-verify
- **WHEN** the git invocation behind the worktree diff (host) or the harvested-ref check (sandboxed) fails while producing its evidence
- **THEN** each medium reports cannot-verify, never clean and never violated

### Requirement: Cleanup on completion
On `Completed` the outcome commit SHALL carry a pending-cleanup marker, and a cleanup commit removing `.gnomish-task/` from the branch tip SHALL follow as the destructive last step of the completion sequence, while every state file stays reachable in branch history as the audit trail.
**Implements**: FR15 of add-git-workflow; FR25 of add-sandbox-core; FR10 of harden-task-branch-contract; FR3, FR4, FR7, NFR-O1 of fix-envelope-medium

#### Scenario: Clean tip, full history
- **WHEN** a task completes
- **THEN** the branch tip contains no `.gnomish-task/` while every round commit remains in history

#### Scenario: Kill between outcome and cleanup does not re-run the final stage
- **WHEN** an instance dies after the `Completed` outcome commit (marker present) but before the cleanup commit
- **THEN** the next pickup finishes the cleanup, pushes, and delivers the tracker finish — without re-entering the engine or re-running any stage

#### Scenario: Cleanup works after dispose
- **WHEN** a sandboxed task completes and its environment is already disposed
- **THEN** the outcome and cleanup commits are still created factory-side and pushed, with no environment required

#### Scenario: Kill between the staged removal and its commit converges on the same worktree
- **WHEN** a host instance dies after `.gnomish-task/` was removed from the worktree and its
  index but before the cleanup commit, and the next pickup reuses that same worktree
- **THEN** the pickup classifies the tip as a finished task awaiting cleanup, the cleanup
  commit lands the removal that was already staged, the tip ends without `.gnomish-task/`, the
  worktree is removed, and a second pickup changes nothing

#### Scenario: A converged staged removal is visible in the log
- **WHEN** the cleanup commit lands a removal a predecessor already staged
- **THEN** one INFO line names the task and says the staged removal was committed

#### Scenario: Cleanup follows every constructive receipt
- **WHEN** a task completes
- **THEN** the cleanup commit is created only after the constructive steps have their receipts, and the terminal tracker finish is delivered only after the constructive receipts as well

#### Scenario: The cleanup commit is built per mode
- **WHEN** a sandboxed task's cleanup commit is created
- **THEN** it is built factory-side from bare tree objects, and no checkout of the branch occurs in factory-owned filesystem
- **WHEN** a host-mode task's cleanup commit is created
- **THEN** it is the worktree cleanup commit

#### Scenario: A tip without the state directory is a cleanup no-op
- **WHEN** the cleanup step runs, in host or sandboxed mode, on a branch tip that carries no `.gnomish-task/`
- **THEN** no commit is created, whatever the host working tree holds — the guard tests the branch tip, never the working tree

### Requirement: Sandboxed round protocol: snapshot and state commits
In sandboxed mode a round SHALL close in two steps: first a snapshot commit of the whole working tree, executed inside the environment with hooks disabled at argv level and harvested, which verification judges as the attempt commit; then, after verification, a state commit of the state files (`state.json`, trace) written into the environment via `putFile`. At the state-commit harvest the factory SHALL verify the state commit's integrity.
**Implements**: FR21, FR22 of add-sandbox-core

#### Scenario: Verification judges the attempt commit
- **WHEN** a sandboxed round closes and verification runs
- **THEN** builtin, fresh-box, and external checks all observe exactly the harvested snapshot commit, and uncommitted box residue influences none of them

#### Scenario: Interrupted verification resumes without burning an attempt
- **WHEN** a factory dies after the snapshot commit but before the state commit
- **THEN** a resuming instance finds the snapshot unrecorded in `state.json`, re-runs verification against it, and the attempt counter is unchanged

#### Scenario: The snapshot subject is read at the register only
- **WHEN** a resuming instance looks for a pending verification
- **THEN** it reads the stage, round and token from the subject of the registered commit, and a snapshot-shaped subject on a commit above the register is not a snapshot

#### Scenario: Daemon-inserted commit is caught by the parent-check
- **WHEN** a background process in the box commits to the branch between the snapshot and state commits
- **THEN** the harvested state commit's parent is not the snapshot commit and the task aborts as a boundary violation

#### Scenario: Tampered state file is caught by read-back
- **WHEN** in-box tampering alters `state.json` content between `putFile` and the state commit
- **THEN** the harvested content differs from what the factory wrote and the task aborts as a boundary violation

#### Scenario: Every check reads the attempt commit its own way
- **WHEN** verification of a sandboxed round runs
- **THEN** builtin checks read the snapshot commit as bare git objects in the factory clone, fresh-box checks and judge votes materialize from it, and external checks poll CI runs of exactly that pushed commit

#### Scenario: A tampered round trace is caught by read-back
- **WHEN** in-box tampering alters the round trace between `putFile` and the state commit
- **THEN** the harvested trace is not byte-identical to what the factory wrote and the task aborts as a boundary violation

#### Scenario: task.json never crosses the environment channel
- **WHEN** a sandboxed task's `task.json` changes
- **THEN** it is committed factory-side as a bare-object commit (see "Task lifecycle port"), never written into the environment, and no read-back runs for it

### Requirement: Sandboxed working copy is an independent full clone
In sandboxed mode, materialize SHALL create the working copy as a clone from the factory's local clone into the environment, built by the one transfer owner (see `git-transfer`): through git's transport path, never its local-path mode, with no hardlinks, a single branch, no tags, and validated objects — no network, no credentials, no remote address inside; the clone SHALL set the agent identity and `gc.auto 0`.
**Implements**: FR3 of add-sandbox-core; FR7, NFR-S1 of own-git-transfer-argv

#### Scenario: The box holds no way to the server
- **WHEN** an environment is materialized in container mode
- **THEN** the in-box clone has no configured remote pointing at the real server and no credentials anywhere in the box

#### Scenario: No shared objects with the factory clone
- **WHEN** the clone is created from the local factory clone
- **THEN** it shares no hardlinked objects with it, so in-box corruption cannot reach the factory's repository

#### Scenario: Operator tags do not enter the box
- **WHEN** the factory clone holds local tags, lightweight or annotated, pointing into the task branch's history
- **THEN** the in-box clone holds no tag

#### Scenario: The seed clone takes the transport path
- **WHEN** the factory clone holds an object git's validation rejects on the task branch
- **THEN** the seed clone refuses it — the local-path shortcut that would have copied it unchecked is never taken

### Requirement: Harvest protocol
The factory SHALL collect results by fetching the task branch from the environment into the quarantine ref `refs/gnomish-quarantine/<task>` with a factory-fixed refspec; the task branch SHALL move to the fetched commit only after the admission inspection passed, fast-forward-only. A tag, a submodule reference or a configuration value authored in the box SHALL change nothing in the factory clone beyond those two refs. Harvest SHALL precede any push.
**Implements**: FR5 of add-sandbox-core; FR6, NFR-S1, UX3 of own-git-transfer-argv; FR13, NFR-S2 of tighten-spec-contract
**Notes**: The refspec never takes a name produced inside the box; rewritten or refused history is the subject of the scenarios below.
**Notes**: The harvest fetch is a transfer built by the one transfer owner (see `git-transfer`); pushes run outside the environment with factory credentials.
**Notes**: The quarantine ref is git's own receive-side shape — objects admitted to the branch only after the check passed (design D18 of tighten-spec-contract).

#### Scenario: Rewritten history is refused at the boundary
- **WHEN** the branch history inside the box was rewritten
- **THEN** the admission finds the fetched commit is not a descendant of the task branch, the branch does not move, and the factory treats it as the existing history-rewrite violation

#### Scenario: The branch moves only through the admission
- **WHEN** any harvest runs — mid-round, snapshot, state commit or salvage
- **THEN** the fetch writes the quarantine ref alone, and the task branch ref is updated by the admission owner after its inspection, never by the fetch

#### Scenario: Refused content is not durable
- **WHEN** the admission refuses the fetched range
- **THEN** the task branch and the register are unchanged locally and at origin, and the refused commits remain reachable only from the quarantine ref for the violation report

#### Scenario: Hooks do not cross the boundary
- **WHEN** the gnome installs git hooks in the in-box clone
- **THEN** harvest transfers branch content only; no hook becomes active in any factory-managed copy

#### Scenario: A box-authored tag does not cross the boundary
- **WHEN** the gnome creates a tag inside the box pointing into the task branch's history
- **THEN** after harvest the factory clone holds no such tag, and `FETCH_HEAD` in the factory clone is untouched

#### Scenario: A malformed object is refused with the rewrite's report shape
- **WHEN** the box's branch carries an object git's validation rejects
- **THEN** the harvest is refused, the task ends as a boundary violation, and the report names what was refused in the same shape as a history-rewrite refusal

#### Scenario: A box-authored submodule or configuration changes nothing
- **WHEN** the gnome adds a submodule reference or sets a configuration value inside the box
- **THEN** after harvest the factory clone differs only in the task branch ref: no submodule is recursed into and no configuration value is taken over

#### Scenario: The harvest admits only the container transport
- **WHEN** the harvest fetch runs
- **THEN** its protocol allowlist names the container transport alone

#### Scenario: The harvest reads no operator configuration
- **WHEN** the operator's git configuration widens transfers (submodule recursion, prune, a URL rewrite)
- **THEN** the harvest fetch reads none of it and transfers only the task branch

### Requirement: Factory git executes no untrusted content
The factory SHALL never check out gnome-branch content into its own filesystem namespace: reading gnome branches happens via bare git object access, and materialization of untrusted content happens only inside task environments. Every factory-managed clone SHALL have `core.hooksPath` pointed at an empty directory.
**Implements**: FR17 of add-sandbox-core

#### Scenario: Reading a gnome branch runs nothing
- **WHEN** the factory reads state files or artifacts from a gnome branch
- **THEN** it reads git objects directly, no checkout occurs in factory-owned paths, and no hook executes

### Requirement: Bounded git network invocations
Every git invocation that talks to a remote — fetching, pushing, listing remote refs, cloning,
updating a remote — SHALL be bounded: it terminates within a configured deadline plus a small kill
margin no matter what the remote does, including a connection that is accepted and then never
answers.
**Implements**: FR1, FR2, FR3, FR5, NFR-R1, NFR-R2 of bound-subprocess-commands

#### Scenario: A silent remote does not hang the run
- **WHEN** a push is issued to a remote that accepts the connection and then sends nothing
- **THEN** the invocation ends within the configured deadline, is reported as timed out, and the run
  continues to its next step

#### Scenario: No process outlives the deadline
- **WHEN** an invocation is terminated on deadline expiry
- **THEN** neither it nor any process it spawned remains running

#### Scenario: Local commands are untouched
- **WHEN** a local command such as a commit or a ref read is issued
- **THEN** it is not bounded and its exit code, output, and error text are exactly as before

#### Scenario: A full output pipe does not hide the deadline
- **WHEN** a bounded invocation writes more output than a pipe buffer holds
- **THEN** the output is drained concurrently with the running process, so the wait neither deadlocks nor outlives the deadline

#### Scenario: A timed-out outcome carries the captured output
- **WHEN** an invocation is forcibly terminated on deadline expiry
- **THEN** its timed-out outcome carries whatever output was captured up to then

### Requirement: Stall detection governs a progressing transfer
The factory SHALL enable git's own no-progress detection for the transports it uses — an abort when
throughput stays below a floor for a sustained window over HTTP, and connect plus keepalive limits
over SSH — configured per invocation only. The deadline SHALL remain a hard wall-clock bound on
every network command, which progress does not extend.
**Implements**: FR4, NFR-S1, G3 of bound-subprocess-commands
**Notes**: Stall detection is the primary mechanism for a dead connection; the operator raises `factory.git-network-timeout` for transfers that need longer.

#### Scenario: A slow but progressing transfer completes
- **WHEN** a large fetch over a slow link keeps transferring data
- **THEN** it is allowed to finish if it ends within the configured deadline; one still progressing
  when the deadline expires is ended as timed out, and the operator raises
  `factory.git-network-timeout` for transfers that need longer

#### Scenario: Operator git configuration is not modified
- **WHEN** any bounded invocation runs
- **THEN** the settings it uses apply to that invocation alone and no file of the operator's git
  configuration is written

### Requirement: Interruption and timeout are named outcomes
An invocation that was interrupted (a shutdown, a revoked claim) and one that expired on its
deadline SHALL each be a distinct outcome, never reported as an ordinary non-zero git exit.
**Implements**: FR6, FR7, FR8, NFR-O1, NFR-O2, NFR-S2, UX2, UX3 of bound-subprocess-commands
**Notes**: A timed-out push is an unknown remote outcome: the transfer may have landed even though the local command was killed.

#### Scenario: An interrupted delivery check spends no re-attempt
- **WHEN** the park delivery check is interrupted mid-push
- **THEN** no second push is attempted and the park report carries no claim that the remote is behind

#### Scenario: A timed-out push reads as a dead remote
- **WHEN** a best-effort push expires on its deadline
- **THEN** one warning is logged naming the timeout, the elapsed time, and the configured deadline,
  distinct from the warning a rejected push produces, and the round continues

#### Scenario: A timed-out delivery push is re-verified before any claim
- **WHEN** the park delivery push expires on its deadline
- **THEN** no second push is attempted, the remote tip is re-checked once within its own bound, and
  the park report claims the remote is behind only if that check confirms the tip absent — an
  unanswerable check yields "delivery could not be verified" instead

#### Scenario: Partial output of a killed process is still scrubbed
- **WHEN** a terminated invocation captured error text containing remote-URL credentials
- **THEN** the credentials are removed before the text reaches any log or operator-visible report

#### Scenario: An interrupted push is not reported as failed
- **WHEN** a best-effort push is interrupted before it ran to a verdict
- **THEN** the operator is not told that the push failed

#### Scenario: An interrupted delivery check reports delivery unverified
- **WHEN** the park delivery check is interrupted
- **THEN** it reports that delivery could not be verified rather than asserting that the remote is behind

### Requirement: Automatic origin divergence resolution
On resume with both local and origin branches present, the runner SHALL reconcile automatically: equal → continue; local behind → fast-forward to origin, discarding uncommitted leftovers; local ahead → continue from local (push catches up); truly diverged under a live claim → discard the local branch and continue from origin.
**Implements**: FR9, NFR-R3 of add-git-workflow; FR8, NFR-R3 of harden-task-branch-contract
**Notes**: Origin wins because a transition is durable across instances only once its push succeeded; that reasoning holds only where the claim protocol is in force, hence the gate on a live claim.

#### Scenario: Diverged histories resolve automatically under the claim
- **WHEN** local and origin task branches have diverged and the resuming instance holds a live claim
- **THEN** the local ref is reset to the origin tip via compare-and-swap, local drafts are dropped, and the run continues from origin with no operator intervention

#### Scenario: A claimless resume refuses the discard
- **WHEN** local and origin have diverged during `gnomish run --resume`, which holds no claim on the task in either execution mode
- **THEN** the local ref and working tree are left exactly as they were and the run exits with the operator-facing divergence report naming both tips, since no lease arbitrated between the two lines

#### Scenario: The reset never touches origin
- **WHEN** the discard-local reset executes
- **THEN** origin history is unchanged — no force push, no rewrite — and only the local ref moves

#### Scenario: A moved tip fails the CAS reset
- **WHEN** the local tip changes between the discard decision and the reset
- **THEN** the compare-and-swap fails, no ref is overwritten blindly, and the branch is classified again

#### Scenario: The CAS reset is the only non-fast-forward write
- **WHEN** any automatic reconciliation path runs — equal, behind, ahead, or diverged
- **THEN** origin receives no force push and no history rewrite, and the local ref moves other than fast-forward only through the compare-and-swap reset of a diverged line

### Requirement: One logical transition, one commit
Every logical transition of a task SHALL become durable as exactly one commit on the task branch; mutually-implied fields SHALL never split across commits. A kill between any two commits therefore never freezes a half-applied transition, and never a tip that permits what no commit has yet allowed.
**Implements**: FR1, FR3, FR5, FR7 of make-checkpoint-gate-durable; FR4 of harden-task-branch-contract

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

### Requirement: Atomic state-file writes
No reader of `.gnomish-task/` state files — a salvaging resume included — SHALL ever observe a partially written `state.json` or `task.json`: host-worktree writers SHALL write a temp file and atomically rename it into place, and the container-side persisters SHALL be atomic at commit granularity.
**Implements**: FR5 of harden-task-branch-contract
**Notes**: Each write medium realizes the invariant per the crash-consistency ADR's durability table.

#### Scenario: A kill mid-write on the host leaves a whole file
- **WHEN** the process dies during a host-worktree state-file write
- **THEN** the path holds either the complete previous content or the complete new content, and the next reader parses it without error

#### Scenario: A kill mid-write in the box never surfaces a partial file
- **WHEN** the process dies during an in-box `state.json` write, leaving a partial file in the box worktree
- **THEN** no commit carries the partial file, and neither resume nor salvage reads it — factory-owned files are restored from the branch tip

### Requirement: Verified tip resolution on durable paths
Any branch-tip resolution whose result is recorded durably (an attempt
commit's tip, a snapshot record) or gates a recovery decision SHALL verify
the resolving invocation succeeded and produced a non-blank ref; on failure
the operation SHALL fail with the git evidence rather than record or compare
a blank value. Read-only polling probes MAY skip their observation on a
failed resolution, but SHALL never treat it as an observed change. Both
attempt-persistence media follow the same rule.
**Implements**: FR13 of harden-logging-observability

#### Scenario: A blank tip is never recorded
- **WHEN** the tip resolution behind an attempt or snapshot record fails
- **THEN** the persist operation fails with the git failure as evidence and
  no record carrying a blank tip is created

#### Scenario: A failed poll observation is not a change
- **WHEN** a mid-round polling probe cannot resolve the branch tip
- **THEN** the probe skips that observation without reporting the tip as
  moved or lost

### Requirement: Base refresh fetch before task creation
In autonomous fresh starts the factory SHALL refresh the resolved base with a
narrow fetch of exactly that ref, after the factory clone is hardened and
before the task is created, writing it to a destination fixed per kind. The
refresh is fail-closed: no task branch is created from a base whose
freshness could not be established.
**Implements**: FR6, FR9, NFR-P1, NFR-R1 of add-base-ref-resolution; FR1, FR4, NFR-R1, NFR-S1 of signal-outage-gate-on-origin-contact; FR5, NFR-R1 of own-git-transfer-argv
**Notes**: The fetch is a transfer built by the one transfer owner (see `git-transfer`). Whether origin was contacted has one reader, the remote outage gate (see factory-serve, "Remote outage gate holds the feed off the tracker").

#### Scenario: Fresh base at claim
- **WHEN** origin's `develop` has advanced past the factory clone's last
  fetch and a task resolving to `develop` is claimed
- **THEN** the task branch starts from origin's current `develop` tip, and
  the clone's own local branches and HEAD are unchanged

#### Scenario: A name origin holds as both a branch and a tag parks the task
- **WHEN** the resolution decision names `hotfix` and origin holds both
  `refs/heads/hotfix` and `refs/tags/hotfix`
- **THEN** the task parks with a report naming both commits, no fetch of
  either is attempted, and neither namespace is preferred over the other

#### Scenario: A ref origin holds in neither namespace parks the task
- **WHEN** the resolution decision names a ref origin answers about and
  holds under neither `refs/heads/` nor `refs/tags/`
- **THEN** the task parks with a report naming the ref, and absence is
  recorded only because origin answered — an unanswered remote stays an
  infrastructure failure

#### Scenario: Tag base lands in refs/tags without force
- **WHEN** the resolution decision names tag `v2.3.0` and the clone holds
  no such tag
- **THEN** the narrow fetch writes `refs/tags/v2.3.0`, no other tag
  appears, and the branch starts from the tagged commit

#### Scenario: Diverging local tag parks the task
- **WHEN** the clone already holds `v2.3.0` pointing at a commit other than
  origin's
- **THEN** the fetch refuses to move the tag, the task parks with a report
  naming the local and origin commits, and no branch is created

#### Scenario: SHA base already present needs no network
- **WHEN** the resolution decision names a commit SHA the clone already
  holds
- **THEN** no fetch runs, the object is verified as a commit, and the
  branch starts from it

#### Scenario: Absent SHA is fetched as objects only
- **WHEN** the resolution decision names a commit SHA the clone does not
  hold and origin serves it
- **THEN** the fetch brings the object with no ref written for it,
  `FETCH_HEAD` is never read, and the branch starts from the SHA

#### Scenario: Unreachable remote creates no branch
- **WHEN** the base fetch exhausts its bounded retries against a dead remote
- **THEN** no task branch exists, no stage attempt is burned, and the
  failure is reported as infrastructure, not as a gnome or quality failure

#### Scenario: A populated submodule does not widen the refresh
- **WHEN** the operator's clone has a submodule initialized and origin's
  new base commit references a newer submodule commit
- **THEN** the refresh contacts the submodule's remote not at all, updates
  no submodule ref, and the task branch starts from the delivered base

#### Scenario: A clone-served success says origin was not contacted
- **WHEN** the resolution decision names a commit SHA the clone already
  holds, or a resume rebinds a pinned ref from the local tip of a clone
  with no origin remote
- **THEN** the successful outcome states that origin was not contacted,
  and the commit it names is the same one the refresh named before this
  fact existed

#### Scenario: A fetched success says origin was contacted
- **WHEN** a branch, a tag, or an absent commit SHA is delivered by a
  narrow fetch, or a resume rebinds through a configured origin
- **THEN** the successful outcome states that origin was contacted, with no
  additional fetch, probe, or subprocess run to learn it

#### Scenario: A branch base force-updates its remote-tracking ref
- **WHEN** the resolution decision names a branch whose origin tip is not a
  descendant of the clone's `refs/remotes/origin/<name>`
- **THEN** the narrow fetch updates `refs/remotes/origin/<name>` to origin's
  tip by force, and the task branch starts from that tip

#### Scenario: The refresh writes no FETCH_HEAD
- **WHEN** a base of any kind is refreshed
- **THEN** no `FETCH_HEAD` is written, and the delivered SHA is read back
  from the destination ref or the object

#### Scenario: The refresh leaves the operator's checkout untouched
- **WHEN** a base of any kind is refreshed
- **THEN** the operator clone's working tree, index, HEAD, `refs/heads/*`,
  and every pre-existing `refs/tags/*` entry are unchanged

#### Scenario: A base object failing validation parks the task
- **WHEN** the refresh receives an object git's validation rejects
- **THEN** the fetch refuses it, the task parks as a task-level outcome,
  and no task branch is created

#### Scenario: A remote refusing fetch-by-SHA parks the task
- **WHEN** the resolution decision names a commit SHA the clone does not
  hold and origin refuses to serve it by SHA
- **THEN** the task parks as a task-level outcome and no task branch is
  created

#### Scenario: The refresh runs under the network bounds and the clone lock
- **WHEN** the base fetch runs
- **THEN** it is bounded by the git network deadline with stall detection
  and credential scrubbing, retried by the existing git infrastructure
  retry policy, and holds the clone's mutation lock like every other fetch

#### Scenario: The origin-contact fact decides nothing
- **WHEN** a refresh states whether origin was contacted
- **THEN** the fact is not persisted, and no binding, park, or release
  decision changes with it

### Requirement: Base pin in task.json
`task.json` SHALL carry the base pin — the resolved ref, its kind (branch,
tag, or commit, as origin classified it at refresh), the commit SHA the
branch was created from, and the source rule that produced the decision —
written in the task-creation commit. The pin extends the existing
`baseCommit` slot behind the wire version gate.
Resume SHALL read the pin at the registered commit and never re-derive the
base decision (see base-ref-resolution).
**Implements**: FR7 of add-base-ref-resolution; NFR-O2 of add-base-ref-resolution; NFR-S2 of tighten-spec-contract

#### Scenario: The pin is read at the register
- **WHEN** resume binds the law of a task whose tip is above its registered commit
- **THEN** the pin comes from the registered commit's `task.json`, and a pin on a commit above the register is never read

#### Scenario: Creation commit carries the pin
- **WHEN** the task-creation commit on a fresh branch is inspected
- **THEN** its `task.json` already names the resolved ref, its kind, the SHA,
  and the source rule, and the SHA equals the commit's own first parent

#### Scenario: Legacy task file reads as unpinned
- **WHEN** a pre-pin `task.json` carrying only `baseCommit` is read
- **THEN** it parses under the version gate with ref and rule absent, and
  resume proceeds exactly as before this change

#### Scenario: Resume re-resolves only the pinned ref
- **WHEN** a pinned task is resumed after its base designator, or the
  configured default, changed
- **THEN** resume fetches the pinned ref name to its current tip and never
  reads the designator, the configured default or default-branch discovery
  to derive the decision again

#### Scenario: Pin round-trips the wire
- **WHEN** a pinned task file is written and read back
- **THEN** ref, kind, SHA, and rule survive unchanged, covered by a
  data-driven round-trip spec over every rule constant and every kind
  constant

#### Scenario: A pin without a kind reads with the kind absent
- **WHEN** a legacy `task.json` carrying only `baseCommit`, or a pin written
  before the kind existed, is read
- **THEN** it stays readable and reports the kind absent

#### Scenario: An unknown pin token reads forward-compatibly
- **WHEN** a `task.json` pin carries a rule or kind token this build does
  not know
- **THEN** the read does not fail: an unknown rule reads as the unknown rule
  and an unknown kind reads as absent

#### Scenario: The law and the pin guard bind from the pinned SHA
- **WHEN** a fresh start pins a base whose SHA differs from the clone's
  `HEAD`
- **THEN** the law and the external-check pin guard bind from the pinned
  SHA, never from the clone's `HEAD`
