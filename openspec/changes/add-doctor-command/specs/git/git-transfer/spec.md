## MODIFIED Requirements

Layered on `Git version floor at startup` as modified by `tighten-spec-contract` (sequenced
before this change): that delta gives the block its `**Implements**:` line and raises the floor
to 2.51.0 (design D13 of tighten-spec-contract, as amended 2026-10-13); this text adds the doctor's non-refusing assessment as the note and the scenario "Doctor
reports an old git", which must survive the later sync.

### Requirement: Git version floor at startup
The factory SHALL read the installed git version once at startup, before any transfer, and
SHALL refuse to run below 2.51.0 with a report naming the floor, the installed version, and the
reason. A git that cannot report its version SHALL be refused the same way. The check SHALL log
one INFO line with the detected version and the floor when it passes.
**Implements**: FR10, NFR-R2, NFR-O1, UX2 of own-git-transfer-argv; NFR-S2 of tighten-spec-contract; FR7 of add-doctor-command
**Notes**: 2.51.0 is the lowest release with the local-clone protections the seed clone depends on (2.45.1), reftable lock retry (2.47) for the factory clone's concurrent ref writers, and fetch ref updates in one transaction (2.51) for the touchpoint fetch (design D13 of tighten-spec-contract).
**Notes**: `gnomish doctor` performs no transfer, so it reports the same assessment as a check result instead of refusing.

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
