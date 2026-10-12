# tracker/tracker-take — delta for add-decision-inheritance

## ADDED Requirements

### Requirement: Subtask claim materializes frozen inherited context
Claiming a task whose hierarchy facts name a parent SHALL fetch the epic
branch's decisions file and the child's brief, freeze them for the
invocation (like the pipeline law), and make them available to briefing
assembly; resume SHALL re-freeze from the current epic file so a decision
accepted between invocations reaches the next invocation. A fetch failure
SHALL never yield a round with silently absent inheritance.
**Implements**: FR4 of add-decision-inheritance

#### Scenario: Claim freezes the inherited context
- **WHEN** a subtask is claimed while the epic file holds two binding
  records
- **THEN** every round of the invocation sees exactly those two, even if the
  epic file gains a third mid-invocation

#### Scenario: Resume re-freezes from the current epic file
- **WHEN** a decision is accepted on the epic file between two invocations of a subtask, and the subtask is resumed
- **THEN** the resumed invocation's inherited context contains the new decision

### Requirement: Inherited-context fetch failures classify by cause
A failed inherited-context fetch SHALL classify by cause under the claim-time
fetch rule of base-ref-resolution (ADR 0005), never by the step that observed
it, with the three-way answer of the existing branch locate step as the
classifier. An unreachable remote SHALL be an infrastructure failure handled
exactly as a failed base refresh; an epic branch or decisions file that origin
reports absent or unreadable SHALL be a task-level failure that parks the task.
**Implements**: FR4 of add-decision-inheritance; FR4 of tighten-spec-contract

#### Scenario: Unreachable remote releases the claim like a base refresh
- **WHEN** the epic-branch fetch exhausts its retries against a dead remote
- **THEN** no engine round runs, the claim is released with no marker and
  no comment, the task is Ready with its abort facts unchanged, and in
  `serve` the remote outage gate opens

#### Scenario: Missing epic branch parks the task
- **WHEN** origin answers and reports no epic branch for the parent the
  hierarchy facts name
- **THEN** the take parks the task with a report naming the inherited-context
  source, no engine round runs, and no stage attempt is burned

#### Scenario: Retries, release and gate follow the base-refresh rule
- **WHEN** the epic-branch fetch meets an unreachable remote
- **THEN** it is retried within the bounded retries, and on exhaustion the claim is released through the plain release path with no abort marker, no comment and no attempt burned

#### Scenario: Origin that never answered is not absence
- **WHEN** the branch locate step for the epic branch gets no answer from origin
- **THEN** the failure classifies as infrastructure, and the task is not parked

#### Scenario: Unreadable decisions file parks the task
- **WHEN** origin holds the epic branch but its decisions file cannot be read
- **THEN** the take parks the task with the report "cannot assemble inherited context" naming the inherited-context source, and no engine round runs

### Requirement: Child finish orders roll-up first
Finishing a subtask with subtree-scoped decisions SHALL land the roll-up on
the epic branch before the tracker finish write, under the child's claim;
the finish retry discipline SHALL cover the window between the two. The
integration child's claim SHALL run the roll-up completeness check before
its first round.
**Implements**: FR3, NFR-R1 of add-decision-inheritance

#### Scenario: Kill between roll-up and finish converges
- **WHEN** an instance dies after the roll-up push but before the tracker
  finish, and the child is resumed
- **THEN** the resume completes the finish without duplicating the roll-up
