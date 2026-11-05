# pipeline/pipeline-config — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Check tokens map to executor names
The loader SHALL map `type: builtin` to executor `files-exist`, `type: command` to `shell`, and
`type: external` to the executor named by `provider` (default `github-actions`), and SHALL
accept `executor: <name>` directly; the named executor SHALL declare role `verdict` or the
check is a located error.
**Implements**: FR1, UX2 of migrate-verify-to-executor-contract

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
**Implements**: FR4, FR7 of migrate-verify-to-executor-contract

#### Scenario: Missing expected list
- **WHEN** an `scm-status` check declares no `expected`
- **THEN** validation reports a located error naming the check

### Requirement: Credentialed checks must run in a fresh box
A check whose executor describe declares credentials SHALL be a located error when it declares
`verify-in: same-box`; the default for such checks is `fresh-box`.
**Implements**: NFR-S1 of migrate-verify-to-executor-contract

#### Scenario: Same-box with a secret is refused
- **WHEN** a check names an executor declaring `SONAR_TOKEN` and `verify-in: same-box`
- **THEN** validation reports a located error naming the check and the credential

## MODIFIED Requirements

### Requirement: Stage model mirrors the stage contract and all verify-check types
Each `StageDefinition` SHALL represent the stage-contract sections (purpose, input, output, control, mechanism/executor, verify, failure/escalation limits, advancement) and SHALL model the verify checks as two typed variants: an executor check, which every `builtin`, `command`, `external` or `executor:` check maps to and which carries its executor name, its token, its `settings` and the engine-common fields, and the `judge` check.
**Implements**: FR2 of load-pipeline-config; FR7 of add-plugin-architecture; FR1 of migrate-verify-to-executor-contract

#### Scenario: All four check types are modeled distinctly
- **WHEN** a stage's `verify` list contains a `builtin`, a `command`, an `external`, and a `judge` check
- **THEN** the model exposes the first three as executor checks naming `files-exist`, `shell` and the `external` check's executor, each keeping the token it was declared with and its type-specific settings (e.g. `command` carries the executable), and the `judge` as its own variant carrying the acceptance-criteria path and vote count
- **AND** the ordered position of each check within the stage is preserved

#### Scenario: External check carries provider and opaque params
- **WHEN** an `external` check declares a `provider` and provider-specific `params`
- **THEN** the model carries the executor name mapped from `provider` and the `params` as the executor's settings alongside the engine-common `interval`, `timeout`, `timeoutClass`, and `pinPaths`
- **AND** no new verify-check sealed variant is added

#### Scenario: Unknown check type is rejected
- **WHEN** a `verify` entry declares a check `type` that is not one of the four known types
- **THEN** validation reports a located error naming the unknown type
