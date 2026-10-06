# Acceptance criteria for the review-artifacts stage

The task branch holds `temporary-docs/gnomish/<task>/review-artifacts.md`: the report of
the project's `/review-artifacts` command, run on the change named in its
`## Artifacts Review:` heading. That command —
`.claude/commands/review-artifacts.md` — defines what the review checks, the
severities, the recommendation format and when an item is worth fixing; read it
first and judge the report against it. A check before you has already confirmed
the change name, the sections, that every recommendation carries its Problem,
Impact, Fix and Fix risk lines, and that the Verdict follows from the severities.
Do not recount them.

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — `git branch --show-current` shows it (`gnomish/github-oinsio-gnomish-factory-79`
gives `github-oinsio-gnomish-factory-79`) — or `local` when the branch does not start
with `gnomish/`. Every report of this pipeline lives under
`temporary-docs/gnomish/<task>/`, so reports of different tasks never overlap
and one directory holds everything to clean up after the task.

Be adversarial toward the recommendations. A harmful or useless one costs more
than a missing one: the next stage revises the change on its strength, and a
recommendation inflated to WARNING turns the verdict into `needs revision` by
itself.

## Every recommendation, one by one

Go through **every** numbered recommendation — not a sample — and put it to these
tests. Open the files it cites; decide from the source, not from the report's
wording.

1. **Evidence.** The cited location exists and shows what the Problem describes.
2. **Impact.** The Impact names a concrete failure if the change is implemented as
   written — the wrong thing built, existing behaviour broken, an implementer who
   cannot tell what to do, a requirement left untested. "Clearer", "more
   consistent", "more robust" with no failure behind it is no impact.
3. **Worth the cost.** Leaving it unfixed is worse than the edit: the fix is not
   larger, riskier or more disruptive than the gap it closes.
4. **No harm.** The Fix agrees with the existing code, `openspec/specs/`, the
   accepted ADRs in `docs/adr/`, `CLAUDE.md`, `.claude/rules/`, and the change's
   own goals and non-goals; its Fix risk is honest — `none` where you can name a
   risk fails this test.
5. **Not on the command's drop list** (step 6): it does not widen the scope,
   reopen a decision `design.md` already weighed without citing what removed its
   grounds, revert what a landed change deliberately removed, give advice instead
   of an edit, or duplicate another item.
6. **Right reference point.** Where the working copy and `origin/main` differ, the
   gap really exists against the one the item names; an item "fixing" what
   `origin/main` already fixed, or the reverse without saying so, fails.
7. **Severity follows the Impact**, by the command's definitions. An Impact that
   only costs clarity is a SUGGESTION, whatever the item claims.

A recommendation that fails test 1, 2, 4, 5 or 6 must be removed; one that fails
test 3 or 7 must be removed or downgraded. Any such recommendation fails the round.

## The report as a whole

- **Landed since.** Every change archived after this change was last revised that
  touches the same capability, classes, modules, config keys or requirement
  headings is listed with what it overlaps; no listed overlap is invented.
- **Freshness.** Every stale claim reported really is stale, and the reality it
  cites is accurate.
- **Nothing material is missed.** No stale class, path, key or `MODIFIED`
  requirement the change relies on, no part already implemented, no missing
  mandatory design decision, no requirement without a scenario or a task —
  without a recommendation. Fail this only for a missed issue that would itself
  be CRITICAL or WARNING.
- **Not an escalation in disguise.** If the change is already implemented, or a
  landed change removed its premise, the stage should have escalated instead of
  reporting (`.gnomish/stages/review-artifacts/instructions.md`, item 4). A report
  whose recommendations amount to "rewrite the whole change" fails.

## Your verdict

Name every recommendation you reject by its number, with the test it fails and
what the source actually says — "R3: remove — fails test 5, design.md:41 rejected
exactly this alternative (D4)"; "R5: downgrade to SUGGESTION — Impact is wording
only". The next attempt acts on your list item by item, so a rejection you saw but
did not name costs a whole attempt later. Report every violation, not the first few.

Judge by reading the report, the task in `.gnomish-task/task.json`, the change's
artifacts, the archived changes the report cites, `openspec/specs/`, `docs/adr/`
and the source. Do not run the build, the tests or the OpenSpec CLI. Start from
the report — its Landed-since entries, Freshness lines and recommendation
locations name the files you need; do not walk the whole tree.

Your turns are limited and each one counts. Read several files in one turn with
parallel `Read` calls, check a pattern across the tree with one `Grep`, and keep a
few turns in reserve: a round that ends without the verdict is lost.
