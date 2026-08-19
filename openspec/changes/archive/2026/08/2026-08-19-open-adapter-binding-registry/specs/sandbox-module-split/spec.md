## REMOVED Requirements

### Requirement: Sandbox port contract unchanged by the split
**Reason**: Superseded by the re-added requirement below. The original
requirement asserts `AdapterBinding` "remains a closed enum (opening it is
change C, out of scope)" with a scenario pinning the closed enum; change C is
this change, so that assertion — and its "AdapterBinding stays a closed enum"
scenario — is deliberately retired, not accidentally dropped.

## ADDED Requirements

### Requirement: Sandbox port contract preserved; AdapterBinding opened by change C
The `TaskExecutionEnvironment` port contract SHALL be unchanged by the split;
the capability-passport reconciliation semantics (operator binds, repo only
tightens, fail-closed on mismatch) are preserved. `AdapterBinding` is NO LONGER
a sealed type: `open-adapter-binding-registry` (change C) opens it into a
discovered first-party registry, so a backend module contributes its binding
and passport without a core enum edit. The port contract and reconciliation
semantics remain unchanged by that opening.
<!-- implements FR8, FR9 of split-into-modules -->
<!-- modified by FR1 of open-adapter-binding-registry -->

#### Scenario: Existing sandbox specs pass unchanged
- **WHEN** the existing execution-environment specs run against the split modules
- **THEN** they pass with no changes to the spec files

#### Scenario: AdapterBinding is a discovered registry
- **WHEN** `AdapterBinding` is inspected after change C
- **THEN** it is resolved from a discovered first-party registry, not a sealed
  enum of core-defined constants
- **AND** `host` and `container` remain available with their prior passports
