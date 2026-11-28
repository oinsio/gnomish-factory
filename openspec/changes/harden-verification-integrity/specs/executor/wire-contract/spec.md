# executor/wire-contract — delta for harden-verification-integrity

## ADDED Requirements

### Requirement: A verdict request names the protected paths the attempt changed
A `verdict` request SHALL carry the protected paths the attempt changed as the optional role
input `protectedChanges`, one entry per path with the glob that protected it and the change
kind. An absent field SHALL read as an empty list, so an executor built before the field reads
the request unchanged.
**Implements**: FR4, NFR-O1 of harden-verification-integrity

#### Scenario: The request carries the protected changes
- **WHEN** an attempt edits a protected file and a verdict request is built
- **THEN** its `roleInputs.protectedChanges` names the path, the glob and the change kind

#### Scenario: An absent protected-change list reads as empty
- **WHEN** a verdict request without `protectedChanges` is read
- **THEN** it reads without error and its protected-change list is empty
