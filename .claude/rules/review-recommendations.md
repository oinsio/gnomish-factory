---
paths:
  - "temporary-docs/**"
---

# Rule: review recommendations — format, worth, resolution

Applies to every command that writes recommendations about a change (`/review-artifacts` on
its artifacts, `/audit-implementation` on its implementation) and to every command that acts
on them (`/update-from-review`, `/fix-from-audit`). One definition, so that a reviewer, the
fixer that triages its output and the judges grading both apply the same tests — and a
human running the commands by hand gets the same result as the factory's pipeline.

## The failure this rule exists for

A reviewer is paid to find problems, so it finds some whether or not they are there: an
alternative the design already rejected, scope the proposal excluded, polish presented as a
defect, a "refresh" that reverts what a landed change removed on purpose. Applied as orders,
such recommendations damage the change they were meant to improve. The cure has two halves:
every recommendation carries what makes it refutable, and whoever acts on it re-verifies it
and may reject it with evidence.

## The format of a recommendation

Each item is self-contained — the reader acts on it without hunting through the report:

```
N. **SEVERITY — <short title>** (`file-or-artifact:line`)
   Problem: what is wrong, restated here even if described earlier.
   Impact: what concretely goes wrong if it stays as it is.
   Fix: the concrete edit to make.
   Fix risk: what the edit could break or cost — checked against the code, the stable specs,
      the ADRs and the change's own goals and non-goals — or `none`.
```

`Impact` makes the item refutable as useless; `Fix risk` makes it refutable as harmful. Both
are claims the author checks before writing them, not boilerplate.

## Worth fixing

An item earns its place only if all of these hold:

1. **Evidence.** The cited location exists and shows the problem.
2. **Impact.** A concrete failure follows if it stays — the wrong thing built, behaviour
   broken, a gate red, an implementer unable to tell what to do. "Clearer", "more
   consistent", "more robust" with no failure behind it is no impact.
3. **Worth the cost.** The fix is not larger, riskier or more disruptive than the gap.
4. **No harm.** The fix agrees with the code, `openspec/specs/`, the accepted ADRs,
   `CLAUDE.md`, `.claude/rules/` and the change's own goals and non-goals.

Drop an item that:

- **widens the scope** — asks for what the proposal's Non-Goals exclude, or grows the change
  past one initiative (`process-invariants.md`);
- **reopens a settled decision** — proposes an alternative `design.md` already considered and
  rejected, unless it cites what landed since and removed that decision's grounds;
- **reverts what landed** — moves the change back toward something a landed change
  deliberately removed;
- **weakens a gate** — reaches green by an exemption, suppression or exclusion the rules do
  not allow (`testing.md`), instead of fixing the cause;
- **restyles working code** — rewrites what is correct and meets the rules, to taste;
- **is advice, not an edit** — "consider X", "add more tests" with no concrete failure;
- **duplicates** another item.

## Severity

Each command defines its own scale for its subject; across all of them, **severity follows the
Impact, never the reverse**. An Impact that only costs clarity is a SUGGESTION, whatever the
title says. When uncertain, verify against the code, and downgrade rather than guess.

A report's verdict blocks — `needs revision`, `not ready` — exactly when at least one CRITICAL
or WARNING recommendation exists, and opens with that verdict on its first line.

## Resolving recommendations

Whoever acts on a report triages every item before editing anything:

- **Re-verify** it: open the location and the source it relies on. The report's wording is a
  claim, not a fact.
- **Apply** it only if it is worth fixing (above). A smaller or different edit that closes the
  same gap with less risk is fine; say so.
- A **SUGGESTION** is applied only when the edit is trivially safe — local, changing no
  behaviour, scope, requirement, task or public surface. Otherwise reject it.
- A fix that would change the change's **intent** is not applied: it needs a human.
- Otherwise **reject** it, with evidence.

The resolution record lists every item of the report, in its numbering, nothing skipped:

```
### <N> — applied
- Recommendation: <SEVERITY — title, as in the report>
- Re-verified: `<file:line>` — what you found there
- Edit: `<file:line>` — what changed (and, if it differs from the proposed Fix, why); every
  location, reconciling edits elsewhere included

### <N> — rejected
- Recommendation: <SEVERITY — title>
- Re-verified: `<file:line>` — what you found there
- Evidence: `<file:line>` — which test of "Worth fixing" or which drop-list entry it fails,
  quoting what the source actually says
```

followed by an **Other edits** section — every edit no recommendation asked for, and why;
`None.` when there are none. An edit the record does not list is an edit nobody reviews.
