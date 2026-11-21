# executor/wire-contract — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: A continuation key names state any instance reaches
An executor SHALL declare `resumable` and return a continuation key only when the state the key
names is reachable by every factory instance; state on the worker — a process, a machine's
files, a box — SHALL NOT be named by one. The engine SHALL send no continuation in this version:
the key is reserved vocabulary, read and dropped.
**Implements**: FR9, NFR-R2 of extract-agent-round-runner

#### Scenario: A returned continuation key is not sent back
- **WHEN** a round's `decision-request` answer carries a continuation key and the decision is answered
- **THEN** the next round's `start` request carries no continuation

## MODIFIED Requirements

### Requirement: Closed status taxonomy
Every result SHALL carry one status: `completed`; `failed` with class `quality`,
`infrastructure` or `limit`; or `pending` with an opaque key. No other status token SHALL be
accepted, and an unknown token SHALL be read as `failed/infrastructure` naming the token. A
`pending` from a `transform` executor SHALL be polled only when its describe declares `poll`.
**Implements**: FR2, NFR-R3 of define-executor-contract; FR10 of extract-agent-round-runner
**Notes**: Layered on the text `define-executor-contract` adds (archived first); only one scenario's WHEN is reworded, by design D11 of extract-agent-round-runner, and one scenario is added.

#### Scenario: Status tokens round-trip
- **WHEN** every status and class constant is written to the wire and read back
- **THEN** each reads back as itself, and the spec iterates the full constant set

#### Scenario: Unknown status fails closed
- **WHEN** a result file carries `status: "done"`
- **THEN** the engine records `failed/infrastructure` with a reason naming `done`

#### Scenario: Pending from a role whose caller cannot poll
- **WHEN** a `transform` executor whose describe declares no `poll` returns `pending`
- **THEN** the round is `failed/infrastructure` with a reason naming the unsupported pending

#### Scenario: Pending with an unusable key fails closed
- **WHEN** a result file carries `status: "pending"` with a `pendingKey` holding a control character (a NUL among them) or longer than 4096 characters
- **THEN** the result reads as `failed/infrastructure` with a reason naming the unusable key, and no poll is spawned with it

#### Scenario: Pending from a transform executor that can be polled
- **WHEN** a `transform` executor whose describe declares `poll` returns `pending` with a usable key
- **THEN** the engine persists the key and polls it (`pipeline/stage-engine`, "A pending transform round is persisted before it is polled")
