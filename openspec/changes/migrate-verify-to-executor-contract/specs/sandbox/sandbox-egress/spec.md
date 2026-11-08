# sandbox/sandbox-egress — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: Default-deny allowlist owned by the operator
The guard SHALL deny every destination not present in the allowlist from factory installation config — the `sandbox` scope of the operator egress block, `factory.egress.sandbox`, parsed by the one egress entry grammar. The allowlist SHALL live outside the box and outside the target repo; a repo may request additions, only the operator grants them.
**Implements**: FR7 of add-sandbox-core; FR17 of migrate-verify-to-executor-contract
**Notes**: The former `factory.sandbox.egress-allowlist` is a removed key (design D16 as amended 2026-11-09); the law's `executors.<name>.egress` grants stay as D10 of define-executor-contract states them.

#### Scenario: Non-allowlisted host is denied
- **WHEN** a process requests a host absent from the allowlist via the guard
- **THEN** the guard refuses the connection and records a structured denial event

#### Scenario: The sandbox scope is read by the one grammar
- **WHEN** `factory.egress.sandbox` lists `*.example.com`
- **THEN** the guard admits `api.example.com` and denies `example.com`, exactly as the in-process guard reads the same entry
