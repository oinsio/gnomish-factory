# Check-dependencies stage instructions

The task names one OpenSpec change of this repository. Your job is to decide one
thing: **can work on that change start now, or must something happen first?**

You produce an answer, not a fix: no code, no edit to any change artifact.

The round ends one of exactly two ways — the change is startable and you finish
the round, or a human has to settle something and you escalate (step 6). Guessing
is not a third way. And the report you write stops nothing: later stages run as
soon as this round finishes and its checks pass, and nothing reads your prose to
decide whether to go on. So every finding that means "work must not start" leaves
through the escalation exit; the report's notes are only for what a human would
like to know while the work proceeds anyway.

## Step 1: ask `origin/main` what has already landed

Your working copy is not the whole truth. It was cut from a pinned base commit
and is never rebased, so a change archived since then still sits in
`openspec/changes/` here as though it were active. Your workspace shares its
object store with the factory's clone, and the factory refreshes `origin/main`
there every time it starts or resumes this task — so that ref is current. Read
it directly:

```
git ls-tree -d --name-only origin/main openspec/changes/
git ls-tree -d -r --name-only origin/main openspec/changes/archive/
```

The first lists the changes still active on the base, the second the archived
ones. Where they disagree with your working copy, `origin/main` decides the
question "has it landed?".

- **Never merge, rebase or pull.** You do not need the landed files, only the
  knowledge of what landed — and this repository denies those commands outright,
  so attempting one loses the round rather than updating anything.
- If `origin/main` does not resolve — a run outside a task branch — say so in the
  report's `## Notes` and judge from the working copy alone.

## Step 2: read what a human already decided

The briefing's `=== Decisions ===` section carries every answer a human has given
on this task, including the answer to a question an earlier round of this very
stage asked. Step 1 tells you what has landed; a decision tells you what a human
concluded — about what has not landed yet, and about whether work may proceed
anyway. Where the two disagree, the decision wins.

- A decision that says a dependency is resolved, or that work may proceed anyway,
  settles that dependency: treat it as satisfied and name it in the report's
  `## Notes`, with the decision text you applied.
- **Never escalate a question a decision has already answered.** The round that
  follows a reply is this same stage, so re-asking sends the task straight back
  to the human and buys the same answer again. If the answer itself is "keep
  waiting", escalate — but say plainly that you are restating it, not discovering
  it.

## Step 3: resolve the change name

The name is a directory name under `openspec/changes/`. It may sit in the task
title, in the task body, or both.

- An explicit `Change: <name>` line in the task text wins outright, however many
  other names are mentioned. That line is how a human disambiguates a task.
- Otherwise collect every distinct candidate from the title and the body — a
  kebab-case token naming a directory under `openspec/changes/`, active or
  archived. Strip any `openspec/changes/` prefix and trailing slash first.
- Exactly one candidate: that is your change.

**Escalate (step 6) instead of proceeding when:**

- there is no candidate at all — including a task that is not about a change;
- there is more than one candidate — list them and ask which one is meant;
- the name is no change directory at all: report it, and name the closest
  existing change if one is obviously a typo of it;
- the change is archived (`openspec/changes/archive/**`) — say where it landed
  and ask whether a new change is meant;
- the directory exists but holds no `proposal.md`: there is nothing to analyse.

One lookalike is not an escalation: when the same name exists both actively and
in the archive, use the active one and say so in the report's `## Notes`.

## Step 4: find the dependencies

A dependency is another **active** change — a directory under
`openspec/changes/` other than `archive/` — that has to reach `main` before this
one can be implemented. Look for:

- **Declared sequencing** in `proposal.md` or `design.md`: a `Supersedes:` line,
  a `Layered on <change> (sequenced before this change)` preamble, "after
  `<change>` lands", "depends on", "builds on".
- **Overlapping `## MODIFIED` requirements** (`.claude/rules/delta-specs.md`):
  for every `### Requirement:` heading under a `## MODIFIED` header in this
  change's deltas, look for the same heading in another active change's deltas.
  A hit without a fixed order declared in both proposals is a dependency, not a
  coincidence — whichever syncs second silently replaces the first.
- **Artifacts that do not exist yet**: the change's tasks or specs name a class,
  module, port, config key, or capability that is absent from the codebase and
  is introduced by another active change. Check the code before believing such a
  claim either way.
- **Ordering stated in this change's own artifacts**: a task that says "once
  `<change>` is archived", a decision whose context names a prerequisite.

Not a dependency: an **archived** change — it has already landed; another change
that merely touches the same files with no ordering requirement; a change this
one is a prerequisite *for* — that belongs in `## Notes`, never in the chain.

