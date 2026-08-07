# pipeline-config

## ADDED Requirements

### Requirement: External check declarations carry a timeout class
The external check declaration SHALL accept an optional timeout class —
`quality` or `infrastructure` — defaulting to `quality` when absent. The
value SHALL load into the typed model; a value outside the two known
classes SHALL be a located validation error identifying the check.
<!-- implements FR9 of add-external-check-github-actions -->

#### Scenario: Absent timeout class defaults to quality
- **WHEN** an `external` check declares no timeout class
- **THEN** the typed model carries the `quality` class and engine behavior
  is unchanged

#### Scenario: Unknown timeout class is rejected
- **WHEN** an `external` check declares a timeout class other than
  `quality` or `infrastructure`
- **THEN** validation reports a located error identifying the check
