# Proposal: add-git-workflow

## Why

The engine and manual run keep all task state in memory: when the process
exits, the task dies with it. The factory's core architectural promise —
stateless instances, working state in the task's git branch, any instance can
resume — has no implementation yet. This change delivers task persistence in
git (state files, a commit per round, worktrees, push), resume across
processes and machines, and external read-only `status`/`usage` commands.

## What Changes

- **ADDED**: task lifecycle port (working name `TaskRepository`, application
  layer) and a git adapter implementing it together with the engine's
  existing `AttemptPersistence` port on one task branch
- **ADDED**: `.gnomish-task/` state directory contract — `task.json`,
  `state.json`, `attempts/<stage>/<round>/trace.jsonl`; one writer per file
- **ADDED**: per-task git worktrees under `~/.gnomish/worktrees/`, lifecycle
  tied to task outcome
- **ADDED**: resume protocol — `gnomish run --dir <dir> --resume <task>`,
  outcome-driven continuation, salvage of interrupted rounds, origin
  divergence rules
- **ADDED**: push policy — best-effort push after each round and on gnome
  commits, hard safety rules coded in the adapter
- **ADDED**: `gnomish status --dir <dir> [<task>] [--json]` — external
  reader over task branches, plus a minimal list mode
- **ADDED**: `gnomish usage --dir <dir> <task> [--json]` — per-stage/round
  token and time usage reconstructed from `state.json` git history
- **MODIFIED**: `gnomish run` CLI — `--dir <dir> [--mode git|in-place]`,
  default `git`; the add-manual-run behavior is preserved as explicit
  `--mode in-place`
- **MODIFIED**: agent-cli executor live loop notices gnome commits and
  triggers mid-round push (deliberate touch of add-agent-executor scope)

## Goals

- **G1**: a task survives process death: every completed round is durable in
  the task branch; resume continues from the recorded position
- **G2**: any factory instance can resume a task that another instance
  pushed to origin — no shared state outside git and (later) the tracker
- **G3**: task progress and cost are observable from outside the running
  process, without mutating the clone or the task working copy
- **G4**: the target project clone given via `--dir` is never mutated by
  git mode; all work happens in a separate worktree

## Non-Goals

- **NG1**: factory loop concerns — task claiming, heartbeats, strict push
  mode, auto fetch/pull of the base, cleanup policies by age, multitask
  analytics and filters
- **NG2**: tracker integration (escalation statuses, final summaries in
  tickets) — the long-lived record after branch deletion is the tracker's
  future job; no factory-side journal archive
- **NG3**: enforcing gnome git discipline by sandboxing — violations are
  detected at the round boundary, not prevented (accepted risk, in line with
  add-agent-executor NG5)
- **NG4**: state-file migration tooling — unknown version refuses resume;
  no converters
- **NG5**: webhooks or any inbound HTTP — external reads are polled/pulled

## Users & Scenarios

- **U1 — operator, one machine**: starts a task in git mode, the process
  dies mid-stage; restarts with `--resume`, the factory salvages the
  interrupted round and continues
- **U2 — second instance / another machine**: resumes a task it never ran:
  fetches the task branch from origin and continues from the recorded state
- **U3 — pipeline author**: dry-runs `.gnomish/` config with
  `--mode in-place` in a scratch directory — no branches, no worktrees, no
  resume, exactly the add-manual-run behavior
- **U4 — observer**: from another terminal, checks a running or parked
  task with `gnomish status` / `gnomish usage` against the clone, without
  touching the task's working copy

## Requirements

### Functional

- **FR1**: A task lifecycle port (`TaskRepository`, application layer) owns
  task-scoped writes: create branch + record task context at start, append a
  `Decision` on resume, record `TaskOutcome`/escalation at
  completion/parking. The engine's `AttemptPersistence` port is unchanged.
- **FR2**: The git adapter implements both ports over one task branch:
  a round = one commit of the entire working tree (gnome changes + state
  file + trace together). Branch name: `gnomish/` + deterministically
  sanitized taskId; the authoritative taskId lives inside `task.json`.
- **FR3**: `.gnomish-task/` at the worktree root holds exactly:
  `task.json` (written by `TaskRepository`: version, taskId, title, body,
  createdAt, baseCommit, decisions, outcome, lastEscalation), `state.json`
  (written by git `AttemptPersistence`: version, position, attemptsUsed,
  attempts, totals), `attempts/<stage>/<round>/trace.jsonl` (tool trace,
  one JSON line per tool call). One writer per file.
