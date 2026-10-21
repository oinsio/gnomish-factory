# git/git-transfer — delta for own-git-invocation-policy

## MODIFIED Requirements

Layered on `Git version floor at startup` as it stands in the main spec after
`tighten-spec-contract` (archived 2026-10-14), which set the floor to 2.51.0; this text raises the
floor to 2.56.0 and gives the new reason. The scenarios are unchanged.

### Requirement: Git version floor at startup
The factory SHALL read the installed git version once at startup, before any transfer, and
SHALL refuse to run below 2.56.0 with a report naming the floor, the installed version, and the
reason. A git that cannot report its version SHALL be refused the same way. The check SHALL log
one INFO line with the detected version and the floor when it passes.
**Implements**: FR10, NFR-R2, NFR-O1, UX2 of own-git-transfer-argv; NFR-S2 of tighten-spec-contract; FR10, UX3 of own-git-invocation-policy
**Notes**: 2.56.0 is the lowest release whose event-level hook switch (`hook.<event>.enabled`) is documented and verified, which the invocation policy needs against hooks defined in the configuration (design D3, D11 of own-git-invocation-policy); it also covers the earlier reasons — local-clone protections (2.45.1), reftable lock retry (2.47), fetch ref updates in one transaction (2.51).

#### Scenario: Old git is refused before any work
- **WHEN** the installed git reports a version below the floor
- **THEN** the process exits with the precondition report and performs no transfer, no claim,
  and no tracker write

#### Scenario: Passing check is logged once
- **WHEN** the installed git is at or above the floor
- **THEN** one INFO line names the version and the floor, and the check does not run again in
  that process
