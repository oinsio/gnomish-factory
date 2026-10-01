## MODIFIED Requirements

### Requirement: Dashboard command renders a single HTML file
`gnomish dashboard` SHALL be a subcommand that renders one HTML file. It
SHALL resolve configuration (tracker, instance name, backoff parameters)
from `--dir <clone>` exactly as `gnomish board` and `gnomish status` do,
through the registered project that clone belongs to.
The default output path SHALL be `dashboard.html` in the instance's
observability directory (`GNOMISH_HOME/projects/<name>/serve/<instance>/`);
`--out` SHALL override it.
<!-- implements FR1 of add-dashboard-page -->
<!-- implements FR10 of add-project-registry -->

#### Scenario: Default output lands beside the observability files
- **WHEN** `gnomish dashboard` runs without `--out` for project `widgets` with the default instance name
- **THEN** the page is written to `GNOMISH_HOME/projects/widgets/serve/default/dashboard.html`

#### Scenario: Explicit output path
- **WHEN** `gnomish dashboard --out=incident.html` runs
- **THEN** the page is written to the given path
