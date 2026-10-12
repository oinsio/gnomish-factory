# Spec Delta: operator/secrets-provider

## MODIFIED Requirements

### Requirement: SecretsProvider port with pluggable adapters
A `SecretsProvider` port SHALL be the single seam through which the
factory resolves named secrets; consumers SHALL be unaffected by the
adapter choice, and the adapter SHALL be selected in factory
installation config. The port resolves secrets by name; it SHALL NOT
expose enumeration of all secrets.
**Implements**: FR18, NFR-S1 of add-sandbox-core

#### Scenario: Consumers do not know the backend
- **WHEN** a component needs the tracker token or another factory secret
- **THEN** it obtains the value through the port by name, and switching the configured adapter requires no consumer change

### Requirement: Env/file adapter is the zero-infrastructure default
An adapter resolving secrets from environment variables and local
files SHALL be the default implementation, requiring no additional
services. It SHALL resolve a secret named `N` from the first of: the file
`N` in the resolved project's secrets folder
(`GNOMISH_HOME/projects/<name>/secrets/N`), the file `N` in the host secrets
folder (`GNOMISH_HOME/secrets/N`), the file named by the variable `N_FILE`,
and the variable `N`.
**Implements**: FR18 of add-sandbox-core; FR8, NFR-S2 of add-project-registry
**Notes**: Vault-class and OIDC adapters arrive with later changes.

#### Scenario: Works out of the box
- **WHEN** an operator configures secrets as env vars or file paths and starts the factory
- **THEN** all factory secrets resolve through the port with no extra infrastructure

#### Scenario: Project secret wins over host and environment
- **WHEN** `GNOMISH_GITHUB_TOKEN` exists as a file in the project's secrets folder, as a file in the host secrets folder, and as an environment variable
- **THEN** the project file's value is used

#### Scenario: Shared agent token from the host folder
- **WHEN** only the host secrets folder holds `CLAUDE_CODE_OAUTH_TOKEN`
- **THEN** every registered project resolves that value

#### Scenario: Loose permissions are refused
- **WHEN** a secret file in a secrets folder is readable by its group
- **THEN** resolution fails naming the file and `chmod 600 <file>`, and the value is not read

#### Scenario: The env/file adapter is the only adapter
- **WHEN** the factory selects its secrets adapter
- **THEN** the env/file adapter is the only implementation of the port available to select

#### Scenario: A secret file's value is its trimmed content
- **WHEN** a secret is resolved from a file whose content is the value surrounded by whitespace and a trailing newline
- **THEN** the resolved value is the file's whole content with the surrounding whitespace removed

### Requirement: All factory secrets resolve through the port
Every factory-held secret — the tracker token today; gateway master
keys, depot credentials, and others as later changes add them — SHALL
be obtained exclusively through the port; no component SHALL read a
secret directly from process env or files outside the adapter.
**Implements**: FR18, NFR-S1 of add-sandbox-core

#### Scenario: One seam to audit
- **WHEN** the codebase is searched for secret consumption
- **THEN** all reads go through the port, and the set of secret names in use is discoverable from its call sites

### Requirement: Secrets are fail-closed and never leak outward
A secret that cannot be resolved SHALL be a configuration error at
startup or an infrastructure failure at use time — never a silent
empty value. Resolved secret values SHALL never be logged and SHALL
never enter a task environment: the positive env allowlist admits only
the explicitly permitted per-task values (virtual key or sentinel).
**Implements**: FR18, NFR-S1 of add-sandbox-core

#### Scenario: Missing secret refuses loudly
- **WHEN** the configured adapter cannot resolve a required secret name
- **THEN** the factory refuses the affected operation with an error naming the secret, and no default or empty value is used

#### Scenario: Port values stay out of the box
- **WHEN** a task environment is created while the factory holds port-resolved secrets
- **THEN** none of those values appear in the environment's allowlisted env
