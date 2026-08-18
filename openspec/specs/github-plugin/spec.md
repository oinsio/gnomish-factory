# github-plugin

## Purpose

Extract GitHub — the tracker adapter, the check adapter, and their shared HTTP core — into a discovered plugin jar built over a private core that is not part of `gnomish-plugin-api`. The bundled github jar loads through exactly the same `ServiceLoader` path a third-party plugin would use, with no privileged built-in shortcut, so removing it disables all github providers without a core source change while the in-memory reference tracker stays in core. The capability also states the trust posture for loading provider jars into the privileged factory process and keeps the sandbox port first-party.

## Requirements

### Requirement: GitHub is extracted into a discovered plugin over a private core
GitHub — the tracker adapter, the check adapter, and their shared HTTP core —
SHALL be packaged into a discovered plugin jar built over a private core that is
NOT part of `gnomish-plugin-api`. The plugin SHALL expose only its SPI factories
and validators through `META-INF/services`; its shared HTTP client, rate-limit,
cache, and retry internals SHALL stay private to the jar.
<!-- implements FR12 of add-plugin-architecture -->

#### Scenario: GitHub is discovered like any third-party plugin
- **WHEN** the factory starts with the github plugin jar on the classpath
- **THEN** the github tracker and check providers are discovered through
  `ServiceLoader`, and core holds no compile-time dependency on the github jar's
  internals

#### Scenario: The plugin's private core is not in the public api
- **WHEN** the `gnomish-plugin-api` surface is inspected
- **THEN** it contains no github HTTP-client, rate-limit, cache, or retry type

### Requirement: The built-in load path is identical to the third-party load path
The default distribution SHALL be core plus a bundled github plugin jar, and the
factory SHALL load that bundled github jar through exactly the same discovery path
a third-party plugin would use — there SHALL be no privileged built-in shortcut.
Removing the github jar SHALL disable all github providers with no core source
change while the factory still starts.
<!-- implements FR12 of add-plugin-architecture -->

#### Scenario: Removing the github jar cleanly disables github providers
- **WHEN** the github plugin jar is removed from the distribution
- **THEN** the factory still starts, the github tracker and check providers are
  absent, and no core source needed to change

#### Scenario: inmemory stays in core as the reference adapter
- **WHEN** the github plugin is absent
- **THEN** the in-memory tracker remains available in core as the reference /
  test-double, unaffected by github's extraction

### Requirement: Loading provider jars has a stated trust posture
Because a discovered provider jar executes inside the privileged factory process
with access to credentials, the change SHALL state an explicit trust posture for
loading such jars: only trusted (first-party or operator-vetted) jars go on the
classpath, documented as an operator responsibility, with the loaded provider
set made observable at startup (the plugin-discovery capability). The sandbox /
`TaskExecutionEnvironment` port SHALL remain first-party and SHALL NOT be
pluginized under this change, since a self-declared capability passport from an
untrusted jar would be a trust hole.
<!-- implements NFR-S3 of add-plugin-architecture -->

#### Scenario: The sandbox port is not pluginized
- **WHEN** the discovery mechanism is applied across ports
- **THEN** the tracker and check ports are discovered, and the sandbox port stays
  first-party with no `ServiceLoader` discovery of third-party backends
