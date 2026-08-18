## Why

Post-SecurityManager JDK (JEP 486) offers no runtime boundary between classpath
jars: whatever the build resolves *is* the trust domain. The sandbox binding
registry (`open-adapter-binding-registry`, NFR-S1) accepts a documented
residual on exactly this: a malicious jar shipping a provider under a trusted
id with the expected passport cannot be stopped at runtime. Gradle dependency
verification closes that gap at build time — every artifact on every classpath
is pinned to a recorded checksum / trusted signing key, so an unexpected jar
never reaches the JVM. This is the JVM analog of GitHub Actions SHA-pinning,
and it hardens the whole supply chain (Maven-Hijack-class packaging attacks,
compromised mirrors), not just the sandbox SPI.

## What Changes

**ADDED**
- Gradle dependency verification enabled repository-wide:
  `gradle/verification-metadata.xml` records checksums (and trusted PGP keys
  where publishers sign) for every resolved artifact — libraries, Gradle
  plugins, and `build-logic` dependencies included.
- A documented, low-friction maintenance workflow: bootstrap generation, the
  update step for dependency bumps, and the Dependabot PR flow (Dependabot does
  not update the metadata file, so the flow must say who/what regenerates it).
- CI enforcement: verification is active on every CI build with no bypass
  flag; an unverified or mismatched artifact fails the build with an
  actionable error.

**PRESERVED (explicit non-change)**
- No runtime verification and no change to any production code path — this is
  a build-time gate only.

## Capabilities

### New Capabilities
- `dependency-verification`: build-time classpath pinning — the verification
  metadata contract (what is pinned, how), the fail-closed behavior on
  unknown/mismatched artifacts, and the maintenance workflow that keeps
  dependency updates cheap.

### Modified Capabilities

<!-- none: quality-gates requirements are unchanged; this adds a new
     capability rather than modifying an existing gate's contract -->

## Goals

- **G1** — Every artifact on every resolvable classpath (including buildscript
  and `build-logic`) is pinned: a swapped or injected jar fails the build
  before any code runs.
- **G2** — Dependency updates stay low-friction: a version bump (manual or
  Dependabot) needs one documented regeneration step and a reviewable metadata
  diff — no hand-editing of checksums.
- **G3** — Close the NFR-S1 residual of `open-adapter-binding-registry`: the
  "malicious jar under a trusted id" scenario is stopped at build time.

## Non-Goals

- **NG1** — Runtime signature or checksum checking; JPMS module-path
  enforcement (stays the optional future hardening it is in
  `open-adapter-binding-registry`).
- **NG2** — Sigstore / provenance attestation of dependencies — revisit when
  the Maven Central ecosystem support matures.
- **NG3** — Vendoring dependencies into the repository.
- **NG4** — Verifying artifacts of the factory's *target* projects (what
  gnomes build in the sandbox) — this change covers the factory's own build
  only.

## Users & Scenarios

- **U1** — A maintainer bumps a dependency (or merges a Dependabot PR): they
  run the documented regeneration command, review the metadata diff alongside
  the version diff, and CI stays green.
- **U2** — A compromised mirror / repository serves a tampered jar for a
  pinned version: the local or CI build fails naming the artifact and the
  checksum mismatch; nothing executes.
- **U3** — A new module or plugin is added: its artifacts are captured by the
  same regeneration step; a forgotten regeneration fails CI with the exact
  missing entries named.

## Requirements

### Functional

- **FR1** — `gradle/verification-metadata.xml` SHALL pin every artifact
  resolved by any resolvable configuration — libraries, Gradle plugins,
  `build-logic` dependencies — by checksum, with trusted PGP keys used where
  the publisher signs.
- **FR2** — A build resolving an artifact that is absent from, or mismatches,
  the metadata SHALL fail with an error naming the artifact, the reason, and
  the fix (the regeneration command).
- **FR3** — The regeneration workflow SHALL be a single documented command
  producing a deterministic, reviewable diff; contributors SHALL NOT hand-edit
  checksums.
- **FR4** — The Dependabot flow SHALL be documented end-to-end: how a
  version-bump PR gets its metadata update (manual step by the reviewer or an
  automated follow-up), so Dependabot PRs do not rot red.
- **FR5** — CI SHALL run with verification enforced and SHALL NOT carry any
  bypass flag; local builds MAY use the documented escape hatch
  (`--write-verification-metadata`) only to regenerate.

### Non-Functional — Reliability

- **NFR-R1** — Regeneration SHALL be idempotent: running it twice with no
  dependency change produces no diff; the metadata file SHALL be stable across
  supported dev platforms (macOS/Linux).

### Non-Functional — Security

- **NFR-S1** — The metadata file is the trust anchor: changes to it SHALL be
  reviewable in PRs like any code change, and the threat model (compromised
  repository/mirror, name-collision packaging attacks, the
  `open-adapter-binding-registry` id-spoofing residual) SHALL be documented
  where the workflow is.

### Non-Functional — Observability

- **NFR-O1** — A verification failure SHALL name the offending artifact, the
  expected vs actual checksum/key, and the fix — never a bare stack trace.

## Operator Experience Criteria

- **UX1** — A routine dependency bump costs one command plus a diff review; a
  contributor who forgets it gets a CI error that tells them exactly what to
  run.
- **UX2** — Day-to-day builds (no dependency changes) are unaffected: no new
  prompts, no measurable slowdown.

## Success Metrics

- **M1** — Tamper test: swapping any pinned jar's bytes (or injecting an
  unlisted artifact) fails the build naming that artifact.
- **M2** — A real dependency bump (e.g., one Dependabot PR) lands green
  through the documented flow.
- **M3** — Full `check` passes on a fresh clone with verification enforced;
  regeneration run twice yields an empty diff (NFR-R1).

## Open Questions

- **Q1** — Checksum-only vs checksums + PGP trusted keys from day one: PGP
  adds publisher identity but key churn; start checksum-only and add keys
  where stable? → design.md.
- **Q2** — Dependabot metadata updates: manual reviewer step vs an automated
  workflow that regenerates metadata on Dependabot branches? → design.md.
- **Q3** — Does the Gradle wrapper jar itself get pinned here (Gradle's
  wrapper-validation action) or is it already covered? → design.md.

## Impact

- **Build** — new `gradle/verification-metadata.xml`; possibly a helper task /
  documented command in the build; no production-code change.
- **CI** — `ci.yml` relies on verification being on by default; optional
  wrapper-validation step (Q3).
- **Process** — dependency-update flow gains one step; documented next to the
  Dependabot config it affects.
- **Depends on / relates** — closes the NFR-S1 residual documented in
  `open-adapter-binding-registry` (D2/Risks); complements existing supply-chain
  gates (OSV-Scanner CVE gate scans *known* versions; this pins *which bytes*
  those versions are).
