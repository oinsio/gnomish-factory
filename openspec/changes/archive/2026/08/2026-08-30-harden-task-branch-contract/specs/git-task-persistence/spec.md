# git-task-persistence — delta for harden-task-branch-contract

## MODIFIED Requirements

### Requirement: Task lifecycle port
A `TaskRepository` application-layer port SHALL own task-scoped lifecycle writes: create the task branch and record the task context at start, append a `Decision` on resume, and record the `TaskOutcome`/escalation at completion or parking. The STARTED commit SHALL carry both `.gnomish-task/task.json` and an initial `.gnomish-task/state.json` positioned at the first stage with zero attempts, so a resume after a first-round crash finds a readable state. A branch whose tip predates this contract — `task.json` present, `state.json` absent — SHALL classify as a legal shape that resumes the first stage from scratch, never as corrupt. The engine's `AttemptPersistence` port SHALL remain unchanged; the git adapter SHALL implement both ports over the same task branch. In sandboxed mode these lifecycle commits SHALL be created factory-side over bare git objects in the factory clone — no working copy, no checkout, no hook execution; the branch ref SHALL advance atomically only if the tip is unchanged, and a stale tip fails the write without force. Recording an outcome SHALL NOT require a live environment. On resume, the decision commit SHALL be created before the environment is materialized, so the working copy contains it from the start. Host mode keeps its worktree commits unchanged.
<!-- implements FR1, FR2 of add-git-workflow -->
<!-- implements FR25 of add-sandbox-core -->
<!-- implements FR3 of harden-task-branch-contract -->

#### Scenario: Start creates the branch with the task context
- **WHEN** a git-mode run starts for a new task
- **THEN** the task branch is created and its first commit adds both `.gnomish-task/task.json` with the task context and an initial `.gnomish-task/state.json` at the first stage with zero attempts

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
- **WHEN** a sandboxed task is resumed with a human decision
- **THEN** the decision commit is created factory-side on the harvested tip and the environment is materialized after it, so the in-box clone already contains the decision

#### Scenario: Abort outcome needs no environment
- **WHEN** a sandboxed task aborts after a boundary violation
- **THEN** the aborted outcome is committed factory-side on the last harvested tip and the kept environment is not touched

