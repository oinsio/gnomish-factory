# sandbox-base-image Specification

## Purpose

The published sandbox base image: a versioned image carrying the factory's image contract (the
`gnome` user, the root-owned control surfaces, the egress plumbing, a git at the factory's floor,
the trust, home-directory and verification helpers) that operator images and the reference recipe build `FROM`,
so no operator copies the contract by hand and a stale image is refused by its contract version.

## Requirements

### Requirement: The base image carries the factory's image contract
The published base image SHALL be built on Ubuntu 24.04 LTS and provide: a user `gnome` with uid 1000 owning `/gnomish/work` and `/gnomish/scratch`; `/etc/gnomish` (Maven settings, the CA drop folder) and the agent-policy folder root-owned and not writable by `gnome`; the proxy environment pointing at the guard's alias and port for curl-class tools, JVM tools and Maven; and `USER gnome` as its final user; and no agent CLI, toolchain or credential.
**Implements**: FR1, NFR-S1, NFR-S2 of add-sandbox-base-image

#### Scenario: An operator image inherits the contract
- **WHEN** an operator builds `FROM` the base and adds only a JDK and an agent CLI
- **THEN** the resulting image runs as `gnome` (uid 1000), `/etc/gnomish` is not writable by that user, and `HTTP_PROXY` names the guard alias and port

#### Scenario: Every tool family finds the proxy
- **WHEN** the published base image's environment is inspected
- **THEN** curl-class tools find the guard's alias and port in `HTTP_PROXY`/`HTTPS_PROXY` in both spellings, JVM tools in `JAVA_TOOL_OPTIONS` and `GRADLE_OPTS`, and Maven in `MAVEN_ARGS` naming the root-owned settings

#### Scenario: No agent CLI in the published image
- **WHEN** the published base image is inspected
- **THEN** no agent CLI binary or package is present

#### Scenario: No toolchain or credential in the published image
- **WHEN** the published base image is inspected
- **THEN** it holds no project toolchain (no JDK, no Node) and no credential

### Requirement: The base image's tools meet the factory's floor
The base image SHALL provide `curl`, CA certificates and a `git` at or above the factory's git floor on `PATH`. The git SHALL be built from the pinned upstream release named by the repository's one git install script, in a build stage the published image does not carry, so the published image holds no compiler or build tool and stays under the size target.
**Implements**: FR1, FR11, NFR-P1 of add-sandbox-base-image
**Notes**: No Ubuntu archive or PPA ships the floor (design D8); the script's pinned version and the floor are kept equal by a spec, so a floor raise is one edit.

#### Scenario: The base's git passes the box's floor check
- **WHEN** a box is materialized on an image built `FROM` the base with no git of its own
- **THEN** the seed-clone floor check passes without the operator installing a git

#### Scenario: The published base stays under the size target
- **WHEN** the published base image is inspected
- **THEN** its size is under 150 MB per architecture and no compiler, header or build tool is present

### Requirement: The gnome's home is root-owned outside declared directories
The base SHALL keep the gnome's home and every entry directly under it root-owned and not writable by `gnome`, its shell startup files inert, except the directories declared with `gnomish-home-dir` (`.claude`, `.cache` in the base), each owned by `gnome`. The helper, run as root, SHALL create each named directory and record it in one root-owned list, against which `gnomish-verify-image` checks the home. The agent policy SHALL allow managed hooks only, which `gnomish-verify-image` checks too.
**Implements**: FR3, FR12, NFR-S2 of add-sandbox-base-image
**Notes**: The content of the declared directories and processes left running persist between rounds of one box; deferred by NG7 and NG8 with their pickup conditions and recorded in threat rows #5 and #39 (design D14).

#### Scenario: The home holds no gnome-writable startup or configuration file
- **WHEN** a process running as `gnome` in a box on the base tries to overwrite `~/.bashrc`, to remove and recreate it, or to create `~/.gitconfig` or `~/.mavenrc`
- **THEN** each attempt fails, and a later process in the same box reads the base's original `~/.bashrc` and finds no `~/.gitconfig` or `~/.mavenrc`

#### Scenario: An operator declares a toolchain directory
- **WHEN** an operator layer runs `gnomish-home-dir .m2` and then `gnomish-verify-image`
- **THEN** `~/.m2` is a directory owned by `gnome`, and the command passes

#### Scenario: An undeclared writable home entry fails the build
- **WHEN** an operator layer makes the gnome's home, a file directly under it, or a directory not declared with `gnomish-home-dir` writable by `gnome`, and then runs `gnomish-verify-image`
- **THEN** the command fails naming each such path and the expected state

#### Scenario: An agent policy without managed-hooks-only fails the build
- **WHEN** an operator layer replaces `/etc/claude-code/managed-settings.json` with a file that does not set `allowManagedHooksOnly` to `true`, and then runs `gnomish-verify-image`
- **THEN** the command fails naming the file, the key and the expected value

### Requirement: Helper commands for trust and verification
The base image SHALL provide `gnomish-trust-ca`, which imports every certificate in the CA drop folder into the system trust store and, when `keytool` is on `PATH`, into the JVM trust store; and `gnomish-verify-image`, which checks the filesystem half of the contract — required tools present, `/gnomish/**` owned and writable by `gnome`, `/etc/gnomish`, the agent-policy folder and the gnome's home (the requirement above) root-owned and not writable by `gnome` — and fails on any violation.
**Implements**: FR2, FR3, NFR-O1 of add-sandbox-base-image
**Notes**: The runtime half of the contract (the user a process runs as, the egress route, the git version) stays with the factory's box self-check and seed-clone floor check.

