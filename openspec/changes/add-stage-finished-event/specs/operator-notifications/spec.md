## Purpose

Best-effort operator notification of run milestones: a webhook notifier observes the engine's
event stream and posts a message on stage boundaries, configured entirely in the factory's own
operator-owned config, structurally unable to affect the run it reports on.

## ADDED Requirements

### Requirement: Webhook notifier observes the engine event stream
The factory SHALL provide a webhook notifier as an engine event listener, registered only when its configuration is present; absent configuration SHALL wire nothing and change no behavior. For each StagePassed event it SHALL POST one JSON payload identifying the task, the boundary kind, the passed stage, and the advanced-to position; for each TaskFinished event it SHALL POST one JSON payload identifying the task, the boundary kind, and the outcome classification. The payload SHALL be self-sufficient: readable in a raw webhook inspector without consulting the factory.
<!-- implements FR3, FR4 of add-stage-finished-event -->
<!-- implements UX3 of add-stage-finished-event -->

#### Scenario: Stage pass produces one delivery
- **WHEN** a stage passes while the notifier is configured
- **THEN** exactly one POST is sent, whose JSON body names the task id, the boundary kind, the passed stage, and the advanced-to position

#### Scenario: Run end produces one delivery
- **WHEN** a run reaches its terminal outcome while the notifier is configured
- **THEN** exactly one POST is sent, whose JSON body names the task id, the boundary kind, and the outcome classification

#### Scenario: Unconfigured notifier is absent
- **WHEN** no notification configuration is set
- **THEN** no notifier is registered and no notification-related behavior exists in the run

### Requirement: Notification configuration is operator-owned
The notification configuration — the webhook URL and an optional delivery timeout — SHALL live in the factory's own configuration under `factory.notify.webhook`, and SHALL NOT be readable from the target project repository's `.gnomish/` directory in any form: the pipeline must not know about the operator's messenger. A malformed or non-https URL SHALL fail at startup/assembly with a message naming the property, not at the first delivery.
<!-- implements FR5 of add-stage-finished-event -->
<!-- implements UX1, UX2 of add-stage-finished-event -->

#### Scenario: Pipeline config cannot configure notifications
- **WHEN** a target project's `.gnomish/` content declares any notification setting
- **THEN** it has no effect on whether or where notifications are sent

#### Scenario: Invalid URL fails fast
- **WHEN** the configured webhook URL is malformed or uses a scheme other than https
- **THEN** the factory refuses it at startup/assembly, naming `factory.notify.webhook` in the error

### Requirement: Notification delivery is best-effort and isolated from the run
A notification delivery failure — connection error, non-2xx response, or timeout — SHALL be logged at WARN and swallowed: it SHALL never alter a run's outcome, burn an attempt, delay persistence, or block the engine's critical path. Delivery is one attempt per event, at-most-once per boundary; there is no retry queue. Every delivery outcome (sent, or failed with its reason) SHALL be logged with the task id and boundary. The listener SHALL return promptly, running the HTTP exchange off the engine's critical path under a bounded timeout.
<!-- implements NFR-R1, NFR-R2, NFR-P1, NFR-O1 of add-stage-finished-event -->

#### Scenario: Broken endpoint never touches the run
- **WHEN** the webhook endpoint answers every delivery with an error or a timeout
- **THEN** every run produces the same outcome and persisted state as with no notifier configured, and each failed delivery is a WARN log line with the task id, boundary, and reason

#### Scenario: Slow endpoint does not slow the engine
- **WHEN** the webhook endpoint stalls a delivery to its timeout
- **THEN** the engine's event emission and next round proceed without waiting for the delivery

### Requirement: Notification egress obeys the factory egress rules
Notification deliveries SHALL follow the factory-egress-allowlist rules for factory-side outbound HTTP: https only; link-local, cloud-metadata, and RFC1918 address classes refused unless the literal address is explicitly permitted; redirect targets re-checked against the same rules; response size and total request time bounded. The destination URL SHALL come from operator configuration only — no value controlled by the target repository or by run content is ever interpolated into it.
<!-- implements NFR-S1 of add-stage-finished-event -->

#### Scenario: Webhook cannot reach an internal address
- **WHEN** the configured webhook host resolves to a link-local, cloud-metadata, or RFC1918 address that is not explicitly permitted
- **THEN** the delivery is refused before connecting and logged with the blocked address class
