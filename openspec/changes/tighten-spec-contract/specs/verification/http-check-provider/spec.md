# Spec Delta: verification/http-check-provider

## MODIFIED Requirements

### Requirement: A built-in http check provider ships in core
Core SHALL ship a built-in `provider: http` external check, discovered through
the same `ServiceLoader` registry as any other provider. It is the escape-hatch
for third-party CI / quality services reachable over HTTP, symmetric to the
`command` check for local verification — an operator points a stage at a REST
endpoint without writing an adapter.
**Implements**: FR9 of add-plugin-architecture

#### Scenario: http provider resolves like any other
- **WHEN** a stage declares an `external` check with `provider: http`
- **THEN** the engine resolves the built-in http check client from the registry —
  the same resolution path used for github or any third-party provider

### Requirement: The http verdict is a declarative pass-when, with optional pending-when polling
The http check verdict SHALL be declared, not coded: a `pass-when` condition —
HTTP 2xx by default, optionally narrowed by a `json-path` and/or `regex`
extraction compared with `equals` — decides pass versus fail. An optional `pending-when`
condition SHALL mark a response as not-yet-terminal, causing the check to poll
(reusing the `External` poll loop, `interval` / `timeout`) until a terminal
result or the timeout.
**Implements**: FR10 of add-plugin-architecture
**Notes**: The `json-path` dialect is a deliberate subset of JSONPath, not a full engine; anything the subset cannot address is `regex` territory. A check with no `pending-when` is a one-shot probe, a degenerate poll of a single request.

#### Scenario: One-shot probe passes on 2xx
- **WHEN** an http check declares only a `pass-when` of the default 2xx and the
  endpoint returns 200
- **THEN** the check passes after a single request, performing no polling

#### Scenario: pass-when narrows on an extracted value
- **WHEN** an http check's `pass-when` extracts a field (`json-path` and/or
  `regex`)
  and compares it with `equals`
- **THEN** the check passes only when the extracted value equals the declared one,
  and fails with the response captured as findings otherwise

#### Scenario: pending-when polls until terminal
- **WHEN** an http check declares a `pending-when` that matches the response
- **THEN** the check polls at `interval` until the response no longer matches
  `pending-when`, then evaluates `pass-when`
- **AND** reaching `timeout` while still pending classifies per the check's
  timeout class, reusing the `External` timeout semantics

#### Scenario: json-path addresses fields and array indexes
- **WHEN** a `pass-when` declares the `json-path`
  `$.projectStatus.conditions[0].status`
- **THEN** the check compares the value at that field, the dialect being an
  optional `$` root, dot-separated field names, and `[n]` array indexes

### Requirement: The http provider contributes no pin paths
The `http` provider SHALL contribute no adapter pin paths: only the
law-declared `pinPaths` of the check pin it, and when the declaration names
none the pin-check passes vacuously, per verification-hardening's empty-union
rule. There is no repo-side definition file an arbitrary REST endpoint could be
pinned to.
**Implements**: FR15 of add-plugin-architecture

#### Scenario: Only law-declared paths pin an http check
- **WHEN** an `external` check with `provider: http` declares `pinPaths` in the
  stage law
- **THEN** the pin-check guard compares exactly those paths — the provider
  contributes none — and an http check declaring no `pinPaths` passes the pin
  vacuously

### Requirement: http authorization resolves credentials by name at runtime
An http check that needs authorization SHALL name a credential resolved through
`SecretsProvider` at the factory level and applied as a request header at
runtime. The committed manifest SHALL carry only the credential name and
non-secret request shape, never the secret value. Each resolved http check's
credential name SHALL join the declared credential set, so it is scrubbed from
the child environment and barred from the passthrough allowlist like any
provider-declared name.
**Implements**: FR11 of add-plugin-architecture; FR17 of add-plugin-architecture; NFR-S1 of add-plugin-architecture

#### Scenario: Secret is injected at request time, never stored in the manifest
- **WHEN** an http check names an authorization credential
- **THEN** the factory resolves it through `SecretsProvider` and sets the request
  header at runtime
- **AND** the committed manifest contains only the credential name, not its value
- **AND** that credential name joins the child-environment scrub /
  never-allowlist set for the run
