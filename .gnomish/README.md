# Running this repository through the factory

This directory is the whole configuration: what the pipeline is (`config.yaml`,
`pipeline.yaml`, `stages/`) and how it is launched (`bin/`). The loader reads
only the first three, so `bin/` and this file are invisible to it.

The pipeline is one stage wide today. `check-dependencies` resolves the OpenSpec
change a task names and decides whether work on it can start now, or whether a
human has to settle something first.

## One-time setup

```bash
mkdir -p ~/.gnomish/secrets/gnomish-factory
install -m 600 /dev/null ~/.gnomish/secrets/gnomish-factory/github-token
# paste a token with issue read/write and label write - the whole file is the value

./gradlew :bootstrap:bootJar      # the jar bin/gnomish launches
```

The secrets directory is named for the repository, not the clone, so every clone
and worktree uses the same token. Logs (`~/.gnomish/logs/<clone>/`) and the
instance name stay per clone on purpose: two daemons must not interleave their
narratives or overwrite each other's snapshot.

If `./gradlew build` stops on the mutation gate, that gate is not needed for a
runnable jar — `:bootstrap:bootJar` alone is enough.

## Filing a task

The task names one change directory under `openspec/changes/`. Put the name in
the issue title, or — whenever the title would carry anything else — put an
explicit line in the body:

```
Change: add-claim-return
```

That line wins over every other name mentioned in the task, and it is the
cheapest thing you can do for the run: without it, a body that mentions two
change names costs a full round and an escalation before anyone learns which one
you meant.

Then label the issue `gnomish:ready`. **That label is the authorization to run
autonomously**, not a priority marker — whoever may set it may spend the
factory's tokens and write to the repository.

## When a task comes back with a question

A task parked as `gnomish:needs-human` is waiting for an answer, and the answer
has to be a **comment**. Moving the label back to `gnomish:ready` without
commenting does not resume anything: the factory sees no reply since its last
acknowledgement and parks the task again, restating the same question. Nothing
breaks, but nothing moves either.

Answer in the thread, then return the label. Useful things to say:

- **which option you chose**, in words — the reply is committed to the task
  branch as a decision and is shown to every later stage of that task, so it
  keeps working long after the question that produced it;
- **what you changed outside the task**: "`fix-claim-epoch-fence` is merged and
  archived". The gnome merges `origin/main` into the task branch at the start of
  every round and will see it — but its own branch is pinned to the base it was
  cut from, so anything you did not merge to `main` is invisible to it, and only
  your reply can convey that;
- **that a question is settled**, plainly. The round that follows your reply is
  the same stage again; a vague answer earns the same question a second time.

## Non-obvious things that cost time and tokens

- **Editing `.gnomish/` does not affect tasks already in flight.** The pipeline
  law freezes at the base commit of each task's branch. A stage fixed today
  applies to tasks branched after the fix reaches `main`; a task already running
  on a broken manifest has to be re-filed, not repaired in place.
- **`.gnomish/` has to be on `main`** (or whichever base the task branches from)
  before `take` or `serve` can use it at all. While it is only on a side branch,
  the only way to run it is `bin/gnomish run`, which reads the working tree.
- **The lifecycle is one-way.** A finished task that is reopened is declined with
  a pointer to file a new one — reopening is never a way to ask for more work.
- **Never rebase or force-push a task branch.** The factory harvests it
  fast-forward-only; rewritten history is refused and the task aborts with its
  evidence left on the branch. Merging into it is fine; the gnome itself does not
  merge (see the next point).
- **This repository's own `.claude/settings.json` governs the gnome.** The gnome
  is Claude Code running in this project, so the `permissions.deny` list there —
  `git commit`, `git merge`, `git rebase`, ... — applies to it exactly as it
  applies to an interactive session, and a stage manifest's `allowedTools` cannot
  widen it. A denied command does not fail loudly: the round ends with the gnome
  asking for permission from an operator who is not there, the stage's checks fail
  on the missing artifact, and the attempt is spent. Before adding a shell command
  to a stage's instructions, check it against that deny list.
- **A claim has a 15-minute time-to-live** (a 5-minute heartbeat, three beats).
  A task stuck in `gnomish:working` with no live factory is reaped, not lost.
- **The escalation does not burn an attempt**, so a question costs one round, not
  a share of the task's budget. Answering properly is cheap; answering vaguely
  buys the same round again.

## Reading the result

The stage leaves `temporary-docs/gnomish/dependencies.md` on the task branch:
the change it resolved, the verdict, every other active change it examined and
why each is or is not a dependency. The tracker thread carries the short version
— the claim, the question if there was one, and the outcome.

## Running it by hand

```bash
.gnomish/bin/gnomish take <issue-ref>          # one task from the tracker
.gnomish/bin/gnomish run --task="add-claim-return" --mode=in-place
.gnomish/bin/gnomish status --task=<id>
```

`run` reads the pipeline from the working tree, so it is the way to try a change
to this directory before committing it. `--mode=in-place` keeps the run out of
git entirely: no branch, no resume, and the gnome works in this very clone.

One consequence of that last point: the stage checks that nothing but its report
changed, and in an in-place run it measures your clone. Commit or stash your own
work in progress first, or the check fails on your edits rather than the gnome's.
