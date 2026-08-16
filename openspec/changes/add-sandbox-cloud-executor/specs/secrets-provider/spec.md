# secrets-provider

## ADDED Requirements

### Requirement: Vault-class adapter
The `SecretsProvider` port SHALL gain a Vault-class adapter
(OpenBao) alongside the env/file adapter; consumers of the port SHALL
be unaffected by the adapter choice.
<!-- implements FR9 of add-sandbox-cloud-executor -->

#### Scenario: Adapter swap is invisible to consumers
- **WHEN** the operator switches factory secrets from the env/file adapter to the Vault-class adapter
- **THEN** every consumer (tracker token, gateway master key) resolves secrets unchanged

### Requirement: OIDC bootstrap for remote executors, no static cloud secrets
Remote execution infrastructure SHALL bootstrap credentials via OIDC
federation: workloads authenticate with their platform identity and
receive short-lived credentials. No static factory secret SHALL be
provisioned into cloud resources; task pods receive only the
already-permitted per-task values (virtual key or sentinel).
<!-- implements FR9, NFR-S1 of add-sandbox-cloud-executor -->

#### Scenario: Nothing durable to steal in the cluster
- **WHEN** all cluster resources of a task and the factory's own deployment are inspected
- **THEN** no long-lived secret value is present; any credential found is short-lived and expires without rotation work

#### Scenario: Fallback stays available
- **WHEN** an operator runs without OIDC-capable infrastructure
- **THEN** the env/file adapter remains fully supported, with the static-secret trade-off stated in docs
