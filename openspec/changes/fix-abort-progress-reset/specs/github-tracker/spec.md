# Delta spec: github-tracker (fix-abort-progress-reset)

## MODIFIED Requirements

### Requirement: Structural comments carry coordination facts
Claim, abort, ack, note, report, and progress comments SHALL carry a
machine-recognizable structural marker plus human-readable text (recommended
shape: leading hidden HTML comment with one-line JSON — kind, instance, time,
format version). The round-trip law of the port contract SHALL hold over these
markers: abort facts readable back by any instance; decisions collected only
after the last ack; the abort count reset by the latest progress marker.
<!-- implements FR7 of add-tracker-port -->
<!-- implements NFR-O1 of add-tracker-port -->
<!-- implements FR4 of fix-abort-progress-reset -->

#### Scenario: Markers are invisible to humans, visible to machines
- **WHEN** the adapter posts a claim comment
- **THEN** the rendered GitHub comment shows only the human-readable line, and a
  fresh adapter instance parses holder, time, and kind from the comment body

#### Scenario: Progress marker is a recognized kind
- **WHEN** the adapter posts a progress comment and a fresh adapter instance
  parses the issue's comments
- **THEN** the progress marker is recognized as the `progress` kind with its
  instance and time, alongside the existing claim/abort/ack/note/report kinds

## ADDED Requirements

### Requirement: Abort count anchored to the latest progress marker
The GitHub adapter SHALL reconstruct a task's abort count as the number of
`abort` markers that appear strictly after the latest `progress` marker on the
issue; `abort` markers at or before the latest `progress` marker SHALL NOT be
counted. When no `progress` marker is present, the adapter SHALL fall back to
the existing claim-streak reconstruction unchanged. The `progress` marker SHALL
NOT act as a claim boundary in claim-holder resolution — the active claim is
still resolved over `claim`/`abort` markers alone.
<!-- implements FR3 of fix-abort-progress-reset -->

#### Scenario: Feed reader resets on progress
- **WHEN** an issue's comments read claim, abort, progress, abort and the
  adapter builds the `listReady` abort facts
- **THEN** the reported count is one (only the abort after the progress marker)

#### Scenario: fetchTask reader resets on progress
- **WHEN** the same comment stream is read via `fetchTask`
- **THEN** the reported abort count is one, identical to the feed reader

#### Scenario: Progress does not disturb claim-holder resolution
- **WHEN** an issue reads claim(A), progress, and the adapter resolves the
  active claim
- **THEN** the active claim is still A (the progress marker is not treated as a
  later boundary that hides the claim)
