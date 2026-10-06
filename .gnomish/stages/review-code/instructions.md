# Review-code stage instructions

The implement stage built this task's OpenSpec change and left `./gradlew check`
green. Now audit that implementation: how completely and how well each task and
requirement is done, against the project's rules.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79`
gives `github-oinsio-gnomish-factory-79`) — or `local` when the branch does not start
with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`, so reports of different tasks never overlap
and one directory holds everything to clean up after the task.

**The audit itself is the project's own `/audit-implementation` command, in its
`quick` mode: read `.claude/commands/audit-implementation.md` and carry it out in
full**, with `.claude/rules/review-recommendations.md` for the recommendations. They
are the one source of how this repository audits an implementation, for a human at
the keyboard and for you alike. Everything below is only what differs because nobody
is watching this round; where it is silent, the command decides.

## What differs in this stage

1. **The arguments.** The change — `$1` — is the `Change:` line of
   `temporary-docs/gnomish/<task>/change.md`. The mode is `quick`: skip step 4, the
   Gradle gate — the implement stage's last check ran the full `./gradlew check` on
   this very commit, and a second run would cost tens of minutes and say nothing new.
   Report the gate as `skipped (quick)`. There is nobody to ask, so never fall back to
   AskUserQuestion.
2. **The diff base.** Where the command scopes the implementation with
   `git diff main...HEAD`, use `git diff origin/main...HEAD`: the task branch was cut
   from `origin/main`, and a local `main` may be absent or stale here.
3. **Explore subagents, one at a time.** The command's per-section fan-out is
   available, but sequential: only one subagent runs at a time here. Launch the next
   only after the previous one has returned; a launch while one is running is refused
   with "Concurrent subagent limit reached … Do not retry", which means "not now".
   Every other subagent type is denied. A subagent's verdict is a lead, not evidence:
   open every `file:line` yourself before a recommendation rests on it.
4. **The implementation record.** `temporary-docs/gnomish/<task>/implement.md` lists
   what the implement stage says it did per task, with the specs it ran and the
   single-owner sweeps. Use it to find the evidence quickly; never as the evidence.
5. **Where the report goes.** Nobody reads your reply. Instead of the command's
   step 10 file, write the **whole report of step 9** — every section, from
   `## Implementation Audit: <name>` to the Verdict — to
   `temporary-docs/gnomish/<task>/review-code.md`, in English. Keep the step 9 layout
   and the rule's recommendation format exactly: checks read both, and the fix-code
   stage works through the recommendations one by one.
6. **The escalation exit.** If the implementation departs from the change's plan so
   far that no list of fixes can reconcile them — a different design built, a
   requirement implemented as its opposite — a human must decide which of the two is
   right. Then write no report and escalate:
   1. run `echo "$GNOMISH_DECISION_FILE"` to learn where the decision file goes;
   2. write that path with the Write tool — one JSON object,
      `{"question": "...", "options": ["...", "..."]}`, the whole situation in
      `question` (what the plan says, what was built, `file:line` for both), newlines
      as `\n`, options that fit the case;
   3. finish your turn. The factory posts the question and parks the task.

   Before escalating, read the briefing's `=== Decisions ===`: if a human already
   answered exactly this, follow the answer instead of asking again.

## Rules of this medium

- **Nobody is watching this round.** A turn that ends in a question to the operator
  is a lost attempt; the escalation exit is the only channel a human reads.
- **A retry is a full audit.** If the report already exists, a previous round was
  rejected and its feedback is in your prompt. Fix every point it names, then
  re-verify the whole report rather than patching only the flagged points.
- Write exactly one file, `temporary-docs/gnomish/<task>/review-code.md`, plus the
  decision file on the escalating path. The audit changes nothing else.
- **Never push**: the remote is the factory's. Do not commit either — not because the
  factory forbids it, but because there is no need and no way: the factory commits
  whatever the working copy holds when your round ends, and this repository's
  `.claude/settings.json` denies `git commit` (with `merge`, `rebase`, `cherry-pick`,
  `revert`), so the attempt would only stall the round. Leave the working copy and the
  branch alone otherwise too: no `checkout`, `reset`, `stash`, `restore`, `clean` or
  `pull`. Never undo work you did not do.
- Use the shell only for read-only git, `grep`, `openspec list|show|validate`, and the
  decision-file `echo`. Do not run Gradle.
- Keep the output small: pipe long command output through `tail`, read the lines you
  need rather than whole files, and keep your closing summary to the verdict and the
  count of recommendations per severity.

**Before you stop, grade yourself as the judge will**, against
`.gnomish/stages/review-code/acceptance.md`.
