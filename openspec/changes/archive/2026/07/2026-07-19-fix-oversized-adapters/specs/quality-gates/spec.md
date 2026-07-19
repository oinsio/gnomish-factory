# Delta spec: quality-gates (fix-oversized-adapters)

## ADDED Requirements

### Requirement: Source file size cap
Every production Java source file SHALL stay within the 200-line hard cap of
`.claude/rules/process-invariants.md`; a file that exceeds it SHALL be split
along a cohesive seam into focused units, each within the cap, without changing
observable behaviour. The cap is enforced in review, not by build tooling.
<!-- implements FR1, FR2 of fix-oversized-adapters -->

#### Scenario: An oversized adapter is split into compliant units
- **WHEN** a production source file exceeds the 200-line cap
- **THEN** it is split so the file and every unit extracted from it is ≤ 200 lines
- **AND** the extraction is behaviour-preserving — no existing spec is modified
  and `./gradlew check` stays green

#### Scenario: Extracted units keep the same behaviour and observability
- **WHEN** logic is moved from an oversized file into a package-private collaborator
- **THEN** the public entry point's behaviour, exit codes, and log output are unchanged
- **AND** coverage and PIT mutation score on the affected classes are no lower than before
