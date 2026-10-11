# pipeline-config — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Check tokens map to executor names
The loader SHALL map `type: builtin` to executor `files-exist`, `type: command` to `shell`, and
`type: external` to the executor named by `provider` (default `github-actions`), and SHALL
accept `executor: <name>` directly; the named executor SHALL declare role `verdict` or the
check is a located error.
<!-- implements FR1, UX2 of migrate-verify-to-executor-contract -->

#### Scenario: Default external provider maps
- **WHEN** an `external` check omits `provider`
- **THEN** the model names executor `github-actions`

#### Scenario: Explicit executor form
- **WHEN** a check declares `executor: sonarqube` and the law declares `executors.sonarqube`
- **THEN** validation passes and the check's settings validate against that executor's schema

### Requirement: SCM status checks declare expected contexts and a threshold
An `scm-status` check SHALL declare a non-empty `expected` list of context names; any verdict
check MAY declare `threshold` (`error` default, `warning`, `note`) used by the engine's verdict
derivation. A missing `expected` list on `scm-status` SHALL be a located error.
<!-- implements FR4, FR7 of migrate-verify-to-executor-contract -->

#### Scenario: Missing expected list
- **WHEN** an `scm-status` check declares no `expected`
- **THEN** validation reports a located error naming the check

### Requirement: Credentialed checks must run in a fresh box
A check whose executor describe declares credentials SHALL be a located error when it declares
`verify-in: same-box`; the default for such checks is `fresh-box`.
<!-- implements NFR-S1 of migrate-verify-to-executor-contract -->

#### Scenario: Same-box with a secret is refused
- **WHEN** a check names an executor declaring `SONAR_TOKEN` and `verify-in: same-box`
- **THEN** validation reports a located error naming the check and the credential
