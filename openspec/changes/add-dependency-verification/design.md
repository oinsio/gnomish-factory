# Design: add-dependency-verification

## Context

See proposal.md — Why. Gradle 9 ships dependency verification natively: a
committed `gradle/verification-metadata.xml` is picked up automatically and
applies to every resolvable configuration, including plugin resolution and the
`build-logic` included build. The repo already has Dependabot (grouped
minor/patch PRs) and a CI pipeline (`ci.yml`) running `check`. Resolves Q1–Q3
of the proposal. Driven by FR1–FR5, NFR-R1, NFR-S1 of
add-dependency-verification.

## Goals / Non-Goals

**Goals:**
- Fail-closed pinning of every resolved artifact with a one-command update
  flow (FR1–FR3).
- Keep IDE sync and day-to-day builds friction-free (UX2).

**Non-Goals:**
- Publisher-identity verification via PGP from day one (upgrade path kept).
- Any runtime mechanism (NG1 of the proposal).

## Decisions

**D1 — Q1 resolved: checksum pinning (sha256) first; PGP trusted keys deferred (FR1).**
Bootstrap with `./gradlew --write-verification-metadata sha256 help` and verify
checksums only. *Rationale:* the threat model is tampered/injected bytes —
checksums close it completely and deterministically; PGP adds publisher
identity but brings keyring maintenance and key-churn noise, and roughly a
third of ecosystem artifacts are unsigned anyway, forcing checksum fallbacks
regardless. The metadata format upgrades in place (`pgp,sha256` + an exported
local keyring) when the ecosystem's signing coverage justifies it. *Alternative
rejected:* `pgp,sha256` from day one — doubles the maintenance surface for a
marginal gain over pinned checksums reviewed in PRs.

**D2 — Q2 resolved: reviewer-run regeneration on Dependabot PRs; no bot-commit automation (FR4).**
The documented flow: check out the Dependabot branch, run the regeneration
command, push the metadata commit, merge green. *Rationale:* Dependabot PRs
are weekly and grouped, so the cost is one command a week; an automation that
regenerates and commits on Dependabot branches would need write-capable
workflow permissions on dependency-controlled input — exactly the kind of
privilege escalation this change exists to prevent. Revisit only if update
volume grows. *Alternative rejected:* auto-regenerating CI job — grants a
workflow triggered by dependency changes the right to rewrite the trust
anchor; the reviewer step keeps a human between the bump and the pin.

**D3 — Q3 resolved: wrapper jar pinned by the official validation action (FR5).**
`gradle/wrapper/gradle-wrapper.jar` is outside dependency verification's
scope, so CI gains the official `gradle/actions/wrapper-validation` step.
*Rationale:* the wrapper executes before any verification does; it is the one
jar the metadata cannot cover. *Alternative rejected:* manual checksum in
docs — unenforced.

**D4 — IDE-only artifacts trusted by classifier, nothing else (UX2).**
`sources` and `javadoc` classifier artifacts are declared trusted (regex
entry) in the metadata. *Rationale:* IDE sync resolves them lazily and they
never execute; without the exemption every IntelliJ sync of a new library
fails verification, which trains contributors to bypass. *Alternative
rejected:* pinning them too — doubles metadata churn for artifacts with no
execution path.

**D5 — Enforcement is default-on everywhere; regeneration is the only escape (FR2, FR5, NFR-S1).**
The committed metadata file activates verification for every build — local and
CI — with no opt-out flag anywhere in CI. Verification failure messages are
Gradle's own (they name artifact, expected/actual checksum) plus a docs
pointer to the regeneration command in the failure-facing documentation.
*Rationale:* a gate with a bypass flag in CI is documentation, not a gate.
*Alternative rejected:* CI-only enforcement via a flag — local builds would
drift and contributors would meet failures only in CI.

## Risks / Trade-offs

- **Metadata churn on every bump** → grouped Dependabot PRs + one idempotent
  command (NFR-R1); the diff is entry-per-artifact and reviewable.
- **IDE sync failures on unpinned tooling artifacts** → D4 trusts
  `sources`/`javadoc`; anything else failing sync is a real gap worth pinning.
- **Contributor forgets regeneration** → CI fails naming the exact missing
  entries and the command (FR2, UX1).
- **Platform-dependent metadata drift** → idempotence check in M3; sha256 is
  platform-neutral.

## Migration Plan

1. Bootstrap `gradle/verification-metadata.xml` (sha256, all configurations),
   add the D4 trusted-classifier entries, commit.
2. Add wrapper-validation to `ci.yml`; confirm no verification bypass exists
   in any workflow.
3. Tamper test (M1): corrupt one cached artifact / add an unlisted dependency
   locally, observe the named failure; document the procedure.
4. Docs: regeneration command, Dependabot reviewer flow, threat model
   (NFR-S1), next to the Dependabot config reference.

Rollback: delete the metadata file and the CI step — verification deactivates
with no other coupling.

## Open Questions

None blocking. Deferred upgrades noted in D1 (PGP keyring) and D2
(automation if update volume grows).
