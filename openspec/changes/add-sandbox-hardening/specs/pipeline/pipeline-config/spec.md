# pipeline/pipeline-config (delta)

## ADDED Requirements

### Requirement: Stage model and provider declaration
The stage model SHALL accept an optional model/provider declaration in
the `Mechanism` section. The declaration SHALL be typed into the
immutable `PipelineDefinition`; the factory uses it to restrict the
segment's virtual key and to route through the gateway. Absent a
declaration, the operator-configured default model applies.
**Implements**: FR7 of add-sandbox-hardening

#### Scenario: Stage declares its model
- **WHEN** a stage manifest declares a model in `Mechanism`
- **THEN** the `PipelineDefinition` exposes it typed, and the segment's virtual key is restricted to it

### Requirement: Server-side tool allowance is explicit
The stage model SHALL accept an optional explicit allowance for
provider server-side tools in `Mechanism`; absent the allowance, the
gateway strips such tools. The allowance is a repo-side declaration,
effective only when the operator has enabled the tool-policy layer; it
widens only the model's tool surface, never the sandbox or egress
policy (see the tighten-only carve-out below).
**Implements**: FR5 of add-sandbox-hardening

#### Scenario: Allowance loads into the typed model
- **WHEN** a stage manifest allows server-side web tools
- **THEN** the `PipelineDefinition` exposes the allowance and the gateway passes the declared tools through for that stage

### Requirement: setup.sh is a recognized law surface
`.gnomish/setup.sh` SHALL be a recognized repo surface, read from the
factory's law clone like other `.gnomish/` content. Its absence is
valid (operator base image alone applies). Pipeline validation SHALL
NOT execute it; it runs only in provisioning.
**Implements**: FR12 of add-sandbox-hardening

#### Scenario: Loading stays read-only
- **WHEN** a pipeline with a setup.sh loads
- **THEN** the file is registered for provisioning by content, and nothing executes during loading

## MODIFIED Requirements

Layered on `Repo declarations can only tighten` as modified by `tighten-spec-contract` (sequenced
before this change): that delta gives the block its `**Implements**:` line and changes nothing
else; this text adds the server-side tool allowance carve-out and the scenario "Tool allowance
passes validation as the carved-out widening"; both must survive the later sync.

### Requirement: Repo declarations can only tighten
Validation SHALL reject any repo-side declaration that weakens isolation: requesting host execution, naming a concrete adapter binding, or relaxing freshness/limits. Adapter binding and any weakening SHALL exist only in factory installation config. Violations SHALL surface as located `ConfigError`s. The one carve-out is the server-side tool allowance in `Mechanism`: a valid repo-side declaration although it widens the model's provider-side tool surface.
**Implements**: FR14 of add-sandbox-core; FR5 of add-sandbox-hardening

#### Scenario: Host request from the repo is rejected
- **WHEN** a stage manifest asks for host execution or a named adapter
- **THEN** loading reports a located `ConfigError` and no pipeline runs

#### Scenario: Tightening is accepted
- **WHEN** a stage manifest declares `requires-fresh` on top of the operator's container binding
- **THEN** validation passes and the stricter setting takes effect

#### Scenario: Tool allowance passes validation as the carved-out widening
- **WHEN** a stage manifest declares the server-side tool allowance
- **THEN** validation accepts it, the allowance has effect only under the operator-enabled tool-policy layer, and it alters neither the sandbox boundary nor the egress allowlist
