## MODIFIED Requirements

Layered on `Image carries the egress plumbing` as modified by `tighten-spec-contract` (sequenced
before this change): that delta gives the block its `**Implements**:` line and changes nothing
else; this text moves the plumbing from the reference recipe into the base image, names the
Maven settings and the CA drop folder, and adds the scenarios "Plumbing is inherited, not copied"
and "The reference recipe inherits the plumbing"; both must survive the later sync.

### Requirement: Image carries the egress plumbing
The sandbox base image SHALL bake in everything egress needs at build time: the CA drop folder and `gnomish-trust-ca` (seam for later TLS interception), proxy settings for tools that ignore proxy env vars (JVM: `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`; Maven: a root-owned `settings.xml` selected by `MAVEN_ARGS`), and registry endpoints as build parameters rather than hardcoded values.
**Implements**: FR7, UX4 of add-sandbox-core; FR1, FR9 of add-sandbox-base-image

#### Scenario: Gradle build flows through the guard
- **WHEN** a Gradle build inside the box resolves dependencies
- **THEN** the traffic goes through the guard to allowlisted registries despite JVM ignoring proxy environment variables

#### Scenario: Plumbing is inherited, not copied
- **WHEN** an operator Dockerfile builds `FROM` the base and sets no proxy variable of its own
- **THEN** curl, Gradle and Maven inside the box all route through the guard

#### Scenario: The reference recipe inherits the plumbing
- **WHEN** the reference recipe is built
- **THEN** it builds `FROM` the base and restates none of the egress plumbing, which it inherits like any operator image
