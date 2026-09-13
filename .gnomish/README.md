# Running this repository through the factory

This directory is the whole configuration: what the pipeline is (`config.yaml`,
`pipeline.yaml`, `stages/`) and how it is launched (`bin/`). The loader reads
only the first three, so `bin/` and this file are invisible to it.

**What the stages are is not written down here.** `pipeline.yaml` lists them in
order, and each `stages/<name>/stage.yaml` opens with a `purpose` line saying what
that stage decides and a comment saying what it leaves behind. This file stays
about the things that are true whatever the stages happen to be.

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
  archived". Anything you landed on `main` the gnome can see for itself — the
  factory keeps `origin/main` current in its clone and the stage reads that ref
  rather than its own pinned files. Anything you did *not* land there is invisible
  to it, and only your reply can convey it;
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
- **A claim has a 15-minute time-to-live** (a 5-minute heartbeat, three beats).
  A task stuck in `gnomish:working` with no live factory is reaped, not lost.
- **The escalation does not burn an attempt**, so a question costs one round, not
  a share of the task's budget. Answering properly is cheap; answering vaguely
  buys the same round again.

## If you add a stage

Lessons that cost a round each to learn. They are about stages in general, so this
list grows with what we learn, not with how many stages there are.

- **This repository's own `.claude/settings.json` governs the gnome.** The gnome is
  Claude Code running in this project, so the `permissions.deny` list there —
  `git commit`, `git merge`, `git rebase`, ... — binds it exactly as it binds an
  interactive session, and a manifest's `allowedTools` cannot widen it. A denied
  command does not fail loudly: the round ends with the gnome asking permission
  from an operator who is not there, the checks then fail on the missing artifact,
  and the attempt is spent. Check every shell command you put in a stage's
  instructions against that list.
- **A check's only remedy is another whole round.** There is no warning channel: a
  `command` check either passes or re-runs the stage from scratch, and a second
  miss escalates to a human. So a check must fail only on output that is unusable
  or unsafe. Anything merely untidy belongs in the instructions, where it shapes
  the work for free instead of buying a second round to reformat the first.
- **A check's failure text is read as an order, not as a report.** It is handed to
  the next attempt as feedback, and the gnome does what it says. Write it as the
  instruction you actually want followed — including what must *not* be touched.
  "Restore those paths" is how the operator's work ended up in a stash.

## Reading the result

Two places, and they never repeat each other. A stage that **finished** left its
artifact on the task branch — which file, and how small it is meant to be, is
written in that stage's own `stage.yaml`. A stage that **could not finish** left a
question in the tracker thread, and no artifact at all: an answer and a file
saying the same thing would be one of them too many.

## Running it by hand

```bash
.gnomish/bin/gnomish take <issue-ref>          # one task from the tracker
.gnomish/bin/gnomish run --task="add-claim-return" --mode=in-place
.gnomish/bin/gnomish status --task=<id>
```

`run` reads the pipeline from the working tree, so it is the way to try a change
to this directory before committing it. `--mode=in-place` keeps the run out of
git entirely: no branch, no resume, and the gnome works in this very clone.

**Commit your own work before an in-place run.** A stage that checks it wrote
nothing but its own artifact measures your clone in this mode, so your uncommitted
edits fail it, and the failure text reaches the gnome as feedback about a dirty
tree. One has already read that as an instruction and moved the operator's work
into `git stash` to pass. Nothing was lost, and the stage now forbids that in two
places (see the third check in `stages/check-dependencies/stage.yaml`) — your
commit is the third guard, and the only one that costs nothing.
