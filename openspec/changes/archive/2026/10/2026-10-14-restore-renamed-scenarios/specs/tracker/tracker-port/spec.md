## ADDED Requirements

### Requirement: Contract suite covers the returned fact
The shared contract spec suite SHALL be extended to verify on every adapter:
the returned fact is false for never-claimed tasks, true after a
park-and-return round-trip, true after a stale-claim removal, and false
after a finish-and-reopen round-trip; and the finished fact is true after a
finish-and-reopen round-trip and false otherwise.
**Implements**: FR7 of add-factory-serve; NFR-R1 of add-factory-serve; FR6 of enforce-finish-terminality

#### Scenario: Returned-fact suite passes on both adapters
- **WHEN** the extended contract suite runs against the in-memory reference
  and the GitHub adapter
- **THEN** every returned-fact, finished-fact, and decline property passes
  without adapter-specific exemptions

#### Scenario: Finish-reopen property is adapter-equivalent
- **WHEN** the same finish-then-human-reopen history is arranged on each
  adapter
- **THEN** both report identical facts (returned false, finished true) —
  the divergence the suite previously missed

#### Scenario: The suite covers the decline operation
- **WHEN** the extended contract suite runs against an adapter
- **THEN** it verifies that the decline operation restores terminal status,
  removes the task from `listReady`, leaves the derivations unpolluted, and is
  a silent no-op (status and thread unchanged) on an already-terminal task

#### Scenario: The suite binds the open-front count to the open listing
- **WHEN** the extended contract suite runs against an adapter
- **THEN** it verifies that `listOpen`'s size equals the open-front count the
  WIP policy consumes
