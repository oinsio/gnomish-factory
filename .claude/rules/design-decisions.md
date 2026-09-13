---
paths:
  - "openspec/changes/**/design.md"
---

# Rule: design.md — local technical decisions

`design.md` inside a change folder is for non-trivial technical decisions scoped to THIS change. Not every change needs one.

## When to create design.md

- There is a real architectural choice (e.g., "polling vs webhooks for tracker updates in this feature")
- Multiple implementation approaches exist and the reasoning should be recorded
- The decision has trade-offs worth documenting

## When NOT to create design.md

- The implementation is straightforward — no alternatives to consider
- The decision is global and not tied to this change — use `docs/adr/` instead

## Format

Two accepted formats — pick by the number of decisions.

### Single decision

```markdown
# Design: <Change name>

## Context
What drove this decision. Reference FR/NFR from proposal.md.

## Decision
What exactly was decided. Be specific.

## Consequences
Positive:
- ...

Negative:
- ...

## Alternatives Considered
**<Alternative 1>**: why considered and why rejected
```

### Multiple decisions

When a change settles several related decisions, use numbered decisions instead of one giant Decision section:

```markdown
# Design: <Change name>

## Context
What drove these decisions. Reference FR/NFR from proposal.md.

## Decisions
**D1 — <short title>.** What was decided. *Rationale:* why.
*Alternative rejected:* what and why not.

**D2 — ...**

## Risks / Trade-offs
- [risk or negative consequence] → mitigation
```

Each Dn must still carry its rationale and at least one rejected alternative; the shared Risks / Trade-offs section covers the negative consequences. Other artifacts reference decisions as `D1`, `D2`, ...

## Mandatory decision: sync surfaces

One decision category is required, not optional. If the change adds a second implementation
of a rule/capability that already exists for another execution mode, layer, or medium — or
touches either end of a pair declared in `manual-sync-pairs.md` — `design.md` MUST record
the decision: **shared abstraction or declared pair**, chosen by that rule's preference
order (a third implementation always extracts the abstraction), with rationale and the
rejected alternative. A change with no such surface states it explicitly:
`Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.` Silence is a format violation — the explicit "none" is what makes the question
checkable by `/review-artifacts` and `/audit-implementation`.

## Mandatory decision: single-owner mechanisms

Sync surfaces answer "where is one rule implemented twice". This decision answers the
reverse question: "who must use the one implementation, and what stops them from not using
it". Whenever a decision says that one component is the only source of a value or the only
place a step happens ("resolve once", "one funnel", "the adapter peels once", "the only
sanitizer"), `design.md` MUST carry a table for it:

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|

- **Value (type)** names the type the result travels as. If it is a primitive a consumer could
  obtain elsewhere (`String`, `Path`, `boolean`), the owner is not enforced — introduce the
  typed value or explain why the primitive cannot be replaced.
- **Consumers** lists every call site that must take the value from the owner, by file. "All
  paths" is not an entry; the implementer's sub-agent (see `implementation.md`) can only check
  a list.
- **Old way removed** names the raw call, default, or signature the owner replaces and states
  that the change deletes it — or lists each surviving exemption (a manual tier, an offline
  path) with its reason.
- **Enforced by** names the mechanism that keeps it true after the change: the parameter type,
  an architecture spec in `:bootstrap`, or a grep gate. Convention is not an entry.

Where the decision claims two values "are one by construction" or "cannot diverge", the table
row is paired with an identity spec on the real medium (`testing.md`, "Invariant specs across
a flow"). A change with no single-owner mechanism states it: `Single-owner mechanisms: none.`
`/review-artifacts` checks the table exists; `/audit-implementation` checks the code matches
every row.

## Rules

- Always reference the FR/NFR/UX from proposal.md that drove the decision
- Keep it concise — this is not a research paper
- If the decision applies beyond this change, it belongs in `docs/adr/NNNN-*.md`
- Local decisions in design.md get archived with the change and are not meant to be updated later
