## MODIFIED Requirements

### Requirement: Worktree lifecycle
In host mode, task worktrees SHALL live in `GNOMISH_HOME/projects/<name>/worktrees/<clone>/<sanitized-task-id>/`, outside the clone, where `<name>` is the registered project and `<clone>` the registered clone name of the `--dir` clone; the path is printed at start and shown by `status`. Two clones of one project SHALL never share a worktree folder. In sandboxed mode, the working copy SHALL be materialized and owned by the bound task environment; its location is a private adapter detail. Cleanup by outcome: Completed → remove the working copy (host: `git worktree remove`; sandboxed: environment dispose) — the branch stays; Escalated/Paused → kept; Aborted → always kept. A kept sandboxed environment SHALL be left with no running processes: the container is stopped, while volume and network remain for salvage and resume. Runner start SHALL run `git worktree prune` and the environment orphan sweep; the worktree janitor SHALL sweep only its own clone's worktree folder.
<!-- implements FR6 of add-git-workflow -->
<!-- implements FR1, FR2 of add-sandbox-core -->
<!-- implements FR9, NFR-R2 of add-project-registry -->

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
