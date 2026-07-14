# Rule: requirement traceability

Every artifact must be traceable to a requirement ID from `proposal.md`. If there is no link — the artifact is either unnecessary or you missed something.

## Requirement ID formats (scoped per change)

- `FR1, FR2...` — functional requirements
- `NFR-P1...` — performance
- `NFR-R1...` — reliability (retries, resume, idempotency)
- `NFR-O1...` — observability (logs, progress reporting, cost tracking)
- `NFR-S1...` — security (credentials, sandbox boundaries)
- `NFR-C1...` — cost (token/money budgets)
- `UX1, UX2...` — operator experience criteria
- `M1, M2...` — success metrics
- `G1, G2...` — goals
- `NG1, NG2...` — non-goals
- `Q1, Q2...` — open questions

Uniqueness is per change: `add-tracker-port.FR1` and `add-pipeline.FR1` are different requirements.

## Where to place traceability links

| Artifact                        | Required reference                          |
|---------------------------------|---------------------------------------------|
| Domain spec invariant/use case  | `# implements FR-X of <change-name>`        |
| Gherkin scenario                | tags `@<change-name> @FR-X` above scenario  |
| Port interface (doc comment)    | `Implements FR-X of <change-name>`          |
| Code unit (doc comment)         | `Implements FR-X of <change-name>`          |
| Test (description or comment)   | `FR-X: <what is verified>`                  |
| Local ADR (`design.md`)         | "Context: driven by FR-X from proposal"     |

## Verification rule

For every FR/NFR/UX in `proposal.md`, at least one implementing entity must exist in code or tests. Use `grep` to verify coverage.
