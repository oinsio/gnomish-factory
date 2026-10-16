# Rule: when the code cannot satisfy the spec as written

Applies to whoever implements or fixes against an OpenSpec change — `/opsx:apply`,
`/opsx:apply-sequential`, `/fix-from-audit`, `/check-issue`, the factory's implement and
fix-code stages — the moment a task, a scenario or a design decision cannot be met by the code
as the artifacts state it. The report that reaches the human is the last step of the analysis
below, never the first.

## The failure this rule exists for

Task 6.5 of `fix-terminal-receipt-convergence` (2026-10-15): the implementer found the `serve`
janitor's branch pass skipping a task branch the clone held only as a remote-tracking ref,
against a scenario that promised convergence. The question it raised offered two options —
"narrow the scenario and the design, no code change (recommended)" or "widen the pass to fetch"
— on the premise that factory clones are `--single-branch`, so the state "arises only from an
operator's manual `git fetch`". One grep refuted the premise: only the container's seed clone is
single-branch, and an IDE's periodic fetch puts every task branch in that state. The analysis
that followed found a class of defect with two instances (a reader deciding from a local read
whose consistency only a fetch guarantees), one label covering two states with two recoveries,
and a daemon duty dying silently with a green build. None of that was visible in the question.

## A scenario is a promise, not a draft

A scenario in a delta or a main spec states what a design decision promised. Code that cannot
meet it is a finding against the code or against the decision. **"Narrow the scenario, no code
change" is not an option an implementer may offer**, and neither is quietly widening a WHEN
clause until the code passes. Revising the decision is a design act: it goes through
`/architect` (Mode C, the decision review) and lands through `/opsx:update`, with the reason
recorded in `design.md` as a rejected or amended alternative. The scenario changes after the
decision does, never instead of it.

## Before the question reaches the human

Every item has a written answer in the report, in this order:

1. **Every premise verified in code, with `file:line`.** A sentence of the form "this only
   happens when…", "the clone is always…", "nothing else reads…" is a claim; grep it. A premise
   that cannot be shown is reported as unverified, not assumed. The premise that decided the
   2026-10-15 question was false and cost one grep.
2. **The class named, and its instances mapped.** Generalize the defect into a question ("which
   reader acts on a value that only a fetch guarantees?", "which window freezes a state no shape
   names?") and search every subsystem and medium for the same shape — the code audit of
   `/architect` Mode A. The map lists the verified-SAFE sites too; a finding with one instance
   is reported as one instance, with the search that showed it.
3. **Alternative zero stated.** What is left if the mechanism in question is deleted, and what
   does that remainder cost per run? (`design-decisions.md`, "Alternative zero"). The option
   that removes a duty names who carries it afterwards; "nobody" is a finding, not an option.
4. **Options ranked by failure class**, not by effort: a permanent stall or a silent loss of a
   duty ranks above a wrong-but-visible result, which ranks above a bounded extra cost (a fetch
   per tick, a tracker read). An option whose cheapness comes from skipping work the spec
   promised says so in its first line.
5. **What each option leaves behind, and who picks it up.** A deferral names its pickup
   condition (`proposal-format.md`, the deferral bullet), never a time; a rejected alternative
   names what breaks it on this project's constraints.

An orchestrator that receives a sub-agent's blocker report without these five answers does not
forward it: it runs `/architect` on the subject (Mode A for a defect, Mode C for a decision the
code contradicts) and brings the human the result. The sub-agent's report is the input, not the
question.

## What this rule does not say

- It does not forbid a short question. A genuine fork the human owns — two valid designs with
  different costs — is asked in two sentences, *after* the five answers show it is genuine.
- It does not forbid narrowing a scenario. It forbids narrowing one *as a fix*: a scenario that
  promised more than the design intends is narrowed by the decision review that says so, with
  the premise verified.
- It does not change `verification-scope.md`: the analysis is reading and grepping, not a build.

## How this is checked

- `/opsx:apply-sequential` (constraint 5), `/fix-from-audit` (triage) and `/check-issue` (step
  6) accept a blocker only in this form, and the sub-agent prompt carries this rule.
- `/review-artifacts` (step 5, "Vs narrowed scenarios") and `/audit-implementation` (step 3)
  report a scenario whose WHEN clause was narrowed, or whose THEN lost a promise, without a
  design decision that cites a verified premise — ❌ CRITICAL: a promise removed to make the code
  pass is the patch this rule exists to prevent.
