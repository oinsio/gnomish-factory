# Implement stage instructions

The earlier stages cleared this task's OpenSpec change, reviewed it against what
has landed, and revised it. Now implement it: every task of its `tasks.md`, until
each is ticked and `./gradlew check` passes.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79`
gives `github-oinsio-gnomish-factory-79`) — or `local` when the branch does not start
with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`, so reports of different tasks never overlap
and one directory holds everything to clean up after the task.

**The work itself is the project's own `/opsx:apply-sequential` command: read
`.claude/commands/opsx/apply-sequential.md` and carry it out in full**, together
with `.claude/commands/opsx/apply.md`, the `/opsx:apply` it wraps. They are the one
source of how this repository implements a change — one task per subagent, strictly
in sequence, single-owner tasks with their consumer list and old-way sweep — for a
human at the keyboard and for you alike. Everything below is only what differs
because nobody is watching this round; where it is silent, the commands decide.

## What differs in this stage

1. **The change** — the commands' `$ARGUMENTS` — is the `Change:` line of
   `temporary-docs/gnomish/<task>/change.md`. There is nobody to ask, so never fall
   back to AskUserQuestion.
2. **Read the commands, do not invoke them.** The Skill tool is not available in
   this round: open the command files and follow their steps yourself.
3. **Subagents.** Give each task to a `general-purpose` subagent, as the wrapper
   requires; every other type is denied. Only one subagent runs at a time here: a
   launch while one is running is refused with "Concurrent subagent limit reached …
   Do not retry", which means "not now" — wait for the running one to return. Every
   subagent prompt carries, besides what the wrapper requires: the change name and
   the paths of its artifacts, `CLAUDE.md` and the `.claude/rules/` files the task
   touches, "Do NOT make any git commits, and never push", and "Never edit
   `.claude/`, `.gnomish/`, `openspec/specs/` or `openspec/changes/archive/`".
4. **A subagent's report is a claim.** Before you tick a task, check its result
   yourself: the files it names exist and hold what it says, and the specs it
   names pass — run them (`./gradlew :<module>:test --tests '<Spec>'`). A task is
   ticked when its work is verified, not when a subagent says so.
5. **Every pause point becomes the escalation exit.** `/opsx:apply` step 6
   ("Pause if": unclear task, design issue, work beyond the spec, temptation to
   narrow or defer) and the wrapper's constraints 5 and 6 (a blocker, a single-owner
   task whose consumers the design does not name) all say "ask the user". Here that
   means: stop launching subagents, leave the tasks done so far ticked and in
   place, and escalate —
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole situation in
      `question` (the task, what blocks it, `file:line`, what you would do),
      newlines as `\n`, options that fit the case;
   3. finish your turn. The factory commits the work so far, posts the question and
      parks the task.

   Before escalating, read the briefing's `=== Decisions ===`: if a human already
   answered exactly this, follow the answer instead of asking again. Never resolve a
   pause point yourself by editing the change's proposal, design or specs — that is a
   decision about the plan, and it belongs to a human.
6. **Resume where the work stands.** The round may start with tasks already ticked:
   an earlier round was rejected by a check, escalated, or ran out of time. Start from
   the first unticked task. A rejected round's feedback is in your prompt: fix what it
   names first.
7. **Finish green.** When every task is ticked, run `./gradlew check` yourself and fix
   what fails before you finish — the same command is the stage's last check, and a
   red one sends the whole stage back. Fix the cause; never weaken a gate to pass it
   (`.claude/rules/testing.md` lists the only accepted exemptions, each with its bar).
8. **The record.** The commands log each task's summary to the reply; nobody reads
   it. Write it instead to `temporary-docs/gnomish/<task>/implement.md`, in English, one
   entry per tasks.md item as you finish it — the review stage after you reads it:

   ```
   ## Implementation: <change-name>

   ### <tasks.md item number> <item title>
   - Done: what was built or changed, with the main files
   - Verified: the specs run and their result
   - Sweep: for a single-owner task, the grep run, every hit, what happened to each
     (`.claude/rules/implementation.md`); omit the line otherwise
   ```

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the operator
  is a lost attempt; the escalation exit is the only channel a human reads.
- **Never push**: the remote is the factory's. Do not commit either — not because the
  factory forbids it, but because there is no need and no way: the factory commits
  whatever the working copy holds when your round ends, and this repository's
  `.claude/settings.json` denies `git commit` (with `merge`, `rebase`, `cherry-pick`,
  `revert`), so the attempt would only stall the round. Leave the branch alone
  otherwise too: no `checkout`, `reset`, `stash`, `restore`, `clean` or `pull`. Never
  undo work you did not do.
- Edit the code, the tests, `docs/` and the change's `tasks.md` (ticks only). Never
  edit `.claude/`, `.gnomish/`, `openspec/specs/` or `openspec/changes/archive/` — a
  check rejects it — nor the change's proposal, design or specs (item 5).
- Keep your own context small: the subagents read and write the code, you read their
  reports and verify. Pipe long command output through `tail`; read the lines you
  need rather than whole files.
