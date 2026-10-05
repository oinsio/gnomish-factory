# Spec Delta: agent-executor

## MODIFIED Requirements

### Requirement: Shared briefing renderer
The briefing section renderer SHALL be a shared component with an explicit public API whose sections accept pre-read data; file reading SHALL remain with each adapter. The judge prompt SHALL use the section subset goal + decisions + criteria + verdict instruction; the stage round prompt SHALL use the full section set. The rendered text SHALL be the same whichever adapter requests a section.
<!-- implements FR4 of remove-interactive-console -->

#### Scenario: Extraction is invisible to the console
- **WHEN** the executor prompt and a spec render the same section over the same pre-read data
- **THEN** the rendered text is identical
