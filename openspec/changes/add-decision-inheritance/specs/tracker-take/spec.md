# tracker-take — delta for add-decision-inheritance

## ADDED Requirements

### Requirement: Subtask claim materializes frozen inherited context
Claiming a task whose hierarchy facts name a parent SHALL fetch the epic
branch's decisions file and the child's brief, freeze them for the
invocation (like the pipeline law), and make them available to briefing
assembly; resume SHALL re-freeze from the current epic file so a decision
accepted between invocations reaches the next invocation. A fetch failure
SHALL be an infrastructure failure (retry, then escalate "cannot assemble
inherited context"), never a round with silently absent inheritance.
<!-- implements FR4 of add-decision-inheritance -->

#### Scenario: Claim freezes the inherited context
- **WHEN** a subtask is claimed while the epic file holds two binding
  records
- **THEN** every round of the invocation sees exactly those two, even if the
  epic file gains a third mid-invocation

#### Scenario: Unreachable epic branch never yields a bare round
- **WHEN** the epic branch cannot be fetched at claim after retries
- **THEN** the take escalates naming the inherited-context fetch, and no
  engine round runs

### Requirement: Child finish orders roll-up first
Finishing a subtask with subtree-scoped decisions SHALL land the roll-up on
the epic branch before the tracker finish write, under the child's claim;
the finish retry discipline SHALL cover the window between the two. The
integration child's claim SHALL run the roll-up completeness check before
its first round.
<!-- implements FR3, NFR-R1 of add-decision-inheritance -->

#### Scenario: Kill between roll-up and finish converges
- **WHEN** an instance dies after the roll-up push but before the tracker
  finish, and the child is resumed
- **THEN** the resume completes the finish without duplicating the roll-up
