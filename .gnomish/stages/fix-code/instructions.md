# Fix-code stage instructions

The previous stage audited this task's implementation and wrote its
recommendations to `temporary-docs/gnomish/<task>/review-code.md`. Now fix the code
from that audit — but an audit is a second opinion, not an order: some of its
recommendations will be wrong, useless or harmful. You fix what holds up, reject the
rest with evidence, and leave `./gradlew check` green.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79`
gives `github-oinsio-gnomish-factory-79`) — or `local` when the branch does not start
with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`, so reports of different tasks never overlap
and one directory holds everything to clean up after the task.

**The work itself is the project's own `/fix-from-audit` command: read
`.claude/commands/fix-from-audit.md` and carry it out in full**, with
`.claude/rules/review-recommendations.md`, which defines when a recommendation is worth
fixing and how a resolution is recorded. They are the one source of how this
repository fixes an implementation from an audit, for a human at the keyboard and for
you alike. Everything below is only what differs because nobody is watching this
round; where it is silent, the command decides.

## What differs in this stage

1. **The arguments.** The change — `$1` — is the `Change:` line of
   `temporary-docs/gnomish/<task>/change.md`; the audit — `$2` — is
   `temporary-docs/gnomish/<task>/review-code.md`. There is nobody to ask, so never
   fall back to AskUserQuestion.
2. **You decide; nobody confirms.** The command's "show the triage and apply only
   after they confirm it" does not apply: your triage is the decision, and the judge
   after your round is the check on it.
3. **Subagents.** Give each accepted fix to a `general-purpose` subagent, as the
   command allows; every other type is denied. Only one runs at a time here: a launch
   while one is running is refused with "Concurrent subagent limit reached … Do not
   retry", which means "not now" — wait for the running one to return. Besides what
   the command requires, every subagent prompt carries "never push" and "Never edit
   `.claude/`, `.gnomish/`, `openspec/specs/`, `openspec/changes/archive/`, or the
   change's proposal, design or delta specs". Verify each subagent's red spec and run
   yourself before you record the fix.
4. **Where the resolution goes.** Instead of the command's dated file, write the
   record of step 5 to `temporary-docs/gnomish/<task>/fix-code.md`, in English,
   keeping its layout exactly: a check matches its `### <N> — applied|rejected`
   entries against the audit's numbering and reads their fields.
5. **No recommendations.** An audit with an empty Recommendations section still gets a
   record (`None.`, then Other edits and Verification), and `./gradlew check` must
   still pass.
6. **What a human must decide leaves through the escalation exit**, not through a
   rejection alone: a recommendation that is right but needs the plan changed, or a
   defect that cannot be fixed within the rules. Then:
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole situation in
      `question` (the recommendation, what you found, `file:line`, what you would do),
      newlines as `\n`, options that fit the case;
   3. finish your turn. Leave the fixes you already made in place: the factory commits
      them with the round, and the round after the answer continues from them.

   Before escalating, read the briefing's `=== Decisions ===`: if a human already
   answered exactly this, follow the answer instead of asking again.

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the operator is
  a lost attempt; the escalation exit is the only channel a human reads.
- **A retry continues the work.** If the record already exists, a previous round was
  rejected and its feedback is in your prompt. Your earlier fixes are still in the
  working copy: revert a fix the feedback calls harmful, fix a recommendation it says
  you wrongly rejected, then re-check the whole record and re-run `./gradlew check`.
- Edit the code, the tests, `docs/` and the change's `tasks.md` (ticks only), and write
  `temporary-docs/gnomish/<task>/fix-code.md`. Never `.claude/`, `.gnomish/`,
  `openspec/specs/`, the archive, or the change's proposal, design or delta specs — a
  check rejects it.
- **Never push**: the remote is the factory's. Do not commit either — not because the
  factory forbids it, but because there is no need and no way: the factory commits
  whatever the working copy holds when your round ends, and this repository's
  `.claude/settings.json` denies `git commit` (with `merge`, `rebase`, `cherry-pick`,
  `revert`), so the attempt would only stall the round. Leave the branch alone
  otherwise too: no `checkout`, `reset`, `stash`, `restore`, `clean` or `pull`. Never
  undo work you did not do.
- Keep your own context small: the subagents read and write the code, you verify their
  results. Pipe long command output through `tail`; read the lines you need rather
  than whole files.

**Before you stop, grade yourself as the judge will**, against
`.gnomish/stages/fix-code/acceptance.md`.
