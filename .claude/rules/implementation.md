# Rule: implementing a shared mechanism — every consumer, or none

Applies to any task that introduces, revises, or wires a **single-owner mechanism**: one
component that is meant to be the only source of some value or the only way to perform some
step (a resolver, a funnel, a peel, a sanitizer choke point, a policy component). The design
half of this rule — how such a mechanism is declared — lives in `design-decisions.md`
("Mandatory decision: single-owner mechanisms"). This file is the implementer's checklist,
loaded into every session, so it is read before any `/opsx:apply` starts.

## The failure this rule exists for

A shared mechanism is built and one path uses it; the other paths keep doing the old thing,
and every unit spec stays green because each component is correct in isolation. The
2026-09-10 review of `add-base-ref-resolution` found exactly this: the base ref was peeled
once for the law, and the branch-creation port kept accepting a bare name and re-resolving
it. Nothing was duplicated, so no sync-pair check fired; the mechanism simply had one consumer
where the design assumed two.

## Definition of done for a single-owner task

A task that says "wire X into A and B", "all paths receive Y", or "Z is the only place that
does W" is not done until every item below has evidence in the task's own report:

1. **Consumer list, from the design, checked off.** The design's single-owner table names
   every consumer of the mechanism's result. Each one is visited and shown to take the value
   from the owner — not to recompute or re-resolve it. A consumer the table missed is added to
   the table in the same task, not silently wired.
2. **Old-way sweep.** Grep for every pre-existing way of obtaining the same value or performing
   the same step (the raw call — `rev-parse`, `resolveRef(String)`, a default literal, a
   direct file read). Every hit is either removed, routed through the owner, or listed as an
   exemption in the design with its reason (a manual tier, an offline path). An unlisted
   survivor is the bug this rule prevents, not a follow-up.
3. **The escape hatch is gone.** If a port or constructor still accepts the raw form the owner
   was introduced to replace (a `String` where a typed value now exists, a nullable that
   defaults), the mechanism is not enforced. Change the signature in the same change; the
   compiler then does what no review does reliably.
4. **Enforcement named.** The design table says what keeps the mechanism single-owner after
   this change: the parameter type, an architecture spec in `:bootstrap`
   (`BaseHeadDefaultBoundarySpec` is the precedent: an allowlist of files, a banned literal
   or call, a scan that asserts it reached every file), or a grep gate. "Convention" is not
   an answer.
5. **Identity spec exists where the design claims identity.** If the design says two values
   "are one by construction" or "cannot diverge", one spec asserts that identity end to end on
   the real medium (see `testing.md`, "Invariant specs across a flow"). Component specs that
   each pass do not establish it.

## For sub-agents implementing one task

The apply flow hands each task to a fresh context. The task text therefore carries the
consumer list and the old-way pattern explicitly — "all four fresh-start paths" is a claim
the sub-agent cannot verify unless the four are named. A task author who cannot name them
has not finished the design. The sub-agent's report ends with the sweep result: the grep it
ran, the hits, and what happened to each.

## Audit obligation

`/audit-implementation` checks every single-owner task against the five items above and
reports an unlisted old-way survivor as ❌ CRITICAL — it is a correctness defect with a
green build, the hardest kind to find later.
