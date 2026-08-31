# serve-observability — delta for add-epic-decomposition

## ADDED Requirements

### Requirement: Ledger records the decomposed outcome
The observability ledger SHALL record a task run ending in decomposition as a
`decomposed` task outcome with the child count, serialized with its own wire
token. Both ends of the declared ledger wire pair (writer mapper and
dashboard aggregator) SHALL know the token, the dashboard SHALL aggregate
decomposed outcomes distinctly, and the wire round-trip spec SHALL iterate
every outcome constant including the new one, pinning the unknown-token
forward-compat arm.
<!-- implements NFR-O1 of add-epic-decomposition -->

#### Scenario: Decomposed outcome round-trips the wire
- **WHEN** the round-trip spec iterates all task-outcome constants
- **THEN** `fromWire(wire(decomposed)) == decomposed` passes alongside every
  existing constant

#### Scenario: Dashboard counts decompositions distinctly
- **WHEN** the ledger holds two decomposed and three completed outcomes
- **THEN** the dashboard aggregation reports them as separate counts
