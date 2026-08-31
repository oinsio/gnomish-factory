# execution-environment — delta

## MODIFIED Requirements

### Requirement: Denial findings readable through the port
The `TaskExecutionEnvironment` port SHALL expose the environment's egress denial findings as structured findings, so consumers reach denials through the contract without knowing the adapter. Each finding SHALL be paired with its source-assigned identity — the denial source's own event timestamp and the source identity — so consumers can merge re-reads idempotently. Environments without an egress guard SHALL return an empty list. Read-back SHALL be best-effort: an unreadable or missing denial source yields an empty list and SHALL never fail the round, the attempt, or the report.
<!-- implements FR1, NFR-R1 of fix-denial-report-attachment -->
<!-- implements FR7 of fix-denial-attribution-durability -->

#### Scenario: Sandboxed environment surfaces its guard denials
- **WHEN** a consumer holding the port type asks a sandboxed environment for denial findings after a round with a denied request
- **THEN** it receives the structured denial findings recorded by the guard, each paired with its source-assigned identity, without downcasting to any adapter type

#### Scenario: Guard-less environment reports no denials
- **WHEN** a consumer asks a host (non-sandboxed) environment for denial findings
- **THEN** it receives an empty list

#### Scenario: Unreadable denial log degrades to empty
- **WHEN** the guard's denial log is missing or unreadable at read-back time
- **THEN** the port returns an empty list and the round completes normally

### Requirement: Denial read position survives the process
The denial findings a round receives are the delta since the previous read, tracked by a cursor the environment advances. Because a denial source outlives the factory process that created it, the `TaskExecutionEnvironment` port SHALL expose that cursor — a read position paired with the identity of the source it was read from — so the factory can commit it with the attempt it delimits, and SHALL accept a cursor committed by an earlier lease before the first read of the current one. The read SHALL hand back the findings and the advanced position as one value, so the position can become durable only through the same call that persists the record.

A restored cursor is an offer, not an instruction: the environment SHALL apply the position only when the paired source identity matches its own live denial source, and SHALL ignore it otherwise. Environments without a denial source SHALL expose no cursor and SHALL accept an offer as a no-op.

Every delegating view of the port SHALL forward the whole denial surface — findings, cursor, restore — to its delegate: a view that answers with the interface's constant defaults while holding a delegate that has real answers violates this requirement. The cursor round-trip SHALL be covered by at least one spec that drives the production delegating view, not a test double.
<!-- implements FR5 of fix-denial-report-attachment -->
<!-- implements FR6 of fix-denial-attribution-durability -->

#### Scenario: A resumed lease reports only its own rounds' denials
- **WHEN** an environment is offered the cursor committed by an earlier lease, naming the denial source it is now attached to, and its first round closes
- **THEN** it reports only the denials recorded after that position, not those the source still holds from earlier rounds

#### Scenario: A cursor from another denial source is ignored
- **WHEN** an environment is offered a cursor whose source identity is not its own live denial source — a resume on another machine, or onto a recreated source
- **THEN** the position is ignored and the environment reads its own source from the beginning, so no real denial is filtered out of the report

#### Scenario: Guard-less environment has no cursor
- **WHEN** a host (non-sandboxed) environment is asked for its denial cursor, or offered one
- **THEN** it exposes none and accepts the offer without failing

#### Scenario: A delegating view forwards the denial surface
- **WHEN** the factory reads the denial cursor, reads findings, or restores a cursor through a delegating view of the port over a guard-backed environment
- **THEN** the delegate's real answers come back — never the interface defaults — and the committed state carries the cursor the delegate reported
