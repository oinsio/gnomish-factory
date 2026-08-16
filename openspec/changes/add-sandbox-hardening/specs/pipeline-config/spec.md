# pipeline-config (delta)

## ADDED Requirements

### Requirement: Stage model and provider declaration
The stage model SHALL accept an optional model/provider declaration in
the `Mechanism` section. The declaration SHALL be typed into the
immutable `PipelineDefinition`; the factory uses it to restrict the
segment's virtual key and to route through the gateway. Absent a
declaration, the operator-configured default model applies.
<!-- implements FR7 of add-sandbox-hardening -->

#### Scenario: Stage declares its model
- **WHEN** a stage manifest declares a model in `Mechanism`
- **THEN** the `PipelineDefinition` exposes it typed, and the segment's virtual key is restricted to it

### Requirement: Server-side tool allowance is explicit
The stage model SHALL accept an optional explicit allowance for
provider server-side tools in `Mechanism`; absent the allowance, the
gateway strips such tools. The allowance is a repo-side declaration and
does not require operator action; it widens only the model's tool
surface, never the sandbox or egress policy.
<!-- implements FR5 of add-sandbox-hardening -->

#### Scenario: Allowance loads into the typed model
- **WHEN** a stage manifest allows server-side web tools
- **THEN** the `PipelineDefinition` exposes the allowance and the gateway passes the declared tools through for that stage

### Requirement: setup.sh is a recognized law surface
`.gnomish/setup.sh` SHALL be a recognized repo surface, read from the
factory's law clone like other `.gnomish/` content. Its absence is
valid (operator base image alone applies). Pipeline validation SHALL
NOT execute it; it runs only in provisioning.
<!-- implements FR12 of add-sandbox-hardening -->

#### Scenario: Loading stays read-only
- **WHEN** a pipeline with a setup.sh loads
- **THEN** the file is registered for provisioning by content, and nothing executes during loading
