# Delta spec: tracker-take (fix-abort-progress-reset)

## ADDED Requirements

### Requirement: Factory emits durable progress at the round boundary
On the first durable round after a claim, the factory SHALL call
`recordProgress` for the task exactly once per claim, from the round-boundary
hook that runs strictly after a round is durably persisted. The call SHALL be
best-effort: because the round is already durable when it runs, a tracker
failure SHALL be logged at WARN and swallowed — it SHALL NOT abort, block, or
fail the run. This is the mechanism that satisfies the `add-tracker-port` FR14
clause "the counter resets on the first durably persisted round after claim".
<!-- implements FR2 of fix-abort-progress-reset -->
<!-- implements NFR-R1 of fix-abort-progress-reset -->
<!-- implements NFR-O1 of fix-abort-progress-reset -->

#### Scenario: First durable round records progress
- **WHEN** a claimed task completes its first durable round
- **THEN** the factory calls `recordProgress` once for that task, after the
  round is persisted and before the revocation check

#### Scenario: Later rounds do not re-emit within the same claim
- **WHEN** a claimed task persists a second and third durable round
- **THEN** the factory does not call `recordProgress` again for that claim

#### Scenario: A progress-record failure never fails the run
- **WHEN** the `recordProgress` call throws (tracker unreachable)
- **THEN** the failure is logged at WARN and the run proceeds exactly as if the
  call had succeeded — no abort, no park, no non-zero exit attributable to it

#### Scenario: Progress resets the counter end-to-end
- **WHEN** a claim records two aborts, is reclaimed, persists a durable round,
  and later aborts once
- **THEN** the abort facts observed for the next backoff/fuse decision report a
  count of one, and backoff is computed from that count
