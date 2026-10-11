# pipeline-config — delta for harden-verification-integrity

## ADDED Requirements

### Requirement: Protected paths, allows-protected and severity
The law MAY declare a top-level `protected:` list of globs; a stage MAY declare
`allows-protected: true`; a check MAY declare `severity: blocking | advisory` (default
`blocking`). The loader SHALL validate globs lexically and SHALL reject `allows-protected` on a
stage with no `protected:` list in the law as a located error.
<!-- implements FR2, FR6 of harden-verification-integrity -->

#### Scenario: Severity token round-trips
- **WHEN** every severity constant is written to the wire and read back
- **THEN** each reads back as itself and an unknown token is a located error

#### Scenario: allows-protected without protected
- **WHEN** a stage declares `allows-protected: true` and the law has no `protected:`
- **THEN** loading reports a located error naming the stage

### Requirement: Agent executor isolation exemption is explicit
The law MAY list `executors.<name>.allow-repo-policy: <reason>` to exempt an agent executor
whose describe declares no isolation fields; a blank reason SHALL be a located error.
<!-- implements FR5 of harden-verification-integrity -->

#### Scenario: Exemption needs a reason
- **WHEN** `allow-repo-policy` is declared with an empty value
- **THEN** loading reports a located error naming the executor
