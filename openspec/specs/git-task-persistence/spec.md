# git-task-persistence

## Purpose

Persist a manual-run task's full history — gnome changes, engine state, and audit trail — as commits on a dedicated git branch, so any factory instance can resume a task from the branch alone, with git as the sole source of durable state.

## Requirements

### Requirement: Task lifecycle port
A `TaskRepository` application-layer port SHALL own task-scoped lifecycle writes: create the task branch and record the task context at start, append a `Decision` on resume, and record the `TaskOutcome`/escalation at completion or parking. The engine's `AttemptPersistence` port SHALL remain unchanged; the git adapter SHALL implement both ports over the same task branch. In sandboxed mode these lifecycle commits SHALL be created factory-side over bare git objects in the factory clone — no working copy, no checkout, no hook execution; the branch ref SHALL advance atomically only if the tip is unchanged, and a stale tip fails the write without force. Recording an outcome SHALL NOT require a live environment. On resume, the decision commit SHALL be created before the environment is materialized, so the working copy contains it from the start. Host mode keeps its worktree commits unchanged.
<!-- implements FR1, FR2 of add-git-workflow -->
<!-- implements FR25 of add-sandbox-core -->

#### Scenario: Start creates the branch with the task context
- **WHEN** a git-mode run starts for a new task
- **THEN** the task branch is created and its first commit adds `.gnomish-task/task.json` with the task context

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

### Requirement: Round commit of the whole working tree
In host mode a round SHALL be persisted as one commit of the entire working tree: gnome changes, the updated `state.json`, and the round trace together; no separate state-only service commit exists for host-mode rounds. In sandboxed mode a round SHALL close per the snapshot-first protocol instead: a snapshot commit of the working tree inside the environment, then — after verification — a separate state commit carrying `state.json` and the round trace. Persist failure SHALL abort the task (strict port) in both modes.
<!-- implements FR2, NFR-R1 of add-git-workflow -->
<!-- implements FR21 of add-sandbox-core -->

#### Scenario: Host round atomicity
- **WHEN** a host-mode round finishes and persist runs
- **THEN** exactly one new commit contains the gnome's file changes, `state.json`, and `attempts/<stage>/<round>/trace.jsonl`

#### Scenario: Sandboxed round closes as snapshot plus state
- **WHEN** a sandboxed round finishes and verification completes
- **THEN** the branch gains a snapshot commit with the gnome's file changes and a separate state commit with `state.json` and the round trace

### Requirement: Task branch naming and base
The task branch SHALL be named `gnomish/` + the sanitized taskId: every character outside `[A-Za-z0-9._-]` replaced by `-`, consecutive `-` collapsed, leading/trailing `.`/`-` stripped; an empty result or `.lock` suffix rejects the taskId. The authoritative taskId lives inside `task.json` — never parsed back from the ref name. The branch SHALL be created from the clone's current state, `--base <ref>` overriding; the base commit is recorded in `task.json`. The runner SHALL NOT fetch or pull the base.
<!-- implements FR2, FR7 of add-git-workflow -->

#### Scenario: Unsafe characters sanitized deterministically
- **WHEN** the taskId is `PROJ 42: fix/it`
- **THEN** the branch is `gnomish/PROJ-42-fix-it` while `task.json` keeps the original id

