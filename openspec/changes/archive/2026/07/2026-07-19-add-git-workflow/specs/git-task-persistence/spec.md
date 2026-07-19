# git-task-persistence (delta)

## ADDED Requirements

### Requirement: Task lifecycle port
A `TaskRepository` application-layer port SHALL own task-scoped lifecycle writes: create the task branch and record the task context at start, append a `Decision` on resume, and record the `TaskOutcome`/escalation at completion or parking. The engine's `AttemptPersistence` port SHALL remain unchanged; the git adapter SHALL implement both ports over the same task branch.
<!-- implements FR1, FR2 of add-git-workflow -->

#### Scenario: Start creates the branch with the task context
- **WHEN** a git-mode run starts for a new task
- **THEN** the task branch is created and its first commit adds `.gnomish-task/task.json` with the task context

#### Scenario: Parking records the outcome
- **WHEN** a run ends with `Escalated`
- **THEN** the outcome and escalation report are committed to `task.json` before the process exits

### Requirement: Round commit of the whole working tree
A round SHALL be persisted as one commit of the entire working tree: gnome changes, the updated `state.json`, and the round trace together. Persist failure SHALL abort the task (strict port); no separate state-only service commit exists for rounds.
<!-- implements FR2, NFR-R1 of add-git-workflow -->

#### Scenario: Round atomicity
- **WHEN** a round finishes and persist runs
- **THEN** exactly one new commit contains the gnome's file changes, `state.json`, and `attempts/<stage>/<round>/trace.jsonl`

### Requirement: Task branch naming and base
The task branch SHALL be named `gnomish/` + the sanitized taskId: every character outside `[A-Za-z0-9._-]` replaced by `-`, consecutive `-` collapsed, leading/trailing `.`/`-` stripped; an empty result or `.lock` suffix rejects the taskId. The authoritative taskId lives inside `task.json` — never parsed back from the ref name. The branch SHALL be created from the clone's current state, `--base <ref>` overriding; the base commit is recorded in `task.json`. The runner SHALL NOT fetch or pull the base.
<!-- implements FR2, FR7 of add-git-workflow -->

#### Scenario: Unsafe characters sanitized deterministically
- **WHEN** the taskId is `PROJ 42: fix/it`
- **THEN** the branch is `gnomish/PROJ-42-fix-it` while `task.json` keeps the original id

### Requirement: State directory with one writer per file
`.gnomish-task/` at the worktree root SHALL hold exactly: `task.json` (written only by `TaskRepository`: version, taskId, title, body, createdAt, baseCommit, decisions[] {text, author?, stage?, at?}, outcome — null | completed | paused{passedStage} | escalated{report} | aborted{failedAt, cause} — and lastEscalation), `state.json` (written only by the git `AttemptPersistence`: version, position, attemptsUsed, attempts[] {round, result, startedAt, checks[], executorUsage, judgeUsage}, totals — inner forms as in status-report v1), and `attempts/<stage>/<round>/trace.jsonl` (one JSON line per tool call; the round is identified by the file path).
<!-- implements FR3 of add-git-workflow -->

#### Scenario: History of past stages lives in git
- **WHEN** the task advances to the next stage
- **THEN** `state.json` contains only the current stage's attempts; earlier rounds remain in the file's git history

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
Task worktrees SHALL live in `~/.gnomish/worktrees/<project-name>/<sanitized-task-id>/`, outside the clone; the path is printed at start and shown by `status`. Cleanup by outcome: Completed → `git worktree remove` (branch stays); Escalated/Paused → kept; Aborted → always kept. Runner start SHALL run `git worktree prune`.
<!-- implements FR6 of add-git-workflow -->

#### Scenario: Aborted keeps the evidence
- **WHEN** a task aborts after a persist failure
- **THEN** the worktree is left in place — it may hold the only copy of unrecorded work

