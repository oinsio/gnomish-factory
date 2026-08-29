# agent-executor

## MODIFIED Requirements

### Requirement: Decision-file protocol carries a structured request
The decision-file schema SHALL support the structured request: `question`,
`options` (at least two entries, each with an id and concrete text), and
`whyBlocked`. The executor prompt SHALL document the schema. A file
missing the structure SHALL still be tolerated for the human path (raw
text as question, park to human), but SHALL NOT qualify for an arbiter
consult; when an arbiter is configured, the round's feedback SHALL name
the missing fields so the gnome can re-raise the fork well-formed.
<!-- implements FR3 of add-decision-arbiter -->

#### Scenario: Well-formed request qualifies for consult
- **WHEN** the decision file parses with question, two options, and
  whyBlocked
- **THEN** the request is eligible for an arbiter consult

#### Scenario: Malformed request falls back to human path with feedback
- **WHEN** the decision file holds free text without options on an
  arbiter-enabled stage
- **THEN** no consult happens, and the recorded feedback names the
  missing schema fields

#### Scenario: Legacy behavior without an arbiter
- **WHEN** the stage configures no arbiter
- **THEN** any decision file — structured or raw — parks to the human
  exactly as before this change
