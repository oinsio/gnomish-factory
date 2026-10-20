# Brief for every implementation sub-agent of own-git-invocation-policy

Change: `own-git-invocation-policy`. Artifacts (read the parts your task needs):
- openspec/changes/own-git-invocation-policy/proposal.md
- openspec/changes/own-git-invocation-policy/design.md (decisions D1–D10; D10 is the single-owner table)
- openspec/changes/own-git-invocation-policy/specs/git/git-invocation-policy/spec.md
- openspec/changes/own-git-invocation-policy/specs/git/git-task-persistence/spec.md
- openspec/changes/own-git-invocation-policy/specs/build-system/module-layering/spec.md
- openspec/changes/own-git-invocation-policy/tasks.md (implement ONLY the task you were given)

Project rules: CLAUDE.md, and in .claude/rules/: testing.md, implementation.md, traceability.md,
process-invariants.md, verification-scope.md, spec-mismatch.md, logging.md (for Java),
lock-scope.md and manual-sync-pairs.md where relevant.

Hard constraints:
- Do NOT make any git commits, and never push. No git checkout/reset/stash/restore/clean/pull.
- Never edit `.claude/`, `.gnomish/`, `openspec/specs/` or `openspec/changes/archive/`. Do not edit
  the change's proposal.md, design.md or specs. Do not tick tasks.md — the orchestrator does that.
- Exception: a task that explicitly says to update `.claude/rules/testing.md` — do NOT edit it;
  report the exact text you would add instead, the orchestrator handles it.
- "Narrow the scenario, no code change" is never an option you may offer. If the task cannot be
  done as written, stop and report a blocker in the form of `.claude/rules/spec-mismatch.md`
  (every premise verified with file:line, the class mapped, alternative zero, options ranked by
  failure class, what each leaves behind). Do not guess around a design gap.
- Never weaken a gate (no @DoNotMutate, exclusion or suppression testing.md does not allow).
- Traceability: code units get `Implements FR-x of own-git-invocation-policy` javadoc; specs get
  `FR-x: ...` in descriptions; scenario markers `// Scenario: <spec-id> / <title>` per testing.md.
- Files target 100–120 lines, 200 hard cap. Max 7 parameters.

Verification per task (verification-scope.md) — never a module-wide `check` of `:bootstrap`,
never a branch-wide PIT run:
1. `./gradlew :<module>:spotlessApply :<module>:compileTestGroovy` for each touched module
2. `./gradlew :<module>:test --tests '<Spec>'` for the specs you wrote/changed and specs of the
   classes you changed (small modules may run their whole `test`)
3. `./gradlew :<module>:pitestVerifyAllKilled -PpitScope=<fqcn>,<fqcn>` for the production
   classes you changed (skip if no production Java changed); fix survivors now
4. Architecture/build specs your task names, by name
5. Platform-subject specs (real processes, pipes, signals) also via `scripts/linux-specs.sh` with
   the same --tests arguments, if Docker is available
Pipe long output through `tail`. Even when a task's text says "`:x:check` green", these scoped
steps are what you run.

Report at the end (concise): files created/changed, specs run and their results (exact commands),
PIT result, and for single-owner tasks the old-way sweep (grep, every hit, what happened to each).
