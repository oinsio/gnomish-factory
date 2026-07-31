# manual-run — delta

## MODIFIED Requirements

### Requirement: Command check runner
The command runner SHALL execute the manifest command via `sh -c` with the
workspace as working directory, merging stderr into stdout and retaining a
bounded output tail (~200 lines / 10 KB). The child environment SHALL be the
factory environment minus the credential variables declared by the active
tracker adapter — the same declared-scrub-list the agent launcher applies;
when no tracker is configured, the environment is inherited unchanged.
Exit 0 → Pass; exit 126/127 → CannotVerify (shell convention for
not-executable / not-found); any other non-zero exit → Fail.
<!-- implements FR7 of add-manual-run -->
<!-- implements FR11, NFR-S1 of add-claim-heartbeat -->

#### Scenario: Red check carries feedback
- **WHEN** the command exits 1 without a findings file
- **THEN** the verdict is Fail with one synthetic finding whose details contain the output tail

#### Scenario: Missing binary is infrastructure
- **WHEN** the command exits 127
- **THEN** the verdict is CannotVerify, honoring the engine's classification table

#### Scenario: Tracker credentials never reach a check
- **WHEN** a command check runs while `GNOMISH_GITHUB_TOKEN` is set in the
  factory environment and the GitHub tracker is active
- **THEN** the check's process environment contains no variable declared as a
  credential by the active tracker adapter
