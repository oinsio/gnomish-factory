# Spec Delta: release/project-licensing

## MODIFIED Requirements

### Requirement: The project is licensed under the Apache License 2.0
The repository root SHALL contain a `LICENSE` file holding the Apache License, Version 2.0
text. The published POM of the plugin contract SHALL declare the same license. The file was
added by the maintainer on 2026-09-26; the license CI job SHALL verify its presence on every
run, and no build task SHALL generate or overwrite it.
**Implements**: FR1, G1 of add-project-license

#### Scenario: License file present
- **WHEN** the license CI job runs on a commit whose root contains `LICENSE`
- **THEN** the presence step passes

#### Scenario: License file missing
- **WHEN** the license CI job runs on a commit whose root lacks `LICENSE`
- **THEN** the job fails naming the missing file
- **AND** no other step of that job hides the failure

### Requirement: A minimal NOTICE records copyright and the dual-license option
The repository root SHALL contain a `NOTICE` file holding: the product name; the line
`Copyright 2026 The Gnomish Factory Authors`; a statement that the project is licensed under
the Apache License 2.0; a statement that the binary distribution bundles third-party
libraries unmodified, each with its own `META-INF` license and notice files; and, for every
bundled dependency offered under a choice of licenses, the option the project takes and the
URL of that dependency's source repository.
**Implements**: FR2, G2, NG4 of add-project-license

#### Scenario: Dual-licensed dependency is recorded
- **WHEN** a reader opens `NOTICE`
- **THEN** it names Logback and Jakarta Annotations as used under the Eclipse Public License
  2.0
- **AND** each entry carries the URL of the component's source repository

#### Scenario: NOTICE stays minimal
- **WHEN** a dependency version is bumped without a license change
- **THEN** `NOTICE` needs no edit

#### Scenario: NOTICE names only the dual-licensed choices
- **WHEN** a reader opens `NOTICE`
- **THEN** it names no bundled dependency that is offered under a single license
- **AND** it reproduces no dependency's license text and lists no version

### Requirement: Every distributed artifact carries the license and notice
Each distributed artifact — the factory boot jar, the plugin-contract jar, and the distribution archives (`.tar.gz`, `.zip`) — SHALL carry the repository-root `LICENSE` and `NOTICE`: a jar as `META-INF/LICENSE` and `META-INF/NOTICE`, an archive at its top-level folder, byte-identical to the root files in both cases.
**Implements**: FR3, G1, G2 of add-project-license; NFR-S2 of add-release-pipeline

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

#### Scenario: One build convention names the terms as jar content
- **WHEN** a module's jars carry `META-INF/LICENSE` and `META-INF/NOTICE`
- **THEN** the root files are named as jar content by the one distribution-terms build
  convention the module applies, and by no other build script

#### Scenario: The identity checks compare bytes, not names
- **WHEN** the license CI job or the release workflow checks a jar entry or an archive copy
- **THEN** it compares the entry's bytes with its root file
- **AND** an entry present under the right name with other content fails the check

### Requirement: The licensing posture is recorded in an ADR
The project SHALL record in `docs/adr/` the license choice, the rule for dual-licensed
dependencies, the exclusion of LGPL from the accepted set with its reason, the position that git, docker and
agent command-line tools are subprocesses the project never distributes, the exclusion of
test-only and build-only dependencies from the gate, and the contributor-agreement decision
with its reversibility.
**Implements**: FR9 of add-project-license

#### Scenario: A reviewer questions an LGPL string in metadata
- **WHEN** a compliance reviewer finds `LGPL-2.1` among Logback's declared licenses
- **THEN** the ADR and `NOTICE` answer that the project takes the EPL-2.0 option
- **AND** the ADR explains why LGPL alone is not accepted

#### Scenario: The ADR states the dual-license rule
- **WHEN** a reader looks up in the ADR how a dependency offered under a choice of licenses is
  judged
- **THEN** the ADR states that the choice is the licensee's, that the dependency passes when one
  option is accepted, and that the project takes the permissive option and records it in
  `NOTICE`

