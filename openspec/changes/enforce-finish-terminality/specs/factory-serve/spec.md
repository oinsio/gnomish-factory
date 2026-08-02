# factory-serve — delta

## ADDED Requirements

### Requirement: Feed declines finished-reopened tasks
The `serve` feed cycle SHALL treat `finished = true` entries from `listReady`
as terminal, never as work: they are excluded from claim candidates (neither
returned-priority nor fresh; they never occupy a slot and never count toward
or against the WIP limit), and each one observed SHALL be declined via the
tracker port's decline operation — restoring its terminal status and posting
the explanation — before candidate selection proceeds over the remaining
entries. A failed decline SHALL be logged and retried naturally on the next
poll cycle (the task simply reappears in the feed); no instance-local
"already declined" memory is kept.
<!-- implements FR3, FR4 of enforce-finish-terminality -->
<!-- implements NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality -->

#### Scenario: Reopened finished task is declined within one poll
- **WHEN** a human moves a finished task back to ready while `serve` is
  polling
- **THEN** within one poll cycle the task's terminal status is restored, the
  explanation comment is posted, no claim is attempted, and no slot is
  consumed

#### Scenario: Decline failure converges on the next cycle
- **WHEN** the decline write fails with a transient tracker error
- **THEN** the cycle logs the failure, claims nothing for that entry, and the
  next poll observes the task still in the feed and declines again

#### Scenario: Finished entries do not distort the WIP gate
- **WHEN** the feed lists W fresh tasks and one reopened finished task with
  open fronts below W
- **THEN** candidate selection runs over the fresh tasks as if the finished
  entry were absent