Four findings are escalations rather than plain dependencies, because no ordering
of work resolves them:

- a **cycle** — this change waits for another that waits back for this one;
- a **dangling reference** — the artifacts name a prerequisite change that exists
  neither actively nor in the archive;
- this change is **superseded** — another active change carries
  `Supersedes: <this change>`, so starting it may be wasted work;
- the change is **already under way** — `tasks.md` carries ticked `- [x]` items,
  its spec deltas are already reflected in `openspec/specs/`, or the code it
  proposes is already there. Someone is mid-flight or has finished; starting
  again duplicates or undoes their work.

## Step 5: write the report

Once a change is resolved, always write `temporary-docs/gnomish/dependencies.md`,
in English, in this shape — both on the startable path and the blocked one:

```
Change: <change-name>
Verdict: no-blocking-dependencies | blocked

## Dependencies
1. <change> - <one line: what forces the order> (<file>:<line>)
...

## Notes
- <only what changes what a human does next>
```

The `Change:` and `Verdict:` lines are checked mechanically, so keep them exactly
in this form. When you escalate at step 3 there is no change to report on — skip
the report and escalate.

**The whole report fits in 1200 bytes.** Nothing checks this — a rewrite would
cost more than any overrun — so it is yours to keep. Count bytes, not lines: one
600-character paragraph costs as much as ten real lines. It is a verdict with its
evidence, not
a record of your reasoning, and every line of it is paid for twice — once to write
and once by whoever reads it. So:

- `## Dependencies` is `none`, or one line per dependency. The evidence is a
  `file:line` pointer; the reader opens it if they doubt you. Do not quote the
  passage, do not explain how you found it.
- **Never list the changes you ruled out.** The verdict already says you checked
  them, and a paragraph each for two dozen changes is the single most expensive
  thing this stage can produce.
- `## Notes` earns its place only when a human would act differently for knowing
  it — an archived namesake you disambiguated, a decision you applied, a call you
  are unsure of. Nothing to say is the normal case: drop the section entirely.
- No summary of the task, no description of your method, no restating these
  instructions back.

## Step 6: the two exits

**No dependencies.** Report with `Verdict: no-blocking-dependencies`, finish your
turn, touch no decision file. That is the whole stage.

**Anything else** — a chain, a cycle, a dangling reference, a superseded change,
a change already under way, an unresolvable name, a tool the environment refused,
or an analysis you cannot finish within the round's budget: do **not** finish the round
as done. Hand it to a human, unless step 2 shows a decision that already answers
exactly this:

1. Run one shell command to learn where the decision file goes:
   `echo "$GNOMISH_DECISION_FILE"`.
2. Write that absolute path with the Write tool. Its content is one JSON object:

   ```json
   {"question": "<change> is blocked by <n> active change(s).\n\nChain (must land in this order):\n1. <change-a> - <why, with evidence>\n2. <change-b> - <why>\n\nSuggested action: ...", "options": ["Work the chain first - park this task until <change-a> lands", "Proceed anyway - the dependency is not blocking in practice", "The chain is wrong - see my comment"]}
   ```

   The `question` text is what the human reads in the tracker, so put the whole
   finding there — the situation, the evidence, and what you would do — short
   enough to read at a glance. Offer options that fit the case at hand rather
   than the ones above: a name to choose, an archive to confirm, a cycle to
   break. Newlines inside the string are written as `\n`; the file must be valid
   JSON.
3. Finish your turn. The factory posts the question to the tracker, parks the
   task for a human, and burns no attempt. Never try to reach the tracker
   yourself.

## Rules

- **Nobody is watching this round.** Your closing summary is read by no human in
  time to answer it, so a turn that ends in a question is a lost attempt. When you
  cannot proceed — including when a tool is refused — take the escalation exit of
  step 6 instead; that is the channel a human actually reads.
- Never run a git command that writes: `commit`, `push`, `merge`, `rebase`,
  `pull`, `checkout`, `reset`. The factory owns the branch, and this repository
  denies several of them outright.
- Write exactly one file, `temporary-docs/gnomish/dependencies.md`, plus the
  decision file on the escalating path. Everything else in the working copy —
  `openspec/`, `src/`, `.gnomish/`, `.gnomish-task/` — stays as you found it.
- Use the shell only for step 1's two `ls-tree` reads and step 6's `echo`. Read
  with Read/Glob/Grep, write with Write.
- Every byte you read or print is context and money: rule each other active
  change in or out from its proposal's opening sections and its delta-spec
  headings rather than reading it whole, and keep your closing summary to a few
  sentences.
