# pipeline-config — delta

## ADDED Requirements

### Requirement: Optional tracker section with core keys
`.gnomish/config.yaml` SHALL support an optional `tracker` section whose core
keys — the only ones the loader itself knows — are `type` (adapter discriminator)
and `abort-threshold` (positive integer, default 3, the core abort-fuse policy
shared by all instances). An absent `tracker` section SHALL be valid: tracker
subcommands are unavailable and all previously specified loading behavior is
unchanged.
<!-- implements FR17 of add-tracker-port -->

#### Scenario: No tracker section
- **WHEN** a `.gnomish/` without a `tracker` section is loaded
- **THEN** loading succeeds exactly as before and the definition reports no
  tracker configuration

#### Scenario: Defaulted threshold
- **WHEN** a `tracker` section declares `type` but no `abort-threshold`
- **THEN** the definition carries threshold 3

### Requirement: Adapter-owned subsection validated at the seam
The `tracker` section SHALL contain a typed subsection named after `type` (e.g.
`github:`), whose schema is declared and validated by the adapter — the loader
delegates subsection validation and never interprets adapter keys. Seam
violations SHALL be located load errors under the existing aggregation contract:
`type` naming an unknown adapter; `type` present without its matching subsection;
a subsection present that does not match `type` (never silently ignored).
<!-- implements FR17 of add-tracker-port -->

#### Scenario: Missing subsection
- **WHEN** the section declares `type: github` with no `github:` subsection
- **THEN** loading fails with a located error naming the missing subsection

#### Scenario: Mismatched subsection
- **WHEN** the section declares `type: github` but contains a `jira:` subsection
- **THEN** loading fails with a located error — the stray subsection is not
  silently ignored

#### Scenario: Adapter errors aggregate with core errors
- **WHEN** the `github` subsection is invalid (bad color hex) and the pipeline
  also has an unrelated core config error
- **THEN** one aggregated result reports both located errors
