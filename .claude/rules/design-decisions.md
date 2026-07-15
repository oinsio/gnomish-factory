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

## Rules

- Always reference the FR/NFR/UX from proposal.md that drove the decision
- Keep it concise — this is not a research paper
- If the decision applies beyond this change, it belongs in `docs/adr/NNNN-*.md`
- Local decisions in design.md get archived with the change and are not meant to be updated later
