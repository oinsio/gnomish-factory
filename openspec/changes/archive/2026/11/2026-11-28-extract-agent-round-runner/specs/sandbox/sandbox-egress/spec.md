# sandbox/sandbox-egress — delta for extract-agent-round-runner

## MODIFIED Requirements

### Requirement: Guard outage is an infrastructure failure
When the guard is unreachable or down, in-flight checks SHALL classify as infrastructure failures (retried per existing policy, no stage attempt burned), and the factory SHALL restart the guard.
**Implements**: NFR-R1 of add-sandbox-core; FR15 of extract-agent-round-runner

#### Scenario: Guard crash burns no attempt
- **WHEN** the guard dies while a stage round is in progress
- **THEN** the round fails as an infrastructure failure, the attempt counter is unchanged, and the guard is restarted before retry

#### Scenario: A dead guard around a check in a box is cannot-verify
- **WHEN** the guard of the box a `program` or `judge` check runs in is not running before the check's request or after it
- **THEN** the check is cannot-verify naming the guard, whatever the executor answered, and the attempt counter is unchanged

#### Scenario: A stage round and a check round are probed by one owner
- **WHEN** a stage round and a check round each run in a box with a guard
- **THEN** each is probed before and after its request by the same component, and no executor or round class probes for itself
