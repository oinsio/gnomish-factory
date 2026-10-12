# sandbox/sandbox-egress (delta)

## ADDED Requirements

### Requirement: Allowlist collapse to gateway and depot
With the depot enabled, the box allowlist SHALL contain only the
gateway and the depot: direct registry hosts SHALL be removed, and the
allowlist and the baked registry parameters SHALL switch together as
one coordinated configuration change. The git host SHALL NOT be on the
box allowlist. The environment startup self-check SHALL additionally
prove the depot is reachable and a direct registry host is denied.
**Implements**: FR2 of add-artifact-depot
**Notes**: The box never needs git-server access: the clone is seeded from the factory's local clone and harvest runs factory-side.

#### Scenario: A former registry host is denied
- **WHEN** a process inside the box sends a request to a registry host that was on the allowlist before the depot was enabled
- **THEN** the guard denies it and the denial is recorded as a finding

#### Scenario: The git host is not a door
- **WHEN** a process inside the box attempts to reach the project's git host
- **THEN** the guard denies it like any non-allowlisted host — cloning and harvesting never required the box to speak to the git server

#### Scenario: Path-encoded exfiltration has nowhere to go
- **WHEN** a rewritten build config sends requests with data encoded in paths toward an upstream registry host
- **THEN** the guard denies them (host absent from the allowlist) and the attempts appear as findings in the task report

#### Scenario: No half-enabled state survives startup
- **WHEN** the depot is enabled but the box cannot reach it, or a direct registry unexpectedly passes
- **THEN** the self-check fails and the task does not start
