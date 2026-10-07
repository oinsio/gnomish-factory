# Spec Delta: execution-environment

## MODIFIED Requirements

### Requirement: Denial read position survives the process
The denial findings a round receives are the delta since the previous read, tracked by a cursor the environment advances. Because a denial source outlives the factory process that created it, the `TaskExecutionEnvironment` port SHALL expose that cursor — a read position paired with the identity of the source it was read from — so the factory can commit it with the attempt it delimits, and SHALL accept a cursor committed by an earlier lease before the first read of the current one.

A restored cursor is an offer, not an instruction: the environment SHALL apply the position only when the paired source identity matches its own live denial source, and SHALL ignore it otherwise. Environments without a denial source SHALL expose no cursor and SHALL accept an offer as a no-op.

The offer SHALL be made as part of building a container round environment, by the one component that builds them, from the branch tip as it stands at that moment — on every path that builds one: the first round's open, a segment boundary, and a resume's reattach. No separate offering step SHALL exist on the run-support surface, so no caller can build a box and offer the position in the wrong order; an environment the lease hands out already carries the offer.

Every delegating view of the port SHALL forward the whole denial surface — findings, cursor, restore — to its delegate: a view that answers with the interface's constant defaults while holding a delegate that has real answers violates this requirement. The cursor round-trip SHALL be covered by at least one spec that drives the production delegating view, not a test double.
<!-- implements FR17 of make-checkpoint-gate-durable -->
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

#### Scenario: A reattached box carries the recorded position
- **WHEN** a container-mode task is resumed onto a surviving guard whose branch tip records a denial cursor and the identities reported with it, and the resume reattaches the box before anything else runs
- **THEN** the box the lease hands out already holds that offer, and the next round's findings contain no denial the record already reported — the same result the first round of a fresh start gets

#### Scenario: The offer is read when the box is built, not when the run was assembled
- **WHEN** a lifecycle commit that moves the recorded cursor lands between the run's assembly and the first environment build
- **THEN** the environment is offered the position the tip records at build time
