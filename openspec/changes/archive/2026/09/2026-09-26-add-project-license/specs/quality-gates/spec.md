# Spec Delta

## ADDED Requirements

### Requirement: Dependency license gate
CI SHALL run a dependency-license gate over the runtime classpath of every distributed
module (the factory boot jar's module and the plugin-contract module) on every push and on
fork pull requests, in a workflow of its own, separate from `./gradlew check`.

The gate SHALL read every license each resolved module declares, normalize license-name
variants to one canonical name, and fail when a module declares no license the allowlist
accepts. A module declaring several licenses SHALL pass when at least one is accepted.

One repository file SHALL be the only source of accepted licenses. It SHALL accept the
licenses known under the SPDX identifiers Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause,
EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1 and GPL-2.0 with Classpath Exception — spelled
as the gate's normalizer names them — and SHALL NOT accept LGPL, GPL or AGPL in any version.
Module-scoped exceptions SHALL not be needed for the shipped inventory; adding one SHALL
state the reason beside it.

A failing gate SHALL name each violating module with its coordinates and every license it
declares in the job log, SHALL attach the violation report to the run, and every run SHALL
attach the full generated license inventory of the distributed modules. The gate SHALL be
covered by a functional build test over a miniature project with an offline repository. The
project SHALL document one command that reproduces the CI verdict locally.

The gate's build plugin SHALL enter the build through the version catalog and the committed
lockfiles and verification metadata, so the dependency-verification and OSV gates cover it
like every other plugin.
<!-- implements FR4, FR5, FR6, FR7, FR8, FR12, NFR-R1, NFR-R2, NFR-O1, NFR-O2, NFR-S2, NFR-C1, UX1 of add-project-license -->

#### Scenario: Copyleft-only dependency fails CI
- **WHEN** a resolved runtime module declares only LGPL-2.1 (or GPL, or AGPL)
- **THEN** the license job fails naming the module's coordinates and its declared licenses
- **AND** the violation report is attached to the run

#### Scenario: Dual-licensed dependency passes without an exception
- **WHEN** a resolved runtime module declares `EPL-2.0` and `LGPL-2.1-only`
- **THEN** the license job passes for that module
- **AND** the allowlist contains no entry naming that module

#### Scenario: Spelling variant is not a violation
- **WHEN** a resolved runtime module declares `The Apache Software License, Version 2.0`
- **THEN** the gate normalizes it to the canonical Apache-2.0 name and passes

#### Scenario: Test-only dependency is outside the gate
- **WHEN** a test-scope dependency declares a license the allowlist does not accept
- **THEN** the license job is unaffected, because only runtime classpaths are evaluated

#### Scenario: Version bump needs no allowlist edit
- **WHEN** a dependency version is bumped and its declared licenses are unchanged
- **THEN** the license job passes with the allowlist unchanged

#### Scenario: Functional test pins the semantics
- **WHEN** the build-logic functional test suite runs
- **THEN** a miniature module declaring only LGPL fails the gate
- **AND** a miniature module declaring EPL-2.0 and LGPL-2.1 passes
- **AND** a miniature module declaring an Apache spelling variant passes

#### Scenario: The gate plugin is locked and verified
- **WHEN** the gate's plugin version is bumped
- **THEN** the bump lands in the version catalog, the buildscript lockfile and the
  verification metadata in one diff
- **AND** a plugin jar missing from the verification metadata fails the build before any
  task of it runs

#### Scenario: Local reproduction
- **WHEN** a maintainer runs the documented command on the same lock state CI evaluated
- **THEN** the verdict and the report equal CI's
