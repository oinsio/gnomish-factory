# Operator Guide: Inspecting Tasks (`gnomish status` / `gnomish usage`)

This guide is the reference for the two read-only subcommands that report on a
task from its git branch: `gnomish status` (current state) and `gnomish usage`
(resource/cost usage). Both work without a tracker, reading directly from the
branch the same way `gnomish run --resume` does — via `git show`/git history
only, never a worktree checkout or a local branch creation (the one exception is
a narrow fetch fallback, described below). Neither ever mutates the clone.

The other read surfaces are elsewhere: `gnomish board` (a Kanban view over the
tracker) is covered in [`operator-guide.md`](operator-guide.md), and
`gnomish dashboard` (a self-contained HTML page) in
[`operator-guide-dashboard.md`](operator-guide-dashboard.md).

Unlike `run`, `--dir=<path>` has **no default** for either subcommand — omitting
it is a usage error (exit code 2).

## `gnomish status`

```bash
gnomish status --dir=<clone-dir> [<task>] [--json]
```

- **List mode** (`<task>` omitted): prints a table over all `gnomish/*` branches, local and remote-tracking alike, deduplicated per task (the local tip wins when both exist).

  | Column   | Meaning                                                   |
  |----------|-----------------------------------------------------------|
  | task     | task id (the branch name for a branch with no readable id) |
  | shape    | the branch's classified shape (see below)                  |
  | stage    | current pipeline stage                                     |
  | attempts | attempts recorded so far                                   |
  | outcome  | last recorded outcome, or the diagnosis of a bad branch     |

  Every `gnomish/*` branch produces exactly one row whatever state it is in — a
  delivered branch whose cleanup commit stripped `.gnomish-task/`, a freshly
  created one with no round yet, an in-flight one, a parked one, and a branch
  whose files cannot be read. One unreadable branch degrades to its own
  diagnostic row naming its shape; it never fails the listing of the others.

- **Single-task mode** (`<task>` given): reads `.gnomish-task/` straight off `gnomish/<task>` via `git show` — no worktree is materialized, no checkout happens, no local branch is created. If the branch isn't already known locally or as a remote-tracking ref, `status` falls back to a narrow fetch of exactly `gnomish/<task>`. Output is the same StatusReport `"version": 1` contract used by the live in-process `status`/`status --json` prompt commands (see [`operator-guide-run.md`](operator-guide-run.md)), plus the task's worktree path if one currently exists.
- **Every shape renders**: the branch's shape is classified before anything is read from it, so `status` reports a delivered branch as delivered and a freshly created one as pending instead of failing on state files that shape does not have. For those branches the output is the shape itself:

  ```
  Task: PROJ-1
  Shape: Delivered
  ```

- **A branch that refuses inspection**: the three quarantine shapes — `Corrupt`, `UnsupportedVersion`, `Unknown` — print a `Diagnosis:` line naming the offending file and the observed versus expected content (for `UnsupportedVersion`, the observed and supported versions) and exit with code 7. No stack trace, and nothing in the clone is mutated. Example:

  ```
  Task: PROJ-1
  Shape: UnsupportedVersion
  Diagnosis: state.json declaring version 2 where this factory supports 1
  ```

  Exit code 7 means "the branch is there but this factory cannot read it" — distinct from code 6 ("no such branch") and code 1 (the tool itself broke).

- **Task not found**: if no `gnomish/<task>` branch exists — typically because its PR was already squash-merged and the branch deleted, see [Merging a gnome's task branch](operator-guide-run.md#merging-a-gnomes-task-branch) — `status` prints `task not found: <task>` and exits with code 6. This is a normal, expected outcome of a task's lifecycle, not an error to investigate.

## `gnomish usage`

```bash
gnomish usage --dir=<clone-dir> <task> [--json]
```

Unlike `status`, `<task>` is mandatory here — there is no list mode.

`usage` reconstructs per-stage/per-round resource usage by walking the git history of `.gnomish-task/state.json` on the task branch, chronologically from oldest to newest — not by parsing commit messages. A commit counts as a "round record" when its `state.json` shows a new attempt (the attempts list grew, or the pipeline position advanced to a new stage visit); salvage, `task.json`, and cleanup commits are naturally skipped since they don't add a new attempt record. `usage` only makes sense in git mode — a `--mode=in-place` run has no branch or history to reconstruct from.

**Text output** (default) is a stage/round table plus totals (wall time, tokens).

**JSON output** (`--json`) exposes full granularity under its own `"version": 1` mini-contract — a separate schema from the `status` StatusReport, not reused:

```json
{
  "version": 1,
  "taskId": "<string>",
  "rows": [
    {
      "stage": "<name>",
      "round": 1,
      "result": "<string>",
      "startedAt": "<ISO-8601 UTC>",
      "checks": [ "..." ],
      "executorUsage": {
        "wallMillis": 0,
        "tokensByModel": {
          "<model>": { "input": 0, "output": 0, "cacheCreation": 0, "cacheRead": 0 }
        },
        "byTool": [ "..." ]
      },
      "judgeUsage": { "perVote": [ "..." ] }
    }
  ],
  "totals": {
    "wallMillis": 0,
    "tokensByModel": {
      "<model>": { "input": 0, "output": 0, "cacheCreation": 0, "cacheRead": 0 }
    },
    "byTool": []
  }
}
```

`totals.byTool` is always empty — totals aggregate executor usage only, not a per-tool breakdown.

**Unreadable history**: a historical commit whose `state.json` cannot be read — an unsupported envelope version, a half-written or hand-edited document — is skipped with a WARN naming the commit, and the walk continues. The table and totals render from the readable commits; only the rounds that broken commit itself would have contributed are missing.

**Task not found**: same as `status` — `usage` prints `task not found: <task>` and exits with code 6 when the branch is gone, which is normal after a squash-merged PR, not a bug.
