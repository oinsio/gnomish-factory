---
name: "OPSX: Propose Checked"
description: Propose a new change via /opsx:propose with pre-generation sync-surface and single-owner scouts and the two mandatory decisions (Sync surfaces, Single-owner mechanisms) in design.md
category: Workflow
tags: [workflow, artifacts, experimental]
---

Wrapper around `/opsx:propose` that catches two defect classes at the proposal stage —
before any code exists: manually-synchronized pairs (`.claude/rules/manual-sync-pairs.md`:
one rule implemented twice, the project's leading divergence-bug source) and single-owner
mechanisms with missing consumers (`.claude/rules/implementation.md`: one implementation
that only some paths use, the class the 2026-09-10 `add-base-ref-resolution` review
found). Do not modify `/opsx:propose` itself; it is managed by OpenSpec and updates would
overwrite the change.

**How it works**: Invoke `/opsx:propose $ARGUMENTS` via the Skill tool, but with the
following additional constraints injected into the execution:

## Execution constraints

1. **Sync-surface scout BEFORE writing artifacts.** Before generating `design.md` (or
   `proposal.md` if no design is planned), inspect the existing codebase:
   - `grep -rn "Kept in sync with" */src/main` plus the registry in
     `.claude/rules/manual-sync-pairs.md` — does the proposed change touch either end of a
     declared pair?
   - Does an implementation of the same rule/capability already exist for another execution
     mode (host vs container), layer, or medium? Search by the capability's domain terms,
     not just class names. If yes, this change is about to create the *second* (or third)
     implementation.

2. **Mandatory "Sync surfaces" answer in design.md.** The generated `design.md` must
   contain a decision (D-number) answering, based on the scout's findings:
   - which existing pairs the change touches, and that mirrored edits are in scope;
   - for any new parallel implementation: **shared abstraction or declared pair**, decided
     by the preference order in `manual-sync-pairs.md` (third implementation → abstraction,
     no exception), with rationale and the rejected alternative;
   - or the explicit line `Sync surfaces: none — this change adds no parallel
     implementation and touches no declared pair.` An absent answer is a format violation —
     silence is not an acceptable "none".

   If the schema's conditional skip omits `design.md`, the Sync surfaces decision goes into
   `proposal.md` instead — and a change that touches a declared pair or adds a parallel
   implementation disqualifies the design skip: create `design.md` and record the decision
   there.

3. **Pairs become tasks.** When the decision is a declared pair, `tasks.md` must include
   placing the `Kept in sync with` markers at both ends (or updating the registry) in the
   same change. When the decision is an abstraction, the extraction is a task, not a hope.

4. **Single-owner scout BEFORE writing artifacts.** Whenever the idea, or a decision being
   drafted, is worded as "once", "only", "the one place", "funnel", "resolve/peel/sanitize
   once", or introduces a component every path must go through, inspect the codebase before
   generating `design.md`:
   - enumerate every call site that today obtains the same value or performs the same step
     the mechanism will own — grep by the raw call (`rev-parse`, `resolveRef(`, a default
     literal, a direct read) and by the domain term, across every module and both execution
     media; these are the mechanism's consumers, listed by file;
   - find the ports and constructors those call sites reach the value through — each
     parameter still typed as a primitive (`String`, `Path`, `boolean`, a nullable with a
     default) is an escape hatch the change must close or exempt;
   - if the mechanism already exists and the idea is to "also use it in X", the scout is
     the same: list every place that does *not* use it yet, not only X.

5. **Mandatory "Single-owner mechanisms" table in design.md.** Per `design-decisions.md`,
   each such decision carries the table Owner / Value (type) / Consumers / Old way removed /
   Enforced by, filled from the scout: consumers named by file (never "all paths"), the
   raw form the change deletes or each exemption with its reason, and the enforcement
   (parameter type first; else an architecture spec in `:bootstrap` or a grep gate). Where
   the decision claims two values are one by construction, the row names the identity spec
   (`testing.md`, "Invariant specs across a flow"). A change with no such mechanism states
   `Single-owner mechanisms: none.` Silence is a format violation.

6. **Consumers become tasks.** Each table row yields tasks that name the consumers to wire,
   the signature to change, the old-way sweep to run (the grep, expected empty or matching
   the exemption list), the enforcement to add, and the identity spec to write — so an
   `apply` sub-agent receives a list it can check, not a claim it must trust
   (`implementation.md`, "For sub-agents implementing one task").

7. Everything else follows `/opsx:propose` unchanged.
