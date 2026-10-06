# Acceptance criteria for the review-code stage

`<task>` in the paths below is the name of this task's branch without its
`gnomish/` prefix — or `local` when the branch does not start with `gnomish/`.

The task branch holds `temporary-docs/gnomish/<task>/review-code.md`: the report of
the project's `/audit-implementation` command, in `quick` mode, on the change named
in its `## Implementation Audit:` heading. That command —
`.claude/commands/audit-implementation.md` — defines what the audit checks and the
severities; `.claude/rules/review-recommendations.md` defines the recommendation
format and when an item is worth fixing. Read both first and judge the report
against them. A check before you has already confirmed the change name, the
sections, that every recommendation carries its Problem, Impact, Fix and Fix risk
lines, and that the Verdict follows from the severities. Do not recount them.

Be adversarial toward the recommendations. A harmful or useless one costs more than
a missing one: the next stage changes the code on its strength, and a
recommendation inflated to WARNING turns the verdict into `not ready` by itself.

## Every recommendation, one by one

Go through **every** numbered recommendation — not a sample — and open the files it
cites; decide from the source, not from the report's wording.

1. **Evidence.** The cited location exists and shows what the Problem describes.
2. **Impact.** A concrete failure follows if it stays: wrong or missing behaviour, a
   task claimed but not done, a rule of `.claude/rules/` broken, an untested path, a
   fail-open. "Cleaner", "more idiomatic", "more robust" with no failure behind it is
   no impact.
3. **Worth the cost.** The fix is not larger, riskier or more disruptive than the gap.
4. **No harm.** The Fix agrees with the change's plan (proposal, design, delta specs),
   `openspec/specs/`, the accepted ADRs, `CLAUDE.md` and `.claude/rules/`; its Fix risk
   is honest — `none` where you can name a risk fails this test.
5. **Not on the rule's drop list**: it does not widen the scope, reopen a design
   decision without new grounds, revert what landed, weaken a gate, restyle working
   code to taste, give advice instead of an edit, or duplicate another item.
6. **Severity follows the Impact**, by the command's definitions.

A recommendation that fails test 1, 2, 4 or 5 must be removed; one that fails test 3
or 6 must be removed or downgraded. Any such recommendation fails the round.

## The report as a whole

- **Tasks.** Every task of the change's `tasks.md` has a verdict with `file:line`
  evidence, and every ✅ really holds in the code. A task marked done whose evidence
  does not exist, accepted as done, fails this criterion.
- **Requirements.** Every requirement id of the proposal has a verdict; the ✅ ones are
  really implemented and tested where the report says.
- **Nothing material is missed.** No task claimed but not done, no requirement without
  a test, no violation of `.claude/rules/` in the touched code (single-owner sweeps,
  lock scope, crash consistency, logging, parameter and file-size limits), no
  fail-open — without a recommendation. Fail this only for a missed issue that would
  itself be CRITICAL or WARNING.
- **Not an escalation in disguise.** An implementation that departs from the plan so
  far that the recommendations amount to "rebuild it" should have been escalated
  (`.gnomish/stages/review-code/instructions.md`, item 6).

## Your verdict

Name every recommendation you reject by its number, with the test it fails and what
the source actually says — "R4: remove — fails 5, testing.md allows @DoNotMutate only
for the three listed reasons"; "R7: downgrade to SUGGESTION — Impact is naming only".
The next attempt acts on your list item by item. Report every violation, not the
first few.

Judge by reading the report, `temporary-docs/gnomish/<task>/implement.md`, the
change's artifacts, `openspec/specs/`, `docs/adr/`, `.claude/rules/` and the source.
Do not run the build or the tests. Start from the report — its evidence and
recommendation locations name the files you need; do not walk the whole tree.

Your turns are limited and each one counts. Read several files in one turn with
parallel `Read` calls, check a pattern across the tree with one `Grep`, and keep a few
turns in reserve: a round that ends without the verdict is lost.
