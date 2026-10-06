# Archive stage instructions

The earlier stages implemented this task's OpenSpec change, audited it and fixed it
from the audit. Now archive it: merge its delta specs into `openspec/specs/` and move
the change into the archive, so the branch carries the code and the contract it
implements together.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it — or `local` when the branch
does not start with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`.

**The work itself is the project's `/opsx:archive` command: read
`.claude/commands/opsx/archive.md` and carry it out in full**, with
`.claude/commands/opsx/sync.md` for the spec merge its step 4 runs inline, and
`.claude/rules/archive-path.md` for where the change goes. They are the one source of
how this repository archives a change, for a human at the keyboard and for you alike.
Everything below is only what differs because nobody is watching this round; where it
is silent, the commands decide.

## What differs in this stage

1. **The change** is the `Change:` line of `temporary-docs/gnomish/<task>/change.md`.
   There is nobody to ask, so never fall back to AskUserQuestion.
2. **Read the commands, do not invoke them.** The Skill tool is not available in this
   round: open the command files and follow their steps yourself.
3. **Every prompt of the command has one answer here:**
   - an artifact that is neither `done` nor `skipped` (step 2), or an unticked task
     (step 3): **do not archive** — escalate (item 6);
   - delta specs that are not yet in the main specs (step 4): **"Sync now"**; already
     synced: **"Archive now"**. Never "Archive without syncing".
4. **The target** is `openspec/changes/archive/YYYY/MM/YYYY-MM-DD-<change>`, not the
   command's flat `archive/YYYY-MM-DD-<change>`: take the date from `date +%F`, the
   year and month directories from that date, and keep an existing date prefix as
   `archive-path.md` says. Move the directory with `mkdir -p` and `mv`; never
   `openspec archive`, which writes the flat path.
5. **A delta that no longer applies is a plan question.** A MODIFIED, REMOVED or RENAMED
   requirement whose header the main spec does not hold, an ADDED requirement whose
   header it already holds with different text, a sync whose re-comparison (end of
   step 4) still differs: stop before moving anything and escalate. Never resolve it by
   editing the delta, and never by rewriting requirements the delta does not name.
6. **The escalation exit.**
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole situation in
      `question` (the change, what blocks it, `file:line`, what you would do), newlines
      as `\n`, options that fit the case;
   3. finish your turn.

   Before escalating, read the briefing's `=== Decisions ===`: if a human already
   answered exactly this, follow the answer instead of asking again.
7. **The record.** The command's summary (step 6) goes to
   `temporary-docs/gnomish/<task>/archive.md` instead of the reply, in English, in this
   layout — a check reads the `Archived to:` line exactly:

   ```
   ## Archive: <change-name>

   Archived to: openspec/changes/archive/YYYY/MM/YYYY-MM-DD-<change-name>/
   Specs:
   - <capability-path>: ADDED <n>, MODIFIED <n>, REMOVED <n>, RENAMED <n>
   ```

   One line per delta spec; `Specs: none` when the change has none.
8. **Finish valid.** Run `openspec validate --specs --strict --no-interactive`; it must
   pass. A failure means the merge is wrong — fix the merge.

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the operator is a
  lost attempt; the escalation exit is the only channel a human reads.
- **A retry continues the work.** If the change is already moved, a previous round was
  rejected and its feedback is in your prompt: fix what it names in the main specs or
  the record, and do not move the change twice.
- Edit only `openspec/specs/` (the capabilities the change has delta specs for), move the
  change directory, and write `temporary-docs/gnomish/<task>/archive.md`. Never the code,
  the tests, `docs/`, `.claude/`, `.gnomish/`, another change, or the content of the
  change being archived — a check rejects it.
- **Never push**: the remote is the factory's. Do not commit either: the factory commits
  whatever the working copy holds when your round ends, and this repository's
  `.claude/settings.json` denies `git commit`. Leave the branch alone otherwise too: no
  `checkout`, `reset`, `stash`, `restore`, `clean` or `pull`, and no `git mv` either — a
  plain `mv` is enough.

**Before you stop, grade yourself as the judge will**, against
`.gnomish/stages/archive/acceptance.md`.
