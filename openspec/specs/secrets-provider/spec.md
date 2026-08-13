# secrets-provider

## Purpose

The `SecretsProvider` port is the single seam through which the factory resolves every secret it holds — the tracker token today, gateway master keys and depot credentials as later changes add them — so consumers never depend on which backend supplies a value. Secrets are looked up by name only, with no enumeration surface; the env/file adapter is the zero-infrastructure default and, in this change, the sole implementation, with Vault-class and OIDC adapters arriving later. Resolution is fail-closed (a missing secret is a startup or use-time error, never a silent empty value) and values never leak outward: they are never logged and never enter a task environment except through the explicitly allowlisted per-task seam.

## Requirements

### Requirement: SecretsProvider port with pluggable adapters
A `SecretsProvider` port SHALL be the single seam through which the
factory resolves named secrets; consumers SHALL be unaffected by the
adapter choice, and the adapter SHALL be selected in factory
installation config. The port resolves secrets by name; it SHALL NOT
expose enumeration of all secrets.
<!-- implements FR18, NFR-S1 of add-sandbox-core -->

#### Scenario: Consumers do not know the backend
- **WHEN** a component needs the tracker token or another factory secret
- **THEN** it obtains the value through the port by name, and switching the configured adapter requires no consumer change

### Requirement: Env/file adapter is the zero-infrastructure default
An adapter resolving secrets from environment variables and local
files SHALL be the default implementation, requiring no additional
services; it SHALL be the sole adapter in this change (Vault-class and
OIDC arrive with later changes).
<!-- implements FR18 of add-sandbox-core -->

#### Scenario: Works out of the box
- **WHEN** an operator configures secrets as env vars or file paths and starts the factory
- **THEN** all factory secrets resolve through the port with no extra infrastructure

### Requirement: All factory secrets resolve through the port
Every factory-held secret — the tracker token today; gateway master
keys, depot credentials, and others as later changes add them — SHALL
be obtained exclusively through the port; no component SHALL read a
secret directly from process env or files outside the adapter.
<!-- implements FR18, NFR-S1 of add-sandbox-core -->

#### Scenario: One seam to audit
- **WHEN** the codebase is searched for secret consumption
- **THEN** all reads go through the port, and the set of secret names in use is discoverable from its call sites

### Requirement: Secrets are fail-closed and never leak outward
A secret that cannot be resolved SHALL be a configuration error at
startup or an infrastructure failure at use time — never a silent
empty value. Resolved secret values SHALL never be logged and SHALL
never enter a task environment: the positive env allowlist admits only
the explicitly permitted per-task values (virtual key or sentinel).
<!-- implements FR18, NFR-S1 of add-sandbox-core -->

#### Scenario: Missing secret refuses loudly
- **WHEN** the configured adapter cannot resolve a required secret name
- **THEN** the factory refuses the affected operation with an error naming the secret, and no default or empty value is used

#### Scenario: Port values stay out of the box
- **WHEN** a task environment is created while the factory holds port-resolved secrets
- **THEN** none of those values appear in the environment's allowlisted env
