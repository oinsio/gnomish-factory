# plugin/github-plugin — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: GitHub is extracted into a discovered plugin over a private core
The GitHub tracker adapter SHALL be packaged into a discovered plugin jar — the
vendor bundle — that exposes only its SPI factory and validator through
`META-INF/services`. The check adapter is no longer a provider of the bundle:
`github-actions` is a built-in verdict executor compiled into the factory (the
`verification/verdict-executors` capability).
**Implements**: FR12 of add-plugin-architecture; FR13, FR14 of migrate-verify-to-executor-contract

#### Scenario: GitHub is discovered like any third-party plugin
- **WHEN** the factory starts with the github plugin jar on the classpath
- **THEN** the github tracker provider is discovered through `ServiceLoader`,
  and core holds no compile-time dependency on the bundle's internals

#### Scenario: The plugin's private core is not in the public api
- **WHEN** the `gnomish-plugin-api` surface is inspected
- **THEN** it contains no github HTTP-client, rate-limit, cache, or retry type;
  those live in the first-party GitHub library, which is neither the api nor
  private to the bundle

### Requirement: The built-in load path is identical to the third-party load path
The default distribution SHALL be core plus a bundled github plugin jar, and the
factory SHALL load that bundled github jar through exactly the same discovery path
a third-party plugin would use — there SHALL be no privileged built-in shortcut.
Removing the github jar SHALL disable the github tracker with no core source
change while the factory still starts.
**Implements**: FR12 of add-plugin-architecture; FR13 of migrate-verify-to-executor-contract

#### Scenario: Removing the github jar cleanly disables github providers
- **WHEN** the github plugin jar is removed from the distribution
- **THEN** the factory still starts, the github tracker provider is absent, and no
  core source needed to change

#### Scenario: inmemory stays in core as the reference adapter
- **WHEN** the github plugin is absent
- **THEN** the in-memory tracker remains available in core as the reference /
  test-double, unaffected by github's extraction

### Requirement: Loading provider jars has a stated trust posture
The change SHALL state an explicit trust posture for loading discovered
provider jars: only trusted (first-party or operator-vetted) jars go on the
classpath, documented as an operator responsibility, with the loaded provider
set made observable at startup (the plugin-discovery capability).
**Implements**: NFR-S3 of add-plugin-architecture; FR13 of migrate-verify-to-executor-contract
**Notes**: A discovered provider jar executes inside the privileged factory process with access to credentials; the sandbox port stays first-party because a self-declared capability passport from an untrusted jar would be a trust hole.

#### Scenario: The sandbox port is not pluginized
- **WHEN** the discovery mechanism is applied across ports
- **THEN** the tracker port is discovered, verdict executors are built-ins or
  programs over the executor contract, and the sandbox port stays first-party with
  no `ServiceLoader` discovery of third-party backends

## ADDED Requirements

### Requirement: One first-party GitHub HTTP core serves the tracker and the built-ins
The GitHub HTTP core (client, credential header, conditional-request cache,
rate-limit classification, retry) SHALL live in one first-party library module
that is NOT part of `gnomish-plugin-api`, consumed by the bundle's tracker and by
the core-side `github-actions` and `scm-status` verdict built-ins alike; no second
GitHub HTTP client SHALL exist.
**Implements**: FR14 of migrate-verify-to-executor-contract

#### Scenario: One GitHub HTTP client serves every GitHub consumer
- **WHEN** the production code is searched for GitHub HTTP client classes
- **THEN** exactly one exists, in the first-party GitHub library, and the bundle's
  tracker and the `github-actions` and `scm-status` built-ins all use it

### Requirement: Core's built-in executors do not depend on the vendor bundle
The `github-actions` and `scm-status` built-ins SHALL be core's own and SHALL stay
available when the github jar is removed. The rule "distribution is not mechanism"
governs the tracker port; a built-in executor is compiled into and named by core
and is not a bundled provider (ADR 0020).
**Implements**: FR13 of migrate-verify-to-executor-contract

#### Scenario: Removing the github jar keeps the core built-ins
- **WHEN** the github plugin jar is removed from the distribution
- **THEN** the executor registry still resolves `github-actions` and `scm-status`
