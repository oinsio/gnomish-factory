# git-task-persistence — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Pipeline pin is task identity on the branch
`task.json` SHALL carry the pinned pipeline name and definition content
hash, written in the same commit that creates the task on the branch —
mutually-implied facts landing together. The wire format change SHALL ride
the existing version gate; pre-routing task files SHALL read as pinned to
the default pipeline with an absent hash, and an absent hash SHALL skip
hash verification (legacy tasks), never fail it.
<!-- implements FR4, NFR-R1 of add-pipeline-routing -->

#### Scenario: Creation commit carries the pin
- **WHEN** the task-creation commit is inspected on a fresh branch
- **THEN** `task.json` in that commit already names the pipeline and hash

#### Scenario: Legacy task file reads as default-pinned
- **WHEN** a pre-routing `task.json` is read
- **THEN** it yields the default pipeline with no hash, and resume proceeds
  without hash verification

#### Scenario: Pin round-trips the wire
- **WHEN** a pinned task file is written and read back
- **THEN** name and hash survive unchanged, covered by the mapper's
  round-trip spec
