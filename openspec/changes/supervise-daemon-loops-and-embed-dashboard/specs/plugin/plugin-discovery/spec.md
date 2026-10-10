# Spec Delta

## Purpose

Gives every `ServiceLoader`-built provider factory one door for the collaborators
the host supplies: a typed context object the factory's single `create` method
receives. The host grows the context by adding an accessor; a plugin cannot
override "the wrong overload" and quietly build a collaborator of its own.

## MODIFIED Requirements

### Requirement: SPI factories construct with no args and receive dependencies as method arguments
Every discovered SPI factory SHALL be instantiable by `ServiceLoader` through a
public no-argument constructor. Every runtime dependency the host supplies —
the secrets seam, the resolved configuration, the instance identity, the claim
epochs, the run context and the time equipment — SHALL reach the factory
through one context object passed to its single `create` method, never
captured in its constructor and never split across overloads. A later
host-provided collaborator SHALL be added as an accessor on the context, not
as a new `create` signature.
<!-- implements FR2 of add-plugin-architecture -->
<!-- implements FR23 of supervise-daemon-loops-and-embed-dashboard -->

#### Scenario: Factory is created before its dependencies exist
- **WHEN** `ServiceLoader` instantiates a discovered factory
- **THEN** it calls the public no-arg constructor and the factory holds no
  injected collaborator
- **AND** a later `create(context)` call receives every host-provided
  collaborator through the context

#### Scenario: One create method per factory
- **WHEN** a provider factory interface (tracker adapter, check client) is
  inspected
- **THEN** it declares exactly one abstract `create` method, taking the
  factory's context type, and no default overload that delegates to another

#### Scenario: The plugin's time comes from the host
- **WHEN** a provider factory builds an adapter that stamps or measures time
- **THEN** the adapter's instant source and sleeper are the ones the context
  carries, and a context built on a virtual time source makes every stamp the
  adapter writes read the virtual instant

#### Scenario: A new host collaborator extends the context
- **WHEN** the host needs to hand plugins one more collaborator
- **THEN** it is added as an accessor on the context type, and every existing
  implementor keeps compiling against the unchanged `create` signature

#### Scenario: The reshape is a recorded break
- **WHEN** the overloaded `create` forms are removed in favour of the context
- **THEN** the plugin api takes a MINOR version bump with its compatibility
  baseline regenerated in the same change, the build script names the break,
  and every in-repo implementor (GitHub, in-memory, the sample plugin) is
  moved in that change
