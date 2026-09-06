# tracker-take — delta for add-decision-inheritance

## ADDED Requirements

### Requirement: Subtask claim materializes frozen inherited context
Claiming a task whose hierarchy facts name a parent SHALL fetch the epic
branch's decisions file and the child's brief, freeze them for the
invocation (like the pipeline law), and make them available to briefing
assembly; resume SHALL re-freeze from the current epic file so a decision
accepted between invocations reaches the next invocation. A fetch failure
SHALL never yield a round with silently absent inheritance, and it SHALL
classify by cause under the claim-time fetch rule of base-ref-resolution
(ADR 0005), never by the step that observed it: an unreachable remote is an
infrastructure failure — bounded retries, then the claim is released
through the plain release path with no abort marker, no comment, and no
attempt burned, and in `serve` the remote outage gate opens — exactly as a
failed base refresh; an epic branch or decisions file that origin reports
absent or unreadable is a task-level failure that parks the task with a
report naming the inherited-context source ("cannot assemble inherited
context"). The three-way answer of the existing branch locate step (origin
answered and holds no such branch / origin holds it or never answered)
SHALL be the classifier.
<!-- implements FR4 of add-decision-inheritance -->

#### Scenario: Claim freezes the inherited context
- **WHEN** a subtask is claimed while the epic file holds two binding
  records
- **THEN** every round of the invocation sees exactly those two, even if the
  epic file gains a third mid-invocation

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
