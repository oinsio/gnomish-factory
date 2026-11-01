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
it is a usage error (exit code 2). The path must be a registered clone (see
[`operator-guide.md` → *Setting up a project*](operator-guide.md#setting-up-a-project));
a symlink to one resolves to it, and an unregistered path exits 2 with the
`gnomish project add` line to paste.

## `gnomish status`

```bash
gnomish status --dir=<clone-dir> [<task>] [--json]
```

- **List mode** (`<task>` omitted): prints a table over all `gnomish/*` branches, local and remote-tracking alike, deduplicated per task (the local tip wins when both exist). The listing fetches nothing per branch, so a task held only as a remote-tracking ref, whose register no fetch has brought into this clone yet, lists as `Unregistered` — its outcome column says the branch was fetched without its factory register and that `gnomish status <task>` fetches it — until a single-task `status` or any other command touching the task fetches the register. A local branch without its register lists the same way, but the factory's branch lookup fetches nothing for a local branch, so a `status` does not change it (see [A finished task whose branch cleanup was cut short](operator-guide.md#a-finished-task-whose-branch-cleanup-was-cut-short)).

  | Column   | Meaning                                                    |
  |----------|------------------------------------------------------------|
  | task     | task id (the branch name for a branch with no readable id) |
  | shape    | the branch's classified shape (see below)                  |
  | stage    | current pipeline stage                                     |
  | attempts | attempts recorded so far                                   |
  | outcome  | last recorded outcome, or the diagnosis of a bad branch    |

  Every `gnomish/*` branch produces exactly one row whatever state it is in — a
  delivered branch whose cleanup commit stripped `.gnomish-task/`, a freshly
  created one with no round yet, an in-flight one, a parked one, and a branch
  whose files cannot be read. One unreadable branch degrades to its own
  diagnostic row naming its shape; it never fails the listing of the others.

- **Single-task mode** (`<task>` given): reads `.gnomish-task/` straight off `gnomish/<task>` via `git show` — no worktree is materialized, no checkout happens, no local branch is created. If no local branch exists, `status` fetches exactly `gnomish/<task>` and its register together (`git clone` does not copy the register); a remote-tracking ref is used only when that fetch does not deliver, and a branch read that way without its register reports `Unregistered` with the same diagnosis as in list mode, naming `gnomish status <task>` as the step that fetches the register, and exits with code 7, as every shape that refuses inspection does (below). A branch still `Unregistered` after that fetch means origin delivered no register for the task or could not be reached — `status` cannot tell which: inspect it before you return the task to the ready state. A local branch is read as it is, with no fetch. Text and `--json` output are two renders of one StatusReport (`"version": 1`), built from the state recorded at the commit the task's factory register (`refs/gnomish-factory/<task>`) names — the factory's latest commit of the task, never a commit the gnome added on top — so a running task shows its last recorded round, not what it is doing right now — plus the task's worktree path if one currently exists. This is the only status path: `gnomish run` reads no input and has no in-process status command (see [`operator-guide-run.md`](operator-guide-run.md)).
- **Totals in the text render**: after the `Totals: tokensByModel=…` line, the text names the snapshot's rounds folded and its cost in the same words `gnomish usage --markdown` uses — `rounds folded: <N>; cost $<priced>` when the cost is complete, `cost at least $<priced>; cost incomplete: <N> tokens unpriced` (or `usage not reported`) when it is a lower bound. `--json` carries the same figures as `totals.rounds`, `totals.cost.complete` and `totals.cost.unpricedTokens`.

- **Every shape renders**: the branch's shape is classified before anything is read from it, so `status` reports a delivered branch as delivered and a freshly created one as pending instead of failing on state files that shape does not have. For those branches the output is the shape itself:

  ```
  Task: PROJ-1
  Shape: Delivered
  ```

- **A branch that refuses inspection**: the four quarantine shapes — `Corrupt`, `UnsupportedVersion`, `Unknown`, `Unregistered` — print a `Diagnosis:` line and exit with code 7. For `Corrupt` and `UnsupportedVersion` it names the offending file and the observed versus expected content (for `UnsupportedVersion`, the observed and supported versions). `Unknown` also covers a branch whose top is not a descendant of the commit its register names; the diagnosis names both commits. A branch carrying task files with no register at all is `Unregistered`, not `Unknown`: its diagnosis names the missing register and the branch tip. No stack trace, and nothing in the clone is mutated. Example:

  ```
  Task: PROJ-1
  Shape: UnsupportedVersion
  Diagnosis: state.json declaring version 2 where this factory supports 1
  ```

  Exit code 7 means "the branch is there but this factory cannot read it" — distinct from code 6 ("no such branch") and code 1 (the tool itself broke).

- **Task not found**: if no `gnomish/<task>` branch exists — typically because its PR was already squash-merged and the branch deleted, see [Merging a gnome's task branch](operator-guide-run.md#merging-a-gnomes-task-branch) — `status` prints `task not found: <task>` and exits with code 6. This is a normal, expected outcome of a task's lifecycle, not an error to investigate.

## `gnomish usage`

```bash
gnomish usage --dir=<clone-dir> <task> [--json | --markdown]
```

Unlike `status`, `<task>` is mandatory here — there is no list mode.

`usage` reconstructs per-stage/per-round resource usage by walking the git history of `.gnomish-task/state.json` on the task branch, chronologically from oldest to newest, up to the commit the task's factory register names (a `state.json` the gnome committed above it is not a round, and a branch with no register reports no rounds) — not by parsing commit messages. A commit counts as a "round record" when its `state.json` shows a new attempt (the attempts list grew, or the pipeline position advanced to a new stage visit); salvage, `task.json`, and cleanup commits are naturally skipped since they don't add a new attempt record. `usage` only makes sense in git mode — a `--mode=in-place` run has no branch or history to reconstruct from.

**Text output** (default) is a stage/round table — each round's tokens summed over its participants and models, and the executor's wall time — followed by the usage snapshot of the walked rounds (tokens, rounds folded, the priced cost and whether it is complete).

**Markdown output** (`--markdown`) is the report the usage-report stage posts on the task's pull request, verbatim: the time elapsed since the first round started (time parked for a human included) with the summed agent and checks times, a per-stage table (rounds, agent time, checks time, tokens, cost) with a total row, a per-role table (`transform (gnome)`, `verdict (judge)`, `decision (arbiter)` — the labels are only how the report shows the roles) and a per-model table, each with input, output, cache write, cache read and their total. Every token and cost figure comes from the same usage fold as `totals`, so the stage rows, the role rows and the model rows each add up to the snapshot. A cost that is not complete is printed as a lower bound: `at least $<priced>; cost incomplete: <N> tokens unpriced`, or `at least $<priced>; cost incomplete: usage not reported` when a round reported no usage at all. `--json` and `--markdown` are mutually exclusive.

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
      "usage": [
        {
          "role": "transform",
          "executor": "<name>",
          "provenance": "reported",
          "wallMillis": 0,
          "byTool": [ "..." ],
          "byModel": {
            "<model>": { "input": 0, "output": 0, "cacheCreation": 0, "cacheRead": 0, "costUsd": 0.0 }
          }
        },
        { "role": "verdict", "executor": "<name>", "vote": 0, "provenance": "absent", "byTool": [], "byModel": {} }
      ]
    }
  ],
  "totals": {
    "byRole": {
      "<role>": { "<model>": { "input": 0, "output": 0, "cacheCreation": 0, "cacheRead": 0 } }
    },
    "cost": { "priced": 0.0, "unpricedTokens": {}, "complete": true },
    "rounds": 1,
    "through": "<round token>"
  }
}
```

Each row's `usage` lists one participant entry per executor invocation of the round: the stage's executor as a `transform` entry, then each judge vote as its own `verdict` entry with its `vote` index. `vote` appears on a `verdict` entry only and `wallMillis` only when known; an entry with provenance `absent` reported nothing and carries an empty `byModel` — unknown, never zero; `costUsd` appears only where the executor priced the model. `state.json` and `status --json` spell the entries the same way.

`totals` is the usage snapshot — the one usage fold over every walked round, every participant included: tokens by role and model (never priced there), the cost as a lower bound (`priced` sums the costs the executors reported, `unpricedTokens` names per role and model the tokens reported without a cost, and `complete` is false whenever a token went unpriced or an entry reported nothing), `rounds` folded and `through`, the token of the last folded round (omitted when none had one). `state.json` and `status --json` carry the task's recorded snapshot in the same shape.

**Unreadable history**: a historical commit whose `state.json` cannot be read — an unsupported envelope version, a half-written or hand-edited document — is skipped with a WARN naming the commit, and the walk continues. The table and totals render from the readable commits; only the rounds that broken commit itself would have contributed are missing.

**Task not found**: same as `status` — `usage` prints `task not found: <task>` and exits with code 6 when the branch is gone, which is normal after a squash-merged PR, not a bug.
