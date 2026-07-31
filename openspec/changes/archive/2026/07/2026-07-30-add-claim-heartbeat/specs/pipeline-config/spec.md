# pipeline-config — delta

## ADDED Requirements

### Requirement: Heartbeat protocol keys in the tracker section
The `tracker` section of `.gnomish/config.yaml` SHALL gain two core keys —
protocol constants shared by all instances, beside `abort-threshold`:
`heartbeat-interval` (duration, default 5 minutes) and
`heartbeat-ttl-multiplier` (integer ≥ 3, default 3; TTL = multiplier ×
interval, so an inconsistent beat/TTL pair is inexpressible). Validation
failures SHALL be located load errors under the existing aggregation
contract.
<!-- implements FR3 of add-claim-heartbeat -->

#### Scenario: Defaults apply
- **WHEN** a `tracker` section declares `type` but neither heartbeat key
- **THEN** the definition carries a 5-minute interval and multiplier 3

#### Scenario: Multiplier below the floor is a load error
- **WHEN** the section declares `heartbeat-ttl-multiplier: 1`
- **THEN** loading fails with a located error naming the minimum of 3

#### Scenario: Heartbeat errors aggregate
- **WHEN** the interval is malformed and the pipeline also has an unrelated
  core config error
- **THEN** one aggregated result reports both located errors
