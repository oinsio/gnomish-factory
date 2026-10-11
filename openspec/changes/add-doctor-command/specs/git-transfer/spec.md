## MODIFIED Requirements

### Requirement: Git version floor at startup
The factory SHALL read the installed git version once at startup, before any transfer, and
SHALL refuse to run below 2.45.1 — the release whose local-clone protections the seed clone
depends on — with a report naming the floor, the installed version, and the reason. A git that
cannot report its version SHALL be refused the same way. The check SHALL log one INFO line with
the detected version and the floor when it passes. `gnomish doctor` is the one exception: it
performs no transfer, and it SHALL report the same assessment as a check result instead of
refusing.
<!-- implements FR10, NFR-R2, NFR-O1, UX2 of own-git-transfer-argv -->
<!-- implements FR7 of add-doctor-command -->

#### Scenario: Old git is refused before any work
- **WHEN** the installed git reports a version below the floor
- **THEN** the process exits with the precondition report and performs no transfer, no claim,
  and no tracker write

#### Scenario: Passing check is logged once
- **WHEN** the installed git is at or above the floor
- **THEN** one INFO line names the version and the floor, and the check does not run again in
  that process

#### Scenario: Doctor reports an old git
- **WHEN** `gnomish doctor` runs with git below the floor
- **THEN** the git check is reported as `fail` with the floor, the installed version and the reason, and the remaining checks still run
