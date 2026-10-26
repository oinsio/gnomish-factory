## MODIFIED Requirements

### Requirement: Idempotent label provisioning as startup smoke test
At startup the adapter SHALL create missing configured labels with their
configured colors and an operator-hint description, idempotently. Color SHALL
apply only at creation — existing labels are never recolored. Provisioning
failure (e.g. no write access to the configured repo, as after a fork with a
stale binding) SHALL fail the run at startup with an error naming the repo and
the likely cause, never mid-task. Provisioning SHALL be built on a non-refusing
assessment that makes the label listing alone and classifies its status; the
adapter's read-only probe SHALL be that assessment.
**Implements**: FR5 of add-tracker-port; NFR-R4 of add-tracker-port; UX1 of add-tracker-port; FR3 of add-doctor-command
**Notes**: Design D7 of add-doctor-command: the probe is the first request startup makes, so `doctor --online` and startup classify one answer with one code.

#### Scenario: Second start changes nothing
- **WHEN** the adapter starts twice against the same repo
- **THEN** the second start issues no label mutations

#### Scenario: Operator recolored a label
- **WHEN** an existing label's color differs from the configured color
- **THEN** provisioning leaves the label untouched

#### Scenario: Fork with stale binding dies at startup
- **WHEN** the token cannot write to the configured repo
- **THEN** startup fails with an error naming the repo, before any task is claimed

#### Scenario: Read probe lists and writes nothing
- **WHEN** the adapter's read-only probe runs against a repo that lacks the configured labels
- **THEN** it makes one label listing request and no label creation, and reports the repo
  readable with the labels still missing
- **AND** a refused listing yields the cause provisioning would name for the same status
