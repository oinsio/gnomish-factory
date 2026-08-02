# tracker-port — delta

## ADDED Requirements

### Requirement: Ready listing carries the returned fact
Each `listReady` entry SHALL carry an adapter-derived "returned" fact — true
when the task's recorded history shows it was previously worked and given
back: a park report (human-returned) or a holder-transition marker
(reaper-returned) exists. Adapters SHALL report the fact only; the
prioritization policy (returned first, WIP accounting) lives in core. No
other port operations are added: the open-front count is the size of the
existing `listOpen` listing.
<!-- implements FR6, FR7 of add-factory-serve -->

#### Scenario: Fresh task is not returned
- **WHEN** `listReady` lists a task that was never claimed
- **THEN** its returned fact is false

#### Scenario: Park round-trip sets the fact
- **WHEN** a task is parked with an escalation report and later moved back to
  ready
- **THEN** `listReady` lists it with the returned fact true

#### Scenario: Reaped task is returned
- **WHEN** a stale claim is removed and its task returns to `Ready`
- **THEN** `listReady` lists it with the returned fact true

### Requirement: Contract suite covers the returned fact
The shared contract spec suite SHALL be extended to verify on every adapter:
the returned fact is false for never-claimed tasks, true after a
park-and-return round-trip, and true after a stale-claim removal — and that
`listOpen`'s size equals the open-front count the WIP policy consumes.
<!-- implements FR7 of add-factory-serve -->
<!-- implements NFR-R1 of add-factory-serve -->

#### Scenario: Suite passes on both adapters
- **WHEN** the extended contract suite runs against the in-memory reference
  and the GitHub adapter
- **THEN** every returned-fact property passes without adapter-specific
  exemptions
