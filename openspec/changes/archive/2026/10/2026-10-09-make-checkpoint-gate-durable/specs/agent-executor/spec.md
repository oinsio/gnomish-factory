# Spec Delta: agent-executor

## MODIFIED Requirements

### Requirement: Decision-file protocol
In git modes the decision request SHALL live in the working copy at `.gnomish-task/decisions/<stage>-a<attempt>-<token>.json` — the single gnome-writable path under `.gnomish-task/` — where `<token>` is the *round token*: the task branch's tip commit at the moment the round opened, minted by the factory and repeated by no later round of the task. The adapter SHALL pass that exact path via `$GNOMISH_DECISION_FILE`, read it through the environment after process exit, and map: that path present → `DecisionNeeded`, absent → `Completed`. No other file SHALL be read: a decision file under any other name — carried over on the branch from an earlier round of the same stage and attempt number, left by a killed round, or written by the gnome beside the real one — SHALL change nothing, whether or not it was removed. The request rides the snapshot (or salvage) commit under its token, so a pending escalation survives factory death and is resumable by any instance from the branch alone: an interrupted verification resumed from a snapshot commit SHALL re-raise the request that snapshot's tree holds at the token path as `DecisionNeeded` with the same tolerant reading, and resume as `Completed` when the snapshot holds none. Reading SHALL be tolerant: invalid JSON → the entire file content becomes the question with empty options; empty file → a fallback question text; the raw content SHALL be logged at WARN on any parse trouble. A `DecisionNeeded` result SHALL carry the same telemetry (usage and trace) as `Completed`. The git-less in-place mode SHALL keep the per-round temp-file transport outside the workspace, whose staleness rule is the deletion after the read.
<!-- implements FR13, FR15, NFR-R4 of make-checkpoint-gate-durable -->
<!-- implements FR3, NFR-R3, NFR-O2, D1 of add-agent-executor -->
<!-- implements FR23 of add-sandbox-core -->

#### Scenario: Agent asks for a decision
- **WHEN** the agent writes `{"question": "Refactor or patch?", "options": ["refactor", "patch"]}` to the path `$GNOMISH_DECISION_FILE` names and exits
- **THEN** the adapter returns `DecisionNeeded` with that question and both options

#### Scenario: No signal means Completed
- **WHEN** the process exits without creating a file at the path `$GNOMISH_DECISION_FILE` names
- **THEN** the adapter returns `Completed`

#### Scenario: Garbage decision file is not lost
- **WHEN** the decision file contains unparseable text
- **THEN** the adapter returns `DecisionNeeded` whose question is the raw content, with empty options, and logs the content at WARN

#### Scenario: Pending decision survives factory death
- **WHEN** a factory dies after the snapshot commit carrying a decision request but before the escalation is recorded
- **THEN** a resuming instance reads the request from the snapshot's tree at the round's token path and escalates with that question, without replaying the round
- **AND** the resumed round carries the token and the snapshot commit that record names — no new token is minted, no tip is re-read — so the state commit it lands is checked against that token as a live round's would be

#### Scenario: An answered request is not asked again
- **WHEN** a container-mode task parked on a decision request is answered, the stage restarts at attempt 0, and the next round's box is cloned from a tip that still carries the earlier request file
- **THEN** the next round is given a path under its own token, reads only that path, and maps to `Completed` unless the gnome wrote a new request there

#### Scenario: Stale request from another round is ignored
- **WHEN** a decision file named for a previous round — another stage, another attempt number, or the same stage and attempt number under an earlier round's token — is present in the working copy
- **THEN** the current round maps to `Completed` and the stale file changes nothing