- **FR4**: State files carry `"version": 1` and follow status-report v1
  JSON conventions (camelCase, ISO-8601 UTC, millisecond durations, sealed
  types via `"type"`). Readers ignore unknown fields; an unknown version
  refuses resume and the `status`/`usage` readers alike, with a clear
  error naming the file and version. Status-report DTOs are NOT reused —
  the state file is a separate contract kept consistent by an equivalence
  contract test.
- **FR5**: `task.json` outcome protocol: `outcome` is null while a visit is
  in progress and is reset to null at the start of each visit (in the commit
  carrying the resume decision); `lastEscalation` is kept separately so
  `status` can show the last question/answer after resume.
- **FR6**: Task worktrees live in
  `~/.gnomish/worktrees/<project-name>/<sanitized-task-id>/`, outside the
  clone. The path is printed to the console and shown by `status`. Worktree
  cleanup by outcome: Completed → removed (branch stays); Escalated/Paused →
  kept; Aborted → always kept. Runner start runs `git worktree prune`.
- **FR7**: `gnomish run --dir <dir> [--mode git|in-place]`, default `git`.
  Git mode: `--dir` is the project clone, the factory creates branch +
  worktree + commits + pushes; the clone itself is untouched. In-place mode:
  the preserved add-manual-run behavior — no git, in-memory state, no
  resume, honest reminder at start. `--base <ref>` overrides the branch
  base (default: current clone state; recorded in `task.json`). Git-only
  flags (`--base`, `--resume`, `--discard-work`) with `--mode in-place` are
  a usage error (exit code 2).
- **FR8**: `gnomish run --dir <dir> --resume <task>` resumes by branch:
  found locally, else remote-tracking, else a narrow fetch of exactly
  `gnomish/<task>`. Continuation is driven by `task.json` outcome:
  escalated → decision dialog → continue; paused → confirmation → next
  stage; outcome null (process died) → continue from recorded position;
  completed → report and exit.
- **FR9**: Local/origin divergence on resume: equal → continue; local
  behind → fast-forward, uncommitted leftovers discarded automatically;
  local ahead (unpushed) → continue from local; diverged → stop with a
  clear error for the human.
- **FR10**: Uncommitted leftovers of an interrupted round are salvaged by
  default: committed as-is in a service commit (not counted as a round in
  `state.json`) and continued — the QC loop evaluates the result.
  `--discard-work` instead resets the working copy to the last recorded
  round (replays the interrupted round; never restarts the whole task).
- **FR11**: Push is best-effort after every round: durability = local
  commit; a failed push logs WARN and work continues. No remote configured →
  purely local mode, no warnings. The adapter also notices gnome commits
  mid-round (tip moved) and pushes best-effort.
- **FR12**: Gnome commits inside a round are allowed (encouraged via stage
  instructions); the round is closed by the adapter's commit updating
  `state.json` + trace. Round-boundary protocol checks: still on the task
  branch, old tip is an ancestor of the new one, `.gnomish-task/` untouched
  by the gnome. Violation = broken durability → persist throws → Aborted.
- **FR13**: `gnomish status --dir <dir> [<task>] [--json]` reads
  `.gnomish-task/` files directly from the branch (`git show`) — no
  worktree, no checkout, no local branch creation; narrow fetch as a last
  resort. Rendering reuses the status-report pure function and JSON contract
  v1 with live-only fields null. Without a task argument: a minimal table
  over all `gnomish/*` branches — local and remote-tracking, deduplicated
  per task with the local tip preferred (task, stage, attempts, outcome).
- **FR14**: `gnomish usage --dir <dir> <task> [--json]` reconstructs
  per-stage/per-round usage (tokens in/out/cache, wall time, verdicts) from
  the git history of `state.json` — the first consumer of "state-file git
  history = attempt archive". Text: a stage/round table + totals; `--json`:
  full granularity (tokensByModel, judge votes) under its own
  `"version": 1` mini-contract. Git mode only.
- **FR15**: On Completed, a final cleanup commit removes `.gnomish-task/`
  from the branch tip; the files remain in branch history as the audit
  trail. Documentation recommends squash-merging gnome PRs.

### Non-Functional — Reliability

- **NFR-R1**: Durability boundary is the local round commit: a failed
  persist (commit) aborts the task (strict port, add-stage-engine D7);
  a failed push never does.
