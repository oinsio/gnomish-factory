# github-tracker — delta

## ADDED Requirements

### Requirement: Returned fact from recorded thread markers
The GitHub adapter SHALL derive the returned fact from the structural markers
already recorded in the issue thread — the park report and the
holder-transition ("stale claim removed") marker — using the existing marker
anchors, without introducing any new coordination artifact or label. Listing
reads SHALL stay within the conditional-request (ETag) discipline the adapter
already uses, so an unchanged queue costs no rate-limit budget.
<!-- implements FR7 of add-factory-serve -->
<!-- implements NFR-P1 of add-factory-serve -->

#### Scenario: Human-returned issue

- **WHEN** an issue carrying a park report is moved back to the ready label
  and `listReady` runs
- **THEN** the entry reports the returned fact true, derived from the thread
  markers alone

#### Scenario: No new artifacts

- **WHEN** the adapter computes the returned fact for a listing
- **THEN** it writes nothing to the issue — the fact is read-only derivation
  from existing markers
