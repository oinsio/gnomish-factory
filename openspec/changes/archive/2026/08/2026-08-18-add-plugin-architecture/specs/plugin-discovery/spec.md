## ADDED Requirements

### Requirement: Providers are discovered through ServiceLoader
The factory SHALL resolve every provider factory (tracker and check) through
Java `ServiceLoader`, and SHALL NOT hold any hardwired `Map.of(...)` provider
registry. The discovery mechanism SHALL be identical for built-in and external
providers — they differ only in packaging (which jar carries the
`META-INF/services` entry), never in how the factory finds or selects them.
<!-- implements FR1 of add-plugin-architecture -->

#### Scenario: A provider on the classpath is discovered without a core edit
- **WHEN** a jar exposing a tracker or check SPI factory via `META-INF/services`
  is present on the classpath
- **THEN** the factory discovers the provider through `ServiceLoader` and its
  discriminator becomes selectable, with no change to any core source file

#### Scenario: No hardwired registry remains
- **WHEN** the codebase is searched for provider registries
- **THEN** no `Map.of(...)` literal maps a discriminator to a provider factory —
  every registry is populated by `ServiceLoader`

### Requirement: SPI factories construct with no args and receive dependencies as method arguments
Every discovered SPI factory SHALL be instantiable by `ServiceLoader` through a
public no-argument constructor. Runtime dependencies — `SecretsProvider` and the
resolved configuration — SHALL be supplied as arguments to the factory's methods,
never captured in its constructor, so discovery never needs to inject
collaborators.
<!-- implements FR2 of add-plugin-architecture -->

#### Scenario: Factory is created before its dependencies exist
- **WHEN** `ServiceLoader` instantiates a discovered factory
- **THEN** it calls the public no-arg constructor and the factory holds no
  injected `SecretsProvider` or config
- **AND** a later `create(...)` call receives the `SecretsProvider` and resolved
  config as method arguments

### Requirement: Registries are per-port and selection is by discriminator, independent per port
Discovery SHALL produce one registry per port, keyed by that port's
discriminator, and provider selection SHALL be resolved independently for each
port. Mixing vendors across ports — for example a github tracker with a gitlab
check provider — SHALL be a supported configuration, and any discovered provider
that no configuration selects SHALL stay dormant (constructed lazily or not
exercised).
<!-- implements FR3 of add-plugin-architecture -->

#### Scenario: Different vendors serve different ports
- **WHEN** the tracker discriminator selects `github` and a check declares
  `provider: gitlab`
- **THEN** the tracker port resolves the github factory and the check port
  resolves the gitlab factory, each from its own registry, with no interference

#### Scenario: Dormant providers are not exercised
- **WHEN** a discovered provider's discriminator is selected by no configuration
- **THEN** the factory neither fails nor performs that provider's work; it simply
  stays available for a future selection

### Requirement: Discovery is deterministic and fails fast
Provider resolution SHALL be deterministic: a discriminator that no discovered
provider serves SHALL fail with a clear error naming the missing provider and the
port, and a discriminator served by two discovered providers SHALL fail with a
clear error naming the conflict. The factory SHALL NOT silently fall back to a
default or arbitrarily pick one of a colliding pair.
<!-- implements FR1 of add-plugin-architecture -->
<!-- implements NFR-R1 of add-plugin-architecture -->

#### Scenario: Unknown provider fails with a named error
- **WHEN** a configuration selects a discriminator that no discovered provider
  serves
- **THEN** resolution fails with an error naming the missing provider and the
  port, not a silent fallback

#### Scenario: Duplicate provider fails with a named error
- **WHEN** two discovered factories claim the same discriminator for one port
- **THEN** resolution fails with an error naming the colliding discriminator and
  both providers

### Requirement: Discovered providers are reported at startup
At startup the factory SHALL report the discovered provider set per port,
including which jar contributed each provider, so an operator can see exactly
what the classpath loaded and a surprise provider is visible before any task
runs.
<!-- implements NFR-O1 of add-plugin-architecture -->
<!-- implements NFR-S3 of add-plugin-architecture -->

#### Scenario: Loaded providers are observable to the operator
- **WHEN** the factory starts
- **THEN** the set of discovered providers per port is reported, each with the
  jar that contributed it

### Requirement: A port is plugin-ready by a four-point criterion
The change SHALL define and apply, independently per port, a four-point
"plugin-ready" criterion: (a) an SPI factory exposing a `type()` / `provider()`
discriminator, (b) a `ServiceLoader`-backed registry, (c) a config subsection
plus a per-provider SPI validator, and (d) discriminator-based selection. The
tracker and check ports SHALL both satisfy all four points after this change.
<!-- implements FR4 of add-plugin-architecture -->

#### Scenario: Tracker and check ports each meet all four points
- **WHEN** the tracker port and the check port are assessed against the criterion
- **THEN** each exposes a discriminator-bearing SPI factory, a `ServiceLoader`
  registry, a config subsection with a per-provider validator, and
  discriminator-based selection
