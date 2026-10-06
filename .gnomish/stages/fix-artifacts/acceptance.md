# Acceptance criteria for the fix-artifacts stage

This round revised one OpenSpec change from a review. Three files tell you what
happened: `temporary-docs/gnomish/<task>/review-artifacts.md` (the review and its numbered
recommendations), `temporary-docs/gnomish/<task>/fix-artifacts.md` (one entry per
recommendation: applied with the edit, or rejected with evidence), and the change
under `openspec/changes/<change>/` as it now stands. How a change is revised from a
review — and when a recommendation is worth fixing — is defined by
`.claude/commands/update-from-review.md` and step 6 of
`.claude/commands/review-artifacts.md`; read them first and judge against them.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79`
gives `github-oinsio-gnomish-factory-79`) — or `local` when the branch does not start
with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`, so reports of different tasks never overlap
and one directory holds everything to clean up after the task.

A check before you has already confirmed that every recommendation has exactly one
entry with its fields, that the change validates under `openspec validate --strict`,
and that nothing outside the change and the stage reports was touched. Do not
recount them.

Both outcomes can be wrong, and both are judged. An applied recommendation can harm
the change the whole implementation will be built on; a rejected one can have been a
real gap that now reaches implementation unfixed.

## Every applied recommendation

1. **The edit is really there** at the location the entry names, and does what the
   entry says.
2. **It closes the gap** the recommendation described — or, where the entry says it
   chose a different edit, that edit closes the same gap.
3. **It harms nothing.** It agrees with the existing code, `openspec/specs/`, the
   accepted ADRs in `docs/adr/`, `CLAUDE.md`, `.claude/rules/`, and the change's own
   goals and non-goals; it does not widen the scope, reopen a decision `design.md`
   already weighed without new grounds, or revert what a landed change deliberately
   removed.
4. **It was worth applying.** It passes the worth-fixing definition; a SUGGESTION was
   applied only as a trivially safe edit that changes no behaviour, scope,
   requirement or task.
5. **The change stayed coherent.** Every other artifact the edit affects was
   reconciled: no requirement without its scenario and task, no task still building
   the old variant, no id referenced but undefined, no term used two ways.

## Every rejected recommendation

6. **The evidence is real.** The location the entry cites exists and says what the
   entry quotes.
7. **The rejection is right.** The recommendation really fails the test the entry
   names. A recommendation that was in fact a real CRITICAL or WARNING gap with a
   safe fix, rejected anyway, fails this criterion — rejecting is not a way to avoid
   work.
8. **No intent change was quietly rejected.** A recommendation that needed a human's
   decision about the change's intent should have been escalated, not rejected.

## The change as a whole

9. **Nothing unrequested.** Every edit under Other edits fixes a validation error
   and nothing more. You are not given the round's diff, so where an artifact plainly
   differs from what the review quoted at a location no entry lists — a requirement,
   scenario or task rewritten with no recommendation behind it — that is an unlisted
   edit and fails this criterion.
10. **Faithful to the change.** The change still proposes what it proposed: same
    problem, same goals; refined, not replaced.

## Your verdict

Name every entry you reject by its number, the criterion it fails and what the source
actually says — "3 (applied): revert — fails 3, ADR 0011 says boundary keys are read
from the project file only"; "5 (rejected): apply — fails 7, the cited path really is
stale (ProjectRegistry.java:122)". The next attempt acts on your list entry by entry,
so a problem you saw but did not name costs a whole attempt later. Report every
violation, not the first few.

Judge by reading the three files above, the change's artifacts, the task in
`.gnomish-task/task.json`, `openspec/specs/`, `docs/adr/` and the source. Do not run
the build, the tests or the OpenSpec CLI. Start from the resolution record — its
locations name the files you need; do not walk the whole tree.

Your turns are limited and each one counts. Read several files in one turn with
parallel `Read` calls, check a pattern across the tree with one `Grep`, and keep a few
turns in reserve: a round that ends without the verdict is lost.
