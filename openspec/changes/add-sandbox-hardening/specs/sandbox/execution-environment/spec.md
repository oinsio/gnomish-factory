# sandbox/execution-environment (delta)

## MODIFIED Requirements

Layered on `Container adapter` as modified by `tighten-spec-contract` (sequenced before this
change): that delta cut the body to the box's three objects and moved the ownership labels into
scenario "Every factory object is labelled from creation"; this text adds the image resolution
(a valid provisioning snapshot first, the operator-configured image otherwise) and the scenario
"Snapshot is preferred over base image"; both must survive the later sync. The labels of
`add-serve-sandbox-lifecycle`, which the former rebase note waited for, are already in the base
(that change is archived).

### Requirement: Container adapter
The container adapter SHALL create per environment: an internal-only task network, a task volume holding the working copy, and one container from the resolved environment image, honoring the `factory.sandbox.runtime` knob. Image resolution SHALL prefer a valid provisioning snapshot of the project, otherwise the operator-configured image (`factory.sandbox.image`). `exec` SHALL run inside that container; `dispose()` SHALL remove container, volume, and network as one idempotent operation.
**Implements**: FR3, NFR-R2 of add-sandbox-core; FR2 of add-serve-sandbox-lifecycle; FR13 of add-sandbox-hardening

#### Scenario: One task, one box
- **WHEN** an environment is materialized for a task in container mode
- **THEN** a labeled container, volume, and internal network exist for it — each carrying key, mode, and project labels from birth — and dispose removes all three

#### Scenario: Dispose is idempotent
- **WHEN** dispose is called twice, or after a partial teardown
- **THEN** the second call succeeds and no task objects remain

#### Scenario: Every factory object is labelled from creation
- **WHEN** the container adapter creates any Docker object
- **THEN** the object carries, from its creation, the `sandbox-lifecycle` ownership labels: factory ownership, the sanitized task environment key, the ownership mode (`tracked` | `manual`), and the project identity

#### Scenario: Snapshot is preferred over base image
- **WHEN** an environment is materialized for a project with a valid provisioning snapshot (fingerprint match, TTL unexpired)
- **THEN** the container is created from the snapshot image; without one, from the operator-configured image

## ADDED Requirements

### Requirement: Box credentials are virtual or sentinel
With the gateway enabled, the AI credential entering the environment
through the base-url/auth-token seam SHALL be the segment's virtual key
— or a sentinel value when guard header injection is active. A real
provider credential SHALL never be passed into any environment. This is
the environment-side statement of the `secrets-provider` invariant
"Secrets are fail-closed and never leak outward", which already names
the per-task virtual key or sentinel as the only admitted values.
**Implements**: FR1, FR2 of add-sandbox-hardening

#### Scenario: Environment receives only the virtual key
- **WHEN** a segment environment is created with the gateway enabled
- **THEN** the allowlisted env contains the gateway base URL and the segment's virtual key, and no real provider key exists anywhere in the box
