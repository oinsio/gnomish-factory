## MODIFIED Requirements

### Requirement: Env/file adapter is the zero-infrastructure default
An adapter resolving secrets from environment variables and local
files SHALL be the default implementation, requiring no additional
services; it SHALL be the sole adapter in this change (Vault-class and
OIDC arrive with later changes). It SHALL resolve a secret named `N` from
the first of: the file `N` in the resolved project's secrets folder
(`GNOMISH_HOME/projects/<name>/secrets/N`), the file `N` in the host secrets
folder (`GNOMISH_HOME/secrets/N`), the file named by the variable `N_FILE`,
and the variable `N`. A file's whole content, trimmed of surrounding
whitespace, is the value. A secret file readable or writable by group or
others SHALL be refused with an error naming the file and the `chmod` that
fixes it, never read.
<!-- implements FR18 of add-sandbox-core -->
<!-- implements FR8, NFR-S2 of add-project-registry -->

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
