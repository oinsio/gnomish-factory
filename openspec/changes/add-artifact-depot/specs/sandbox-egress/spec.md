# sandbox-egress (delta)

## ADDED Requirements

### Requirement: Allowlist collapse to gateway and depot
With the depot enabled, the box allowlist SHALL contain only the
gateway and the depot: direct registry hosts SHALL be removed, the
allowlist and the baked registry parameters SHALL switch together as
one coordinated configuration change, and requests to former registry
hosts SHALL be denied and recorded as findings. The git host SHALL NOT
be on the box allowlist: the clone is seeded from the factory's local
clone and harvest runs factory-side, so the box never needs git-server
access. The environment startup self-check SHALL additionally prove
the depot is reachable and a direct registry host is denied.
<!-- implements FR2 of add-artifact-depot -->

#### Scenario: The git host is not a door
- **WHEN** a process inside the box attempts to reach the project's git host
- **THEN** the guard denies it like any non-allowlisted host — cloning and harvesting never required the box to speak to the git server

#### Scenario: Path-encoded exfiltration has nowhere to go
- **WHEN** a rewritten build config sends requests with data encoded in paths toward an upstream registry host
- **THEN** the guard denies them (host absent from the allowlist) and the attempts appear as findings in the task report

#### Scenario: No half-enabled state survives startup
- **WHEN** the depot is enabled but the box cannot reach it, or a direct registry unexpectedly passes
- **THEN** the self-check fails and the task does not start
