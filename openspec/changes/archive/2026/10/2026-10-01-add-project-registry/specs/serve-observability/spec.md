## MODIFIED Requirements

### Requirement: Files live in a per-instance-name directory stable across restarts
Observability files SHALL live in `GNOMISH_HOME/projects/<name>/serve/<instance>/` —
the snapshot at `snapshot.json`, ledgers under their daily names — keyed
by the registered project and the configured instance name
(`factory.instance-name`, default `default`). The full instance id (with
per-process suffix) SHALL appear inside the data, never in the path, and SHALL
begin with the project name so a tracker comment names the project. Two daemons
sharing one project and one configured instance name on a host is a documented
misconfiguration; no locking.
<!-- implements FR9 of add-serve-observability -->
<!-- implements FR10 of add-project-registry -->

#### Scenario: Restart keeps the paths
- **WHEN** the daemon restarts (new instance-id suffix)
- **THEN** snapshot and ledger paths are unchanged and records carry the new
  full id

#### Scenario: Two projects on one host keep separate state
- **WHEN** daemons for projects `widgets` and `gateway` run on one host with the default instance name
- **THEN** their snapshots live in `projects/widgets/serve/default/` and `projects/gateway/serve/default/`, and neither overwrites the other
