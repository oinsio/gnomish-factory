## ADDED Requirements

### Requirement: Notification webhook egress obeys the same egress rules
The operator-notification webhook SHALL be governed by the same factory-side egress rules as the http check: only `https`; link-local, cloud-metadata, and RFC1918 address classes blocked unless the operator explicitly permitted that literal address; redirect targets re-checked against the same rules; and redirects, response size, and total request time bounded. The webhook destination is declared directly by operator configuration (`factory.notify.webhook`), so declaring it is itself the operator's egress consent for that host — but the address-class, scheme, redirect, and resource rules still apply to it, and nothing repo-committed can introduce or alter a notification destination.
<!-- implements NFR-S1 of add-stage-finished-event -->

#### Scenario: Notification cannot escape into a blocked class
- **WHEN** the configured webhook host resolves to, or redirects into, a blocked address class not explicitly permitted
- **THEN** the delivery is refused with the blocked address class named, before any request reaches that address

#### Scenario: Repo content cannot steer notification egress
- **WHEN** a target repository's manifest or stage content names any notification destination
- **THEN** it has no effect: the only notification destination is the one in operator configuration
