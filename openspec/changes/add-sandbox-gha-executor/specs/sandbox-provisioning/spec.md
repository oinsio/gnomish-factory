# sandbox-provisioning (delta)

## ADDED Requirements

### Requirement: GHA-bound stages provision via workflow setup steps
For stages bound to the GHA adapter, toolchain provisioning SHALL be
expressed in the factory-owned workflow template (setup and cache
actions) installed in the default branch; `.gnomish/setup.sh` SHALL NOT
execute on runners, and the snapshot cache SHALL NOT extend to them.
<!-- implements FR10 of add-sandbox-gha-executor -->

#### Scenario: Toolchain lives in the template
- **WHEN** a GHA-bound check stage needs a JDK and Docker
- **THEN** the template's setup steps provide them on the fresh runner, and no factory image or snapshot is involved

#### Scenario: Provisioning law stays operator-owned
- **WHEN** a gnome branch modifies the workflow template or adds setup scripts
- **THEN** provisioning of GHA runs is unaffected — the executed template comes from the default branch only
