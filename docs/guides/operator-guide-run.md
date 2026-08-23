# Operator Guide: Running a Single Task (`gnomish run`)

This guide is the reference for `gnomish run` — driving **one ad-hoc task through
one pipeline** without a tracker. It assumes the factory is built (see the main
[README](../../README.md#building)) and the target project has a working `.gnomish/`
pipeline. The tracker-driven workflow layered on top is
[`operator-guide.md`](operator-guide.md); read-only task inspection
(`gnomish status` / `gnomish usage`) is
[`operator-guide-inspect.md`](operator-guide-inspect.md); where the gnome process
actually executes — an ephemeral container box by default, or the host — is
[`operator-guide-sandbox.md`](operator-guide-sandbox.md)'s territory.

`gnomish run` executes one task through the whole quality-control cycle. By
default it is manifest-driven: real `agent-cli` and judge adapters run each stage
(see [Manifest-driven run and `--interactive` overrides](#manifest-driven-run-and---interactive-overrides)).
Passing `--interactive` swaps in a human standing in for the gnome instead: you
read each stage briefing and press Enter to complete it, answer the verify
checks, and resolve escalations at the prompt. It doubles as the pipeline
author's dry-run tool for a project's `.gnomish/`, and as the harness that proves
the engine's port shapes survive contact with real adapters.

There is no launcher script yet; run it through the boot jar (or `bootRun`) and
pass the task flags. With **no** run flag present the application keeps its plain
boot-and-exit behavior. `run` is the implicit default subcommand —
`gnomish --task=... --dir=...` and `gnomish run --task=... --dir=...` are
equivalent — so existing invocations keep working.

```bash
# via the boot jar (./gradlew build produces it under build/libs/)
java -jar build/libs/*.jar --task="fix the flaky login spec" --dir=/path/to/target-repo

# or straight from Gradle
./gradlew bootRun --args='--task="fix the flaky login spec" --dir=/path/to/target-repo'
```

## Flags

Flags use Spring's `--key=value` form (quote values with spaces):

| Flag                              | Required           | Default             | Meaning                                                                                                    |
|-----------------------------------|--------------------|---------------------|------------------------------------------------------------------------------------------------------------|
| `--dir=<path>`                    | no                 | `.` (cwd)           | project clone directory **and** the `.gnomish/` pipeline location                                          |
| `--task="<text>"`                 | one of these two\* | —                   | task description inline (first line → title, rest → body); mutually exclusive with `--task-file`           |
| `--task-file=<path>`              | one of these two\* | —                   | task description read from a file                                                                          |
| `--task-id=<id>`                  | no                 | auto-generated      | override the generated id (`[A-Za-z0-9_-]+`); makes logs and JSON stable                                   |
| `--from-stage=<name>`             | no                 | first stage         | start partway through the pipeline, skipping earlier stages' checks                                        |
| `--mode=git\|in-place`            | no                 | `git`               | task workflow mode — see [Git mode vs. in-place mode](#git-mode-vs-in-place-mode)                          |
| `--base=<ref>`                    | no                 | current clone state | git mode only; override the branch base                                                                    |
| `--resume=<task>`                 | no                 | —                   | git mode only; resume a task by id instead of starting a new one — see [Resuming a task](#resuming-a-task) |
| `--discard-work`                  | no                 | `false`             | git mode only; requires `--resume`; discards the interrupted round instead of salvaging it                 |
| `--interactive[=executor\|judge]` | no                 | —                   | human plays a role instead of the real adapter — see below                                                 |

\* Exactly one of `--task`/`--task-file` is required unless `--resume` is given, in which case none of `--task`/`--task-file`/`--task-id`/`--from-stage` may be used. `--base`, `--resume`, and `--discard-work` are rejected together with `--mode=in-place` (exit code 2, usage error).

At **any** prompt you can type `status` or `status --json` to print the live task report, and **Ctrl-D** is always a safe exit. After every attempt the operator gets a one-line summary; a full report prints at the end. The runner writes nothing inside the project clone — logs, findings, and (in git mode) the task workspace all live outside it.

## Git mode vs. in-place mode

**Git mode (`--mode=git`, the default)** treats `--dir` as the project clone and never mutates it directly. It creates a task branch `gnomish/<sanitized-task-id>`, checks it out into a dedicated worktree, and commits the state file and stage artifacts after every round, pushing best-effort as it goes. The branch name and worktree path print upfront so you can inspect progress with plain `git` commands while the run is in flight. This is what makes a task **resumable**: a died process, a returned escalation, or a paused checkpoint can all be picked up later — by the same machine or another one — from the last committed round.

Worktrees live outside the clone, under `~/.gnomish/worktrees/<project-name>/<sanitized-task-id>/`, where `<project-name>` is the clone directory's own name (from `--dir`) — so one factory instance can serve several projects without collisions. `git worktree prune` runs at every start. Cleanup depends on how the task ends: **completed** tasks have their worktree removed (the branch stays for history); **escalated** or **paused** tasks keep the worktree for a fast resume; **aborted** tasks always keep it, since it may hold the only copy of unsaved work.

**In-place mode (`--mode=in-place`)** is the preserved legacy behavior: no git, no worktree, in-memory state only, no resume — if the process dies, the task's progress is lost. It remains useful as a pipeline author's dry-run of a project's `.gnomish/` config in a scratch directory, where you don't want branches or worktrees created at all.

## Resuming a task

`gnomish run --dir <dir> --resume <task>` locates the task branch — checking the local repo first, then a remote-tracking branch, then falling back to a narrow fetch of exactly `gnomish/<task>` — and continues from its recorded state:

- **escalated** → re-opens the decision dialog;
- **paused** (manual checkpoint) → asks for confirmation before proceeding;
- **no recorded outcome** (the process died mid-round) → continues from the recorded position; any uncommitted work from the interrupted round is salvaged into a service commit by default, or discarded and the round replayed if `--discard-work` is given;
- **completed** → reports the outcome and exits without further work.

If the local branch and its remote counterpart have diverged, resume needs a human: equal state continues normally, a local branch behind origin fast-forwards (discarding any uncommitted leftovers), and a local branch ahead of origin continues from local — but a true divergence is a hard stop (exit code 5, `DivergedBranchException`) rather than an automatic merge.

## Exit codes

The process exit code reports the outcome — anything `>= 10` means the engine reached a legitimate terminal state:

| Code | Meaning                                                             |
|------|---------------------------------------------------------------------|
| 0    | completed                                                           |
| 1    | internal error                                                      |
| 2    | usage error                                                         |
| 3    | pipeline load failure                                               |
| 4    | stdin exhausted mid-stage (Ctrl-D at an ordinary prompt)            |
| 5    | diverged branch on resume — needs a human to reconcile              |
| 6    | task not found (`status`/`usage` only — no `gnomish/<task>` branch) |
| 10   | escalated (attempts exhausted / undecidable)                        |
| 11   | paused at a manual checkpoint                                       |
| 12   | aborted                                                             |

`take` and `serve` carry their own exit-code tables — see
[`operator-guide.md`](operator-guide.md) and
[`operator-guide-serve.md`](operator-guide-serve.md).

## What reaches `origin`, and when

Pushing the task branch is the factory's job, never yours. Every commit the factory writes is followed by a best-effort push of `gnomish/<task>` to `origin`, under the exact refspec `gnomish/<task>:gnomish/<task>` and never with `--force`:

- **round commits** — the state file and stage artifacts, after every attempt;
- **lifecycle commits** — task started, a resume decision appended, the terminal outcome, the `Completed` cleanup commit, and the tracker-write-confirmed commit. The outcome and its cleanup commit travel together in one push, so a completed task's branch reaches the remote in its final form: cleanup at the tip, no `.gnomish-task/` files in the PR diff.

Push is best-effort by design: durability is the recorded branch state, so a failed push logs one WARN and the run continues. Two mechanisms close the gap a lost push leaves:

- **Touchpoint reconciliation.** At resume start and at a run's terminal boundary — unless that boundary parks the task, where the fence below does the same job more thoroughly — the factory compares `origin`'s tip for the branch with the local one. If `origin` is missing the branch or holds a strict ancestor of the local tip, it pushes. So a push lost to a crash or an outage is delivered by the next instance to touch the task, whichever machine that is. It costs one `ls-remote` when `origin` is already current, never blocks the run, and does nothing at all where the two histories diverged — repairing that is a human's call.
- **Delivery fence before a park.** Before the tracker is told a task is escalated or paused, the factory verifies the park's commit — the recorded outcome and its pending-write marker — is on `origin`, pushing with one bounded re-attempt if it is not. This is what makes a park safe to pick up from another machine: the tracker never announces a park whose commit `origin` lacks. If the fence exhausts its attempts, the park still lands and its report on the tracker carries one extra line saying `origin` is behind the recorded park; that line names the branch and the `git push origin <branch>` that fixes it — until it is pushed, another instance resuming the task would read stale state.

In a clone with **no `origin` remote**, all of this is silent: no push is attempted at any point, and no warnings are logged. A purely local run behaves exactly as it did before.

> **Caveat: `push.default = matching` hides push failures.** With that setting, your own manual `git push` in the clone also pushes every same-named branch — including the factory's task branches. The remote then looks up to date whether or not the factory's own pushes ever worked, which is precisely how a missing push can go unnoticed for a long time. If you are verifying replication behavior, use `push.default = simple` (git's default since 2.0) or check `git ls-remote origin 'refs/heads/gnomish/*'` directly rather than trusting what your last manual push left behind.

> **Git never prompts.** Every git command the factory runs has interactive credential prompting switched off, so an expired token or a missing helper makes a push or fetch FAIL immediately rather than hang waiting for a password. In `gnomish run`, which inherits your terminal, that is the difference between a run that reports a failed push and a run that sits there forever.

> **Credentials in the `origin` URL are masked in what the factory writes.** If your clone's `origin` is `https://<token>@host/owner/repo.git` (or `https://user:<token>@…`), git's own failure output can echo that credential back — most visibly in `could not read Password for 'https://<token>@host'`, which is exactly what a factory subprocess with no terminal hits. Every git command's error output is stripped of that `userinfo@` prefix before the factory logs it or puts it in a report the tracker publishes, so a push WARN reads `https://***@host`. The mask is defence in depth, not a licence: prefer a git credential helper over a token in the remote URL, since a URL-embedded token is still readable in `.git/config` and in the process list of anything that echoes the remote.

## Merging a gnome's task branch

On completion, the factory strips `.gnomish-task/` from the branch tip in a final cleanup commit, but every round leading up to it stays reachable in the branch history as an audit trail — that's what makes resume and escalation reviewable. That history is internal bookkeeping, not something a target project wants in its permanent log. **Squash-merge** gnome PRs into the target project's mainline so only the final clean diff lands there and the round-by-round journal stays behind on the (eventually discarded) task branch.

## Manifest-driven run and `--interactive` overrides

By default `gnomish run` is **manifest-driven, not interactive**: it reads the target project's `.gnomish/` pipeline and wires each stage's real adapter straight from the manifest — an `agent-cli` stage executor gets the CLI executor (a real `claude -p` subprocess per round), and every `judge` verify check gets the CLI judge, regardless of the stage's own executor type. This is the normal, paid mode, and starting a real agent round requires **no confirmation gate** by design — that is the tool's purpose, and the operator is present. Where that agent process executes — an ephemeral container box by default, or the host — is decided by the sandbox binding, not the manifest; see [`operator-guide-sandbox.md`](operator-guide-sandbox.md). `api` stages aren't supported yet and are rejected at startup (exit 3, before any dialog), naming the offending stage.

`--interactive` overrides the wiring, entirely or per role:

| Flag                     | Effect                                                                                                            |
|--------------------------|-------------------------------------------------------------------------------------------------------------------|
| *(absent)*               | manifest-driven: real CLI executor + real CLI judge (default, paid)                                               |
| `--interactive`          | full add-manual-run behavior: human plays both executor and judge                                                 |
| `--interactive=executor` | human plays the executor; judge stays the real CLI judge — verdict calibration                                    |
| `--interactive=judge`    | human plays the judge; executor stays the real CLI agent — judge-prompt debugging without paying for agent rounds |

`--interactive` may be given only once. External checks are always interactive regardless of this flag.

## Manifest settings vs. installation properties

A stage's `executor.settings` (and a `judge` check's own `settings`) accept exactly four keys — `allowedTools`, `disallowedTools`, `maxTurns`, `roundTimeout` — validated at startup before any dialog; an unrecognized key or malformed value is a startup error naming the stage/check and the key. These are portable, repo-level settings that travel with the pipeline definition.

Installation-level configuration — things that are true of *this machine*, not the repo — lives in `factory.*` application properties instead, never the manifest. The properties relevant to `run`:

| Property                             | Meaning                                                                                                                       |
|--------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `factory.agent-cli-binary`           | path or name of the agent CLI binary (default: `claude` on `PATH`)                                                            |
| `factory.agent-cli-tail-drain-grace` | how long a round waits, after the agent process exits, for its stdout drain to deliver the already-piped tail of the stream (default: `5s`). The drain runs concurrently with the process, so by exit time only the bytes still in the pipe remain and the default needs no tuning; raise it only if a pathologically loaded host starts reporting "agent stdout drain did not finish". A non-positive or malformed value is a startup error |
| `factory.sandbox.env-passthrough`    | environment variable names passed through into the gnome's execution environment — see [`operator-guide-sandbox.md`](operator-guide-sandbox.md) |

(`factory.agent-cli-env-passthrough` is superseded by `factory.sandbox.env-passthrough` and ignored; it is kept only so existing configs still bind.) The other `factory.*` roots belong to other subsystems: `factory.sandbox.*` ([`operator-guide-sandbox.md`](operator-guide-sandbox.md)), `factory.serve.*` ([`operator-guide-serve.md`](operator-guide-serve.md)), and `factory.instance-name` / `factory.tracker.*` / `factory.check.*` / `factory.connections.*` ([`operator-guide.md`](operator-guide.md), [`operator-guide-github-actions-check.md`](operator-guide-github-actions-check.md)).

## Ollama E2E prerequisite

`./gradlew ollamaE2eTest` runs a local E2E suite that points the real `claude` CLI at a locally running Ollama instance (native Anthropic-compatible API since Ollama v0.14, via `ANTHROPIC_BASE_URL`) and drives a trivial stage through `gnomish run` end to end. It's excluded from `check`/`test`/`build` and is a native dev-machine prerequisite, not a Testcontainers layer — dockerized Ollama has no Metal access on macOS and is too slow. Individual specs skip cleanly with a clear message when Ollama or `claude` isn't available, so it's safe to run without any setup.