- **NFR-R2**: A crash between the last round commit and the outcome write
  yields "rounds present, no outcome" — `status` reports it honestly as
  in-progress/interrupted (matches nullable live fields of contract v1).
- **NFR-R3**: For other instances, unpushed work does not exist: resume
  semantics rely only on what reached origin, and the divergence rules
  (FR9) keep the two histories reconcilable without force.

### Non-Functional — Observability

- **NFR-O1**: For a live task, `status`/`usage` show the last recorded
  round boundary, not "right now" — status via shared state, by design.
- **NFR-O2**: Every skipped or failed push, salvage, and protocol-check
  failure is logged with taskId/stage/round context; instance logs stay in
  `~/.gnomish/logs/` and never enter the branch.

### Non-Functional — Security

- **NFR-S1**: Push is the adapter's monopoly: git credentials are never
  handed to the gnome; nothing push-related enters prompts. Hard rules are
  factory Java logic: push exactly `origin gnomish/<task>`, never
  `--force`; a non-fast-forward push fails with WARN, no force retries;
  mid-round push runs only after ancestry checks.
- **NFR-S2**: Only `.gnomish-task/` structural artifacts enter the branch;
  decision-file temp dirs and logs stay outside the workspace
  (add-agent-executor NFR-S2 preserved).

### Non-Functional — Cost

- **NFR-C1**: `usage` accounts every recorded round of every stage visit
  (including failed attempts), so operators can see token/time cost before
  merging; no separate cost store is introduced.

## Operator Experience Criteria

- **UX1**: `run` in git mode prints the branch name and worktree path
  upfront; the operator always knows where the work lives
- **UX2**: resume dialogs mirror the in-process escalation dialogs of
  manual-run — same questions, same feel, state loaded from the branch
- **UX3**: after a task branch is deleted (merged PR), `status`/`usage` say
  "task not found" plainly — branch death after merge is normal, not a loss
- **UX4**: `--mode in-place` reminds at start that exit means the task dies

## Success Metrics

- **M1**: `kill -9` at any point of a git-mode run → `--resume` completes
  the task; loss is bounded by the interrupted round's uncommitted work,
  which salvage recovers into the QC loop by default
- **M2**: contract test: StatusReport rendered from state files ≡
  StatusReport rendered from live events, anchored by
  `status-report-v1.reference.json` (byte-identical)
- **M3**: `status`/`usage` leave the clone unchanged: no working-copy
  mutation, no local branches created; the only allowed side effect is the
  explicit narrow fetch of `gnomish/<task>`
- **M4**: after Completed, the branch tip contains no `.gnomish-task/`,
  while every round remains reachable in branch history

## Open Questions

- **Q1**: exact machine-recognizable commit-message scheme for service
  commits (round, task.json, salvage, cleanup) — fixed in design; drives
  the `usage` history-reconstruction algorithm
- **Q2**: final disposition of each add-manual-run flag (`--task`,
  `--task-file`, `--task-id`, `--from-stage`) within the new
  `--dir`/`--mode` line-up — settled in the manual-run delta spec

## Capabilities

### New Capabilities

- `git-task-persistence`: task branch as durable state — lifecycle port +
  git adapter, `.gnomish-task/` file contracts, round commits, salvage,
  push policy, worktrees, resume protocol
- `task-inspection`: read-only `gnomish status` and `gnomish usage`
  commands over task branches, including the branch-list mode and the
  usage JSON mini-contract

### Modified Capabilities

- `manual-run`: CLI surface reshaped — `--dir` + `--mode git|in-place`
  (default `git`), new git-mode flags (`--base`, `--resume`,
  `--discard-work`), in-place mode preserved as the explicit legacy
  behavior; usage-error rules extended

## Impact

- New application-layer port (`TaskRepository`) and a git adapter package
  (git subprocess, no JGit) implementing it plus `AttemptPersistence`
- New CLI subcommands (`status`, `usage`) and reworked `run` argument
  parsing; exit-code contract of manual-run reused
- Touches the agent-cli executor's live loop (mid-round push trigger) —
  coordinated with the active add-agent-executor change
- New test surface: local bare git repos for workflow tests; Testcontainers
  + Gitea E2E layer introduced for real-remote scenarios
- Engine domain (`domain/engine/`) unchanged; status-report contract v1
  unchanged
