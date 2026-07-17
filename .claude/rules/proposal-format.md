---
paths:
  - "openspec/changes/**/proposal.md"
---

# Rule: proposal.md format

The proposal is the PRD of a change. It describes WHAT we build and WHY, never HOW (that goes into `design.md`).

## Required sections

Every proposal.md must contain:

1. **Why** — problem, audience, urgency
2. **What Changes** — ADDED / MODIFIED / REMOVED capabilities
3. **Goals** — G1, G2... (measurable)
4. **Non-Goals** — NG1, NG2... (explicitly out of scope)
5. **Users & Scenarios** — U1, U2...
6. **Requirements**
   - Functional: FR1, FR2...
   - Non-Functional Performance: NFR-P1...
   - Non-Functional Reliability: NFR-R1... (retries, resume, idempotency)
   - Non-Functional Observability: NFR-O1... (logs, progress, cost tracking)
   - Non-Functional Security: NFR-S1... (credentials, sandbox boundaries)
   - Non-Functional Cost: NFR-C1... (token budgets)
7. **Operator Experience Criteria** — UX1, UX2... (what the human sees in tracker/logs/config)
8. **Success Metrics** — M1, M2... (concrete numbers, not "works well")
9. **Open Questions** — Q1, Q2...

Include only the NFR subsections that apply — but consider each of them explicitly.

## Conditional sections

- **Capabilities** — New / Modified capability list, when the OpenSpec schema generates it (duplicates What Changes at the capability granularity)
- **Impact** — affected scope: modules/packages touched, new dependencies, effect on existing code; boundaries only, no design reasoning
- **Behavior** — reference to behavior specs (Gherkin features) with `@<change-name>` tags, once the test stack exists
- **Affected stages** — which pipeline stage contracts this change adds or modifies

## Rules

- Every requirement MUST have an ID (FR1, NFR-R1, UX1, etc.)
- Success metrics must be concrete and measurable (not "feature works")
- Non-Goals are as important as Goals — they prevent scope creep
- No implementation *choices* — picking a library or schema belongs in design.md. Referencing already-fixed decisions (the ADR 0001 tech stack, an existing ADR) is fine: that is context, not a choice
- Operator Experience criteria describe subjective expectations that must be explicitly agreed upon
