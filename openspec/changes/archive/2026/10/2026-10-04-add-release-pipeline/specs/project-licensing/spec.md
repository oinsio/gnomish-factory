## MODIFIED Requirements

### Requirement: Every distributed artifact carries the license and notice
Each distributed artifact — the factory boot jar, the plugin-contract jar, and the distribution archives (`.tar.gz`, `.zip`) — SHALL carry the repository-root `LICENSE` and `NOTICE`: a jar as `META-INF/LICENSE` and `META-INF/NOTICE`, an archive at its top-level folder, byte-identical to the root files in both cases. One build convention SHALL be the only place that names the root files as jar content, and the license CI job and the release workflow SHALL verify the identity by comparing the bytes of every entry with its root file, not by listing entry names.
<!-- implements FR3, G1, G2 of add-project-license -->
<!-- implements NFR-S2 of add-release-pipeline -->

#### Scenario: Boot jar carries the terms
- **WHEN** the factory boot jar is built
- **THEN** it contains `META-INF/LICENSE` and `META-INF/NOTICE`
- **AND** their content equals the root `LICENSE` and `NOTICE`

#### Scenario: Plugin-contract jar carries the terms
- **WHEN** the plugin-contract jar is built
- **THEN** it contains `META-INF/LICENSE` and `META-INF/NOTICE`
- **AND** their content equals the root `LICENSE` and `NOTICE`

#### Scenario: A jar entry drifts from the root file
- **WHEN** a built jar's `META-INF/NOTICE` or `META-INF/LICENSE` differs by one byte from the
  root file
- **THEN** the license CI job fails naming the jar and the entry

#### Scenario: Bundled libraries keep their own notices
- **WHEN** the factory boot jar is inspected
- **THEN** every bundled third-party jar is present unmodified under `BOOT-INF/lib/`
- **AND** the `META-INF/LICENSE` and `META-INF/NOTICE` files those jars ship with are intact
  inside them

#### Scenario: Distribution archive carries the terms
- **WHEN** the release workflow builds the distribution archives
- **THEN** each archive's top-level folder contains `LICENSE` and `NOTICE` equal to the root files, and its `lib/` jar carries them under `META-INF/`

#### Scenario: An archive copy drifts from the root file
- **WHEN** an unpacked archive's top-level `LICENSE` or `NOTICE` differs by one byte from the repository-root file, or is missing
- **THEN** the release workflow fails naming the file and publishes nothing
