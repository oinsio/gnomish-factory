# operator/secrets-provider

## ADDED Requirements

### Requirement: Vault-class adapter
The `SecretsProvider` port SHALL gain a Vault-class adapter
(OpenBao) alongside the env/file adapter; consumers of the port SHALL
be unaffected by the adapter choice.
**Implements**: FR9 of add-sandbox-cloud-executor

#### Scenario: Adapter swap is invisible to consumers
- **WHEN** the operator switches factory secrets from the env/file adapter to the Vault-class adapter
- **THEN** every consumer (tracker token, gateway master key) resolves secrets unchanged

### Requirement: OIDC bootstrap for remote executors, no static cloud secrets
Remote execution infrastructure SHALL bootstrap credentials via OIDC
federation: workloads authenticate with their platform identity and
receive short-lived credentials. No static factory secret SHALL be
provisioned into cloud resources; task pods receive only the
already-permitted per-task values (virtual key or sentinel).
**Implements**: FR9, NFR-S1 of add-sandbox-cloud-executor

#### Scenario: Nothing durable to steal in the cluster
- **WHEN** all cluster resources of a task and the factory's own deployment are inspected
- **THEN** no long-lived secret value is present; any credential found is short-lived and expires without rotation work

#### Scenario: Fallback stays available
- **WHEN** an operator runs without OIDC-capable infrastructure
- **THEN** the env/file adapter remains fully supported, with the static-secret trade-off stated in docs

## MODIFIED Requirements

Layered on `Env/file adapter is the zero-infrastructure default` as modified by
`tighten-spec-contract` (sequenced before this change): that delta added the scenario "The
env/file adapter is the only adapter" and the note that other adapters arrive later; this change
adds the Vault-class adapter, so this text narrows that scenario to the default selection and
points the note at the two requirements above; the rest of that delta's text must survive.

### Requirement: Env/file adapter is the zero-infrastructure default
An adapter resolving secrets from environment variables and local
files SHALL be the default implementation, requiring no additional
services. It SHALL resolve a secret named `N` from the first of: the file
`N` in the resolved project's secrets folder
(`GNOMISH_HOME/projects/<name>/secrets/N`), the file `N` in the host secrets
folder (`GNOMISH_HOME/secrets/N`), the file named by the variable `N_FILE`,
and the variable `N`.
**Implements**: FR18 of add-sandbox-core; FR8, NFR-S2 of add-project-registry; FR9 of add-sandbox-cloud-executor
**Notes**: The Vault-class adapter and the OIDC bootstrap are stated by their own requirements, `Vault-class adapter` and `OIDC bootstrap for remote executors, no static cloud secrets`.

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
- **WHEN** the factory selects its secrets adapter and the installation config names none
- **THEN** the env/file adapter is the only one it can select; every other adapter, the Vault-class one included, is selected only when the installation config names it

#### Scenario: A secret file's value is its trimmed content
- **WHEN** a secret is resolved from a file whose content is the value surrounded by whitespace and a trailing newline
- **THEN** the resolved value is the file's whole content with the surrounding whitespace removed