#### Scenario: Concurrent tip movement fails the write
- **WHEN** another factory instance moves the task branch between the tip read and the ref update
- **THEN** the lifecycle write fails without force and no existing commit is lost

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation), `state.json` (its initial version written once by `TaskRepository` as part of the STARTED commit; every later write only by the git `AttemptPersistence`: version, position, attemptsUsed, attempts[] {round, result, startedAt, checks[], denials[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1), `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the round is identified by the file path), and — in git modes — `decisions/<stage>-a<attempt>.json` (written only by the gnome; the single gnome-writable path under `.gnomish-task/`, per the decision-file protocol).
<!-- implements FR3 of add-git-workflow -->
<!-- implements FR23 of add-sandbox-core -->
<!-- implements FR4 of fix-denial-report-attachment -->
<!-- implements FR3 of harden-task-branch-contract -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

### Requirement: Salvage of interrupted rounds
Uncommitted leftovers of an interrupted round SHALL be salvaged by default, through the port: if the environment (or its volume) still exists, the factory SHALL commit the leftovers via `exec` inside it — as a service commit that is not a round in `state.json` — and harvest them; if the environment is gone, resume SHALL continue from the last harvested branch state, losing at most the uncommitted tail, never corrupting recorded rounds. Salvage SHALL apply only to gnome-owned work files: factory-owned files under `.gnomish-task/` are restored from the branch tip and never taken from the dirty worktree, per the shared factory-owned-paths policy (see execution-environment). Then the run continues — verification judges the result. `--discard-work` SHALL instead reset the working copy to the last recorded round — sandboxed: dispose the environment and materialize a fresh one from the branch — replaying the interrupted round only, never restarting the task.
<!-- implements FR10 of add-git-workflow -->
<!-- implements FR6 of add-sandbox-core -->
<!-- implements FR5 of harden-task-branch-contract -->

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

### Requirement: Cleanup on completion
On `Completed` the outcome commit SHALL carry a pending-cleanup marker; the cleanup commit removing `.gnomish-task/` from the branch tip is the destructive last step of the completion sequence, created only after the constructive steps have their receipts, and the terminal tracker finish is delivered only after the constructive receipts as well. A tip recording `Completed` with the marker but without the cleanup commit is a finished task awaiting cleanup, never one to re-execute. All state files remain reachable in branch history as the audit trail. In sandboxed mode the cleanup commit SHALL be built factory-side from bare tree objects — no checkout of the branch ever occurs in factory-owned filesystem — and SHALL NOT require a live environment: the last in-box commit is the state commit, and the environment MAY be disposed before the outcome and cleanup commits are created. Host mode keeps the worktree cleanup commit.
<!-- implements FR15 of add-git-workflow -->
<!-- implements FR25 of add-sandbox-core -->
<!-- implements FR10 of harden-task-branch-contract -->

#### Scenario: Clean tip, full history
- **WHEN** a task completes
- **THEN** the branch tip contains no `.gnomish-task/` while every round commit remains in history

#### Scenario: Kill between outcome and cleanup does not re-run the final stage
- **WHEN** an instance dies after the `Completed` outcome commit (marker present) but before the cleanup commit
- **THEN** the next pickup finishes the cleanup, pushes, and delivers the tracker finish — without re-entering the engine or re-running any stage

#### Scenario: Cleanup works after dispose
- **WHEN** a sandboxed task completes and its environment is already disposed
- **THEN** the outcome and cleanup commits are still created factory-side and pushed, with no environment required

### Requirement: Best-effort push
After every round commit the adapter SHALL push best-effort: durability is the recorded branch state; a failed push logs WARN and work continues. In sandboxed mode harvest SHALL precede every push, and a failed harvest skips the push with WARN. The live loop SHALL notice a moved branch tip — host: after tool events (a gnome commit); sandboxed: via the rate-limited environment poll of the Harvest protocol — and harvest and push best-effort mid-round. With no remote configured the run is purely local with no warnings. One exception: for a stage declaring external checks, delivery of the attempt commit is not best-effort — it is a verified precondition of the poll loop (see stage-engine), and an undeliverable commit classifies the check as an infrastructure failure instead of letting it expire as a poll timeout.

The same best-effort push SHALL follow every task lifecycle commit a mode records — an appended resume decision, every terminal outcome, the `Completed` cleanup commit, and the tracker-write-confirmed commit — in both host mode and sandboxed mode, with one push per lifecycle operation. The `Completed` outcome commit and its cleanup commit are two lifecycle operations with two pushes, not one: the terminal tracker write runs between them, and the outcome commit is the intent that write must never precede (FR10). One carve-out: the first push of a newly created task branch is load-bearing — it retries within a bound, and on exhaustion the take aborts before any round starts, so a claim never proceeds on a branch origin has not seen; all subsequent pushes stay best-effort. A timed-out first push is an unknown remote outcome: before any re-push the retry loop SHALL re-check the remote tip within its own bound and SHALL treat a confirmed landed ref as success — the retries here are an infrastructure budget, never the bounded re-attempt that bound-subprocess-commands forbids spending on an interrupted or timed-out invocation. Exactly one code path SHALL own the push-after-lifecycle-commit rule: no caller-side lifecycle pushes remain. The push SHALL complete — succeed, fail with WARN, or no-op with no remote — before the lifecycle operation returns to its caller, so any signal the caller sends next (a tracker write) happens after the replication attempt, never before. A failed lifecycle push logs one WARN naming the task, branch, and lifecycle event.
<!-- implements FR11 of add-git-workflow -->
<!-- implements FR5, FR21 of add-sandbox-core -->
<!-- implements FR1, FR2, FR6, NFR-O1 of fix-lifecycle-push -->
<!-- implements FR7 of harden-task-branch-contract -->

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
- **THEN** the adapter harvests the branch and pushes it best-effort

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

## REMOVED Requirements

### Requirement: Origin divergence rules
Superseded by the ADDED requirement "Automatic origin divergence resolution": true divergence no longer stops the run with an error for the human — it resolves automatically under the live claim. Without a live claim the stop-and-report rule survives, now scoped to that case by the ADDED requirement.
<!-- implements FR8 of harden-task-branch-contract -->

## ADDED Requirements

### Requirement: Automatic origin divergence resolution
On resume with both local and origin branches present, the runner SHALL reconcile: equal → continue; local behind → fast-forward to origin, discarding uncommitted leftovers automatically; local ahead → continue from local (push catches up); truly diverged, under a live claim → discard the local branch automatically — reset the local ref to the origin tip, drop local drafts — and continue, with no operator flag and no exit demanding manual git surgery. Origin wins because a transition is durable across instances only once its push succeeded — a local commit that never reached origin is not durable and may be discarded. That reasoning holds only where the claim protocol is in force, so the discard SHALL be gated on a live claim: a claimless resume SHALL leave the local line intact and report the divergence to the operator instead. The discard reset SHALL be an explicit compare-and-swap against the local tip the discard decision was made on: a tip moved in between fails the reset and reclassification runs. No automatic path SHALL force-push or rewrite origin history; the CAS reset of the local ref is the only non-fast-forward write.
<!-- implements FR9, NFR-R3 of add-git-workflow -->
<!-- implements FR8, NFR-R3 of harden-task-branch-contract -->

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

### Requirement: One logical transition, one commit
Every logical transition of a task SHALL become durable as exactly one commit on the task branch; mutually-implied fields SHALL never split across commits. In particular: a human decision lands in the same commit as the attempt-counter reset it implies; a passing round lands in the same commit as the advanced pipeline position; a container park's outcome lands in the same commit as its pending marker. A kill between any two commits therefore never freezes a half-applied transition.
<!-- implements FR4 of harden-task-branch-contract -->

#### Scenario: Decision and attempt reset are one commit
- **WHEN** a resume decision is recorded
- **THEN** the commit carrying the decision also carries the reset attempt counter, and no tip exists with one but not the other

#### Scenario: Pass and advancement are one commit
- **WHEN** a round's verification passes and the stage advances
- **THEN** the commit recording the passing round also records the advanced position, so a kill after it never re-runs the green stage

#### Scenario: Park outcome and marker are one commit
- **WHEN** a container-mode task parks
- **THEN** its outcome and the pending marker land in one commit, and no tip shows the outcome without the marker or the marker without the outcome

### Requirement: Atomic state-file writes
No reader of `.gnomish-task/` state files — a salvaging resume included — SHALL ever observe a partially written `state.json` or `task.json`. Each write medium realizes the invariant per the crash-consistency ADR's durability table: host-worktree writers SHALL write a temp file and atomically rename it into place; the container-side persisters are atomic at commit granularity — round state is written into the box and committed in-box, lifecycle commits are built from bare objects — so a partial in-box write never reaches a commit, and the factory-owned-paths restore rule keeps salvage away from it.
<!-- implements FR5 of harden-task-branch-contract -->

#### Scenario: A kill mid-write on the host leaves a whole file
- **WHEN** the process dies during a host-worktree state-file write
- **THEN** the path holds either the complete previous content or the complete new content, and the next reader parses it without error

#### Scenario: A kill mid-write in the box never surfaces a partial file
- **WHEN** the process dies during an in-box `state.json` write, leaving a partial file in the box worktree
- **THEN** no commit carries the partial file, and neither resume nor salvage reads it — factory-owned files are restored from the branch tip
