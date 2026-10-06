# Acceptance criteria for the fix-code stage

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — or `local` when the branch does not start with `gnomish/`.

This round fixed one change's implementation from an audit. Three things tell you
what happened: `temporary-docs/gnomish/<task>/review-code.md` (the audit and its
numbered recommendations), `temporary-docs/gnomish/<task>/fix-code.md` (one entry
per recommendation: applied with the edit and its red spec, or rejected with
evidence), and the code as it now stands. How an implementation is fixed from an
audit — and when a recommendation is worth fixing — is defined by
`.claude/commands/fix-from-audit.md` and `.claude/rules/review-recommendations.md`;
read them first and judge against them.

A check before you has already confirmed that every recommendation has exactly one
entry with its fields, that `./gradlew check` passes, and that nothing forbidden was
touched. Do not recount them, and do not run anything.

Both outcomes can be wrong, and both are judged. An applied fix can harm working
code; a rejected recommendation can have been a real defect that now ships.

## Every applied recommendation

1. **The fix is really there** at the locations the entry names, and does what the
   entry says.
2. **It closes the defect** the recommendation described — or, where the entry says it
   chose a different fix, that fix closes the same defect.
3. **A behaviour fix has its spec.** The red spec the entry names exists, asserts the
   behaviour the recommendation was about, and would fail without the fix — read it
   against the code it covers.
4. **It harms nothing.** It agrees with the change's plan, `openspec/specs/`, the
   accepted ADRs, `CLAUDE.md` and `.claude/rules/`; it does not weaken a gate (an
   exemption, suppression or exclusion `testing.md` does not allow), restyle working
   code, or widen the change's scope.
5. **It was worth applying** by the rule; a SUGGESTION was applied only as a local
   edit that changes no behaviour and no public surface.

## Every rejected recommendation

6. **The evidence is real.** The cited location exists and says what the entry quotes.
7. **The rejection is right.** The recommendation really fails the test the entry
   names. A real CRITICAL or WARNING defect with a safe fix, rejected anyway, fails
   this criterion — rejecting is not a way to avoid work.
8. **No plan question was quietly rejected.** A recommendation that was right but
   needed the plan changed should have been escalated, not only rejected.

## The work as a whole

9. **Nothing unrequested.** Every edit under Other edits fixes a `./gradlew check`
   failure and nothing more. You are not given the round's diff, so where code plainly
   differs from what the audit quoted at a location no entry lists, that is an
   unlisted edit and fails this criterion.

## Your verdict

Name every entry you reject by its number, the criterion it fails and what the source
actually says — "2 (applied): revert — fails 4, the new catch swallows the
interruption that lock-scope.md requires to propagate"; "5 (rejected): fix — fails 7,
the cited fail-open is real (GitExec.java:85)". The next attempt acts on your list
entry by entry. Report every violation, not the first few.

Judge by reading the three sources above, the change's artifacts, `openspec/specs/`,
`docs/adr/`, `.claude/rules/` and the source. Start from the resolution record — its
locations name the files you need; do not walk the whole tree.

Your turns are limited and each one counts. Read several files in one turn with
parallel `Read` calls, check a pattern across the tree with one `Grep`, and keep a few
turns in reserve: a round that ends without the verdict is lost.
