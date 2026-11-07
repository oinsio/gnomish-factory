# plugin/plugin-discovery — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: Providers are discovered through ServiceLoader
The factory SHALL resolve every provider factory of the tracker port through
Java `ServiceLoader`, and SHALL NOT hold any hardwired `Map.of(...)` provider
registry. The discovery mechanism SHALL be identical for built-in and external
providers — they differ only in packaging (which jar carries the
`META-INF/services` entry), never in how the factory finds or selects them.
**Implements**: FR1 of add-plugin-architecture; FR13 of migrate-verify-to-executor-contract
**Notes**: Verdict executors are not providers of this mechanism: a built-in is compiled into and named by core, an external one is a program declared in the law (ADR 0020).

#### Scenario: A provider on the classpath is discovered without a core edit
- **WHEN** a jar exposing a tracker SPI factory via `META-INF/services`
  is present on the classpath
- **THEN** the factory discovers the provider through `ServiceLoader` and its
  discriminator becomes selectable, with no change to any core source file

#### Scenario: No hardwired registry remains
- **WHEN** the codebase is searched for provider registries
- **THEN** no `Map.of(...)` literal maps a discriminator to a provider factory —
  every registry is populated by `ServiceLoader`

### Requirement: A port is plugin-ready by a four-point criterion
The change SHALL define and apply, independently per port, a four-point
"plugin-ready" criterion: (a) an SPI factory exposing a `type()` / `provider()`
discriminator, (b) a `ServiceLoader`-backed registry, (c) a config subsection
plus a per-provider SPI validator, and (d) discriminator-based selection. The
tracker port SHALL satisfy all four points. The check port left the criterion when
checks became verdict executors: a built-in is named by core and an external one
is a law-declared program speaking the executor contract, so no SPI factory,
discovered registry or per-provider validator exists for it.
**Implements**: FR4 of add-plugin-architecture; FR13 of migrate-verify-to-executor-contract

#### Scenario: Tracker and check ports each meet all four points
- **WHEN** the tracker port is assessed against the criterion
- **THEN** it exposes a discriminator-bearing SPI factory, a `ServiceLoader`
  registry, a config subsection with a per-provider validator, and
  discriminator-based selection
- **AND** the check port is assessed as not pluginizable by this criterion, its
  extension point being the executor contract
