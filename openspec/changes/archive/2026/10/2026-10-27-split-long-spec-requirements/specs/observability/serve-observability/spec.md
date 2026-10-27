## MODIFIED Requirements

### Requirement: taskOutcome lines for terminal results carrying a final state
A `taskOutcome` line SHALL be appended for each terminal slot result with a
final state — `outcome` ∈ `delivered | awaitingHuman | aborted | revoked`,
`parkReason` only with `awaitingHuman` — with `taskId`, `stage`, `attemptsUsed`,
`startedAt`, `finishedAt`, `wallMillis`, `tokensByModel`. A slot ended by an
unclassified throwable SHALL append its line with `outcome: aborted` at the slot
boundary. `EmptyQueue`, `Skipped` and precondition-released results SHALL
NOT produce lines.
**Implements**: FR11 of add-serve-observability; FR18 of add-sandbox-base-image; FR1, FR2 of split-long-spec-requirements
**Notes**: A released task writes no line for the reason a `Skipped` one writes none: no engine ran and nothing was spent; its episode is the `daemonCondition` line's (design D20).
**Notes**: `stage` is null at the pipeline end.

#### Scenario: Delivered task leaves one line
- **WHEN** a slot delivers a task
- **THEN** one `taskOutcome` line with `outcome: delivered` and its token
  totals is appended

#### Scenario: Lost claim race leaves no line
- **WHEN** a slot ends with `Skipped`
- **THEN** no ledger line is written (no engine run — no spend)

#### Scenario: A crashed slot leaves one line
- **WHEN** a slot's run dies with a throwable no arm classifies
- **THEN** one `taskOutcome` line with `outcome: aborted`, a null `stage`, zero tokens and the slot's wall time is appended before the uncaught-crash event is logged, and the dashboard counts the slot among the aborted
