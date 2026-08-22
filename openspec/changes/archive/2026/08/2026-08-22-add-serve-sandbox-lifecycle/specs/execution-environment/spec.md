# execution-environment — delta

## REMOVED Requirements

### Requirement: Orphan cleanup at startup
**Reason**: The name-snapshot contract ("find factory-labelled objects that belong to no live task and remove them", with an implicit single-owner, sweep-before-create ordering) is unsafe under concurrent slots, sibling instances, and sibling projects: liveness was derived from a moving snapshot of names instead of from the objects themselves.
**Migration**: Replaced by the ownership-based policy of the `sandbox-lifecycle` capability — labels stamped at creation, claim-heartbeat liveness, the decision matrix, and the aged reaper. Entry-point scheduling moves to `factory-serve` (periodic tick), `tracker-take` and `manual-run` (startup pass).

## MODIFIED Requirements

### Requirement: Container adapter
The container adapter SHALL create per environment: an internal-only task network, a task volume holding the working copy, and one container from the operator-configured image (`factory.sandbox.image`), honoring the `factory.sandbox.runtime` knob. `exec` SHALL run inside that container; `dispose()` SHALL remove container, volume, and network as one idempotent operation. All factory-created Docker objects SHALL carry, atomically from creation, the ownership labels defined by `sandbox-lifecycle`: factory ownership, the sanitized task environment key, the ownership mode (`tracked` | `manual`), and the project identity.
<!-- implements FR3, NFR-R2 of add-sandbox-core -->
<!-- implements FR2 of add-serve-sandbox-lifecycle -->

#### Scenario: One task, one box
- **WHEN** an environment is materialized for a task in container mode
- **THEN** a labeled container, volume, and internal network exist for it — each carrying key, mode, and project labels from birth — and dispose removes all three

#### Scenario: Dispose is idempotent
- **WHEN** dispose is called twice, or after a partial teardown
- **THEN** the second call succeeds and no task objects remain

## ADDED Requirements

### Requirement: Container realization of lifecycle roles
The container adapter SHALL realize the lifecycle roles of the `sandbox-lifecycle` decision matrix through its object naming: environments are keyed `<key>` (main), `<key>-j` (fresh judge box), `<key>-v` (fresh verification box), and object names carry the adapter's factory name prefixes (box, volume, network, and the per-environment-key egress guard). The one-shot seed-clone helper runs as an anonymous container carrying the factory and task labels but no factory-named identity; a factory-labelled container matching no factory name pattern SHALL classify as the seed-helper role. Sweep classification SHALL derive an object's role and task key from its labels and this naming — a `-j`/`-v`-suffixed environment key resolves to its base task key, so a live task's judge and verification objects classify alive — never by recomputing expected names from a live-task snapshot.
<!-- implements FR4 of add-serve-sandbox-lifecycle -->

#### Scenario: Role recovered from the object itself
- **WHEN** the sweep classifies a listed factory-labelled container it did not create
- **THEN** the object's role (main box, judge, verification, guard, seed helper) and task key are derived from its own labels and name, with no reference to any in-memory key set