### Requirement: State directory with one writer per file
`.gnomish-task/` at the working-copy root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation), `state.json` (written only by the git `AttemptPersistence`: version, position, attemptsUsed, attempts[] {round, result, startedAt, checks[], denials[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1), `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the round is identified by the file path), and — in git modes — `decisions/<stage>-a<attempt>.json` (written only by the gnome; the single gnome-writable path under `.gnomish-task/`, per the decision-file protocol).
<!-- implements FR3 of add-git-workflow -->
<!-- implements FR23 of add-sandbox-core -->
<!-- implements FR4 of fix-denial-report-attachment -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

#### Scenario: Decision file keeps the one-writer rule
- **WHEN** a round leaves a decision request in `.gnomish-task/decisions/`
- **THEN** the gnome is that file's only writer and every other `.gnomish-task/` path keeps its single factory-side writer

### Requirement: Attempt denials in the state file
Each attempt record in `state.json` SHALL carry a denials list — structured findings recorded by the environment's egress guard during that attempt's round — separate from check results. The list SHALL NOT participate in the attempt's result, the stage's overall verdict, or the prior-failure feedback of a retry. The field is additive under contract v1: readers SHALL treat an absent field as an empty list, and existing documents without it remain readable.
<!-- implements FR2, FR4 of fix-denial-report-attachment -->

#### Scenario: Denials persist with the attempt
- **WHEN** a round with a guard denial is committed to the task branch
- **THEN** the attempt's entry in `state.json` carries the denial finding, and the attempt's result is unchanged by its presence

#### Scenario: Pre-existing state files stay readable
- **WHEN** a state file written before this contract addition is read
- **THEN** it parses under contract v1 with every attempt's denials read as empty

#### Scenario: Denials never feed retries
- **WHEN** an attempt with denials fails on a check and the stage retries
- **THEN** the feedback context of the retry contains only the check findings, not the denials

### Requirement: Denial cursor in the state file
`state.json` SHALL carry the environment's denial read position at commit time — the opaque position paired with the identity of the denial source it was read from — so an instance resuming the task continues the denial delta where the committed attempt left it, instead of re-reading everything the source still holds. The field is environment bookkeeping, not task state: it SHALL NOT affect the position, attempts, or usage a reader reconstructs. It is additive under contract v1: an absent field means "no cursor to resume from", and a writer with no denial source records none.
<!-- implements FR5 of fix-denial-report-attachment -->

#### Scenario: The cursor is committed with the attempt it delimits
- **WHEN** a sandboxed round is committed to the task branch
- **THEN** `state.json` records the environment's denial read position and the identity of the source it was read from

#### Scenario: Resume continues the delta instead of replaying it
- **WHEN** an instance resumes a task whose recorded cursor names the denial source it reattaches to
- **THEN** the first round after resume reports only its own denials, and earlier rounds' denials — already recorded in their own attempts — are not attached to it again

#### Scenario: A state file written before the cursor existed stays readable
- **WHEN** a state file with no cursor field is read
- **THEN** it parses under contract v1 and the run reads its denial source from the beginning

### Requirement: State-file JSON contract v1
`task.json` and `state.json` SHALL carry `"version": 1` and follow status-report v1 conventions (camelCase, ISO-8601 UTC, millisecond durations, sealed types via `"type"`). Readers SHALL ignore unknown fields; an unknown version SHALL refuse resume and the inspection commands (`status`/`usage`) alike, with a clear error naming the file and the unsupported version. Status-report DTOs SHALL NOT be reused; a contract test SHALL hold the StatusReport rendered from state files equivalent to one rendered from live events, anchored by `status-report-v1.reference.json`.
<!-- implements FR4 of add-git-workflow -->

#### Scenario: Unknown version refuses resume
- **WHEN** `state.json` carries `"version": 2`
- **THEN** resume stops with an error naming the file and the unsupported version

#### Scenario: Equivalence with the live report
- **WHEN** the same task history is rendered from events and from the persisted files
- **THEN** the two StatusReports are equivalent per the reference contract

### Requirement: Outcome protocol in task.json
`outcome` SHALL be null while a visit is in progress and SHALL be reset to null at the start of each resumed visit, in the commit carrying the resume decision. `lastEscalation` SHALL be kept separately from `outcome` so the last question/answer stays visible after resume.
<!-- implements FR5 of add-git-workflow -->

#### Scenario: Parked and interrupted are distinguishable
- **WHEN** a resumed task's process dies mid-stage
- **THEN** `task.json` shows outcome null (interrupted) while a parked task shows its recorded outcome

### Requirement: Worktree lifecycle
In host mode, task worktrees SHALL live in `~/.gnomish/worktrees/<project-name>/<sanitized-task-id>/`, outside the clone; the path is printed at start and shown by `status`. In sandboxed mode, the working copy SHALL be materialized and owned by the bound task environment; its location is a private adapter detail. Cleanup by outcome: Completed → remove the working copy (host: `git worktree remove`; sandboxed: environment dispose) — the branch stays; Escalated/Paused → kept; Aborted → always kept. A kept sandboxed environment SHALL be left with no running processes: the container is stopped, while volume and network remain for salvage and resume. Runner start SHALL run `git worktree prune` and the environment orphan sweep.
<!-- implements FR6 of add-git-workflow -->
<!-- implements FR1, FR2 of add-sandbox-core -->

#### Scenario: Aborted keeps the evidence
- **WHEN** a task aborts after a persist failure
- **THEN** the working copy (worktree or environment) is left in place — it may hold the only copy of unrecorded work

#### Scenario: Kept environment is stopped, not running
- **WHEN** a sandboxed task escalates
- **THEN** its container is stopped with volume and network retained, and no gnome process keeps executing

### Requirement: Resume from the recorded branch
`--resume <task>` SHALL locate the branch: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` — never fetching anything else, then continue by `task.json` outcome: escalated → decision dialog; paused → confirmation; null → continue from the recorded position; completed → report "task done" and exit. When the task working copy does not exist locally (another machine, or removed), resume SHALL materialize it through the bound task environment from the branch state alone.
<!-- implements FR8 of add-git-workflow -->
<!-- implements FR6 of add-sandbox-core -->

#### Scenario: Another instance resumes from origin
- **WHEN** the branch exists only on origin
- **THEN** resume fetches that single ref and materializes an environment that continues from the recorded position

### Requirement: Origin divergence rules
On resume with both local and origin branches present, the runner SHALL reconcile: equal → continue; local behind → fast-forward to origin, discarding uncommitted leftovers automatically; local ahead → continue from local (push catches up); diverged → stop with a clear error for the human.
<!-- implements FR9, NFR-R3 of add-git-workflow -->

#### Scenario: Diverged histories stop the run
- **WHEN** local and origin task branches have diverged
- **THEN** resume exits with an error describing both tips and no automatic resolution is attempted

### Requirement: Salvage of interrupted rounds
Uncommitted leftovers of an interrupted round SHALL be salvaged by default, through the port: if the environment (or its volume) still exists, the factory SHALL commit the leftovers via `exec` inside it — as a service commit that is not a round in `state.json` — and harvest them; if the environment is gone, resume SHALL continue from the last harvested branch state, losing at most the uncommitted tail, never corrupting recorded rounds. Then the run continues — verification judges the result. `--discard-work` SHALL instead reset the working copy to the last recorded round — sandboxed: dispose the environment and materialize a fresh one from the branch — replaying the interrupted round only, never restarting the task.
<!-- implements FR10 of add-git-workflow -->
<!-- implements FR6 of add-sandbox-core -->

#### Scenario: Salvage feeds the QC loop
- **WHEN** resume finds uncommitted changes from a dead process
- **THEN** they are committed as a salvage commit and the next round starts with them in the working copy

#### Scenario: Volume survives a dead container
- **WHEN** a factory instance dies mid-round but the task volume remains
- **THEN** a resuming instance salvages the leftovers from the volume as a salvage commit and continues

#### Scenario: Environment lost entirely
- **WHEN** both container and volume are gone
- **THEN** resume materializes a fresh environment from the branch and continues from the last recorded round without error

#### Scenario: Deliberate discard
- **WHEN** resume runs with `--discard-work` in sandboxed mode
- **THEN** the old environment is disposed and a fresh one materialized at the last round commit, with `state.json` unchanged

### Requirement: Best-effort push
After every round commit the adapter SHALL push best-effort: durability is the recorded branch state; a failed push logs WARN and work continues. In sandboxed mode harvest SHALL precede every push, and a failed harvest skips the push with WARN. The live loop SHALL notice a moved branch tip — host: after tool events (a gnome commit); sandboxed: via the rate-limited environment poll of the Harvest protocol — and harvest and push best-effort mid-round. With no remote configured the run is purely local with no warnings. One exception: for a stage declaring external checks, delivery of the attempt commit is not best-effort — it is a verified precondition of the poll loop (see stage-engine), and an undeliverable commit classifies the check as an infrastructure failure instead of letting it expire as a poll timeout.
<!-- implements FR11 of add-git-workflow -->
<!-- implements FR5, FR21 of add-sandbox-core -->

#### Scenario: Push failure does not stop work
- **WHEN** origin is unreachable after a round commit
- **THEN** a WARN is logged and the next round starts normally

#### Scenario: Gnome commit triggers a push
- **WHEN** the gnome commits mid-round in host mode and the next tool event is observed
- **THEN** the adapter pushes the task branch best-effort

#### Scenario: Mid-round tip movement in a box is pushed
- **WHEN** the gnome commits mid-round inside the environment and the rate-limited poll observes the moved tip
- **THEN** the adapter harvests the branch and pushes it best-effort

### Requirement: Push safety rules
Push SHALL be the adapter's monopoly, coded in factory logic — never expressed as rules for the gnome: credentials are not exposed to the agent and prompts contain nothing about push. Push SHALL never run inside a task environment; it runs factory-side, after harvest, with factory credentials. The adapter SHALL push exactly `origin gnomish/<task>`, NEVER with `--force`; a non-fast-forward push fails with WARN and no force retry. Mid-round push preconditions: host — HEAD is on the task branch and the old tip is its ancestor; sandboxed — the fast-forward-only harvest itself proves ancestry, and a refused harvest skips the push with WARN — leaving the authoritative verdict to the round-boundary check.
<!-- implements NFR-S1 of add-git-workflow -->
<!-- implements FR5, NFR-S1 of add-sandbox-core -->

#### Scenario: Non-fast-forward never escalates to force
- **WHEN** a push is rejected as non-fast-forward
- **THEN** the adapter logs WARN and does not retry with `--force`

#### Scenario: No push machinery inside the box
- **WHEN** a sandboxed round runs
- **THEN** the environment holds no push credentials and no remote address, and the only path to origin is the factory-side push after harvest

### Requirement: Gnome commits within a round
Gnome commits inside a round SHALL be allowed (encouraged via stage instructions, using plain git); the adapter's commit closes the round. Boundary verification SHALL run factory-side against harvested refs in sandboxed mode: history rewrite is refused by the fast-forward-only harvest itself; `.gnomish-task/` SHALL be untouched by the gnome between tips — with exactly one carve-out, `decisions/<stage>-a<attempt>.json` (FR23); the in-box HEAD check before the snapshot commit is advisory only. In host mode the existing worktree checks (HEAD on the task branch, previous tip an ancestor, `.gnomish-task/` untouched) remain. A violation breaks durability: persist SHALL throw, aborting the task, with the evidence kept on the branch and in the kept environment.
<!-- implements FR12 of add-git-workflow -->
<!-- implements FR21, FR23 of add-sandbox-core -->

#### Scenario: Fine-grained gnome history is preserved
- **WHEN** the gnome makes three commits during a round
- **THEN** the round-closing commit builds on them and all four commits reach the branch

#### Scenario: History rewrite aborts
- **WHEN** at the round boundary the previous tip is no longer an ancestor of the branch
- **THEN** persist throws (host) or the ff-only harvest refuses (sandboxed) and the task ends Aborted

#### Scenario: Decision request is the one permitted state-directory write
- **WHEN** a gnome commit adds `.gnomish-task/decisions/<stage>-a<attempt>.json` and touches nothing else under `.gnomish-task/`
- **THEN** boundary verification passes; any other `.gnomish-task/` change still aborts

### Requirement: Cleanup on completion
On `Completed` the adapter SHALL add a final cleanup commit removing `.gnomish-task/` from the branch tip; all state files remain reachable in branch history as the audit trail. In sandboxed mode the cleanup commit SHALL be built factory-side from bare tree objects — no checkout of the branch ever occurs in factory-owned filesystem — and SHALL NOT require a live environment: the last in-box commit is the state commit, and the environment MAY be disposed before the outcome and cleanup commits are created. Host mode keeps the worktree cleanup commit.
<!-- implements FR15 of add-git-workflow -->
<!-- implements FR25 of add-sandbox-core -->

#### Scenario: Clean tip, full history
- **WHEN** a task completes
- **THEN** the branch tip contains no `.gnomish-task/` while every round commit remains in history

#### Scenario: Cleanup works after dispose
- **WHEN** a sandboxed task completes and its environment is already disposed
- **THEN** the outcome and cleanup commits are still created factory-side and pushed, with no environment required

### Requirement: Sandboxed round protocol: snapshot and state commits
In sandboxed mode a round SHALL close in two steps. First, a snapshot commit of the whole working tree is executed inside the environment (hooks disabled at argv level) and harvested; verification judges that attempt commit — builtin checks read it as bare git objects in the factory clone, fresh-box checks and judge votes materialize from it, external checks poll CI runs of exactly that pushed commit. Second, after verification, state files (`state.json`, trace) are written via `putFile` and committed as the state commit. At the state-commit harvest the factory SHALL verify integrity: the state commit's parent SHALL be the snapshot commit, and the channel-delivered factory files (`state.json`, the round trace) SHALL be byte-identical to what the factory wrote; any mismatch SHALL abort the task as a boundary violation. `task.json` never crosses the environment channel — its commits are factory-side bare-object commits (see "Task lifecycle port") and need no read-back. Host mode SHALL keep the existing single round commit and tree-level verification.
<!-- implements FR21, FR22 of add-sandbox-core -->

#### Scenario: Verification judges the attempt commit
- **WHEN** a sandboxed round closes and verification runs
- **THEN** builtin, fresh-box, and external checks all observe exactly the harvested snapshot commit, and uncommitted box residue influences none of them

#### Scenario: Interrupted verification resumes without burning an attempt
- **WHEN** a factory dies after the snapshot commit but before the state commit
- **THEN** a resuming instance finds the snapshot unrecorded in `state.json`, re-runs verification against it, and the attempt counter is unchanged

#### Scenario: Daemon-inserted commit is caught by the parent-check
- **WHEN** a background process in the box commits to the branch between the snapshot and state commits
- **THEN** the harvested state commit's parent is not the snapshot commit and the task aborts as a boundary violation

#### Scenario: Tampered state file is caught by read-back
- **WHEN** in-box tampering alters `state.json` content between `putFile` and the state commit
- **THEN** the harvested content differs from what the factory wrote and the task aborts as a boundary violation

### Requirement: Sandboxed working copy is an independent full clone
In sandboxed mode, materialize SHALL create the working copy as `git clone --no-hardlinks` from the factory's local clone into the environment — no network, no credentials, no remote address inside; the clone SHALL set the agent identity and `gc.auto 0`.
<!-- implements FR3 of add-sandbox-core -->

#### Scenario: The box holds no way to the server
- **WHEN** an environment is materialized in container mode
- **THEN** the in-box clone has no configured remote pointing at the real server and no credentials anywhere in the box

#### Scenario: No shared objects with the factory clone
- **WHEN** the clone is created from the local factory clone
- **THEN** it shares no hardlinked objects with it, so in-box corruption cannot reach the factory's repository

### Requirement: Harvest protocol
The factory SHALL collect results by fetching the task branch from the environment with a factory-fixed refspec (never names produced inside the box), fast-forward-only and `--no-recurse-submodules`; rewritten history SHALL be refused. Harvest SHALL precede any push; pushes continue to run outside the environment with factory credentials. Branch-tip observation SHALL be polled and rate-limited on the factory side; event-driven tip detection, if enabled, SHALL watch `.git/logs/HEAD` (refs may be silently packed into `packed-refs`) and SHALL only wake the rate-limited poll.
<!-- implements FR5 of add-sandbox-core -->

#### Scenario: Rewritten history is refused at the boundary
- **WHEN** the branch history inside the box was rewritten
- **THEN** the harvest fetch fails non-fast-forward and the factory treats it as the existing history-rewrite violation

#### Scenario: Hooks do not cross the boundary
- **WHEN** the gnome installs git hooks in the in-box clone
- **THEN** harvest transfers branch content only; no hook becomes active in any factory-managed copy

### Requirement: Factory git executes no untrusted content
The factory SHALL never check out gnome-branch content into its own filesystem namespace: reading gnome branches happens via bare git object access, and materialization of untrusted content happens only inside task environments. Every factory-managed clone SHALL have `core.hooksPath` pointed at an empty directory.
<!-- implements FR17 of add-sandbox-core -->

#### Scenario: Reading a gnome branch runs nothing
- **WHEN** the factory reads state files or artifacts from a gnome branch
- **THEN** it reads git objects directly, no checkout occurs in factory-owned paths, and no hook executes
