---
name: "OPSX: Propose Checked"
description: Propose a new change via /opsx:propose with a pre-generation sync-surface scout and a mandatory Sync surfaces decision in design.md
category: Workflow
tags: [workflow, artifacts, experimental]
---

Wrapper around `/opsx:propose` that catches manually-synchronized pairs at the proposal
stage — before any code exists (see `.claude/rules/manual-sync-pairs.md` for why pairs are
the project's leading divergence-bug source). Do not modify `/opsx:propose` itself; it is
managed by OpenSpec and updates would overwrite the change.

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

4. Everything else follows `/opsx:propose` unchanged.
