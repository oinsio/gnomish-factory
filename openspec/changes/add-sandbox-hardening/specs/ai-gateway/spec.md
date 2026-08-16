# ai-gateway

## ADDED Requirements

### Requirement: Real provider keys live only at the gateway
The box SHALL reach AI providers only through the factory-owned local
gateway; real provider keys SHALL exist only in gateway configuration,
supplied via the `SecretsProvider` port. When the gateway is enabled,
direct AI-provider hosts SHALL be absent from the box allowlist.
<!-- implements FR1 of add-sandbox-hardening -->

#### Scenario: Foreign-account exfiltration path is gone
- **WHEN** a process inside the box attempts to reach the AI provider host directly with a stolen foreign key
- **THEN** the guard denies the connection because the provider host is not allowlisted; only the gateway is reachable

#### Scenario: Provider key never enters the box
- **WHEN** an environment is created with the gateway enabled
- **THEN** the box environment contains only the virtual key (or sentinel), and no real provider credential

### Requirement: Per-segment virtual keys with budget, expiry, and model restriction
Before a stage-segment environment starts, the factory SHALL issue a
virtual key restricted to the segment's stage-declared models, with an
expiry and a budget ceiling equal to the task budget remaining at
issuance. The key SHALL be revoked at segment end, task completion, and
escalation.
<!-- implements FR2 of add-sandbox-hardening -->

#### Scenario: Key matches the segment
- **WHEN** a segment whose stages declare model X starts
- **THEN** the issued key permits model X only, and a request for another model is refused by the gateway

#### Scenario: Segment switch rotates the credential
- **WHEN** a task crosses a segment boundary
- **THEN** the previous key is revoked and a fresh key is issued for the new environment

### Requirement: Budget exhaustion is a visible budget failure
When a virtual key's budget is exhausted or the key expires mid-task,
the factory SHALL surface a distinct budget failure stating spent and
ceiling amounts; it SHALL NOT burn a stage attempt as a quality failure
and SHALL NOT present the condition as a generic provider error.
<!-- implements FR3 of add-sandbox-hardening -->

#### Scenario: Budget ceiling stops the task legibly
- **WHEN** the gnome's requests exhaust the key budget during a round
- **THEN** the task escalates with "budget exceeded: spent X of Y" and the stage attempt counter is unchanged

### Requirement: Rate limit per key
The gateway SHALL enforce a request rate limit per virtual key; the
budget ceiling doubles as the volume cap.
<!-- implements FR4 of add-sandbox-hardening -->

#### Scenario: Request storm is throttled
- **WHEN** in-box processes exceed the configured requests-per-minute on one key
- **THEN** excess requests are refused by the gateway without affecting other tasks' keys

### Requirement: Server-side tool policy
The gateway SHALL remove provider server-side tools (web search, URL
fetch) from requests unless the stage `Mechanism` explicitly allows
them; each removal SHALL be recorded and attached to the task report.
<!-- implements FR5 of add-sandbox-hardening -->

#### Scenario: Provider-side fetch cannot bypass the guard
- **WHEN** a request declares a server-side web tool on a stage without the allowance
- **THEN** the request reaches the provider with the tool absent and the removal appears as a finding

#### Scenario: Explicit allowance passes through
- **WHEN** a stage `Mechanism` allows server-side web tools
- **THEN** the declared tools reach the provider unchanged

### Requirement: Gateway ledger is the authoritative cost record
Per-task spend SHALL be read from the gateway ledger and attached to
the task report; spend anomalies relative to a stage-typical baseline
SHALL be flagged as findings.
<!-- implements FR6, NFR-O1 of add-sandbox-hardening -->

#### Scenario: Report shows ledger spend
- **WHEN** a task completes
- **THEN** the task report carries the gateway-measured spend per segment, reconciling with the task budget

#### Scenario: Anomalous spend is flagged
- **WHEN** a segment spends ≥ the configured multiple of the stage-typical volume
- **THEN** the task report contains a spend-anomaly finding

### Requirement: Per-stage provider and protocol translation
When a stage declares a model of a different provider than the agent
CLI's native protocol, the gateway SHALL translate the wire protocol;
the agent CLI configuration SHALL NOT change beyond the existing
base-url/auth-token seam.
<!-- implements FR7 of add-sandbox-hardening -->

#### Scenario: Review stage on another provider
- **WHEN** a review stage declares a non-Anthropic model and the gnome CLI speaks the Anthropic protocol
- **THEN** requests are translated by the gateway and answered by the declared model, with the key restricted to it

### Requirement: Gateway unavailability is fail-closed
Key issuance failure or gateway unavailability SHALL be an
infrastructure failure: the task or round does not start or is retried
per existing policy, no stage attempt is burned, and execution never
proceeds with a real provider key as a fallback.
<!-- implements NFR-R1 of add-sandbox-hardening -->

#### Scenario: Gateway down means no weaker fallback
- **WHEN** the gateway is unreachable at segment start
- **THEN** the segment does not start, the failure is infrastructure-class, and no environment receives a real provider credential
