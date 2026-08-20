# manual-run — delta

## ADDED Requirements

### Requirement: Manual ownership mode
Container environments created by `gnomish run` SHALL be labelled with ownership mode `manual`. Manual objects are governed by the age-only policy of `sandbox-lifecycle` — no claim oracle — so a live manual session is never disturbed by a coexisting daemon, and a forgotten manual zombie is still reclaimed after the configured thresholds.
<!-- implements FR2, FR7 of add-serve-sandbox-lifecycle -->

#### Scenario: Manual session beside a daemon
- **WHEN** a container `gnomish run` session works on a host where `gnomish serve` also runs
- **THEN** the daemon's sweep classifies the manual objects by their mode label and applies only the age policy — the running manual box under the threshold is untouched

### Requirement: Run startup sweep degrades without a tracker
The `run` startup sweep pass SHALL evaluate the shared `sandbox-lifecycle` policy. When no tracker is configured, tracked objects of other tasks SHALL receive skipped-no-verdict (untouched, logged); manual objects follow the age policy, which needs no tracker; the run's own task key keeps its existing `--discard-work` and reattach semantics. Verdicts SHALL be logged in the uniform vocabulary.
<!-- implements FR6, FR9, NFR-O4, NFR-R1 of add-serve-sandbox-lifecycle -->

#### Scenario: Trackerless run touches no tracked object
- **WHEN** a `gnomish run` without tracker configuration starts on a host holding another task's tracked objects
- **THEN** those objects are reported skipped-no-verdict and untouched, and the run proceeds normally
