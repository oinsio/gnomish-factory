# sandbox-provisioning (delta)

## ADDED Requirements

### Requirement: Provisioning resolves through the depot
With the depot enabled, baked build configs SHALL resolve to the depot
through the change-A registry parameters, and provisioning downloads —
including `.gnomish/setup.sh` execution — SHALL flow through the depot
under the same cooldown and block policy as task-phase resolution. No
registry credential SHALL be present in any provisioning phase; the
provisioning network model is identical to the task's (gateway +
depot). <!-- implements FR6, FR8 of add-artifact-depot -->

#### Scenario: setup.sh sees the same world as the gnome
- **WHEN** a provisioning run installs toolchain dependencies via setup.sh
- **THEN** every download resolves through the depot under serve-time policy, and no wider network access or registry secret exists in the provisioning environment

#### Scenario: Enforcement does not depend on the baked configs
- **WHEN** a setup script overrides the baked registry configuration with direct upstream URLs
- **THEN** those requests die at the guard as recorded denials and provisioning fails visibly rather than bypassing policy
