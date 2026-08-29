# agent-executor — delta for add-decision-inheritance

## ADDED Requirements

### Requirement: Briefing renders inherited context
When a task carries inherited context, executor briefings SHALL render a
dedicated section with the child's brief and the binding inherited decisions
verbatim — each with provenance and rejected alternatives — followed by the
conflict rule (contradiction = escalate a proposed supersede, never record
an override). Judge briefings SHALL receive the same binding decisions
inside the hardened data delimiters used for existing decisions. Tasks
without inherited context SHALL produce today's briefing byte-for-byte.
<!-- implements FR4, FR5 of add-decision-inheritance -->

#### Scenario: Inherited section present and verbatim
- **WHEN** a subtask round's briefing is built from two binding inherited
  decisions
- **THEN** the section carries both verbatim with provenance and the
  conflict rule text

#### Scenario: Non-hierarchical task briefing unchanged
- **WHEN** a task with no parent and no inherited context runs a round
- **THEN** the built briefing is identical to the pre-change format