### Requirement: External contributions require a signed contributor agreement
Every pull request from an account that has not signed the project's Contributor License
Agreement SHALL be blocked by an automated check that requests the signature in one comment
with one action.
**Implements**: FR10, G4, NFR-S1, NFR-C1, UX2 of add-project-license

#### Scenario: First pull request from an unsigned account
- **WHEN** an account without a recorded signature opens a pull request
- **THEN** the check fails and posts one comment describing how to sign
- **AND** signing through that comment turns the check green on the same pull request

#### Scenario: Later pull request from a signed account
- **WHEN** an account with a recorded signature opens another pull request
- **THEN** the check passes without asking again

#### Scenario: Maintainer pull request
- **WHEN** the repository owner opens a pull request
- **THEN** the check passes without a signature request

#### Scenario: A signature is durable and needs no new push
- **WHEN** an account signs through the check's comment
- **THEN** the signature is recorded outside the pull request's branch
- **AND** the check turns green on that pull request without a new push, and stays green for
  every later pull request from the same account

#### Scenario: The contributing guide describes the agreement
- **WHEN** a reader opens `CONTRIBUTING.md`
- **THEN** it states that a signed agreement is required, links the agreement text, and
  describes the one-time signing flow

#### Scenario: The check runs no pull-request code and writes little
- **WHEN** the agreement check runs on a pull request
- **THEN** it checks out and executes no code from the pull request
- **AND** its token may write only the signature record, the comment, and the commit status

#### Scenario: The check's job is bounded by thirty minutes
- **WHEN** the agreement check's job hangs
- **THEN** it is cancelled after the 30-minute timeout the `quality-gates` capability requires
  of every CI job

### Requirement: Contributing guide states that pull requests are closed
`CONTRIBUTING.md` SHALL state in its first paragraph that pull requests are not accepted for
now, that the CLA check exists and will apply when they open, and that contributions today
are issues and plugin announcements. It SHALL link the CLA text and the community plugin
registry.
**Implements**: FR13, UX4 of add-project-license

#### Scenario: Would-be contributor reads the guide
- **WHEN** someone opens `CONTRIBUTING.md` intending to prepare a pull request
- **THEN** the first paragraph tells them pull requests are closed and what they can do
  instead

### Requirement: Community plugin registry
The repository SHALL hold `docs/community-plugins.md` with one row per third-party plugin —
name, link, what tracker or check it adapts, maintainer, declared license — and a disclaimer
that plugins are listed as-is and are neither vetted nor endorsed by the project.
**Implements**: FR14, UX4, M5 of add-project-license

#### Scenario: Plugin author announces a plugin
- **WHEN** a plugin author opens an issue from the plugin-announcement form with all fields
  filled
- **THEN** the maintainer can add the registry row from the issue alone, without asking for
  more
- **AND** the row states the plugin's own license

#### Scenario: Operator reads the registry
- **WHEN** an operator opens the registry
- **THEN** the disclaimer tells them the project does not vet or endorse the entries
- **AND** each entry's license is visible without following the link

#### Scenario: Registry is reachable
- **WHEN** a reader is in README or in the adapter-author guide
- **THEN** a link leads to the registry

#### Scenario: The terms are in the glossary
- **WHEN** a reader looks up "community plugin registry" or "plugin announcement" in
  `docs/glossary.md`
- **THEN** each has an entry naming the document or issue form it refers to

#### Scenario: The announcement form's fields are the registry's columns
- **WHEN** a reader compares the plugin-announcement issue form with the registry
- **THEN** the form's fields equal the registry's columns, one to one
- **AND** the registry tells a plugin author to announce through that form, with no pull
  request of their own

### Requirement: README states the license and the operator's own tooling terms
The README SHALL contain a License section of at most ten lines stating the Apache License
2.0, pointing at `NOTICE` and `CONTRIBUTING.md`, and stating that the agent command-line
tool a sandbox image installs is brought by the operator under its vendor's terms and is not
part of the factory's distribution.
**Implements**: FR11, UX3 of add-project-license

#### Scenario: Operator reads the terms
- **WHEN** an operator opens the README's License section
- **THEN** they learn the license, where the notices are, how to contribute, and that the
  agent CLI is licensed separately
