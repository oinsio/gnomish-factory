# Fix-artifacts stage instructions

The previous stage reviewed this task's OpenSpec change and wrote its
recommendations to `temporary-docs/gnomish/<task>/review-artifacts.md`. Now revise the
change from that review — but a review is a second opinion, not an order: some of
its recommendations will be wrong, useless or harmful. You apply what holds up,
reject the rest with evidence, and leave the change coherent and valid.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79`
gives `github-oinsio-gnomish-factory-79`) — or `local` when the branch does not start
with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`, so reports of different tasks never overlap
and one directory holds everything to clean up after the task.

**The work itself is the project's own `/update-from-review` command: read
`.claude/commands/update-from-review.md` and carry it out in full**, together
with `.claude/commands/opsx/update.md`, the `/opsx:update` it wraps, and
`.claude/rules/review-recommendations.md`, which defines when a recommendation is
worth fixing and how a resolution is recorded. They are the one source of how this repository revises a change from a
review, for a human at the keyboard and for you alike. Everything below is only what
differs because nobody is watching this round; where it is silent, the commands
decide.

## What differs in this stage

1. **The arguments.** The change — the command's `$1` — is the `Change:` line of
   `temporary-docs/gnomish/<task>/change.md`; the report — `$2` — is
   `temporary-docs/gnomish/<task>/review-artifacts.md`. There is nobody to ask, so never fall
   back to AskUserQuestion.
2. **Read the commands, do not invoke them.** The Skill tool is not available in this
   round: open the command files and follow their steps yourself. No subagents either.
3. **You decide; nobody confirms.** `/opsx:update` step 5 and constraint 6 of the
   wrapper — show each revision, write only after the user confirms — do not apply:
   your triage under constraint 1 is the decision, and the judge after your round is
   the check on it. Write every accepted edit directly.
4. **Where the resolution goes.** Instead of the wrapper's dated file under
   `temporary-docs/`, write the resolution record of constraint 4 to
   `temporary-docs/gnomish/<task>/fix-artifacts.md`, in English, keeping its layout exactly:
   a check after your round matches its `### <N> — applied|rejected` entries against
   the review's numbering and reads their fields.
5. **No recommendations.** A review with an empty Recommendations section still gets
   a resolution record (`None.`, then Other edits and Validation), and the change must
   still validate.
6. **What a human must decide leaves through the escalation exit**, not through a
   rejection: a recommendation whose fix would change the change's intent, or a
   `--strict` validation error that needs a decision rather than a faithful
   restatement. Then:
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole situation in
      `question` (the recommendation, what you found, `file:line`, what you would do),
      newlines as `\n`, options that fit the case;
   3. finish your turn. The factory posts the question and parks the task. Leave the
      edits you already made in place: they are committed with the round and the
      next round, after the answer, continues from them.

   Before escalating, read the briefing's `=== Decisions ===`: if a human already
   answered exactly this, follow the answer instead of asking again.

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the operator
  is a lost attempt; the escalation exit is the only channel a human reads.
- **A retry continues the work.** If the resolution record already exists, a previous
  round was rejected and its feedback is in your prompt. Your earlier edits are still
  in the working copy: fix every point the feedback names — revert an edit it calls
  harmful, apply a recommendation it says you wrongly rejected — then re-check the
  whole record against the review and the artifacts, not only the flagged entries.
- Edit only the files under `openspec/changes/<change>/` and write
  `temporary-docs/gnomish/<task>/fix-artifacts.md` (plus the decision file on the escalating
  path). The review report, `change.md`, `openspec/specs/`, the archive, other
  changes, the code, `.gnomish/` and `.claude/` stay as you found them.
- **Never push**: the remote is the factory's. Do not commit either — not because the
  factory forbids it, but because there is no need and no way: the factory commits
  whatever the working copy holds when your round ends, and this repository's
  `.claude/settings.json` denies `git commit` (with `merge`, `rebase`, `cherry-pick`,
  `revert`), so the attempt would only stall the round. Leave the working copy and the
  branch alone otherwise too: no `checkout`, `reset`, `stash`, `restore`, `clean` or
  `pull`. Never undo work you did not do.
- Use the shell only for read-only git, `openspec status|instructions|show|validate`,
  and the decision-file `echo`. Do not run Gradle.
- Keep the output small: pipe long command output through `tail`, read the lines you
  need rather than whole files, and keep your closing summary to the counts of applied
  and rejected recommendations and the validation result.

**Before you stop, grade yourself as the judge will**, against
`.gnomish/stages/fix-artifacts/acceptance.md`.
