## MODIFIED Requirements

Layered on `Git version floor at startup` as modified by `own-git-invocation-policy` (sequenced
before this change): that delta raised the floor to 2.56.0, added `FR10, UX3 of
own-git-invocation-policy` to the `**Implements**:` line and replaced the note with the 2.56.0
reason; this text keeps all three and adds the doctor's non-refusing assessment as the second
note and the scenario "Doctor reports an old git", which must survive the later sync.

### Requirement: Git version floor at startup
The factory SHALL read the installed git version once at startup, before any transfer, and
SHALL refuse to run below 2.56.0 with a report naming the floor, the installed version, and the
reason. A git that cannot report its version SHALL be refused the same way. The check SHALL log
one INFO line with the detected version and the floor when it passes.
**Implements**: FR10, NFR-R2, NFR-O1, UX2 of own-git-transfer-argv; NFR-S2 of tighten-spec-contract; FR10, UX3 of own-git-invocation-policy; FR7 of add-doctor-command
**Notes**: 2.56.0 is the lowest release whose event-level hook switch (`hook.<event>.enabled`) is documented and verified, which the invocation policy needs against hooks defined in the configuration (design D3, D11 of own-git-invocation-policy); it also covers the earlier reasons — local-clone protections (2.45.1), reftable lock retry (2.47), fetch ref updates in one transaction (2.51).
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
