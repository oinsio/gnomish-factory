# sandbox-lifecycle — delta

## MODIFIED Requirements

### Requirement: Uniform verdict events
Every sweep evaluation SHALL emit one verdict event per object — category (checked-alive, kept-under-threshold, stopped-orphan, disposed-aged, disposed-reconstructible, skipped-no-verdict), object name, role, ownership mode, task key, reason, and age — through a listener seam. A manual box stopped by the age threshold SHALL emit stopped-orphan with mode `manual`, so sinks can distinguish a routine age-policy stop from a dead-instance symptom. All entry points (`run`, `take`, `serve`) SHALL evaluate the same policy component and differ only in where events sink. A decorator observing the pass SHALL preserve the caller's extra sink: the extra sink passed at an entry point receives every verdict event alongside the decorator's own sinks, never dropped through an inherited default.
<!-- implements FR9, NFR-O4 of add-serve-sandbox-lifecycle -->
<!-- implements FR6 of fix-denial-attribution-durability -->

#### Scenario: Same vocabulary in daemon and one-shot logs
- **WHEN** the same unowned stopped box is evaluated by a daemon tick and by a `take` startup pass
- **THEN** both emit the identical category and reason; the daemon's sinks to its ledger, take's to its log

#### Scenario: An extra sink survives the observing decorator
- **WHEN** an entry point runs the sweep pass through the observing decorator and passes an extra verdict sink
- **THEN** the extra sink receives every verdict event the decorator's own sinks receive
