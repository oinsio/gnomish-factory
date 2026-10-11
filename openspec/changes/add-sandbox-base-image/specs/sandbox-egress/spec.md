## MODIFIED Requirements

### Requirement: Image carries the egress plumbing
The sandbox base image SHALL bake in everything egress needs at build time: the CA drop folder and `gnomish-trust-ca` (seam for later TLS interception), proxy settings for tools that ignore proxy env vars (JVM: `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`; Maven: a root-owned `settings.xml` selected by `MAVEN_ARGS`), and registry endpoints as build parameters rather than hardcoded values. An operator image built `FROM` the base SHALL inherit the plumbing without restating it; the reference recipe SHALL be such an image.
<!-- implements FR7, UX4 of add-sandbox-core -->
<!-- implements FR1, FR9 of add-sandbox-base-image -->

#### Scenario: Gradle build flows through the guard
- **WHEN** a Gradle build inside the box resolves dependencies
- **THEN** the traffic goes through the guard to allowlisted registries despite JVM ignoring proxy environment variables

#### Scenario: Plumbing is inherited, not copied
- **WHEN** an operator Dockerfile builds `FROM` the base and sets no proxy variable of its own
- **THEN** curl, Gradle and Maven inside the box all route through the guard
