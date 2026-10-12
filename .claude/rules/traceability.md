# Rule: requirement traceability

Every artifact must be traceable to a requirement ID from `proposal.md`. If there is no link — the artifact is either unnecessary or you missed something.

## Requirement ID formats (scoped per change)

- `FR1, FR2...` — functional requirements
- `NFR-P1...` — performance
- `NFR-R1...` — reliability (retries, resume, idempotency)
- `NFR-O1...` — observability (logs, progress reporting, cost tracking)
- `NFR-S1...` — security (credentials, sandbox boundaries)
- `NFR-C1...` — cost (token budgets)
- `UX1, UX2...` — operator experience criteria
- `M1, M2...` — success metrics
- `G1, G2...` — goals
- `NG1, NG2...` — non-goals
- `Q1, Q2...` — open questions

Uniqueness is per change: `add-tracker-port.FR1` and `add-pipeline.FR1` are different requirements.

## Where to place traceability links

| Artifact                       | Required reference                          |
|--------------------------------|---------------------------------------------|
| Spec requirement (markdown)    | `**Implements**: FR-X of <change-name>`     |
| Spec scenario                  | `// Scenario: <spec-id> / <title>` in the test that exercises it |
| Domain spec invariant/use case | `# implements FR-X of <change-name>`        |
| Gherkin scenario               | tags `@<change-name> @FR-X` above scenario  |
| Port interface (doc comment)   | `Implements FR-X of <change-name>`          |
| Code unit (doc comment)        | `Implements FR-X of <change-name>`          |
| Test (description or comment)  | `FR-X: <what is verified>`                  |
| Local ADR (`design.md`)        | "Context: driven by FR-X from proposal"     |

**Spec requirements.** Each requirement carries exactly one `**Implements**:` line, after the
requirement text and before its first scenario. A requirement driven by several changes joins
them with `; ` (`**Implements**: FR1, FR2 of add-x; FR3 of fix-y`). The old HTML-comment
form (`implements … ` inside `<!-- -->`) is not accepted in `openspec/specs/` or in an
unarchived change; archived changes keep it, since they are immutable. The YAML-like forms (domain spec
invariants and use cases) keep `# implements`.

**Spec scenarios.** `<spec-id>` is the capability path (`verification/verification-hardening`)
and `<title>` is the scenario heading, copied verbatim. The marker sits on the line above the
feature that exercises the scenario, or inside it when one feature covers several. The marker
rules and exemptions are in `testing.md` ("Every scenario has a test"); the requirement shape
is in `delta-specs.md`.

The gate `./gradlew checkSpecContract` (or `scripts/check-spec-contract.sh`) checks both: the
`**Implements**:` line of every requirement, and a marker for every scenario in both
directions.

## Verification rule

For every FR/NFR/UX in `proposal.md`, at least one implementing entity must exist in code or tests. Use `grep` to verify coverage.
