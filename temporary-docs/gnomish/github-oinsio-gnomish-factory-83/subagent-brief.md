# Brief for every task sub-agent of make-checkpoint-gate-durable

Working directory: /Users/oinsio/.gnomish/projects/gnomish-factory/worktrees/gnomish-factory/github-oinsio-gnomish-factory-83
(a git worktree — stay in it).

Change: `make-checkpoint-gate-durable`. Artifacts (read what your task needs; design.md and
tasks.md always):
- openspec/changes/make-checkpoint-gate-durable/proposal.md
- openspec/changes/make-checkpoint-gate-durable/design.md (D1–D9; single-owner table D7 at lines 176–184; kill-window table after it)
- openspec/changes/make-checkpoint-gate-durable/tasks.md
- openspec/changes/make-checkpoint-gate-durable/specs/**/spec.md (git-task-persistence,
  lifecycle/task-branch-contract, manual-run, stage-engine, status-report, tracker-take)

Project rules: CLAUDE.md and `.claude/rules/` — always `testing.md`, `verification-scope.md`,
`implementation.md`, `process-invariants.md`, `traceability.md`, `logging.md` (for Java); plus
`crash-consistency.md`, `manual-sync-pairs.md`, `lock-scope.md` when the task touches them.

## Hard constraints

- Do NOT make any git commits, and never push. No `git checkout/reset/stash/restore/clean/pull`.
- Never edit `.claude/`, `.gnomish/`, `openspec/specs/` or `openspec/changes/archive/`.
- Do not edit the change's proposal/design/specs, and do not tick tasks.md — the orchestrator does.
- Implement ONLY your task. Earlier tasks are already done in the working tree; later tasks
  are not — if the build needs a minimal stub for a later task to compile, say so explicitly.
- Nobody can answer questions. If the task cannot be done as written (unclear, a design
  issue, would need scope beyond the spec, or you are tempted to narrow/defer specified
  behaviour), STOP and report it as a BLOCKER with file:line and what you would do — do not guess.
- TDD with Spock; traceability comments `FR-X: ...` / `Implements FR-X of make-checkpoint-gate-durable`.
- File size target ≤ 200 lines; parameter limit 7 (compile-time gate).

## Verification (per task — `.claude/rules/verification-scope.md`)

1. `./gradlew :<module>:spotlessApply :<module>:compileTestGroovy` for each touched module.
2. Run the specs you wrote/changed and the specs of the classes you changed, by name:
   `./gradlew :<module>:test --tests '<Spec>'`. Never the whole `:bootstrap:test`.
3. `./gradlew :<module>:pitestVerifyAllKilled -PpitScope=<fqcn>,<fqcn>` over the production
   classes you changed; fix survivors now.
4. Never run a module-wide or root `check`, never a branch-wide PIT run.
Pipe long output through `tail`.

## Report (final message)

- Done: what changed, main files.
- Verified: exact commands run and results (spec names, pass counts, PIT result).
- Sweep (single-owner tasks): grep commands run, every hit, and what happened to each.
- Blockers / deviations from the task text, if any.
