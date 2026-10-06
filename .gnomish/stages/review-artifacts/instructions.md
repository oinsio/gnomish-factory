# Review-artifacts stage instructions

The previous stage cleared one OpenSpec change: nothing it depends on is still
waiting to land. Now review that change's artifacts — above all, how much of it
still holds against what has landed since it was written.

**The review itself is the project's own `/review-artifacts` command:
read `.claude/commands/review-artifacts.md` and carry it out in full.** It is the
one source of how this repository reviews a change, for a human at the keyboard
and for you alike. Everything below is only what differs because nobody is
watching this round; where it is silent, the command decides.

## What differs in this stage

1. **The change name** — the command's `$1` — is the `Change:` line of
   `temporary-docs/gnomish/dependencies.md`. Review exactly that change; there is
   nobody to ask, so never fall back to AskUserQuestion.
2. **No subagents.** The command's fan-out to Explore subagents is not available
   here (the `Agent` tool is disabled): cover the dimensions yourself, one after
   another. The briefing's task text is in `.gnomish-task/task.json` (read-only).
3. **Where the report goes.** Nobody reads your reply, so the reply is not a place
   for the report. Instead of the command's step 7 file, write the **whole report
   of step 6** — every section, from `## Artifacts Review: <name>` to the Verdict —
   to `temporary-docs/gnomish/review-artifacts.md`, in English. Write no other
   file under `temporary-docs/`. The stage that revises the change reads this file
   recommendation by recommendation, and checks after your round read its
   structure, so keep the step 6 layout and the recommendation format exactly.
4. **Two outcomes are not recommendations.** If the change is **already
   implemented**, wholly or so largely that what is left is not the change it
   describes, or its **premise is gone** — the problem in the proposal's `## Why`
   was solved another way, or a landed change decided the opposite — no edit to
   the artifacts resolves it; a human decides whether to rewrite, cut or abandon
   the change. Then write no report and escalate:
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole finding in
      `question` (what landed, where, `file:line`, what of the change still
      stands), newlines as `\n`, options that fit the case;
   3. finish your turn. The factory posts the question and parks the task.

   Before escalating, read the briefing's `=== Decisions ===`: if a human already
   answered exactly this, follow the answer instead of asking again.

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the
  operator is a lost attempt; the escalation exit is the only channel a human reads.
- **A retry is a full review.** If the report already exists, a previous round was
  rejected and its feedback is in your prompt. Fix every point it names, then
  re-verify the whole report rather than patching only the flagged points.
- Write exactly one file, `temporary-docs/gnomish/review-artifacts.md`, plus the
  decision file on the escalating path. Never undo work you did not do.
- **Never push**: the remote is the factory's. Do not commit either — not because
  the factory forbids it, but because there is no need and no way: the factory
  commits whatever the working copy holds when your round ends, and this
  repository's `.claude/settings.json` denies `git commit` (with `merge`, `rebase`,
  `cherry-pick`, `revert`), so the attempt would only stall the round. Leave the
  working copy and the branch alone otherwise too: no `checkout`, `reset`, `stash`,
  `restore`, `clean` or `pull`.
- Use the shell only for read-only git, `openspec list|show|validate`, and the
  decision-file `echo`. Do not run Gradle.
- Keep the output small: pipe long command output through `tail`, read the lines
  you need rather than whole files, and keep your closing summary to the verdict
  and the count of recommendations per severity.

**Before you stop, grade yourself as the judge will**, against
`.gnomish/stages/review-artifacts/acceptance.md`.