### Requirement: Resume from the recorded branch
`--resume <task>` SHALL locate the branch: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` — never fetching anything else, then continue by `task.json` outcome: escalated → decision dialog; paused → confirmation; null → continue from the recorded position; completed → report "task done" and exit. When the task worktree does not exist locally (another machine, or removed), resume SHALL materialize it at the standard location before continuing.
<!-- implements FR8 of add-git-workflow -->

#### Scenario: Another instance resumes from origin
- **WHEN** the branch exists only on origin
- **THEN** resume fetches that single ref and continues from the recorded position

### Requirement: Origin divergence rules
On resume with both local and origin branches present, the runner SHALL reconcile: equal → continue; local behind → fast-forward to origin, discarding uncommitted leftovers automatically; local ahead → continue from local (push catches up); diverged → stop with a clear error for the human.
<!-- implements FR9, NFR-R3 of add-git-workflow -->

#### Scenario: Diverged histories stop the run
- **WHEN** local and origin task branches have diverged
- **THEN** resume exits with an error describing both tips and no automatic resolution is attempted

### Requirement: Salvage of interrupted rounds
Uncommitted leftovers of an interrupted round SHALL be salvaged by default: committed as-is in a service commit that is not a round in `state.json`, then the run continues — verification judges the result. `--discard-work` SHALL instead reset the working copy to the last recorded round, replaying the interrupted round only — never restarting the task.
<!-- implements FR10 of add-git-workflow -->

#### Scenario: Salvage feeds the QC loop
- **WHEN** resume finds uncommitted changes from a dead process
- **THEN** they are committed as a salvage commit and the next round starts with them in the working copy

#### Scenario: Deliberate discard
- **WHEN** resume runs with `--discard-work`
- **THEN** the working copy equals the last round commit and `state.json` is unchanged

### Requirement: Best-effort push
After every round commit the adapter SHALL push best-effort: durability is the local commit; a failed push logs WARN and work continues. The agent-cli live loop SHALL notice a moved branch tip after tool events (a gnome commit) and push best-effort mid-round. With no remote configured the run is purely local with no warnings.
<!-- implements FR11 of add-git-workflow -->

#### Scenario: Push failure does not stop work
- **WHEN** origin is unreachable after a round commit
- **THEN** a WARN is logged and the next round starts normally

#### Scenario: Gnome commit triggers a push
- **WHEN** the gnome commits mid-round and the next tool event is observed
- **THEN** the adapter pushes the task branch best-effort

### Requirement: Push safety rules
Push SHALL be the adapter's monopoly, coded in factory logic — never expressed as rules for the gnome: credentials are not exposed to the agent and prompts contain nothing about push. The adapter SHALL push exactly `origin gnomish/<task>`, NEVER with `--force`; a non-fast-forward push fails with WARN and no force retry; mid-round push runs only after verifying HEAD is on the task branch and the old tip is its ancestor — otherwise it is skipped with WARN, leaving the authoritative verdict to the round-boundary check.
<!-- implements NFR-S1 of add-git-workflow -->

#### Scenario: Non-fast-forward never escalates to force
- **WHEN** a push is rejected as non-fast-forward
- **THEN** the adapter logs WARN and does not retry with `--force`

### Requirement: Gnome commits within a round
Gnome commits inside a round SHALL be allowed (encouraged via stage instructions, using plain git); the adapter's commit closes the round. At the round boundary the adapter SHALL verify: HEAD is on the task branch, the previous tip is an ancestor of HEAD, and `.gnomish-task/` was not modified by the gnome. A violation breaks durability: persist SHALL throw, aborting the task.
<!-- implements FR12 of add-git-workflow -->

#### Scenario: Fine-grained gnome history is preserved
- **WHEN** the gnome makes three commits during a round
- **THEN** the round-closing commit builds on them and all four commits reach the branch

#### Scenario: History rewrite aborts
- **WHEN** at the round boundary the previous tip is no longer an ancestor of HEAD
- **THEN** persist throws and the task ends Aborted

### Requirement: Cleanup on completion
On `Completed` the adapter SHALL add a final cleanup commit removing `.gnomish-task/` from the branch tip; all state files remain reachable in branch history as the audit trail.
<!-- implements FR15 of add-git-workflow -->

#### Scenario: Clean tip, full history
- **WHEN** a task completes
- **THEN** the branch tip contains no `.gnomish-task/` while every round commit remains in history