#### Scenario: CA reaches the JVM after the JDK is grafted
- **WHEN** an operator image drops a certificate into the CA folder, grafts a JDK, then runs `gnomish-trust-ca`
- **THEN** the certificate is present in both the system trust store and the JVM trust store

#### Scenario: An empty CA folder is a no-op
- **WHEN** `gnomish-trust-ca` runs with no certificate in the CA drop folder
- **THEN** it succeeds and changes neither trust store

#### Scenario: Two CA files whose names differ only in letter case are refused
- **WHEN** the CA drop folder holds two files whose names differ only in letter case, and `gnomish-trust-ca` runs with or without `keytool` on `PATH`
- **THEN** it fails before writing anything, naming both files and the fix, because `keytool` folds aliases to lower case and the second file's import would replace the first's

#### Scenario: Every violation is listed
- **WHEN** `gnomish-verify-image` finds more than one violation
- **THEN** it fails listing every violation, each with its path and the expected state

#### Scenario: A loosened control surface fails the build
- **WHEN** an operator layer makes `/etc/gnomish` writable by `gnome` and then runs `gnomish-verify-image`
- **THEN** the command fails naming `/etc/gnomish` and the expected root-owned, non-writable state

### Requirement: The image states its contract version
The base image SHALL carry the label `io.gnomish.image-contract=<N>` and the OCI labels `org.opencontainers.image.version`, `.source` and `.revision`. The contract version, the guard alias and the guard port SHALL have one source file in the repository, read by the image build and by the factory; neither the Dockerfile nor any Java source SHALL spell those values.
**Implements**: FR4, FR6 of add-sandbox-base-image

#### Scenario: One edit changes both sides
- **WHEN** the contract version in the source file is raised from 1 to 2
- **THEN** a newly built base carries `io.gnomish.image-contract=2` and the factory built from the same commit requires 2

### Requirement: The base is published with the release and rebuilt weekly
The release workflow SHALL build the base for `linux/amd64` and `linux/arm64` and push it to `ghcr.io/oinsio/gnomish-sandbox-base` as `X.Y.Z` and, for a final release only, `X.Y`, with a provenance attestation and a published vulnerability scan report; it SHALL refuse to overwrite an existing `X.Y.Z`. A weekly workflow SHALL rebuild the highest-versioned final release's source and push only its `X.Y` tag, attested and scanned alike. Scan findings SHALL be reported, not block publication.
**Implements**: FR7, FR8, NFR-S3, NFR-C1 of add-sandbox-base-image
**Notes**: A pre-release pushes only its full version tag, so `X.Y` never moves to a pre-release (design D6, D11). The scenarios about the registry's state are verified by their workflow runs and listed in the capability's spec-contract exemption file, each naming its workflow, written when this capability first reaches the main specs (design D10); the refusal and the selection are decided by scripts with specs (design D15).

#### Scenario: Release pushes both tags
- **WHEN** tag `v0.2.0` is released
- **THEN** `gnomish-sandbox-base:0.2.0` and `:0.2` exist for both architectures with an attestation

#### Scenario: A pre-release pushes only its full tag
- **WHEN** tag `v0.3.0-rc.1` is released
- **THEN** `gnomish-sandbox-base:0.3.0-rc.1` exists for both architectures with an attestation, and `:0.3` is neither created nor moved

#### Scenario: Weekly rebuild leaves the release tag alone
- **WHEN** the weekly rebuild runs after `v0.2.0`
- **THEN** `:0.2` points at a new digest and `:0.2.0` keeps its original digest

#### Scenario: An existing release tag is not overwritten
- **WHEN** tag `v0.2.0` is released and the registry already holds `gnomish-sandbox-base:0.2.0`
- **THEN** the release's image publication fails naming `0.2.0` and how to attest an existing tag, and nothing is pushed

#### Scenario: An unanswered registry check publishes nothing
- **WHEN** tag `v0.2.0` is released and the registry check for `0.2.0` fails with anything other than the registry's answer that no such manifest exists
- **THEN** the image publication fails naming the tag and the registry's error, and nothing is pushed

#### Scenario: The weekly rebuild takes the highest final release
- **WHEN** the releases are `v0.2.0`, a draft `v0.4.0`, the pre-release `v0.3.0-rc.1`, and `v0.1.5` created after `v0.2.0`
- **THEN** the weekly rebuild builds the source of `v0.2.0`

### Requirement: The reference recipe builds on the base
The reference recipe SHALL build `FROM` the base of the factory version it ships with, add a JDK and Node (grafted from official images or installed), the agent CLI, and end with `gnomish-trust-ca` followed by `gnomish-verify-image`. It SHALL NOT restate any contract item the base provides.
**Implements**: FR9, UX1 of add-sandbox-base-image

#### Scenario: Recipe builds and verifies
- **WHEN** the recipe is built with default arguments
- **THEN** the build succeeds, `gnomish-verify-image` passes, and the image carries the base's contract label
