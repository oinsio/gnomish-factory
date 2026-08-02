# pipeline-config — delta

## ADDED Requirements

### Requirement: WIP-limit key in the tracker section
The `tracker` section of `.gnomish/config.yaml` SHALL gain the core key
`wip-limit` (integer ≥ 1, default 10) — a protocol constant shared by all
instances, beside `abort-threshold` and the heartbeat keys, read only from
the factory's own clone (a gnome must not be able to raise the project's WIP
limit). Validation failures SHALL be located load errors under the existing
aggregation contract.
<!-- implements FR6 of add-factory-serve -->
<!-- implements NFR-S3 of add-factory-serve -->

#### Scenario: Default applies

- **WHEN** a `tracker` section declares `type` but no `wip-limit`
- **THEN** the definition carries WIP limit 10

#### Scenario: Zero limit is a load error

- **WHEN** the section declares `wip-limit: 0`
- **THEN** loading fails with a located error naming the minimum of 1
