# git/git-task-persistence — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: A decision records the question it answers
The decision commit that consumes an `escalated` outcome whose report is a decision request
SHALL record that report's question and options on the decision it appends, in the same commit
as the reply; a decision that consumes any other outcome SHALL record none. A `task.json` whose
decisions carry no question SHALL read as before.
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: The reply and its question land in one commit
- **WHEN** a task parked on a decision request is answered, on the host or in container mode
- **THEN** the decision the decision commit appends to `task.json` carries the reply, the question and the options of the consumed report

#### Scenario: A reply to another escalation records no question
- **WHEN** a task parked as `escalated(AttemptsExhausted)` is returned with a reply
- **THEN** the appended decision carries the reply and no question

#### Scenario: Decisions written before the question read as before
- **WHEN** a `task.json` whose decisions carry only body, stage, author and time is read
- **THEN** each decision reads with no question and is written back unchanged
