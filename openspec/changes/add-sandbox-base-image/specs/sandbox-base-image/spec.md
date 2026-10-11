## ADDED Requirements

### Requirement: The base image carries the factory's image contract
The published base image SHALL be built on Ubuntu 24.04 and provide: `git`, `curl` and CA certificates on `PATH`; a user `gnome` with uid 1000 owning `/gnomish/work` and `/gnomish/scratch`; `/etc/gnomish` (Maven settings, the CA drop folder) and the agent-policy folder root-owned and not writable by `gnome`; the proxy environment pointing at the guard's alias and port for curl-class tools (`HTTP_PROXY`/`HTTPS_PROXY` in both spellings), JVM tools (`JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`) and Maven (`MAVEN_ARGS` naming the root-owned settings); and `USER gnome` as its final user. It SHALL contain no agent CLI, no project toolchain and no credential.
<!-- implements FR1, NFR-S1, NFR-S2, NFR-P1 of add-sandbox-base-image -->

#### Scenario: An operator image inherits the contract
- **WHEN** an operator builds `FROM` the base and adds only a JDK and an agent CLI
- **THEN** the resulting image runs as `gnome` (uid 1000), `/etc/gnomish` is not writable by that user, and `HTTP_PROXY` names the guard alias and port

#### Scenario: No agent CLI in the published image
- **WHEN** the published base image is inspected
- **THEN** no agent CLI binary or package is present

### Requirement: Helper commands for trust and verification
The base image SHALL provide `gnomish-trust-ca`, which imports every certificate in the CA drop folder into the system trust store and, when `keytool` is on `PATH`, into the JVM trust store, succeeding with no change when the folder is empty; and `gnomish-verify-image`, which checks the filesystem half of the contract — required tools present, `/gnomish/**` owned and writable by `gnome`, `/etc/gnomish` and the agent-policy folder root-owned and not writable by `gnome` — and fails listing every violation with the path and the expected state. The runtime half (the user a process runs as, the egress route) stays with the factory's box self-check.
<!-- implements FR2, FR3, NFR-O1 of add-sandbox-base-image -->

#### Scenario: CA reaches the JVM after the JDK is grafted
- **WHEN** an operator image drops a certificate into the CA folder, grafts a JDK, then runs `gnomish-trust-ca`
- **THEN** the certificate is present in both the system trust store and the JVM trust store

#### Scenario: A loosened control surface fails the build
- **WHEN** an operator layer makes `/etc/gnomish` writable by `gnome` and then runs `gnomish-verify-image`
- **THEN** the command fails naming `/etc/gnomish` and the expected root-owned, non-writable state

### Requirement: The image states its contract version
The base image SHALL carry the label `io.gnomish.image-contract=<N>` and the OCI labels `org.opencontainers.image.version`, `.source` and `.revision`. The contract version, the guard alias and the guard port SHALL have one source file in the repository, read by the image build and by the factory; neither the Dockerfile nor any Java source SHALL spell those values.
<!-- implements FR4, FR6 of add-sandbox-base-image -->

#### Scenario: One edit changes both sides
- **WHEN** the contract version in the source file is raised from 1 to 2
- **THEN** a newly built base carries `io.gnomish.image-contract=2` and the factory built from the same commit requires 2

### Requirement: The base is published with the release and rebuilt weekly
The release workflow SHALL build the base for `linux/amd64` and `linux/arm64` and push it to `ghcr.io/oinsio/gnomish-sandbox-base` as `X.Y.Z` and `X.Y`, with a provenance attestation and a published vulnerability scan report; it SHALL refuse to overwrite an existing `X.Y.Z`. A weekly scheduled workflow SHALL rebuild the newest release's source and push only its `X.Y` tag, with attestation and scan report. Scan findings SHALL be reported, not block publication.
<!-- implements FR7, FR8, NFR-S3, NFR-C1 of add-sandbox-base-image -->

#### Scenario: Release pushes both tags
- **WHEN** tag `v0.2.0` is released
- **THEN** `gnomish-sandbox-base:0.2.0` and `:0.2` exist for both architectures with an attestation

#### Scenario: Weekly rebuild leaves the release tag alone
- **WHEN** the weekly rebuild runs after `v0.2.0`
- **THEN** `:0.2` points at a new digest and `:0.2.0` keeps its original digest

### Requirement: The reference recipe builds on the base
The reference recipe SHALL build `FROM` the base of the factory version it ships with, add a JDK and Node (grafted from official images or installed), the agent CLI, and end with `gnomish-trust-ca` followed by `gnomish-verify-image`. It SHALL NOT restate any contract item the base provides.
<!-- implements FR9, UX1 of add-sandbox-base-image -->

#### Scenario: Recipe builds and verifies
- **WHEN** the recipe is built with default arguments
- **THEN** the build succeeds, `gnomish-verify-image` passes, and the image carries the base's contract label
