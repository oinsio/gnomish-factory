# Design: fix-osv-dependency-gate

## Context

Driven by FR1–FR9 and NFR-S1/S2, NFR-R1/R2 of `proposal.md`.

Current state, as measured on the failing run:

| Advisory | Artifact | Scope | Lockfiles | Status |
|---|---|---|---|---|
| GHSA-355h, GHSA-qh8g, GHSA-wjpw, GHSA-2fvj, GHSA-7p3p | `jetty-*` 11.0.26 | test | `adapters/github`, `bootstrap` | already reviewed & allowlisted, allowlist not applied |
| GHSA-r4gv | `handlebars` 4.3.1 | test | `adapters/github`, `bootstrap` | already reviewed & allowlisted, allowlist not applied |
| GHSA-hjcp | `httpclient5` 5.6.1 → 5.6.3 | test + buildscript | `adapters/github`, `bootstrap`, `bootstrap` buildscript | new, fixable |
| GHSA-hf6x, GHSA-v3jc | `httpcore5(-h2)` 5.4.2 → 5.4.3 | test | `adapters/github` | pin exists, does not reach this module |
| GHSA-qv9r | `log4j-api` 2.25.4 → 2.25.5 | **production runtime** | `bootstrap` | new, fixable |

Three constraints shape the approach:

1. **OSV-Scanner resolves `osv-scanner.toml` per scanned package source**, i.e.
   next to each lockfile. The repo-root file was written when the build had one
   lockfile at the root; `split-into-modules` moved the lock state under
   `adapters/github/` and `bootstrap/` and the config stopped being found.
2. **The Spring Boot 4.1.0 BOM pins `httpclient5` with `strictly`**, and
   `httpclient5` in turn strictly requires `httpcore5(-h2)`. A `constraints {}`
   entry cannot raise a `strictly` requirement — it fails resolution instead.
   This is the same wall `bootstrap/build.gradle` already hit for `httpcore5`.
3. **Jetty 11 is EOL on Maven Central at 11.0.26** (verified 2026-08-16); every
   listed Jetty fix is 12.x-only, and Jetty 12 requires WireMock 4.x, still beta
   (4.0.0-beta.38). The existing accepted-risk entries remain the only option
   (NG1).

## Goals / Non-Goals

**Goals:**

- Make the allowlist's reach and the overrides' reach *structural*, so neither
  silently lapses the next time a module is added or extracted.
- Keep the fix minimal in dependency terms: patch-level bumps, no line changes.

**Non-Goals:**

- Re-deciding scanner, trigger pattern, or SARIF plumbing.
- Any change to production source or module boundaries.

## Decisions

**D1 — One repo-root config, passed explicitly via the job's `scan-args`.**
The workflow gains `--config=osv-scanner.toml` alongside the existing `-r ./`.
An explicit `--config` applies to every package source the scan walks,
regardless of directory, which is exactly FR1/FR2 and M5: a future module's
lockfile is covered on the day it appears.
*Rationale:* one file, one review surface (UX2), one expiry calendar (NFR-S2).
*Alternative rejected:* copying `osv-scanner.toml` into each lockfile directory
— it is the scanner's default resolution, but it produces N drifting copies and,
decisively, a newly added module silently loses every suppression, which is the
precise failure this change exists to fix.
*Alternative rejected:* moving the lock state back to the repo root — undoes
`split-into-modules` to satisfy a scanner's file-lookup rule.
*Trade-off:* an explicit `--config` overrides any per-directory config the
scanner would otherwise find; since this repo has none, nothing is shadowed, and
the workflow comment states the rule so a future per-module config is not added
in the expectation that it applies.

**D2 — `resolutionStrategy.force`, not `constraints`, for the HTTP-components
family.** `httpclient5` is BOM-pinned `strictly` and drags a `strictly` on
`httpcore5(-h2)`; only `force` overrides that (FR3, FR4).
*Rationale:* documented precedent already in `bootstrap/build.gradle` for
`httpcore5`; the failure mode of the alternative is a resolution error, not a
silent downgrade.
*Alternative rejected:* `constraints { … }` — cannot raise a `strictly`.
*Alternative rejected:* excluding the artifacts — WireMock and Testcontainers
need them at test runtime.

**D3 — The test-classpath security forces move into a convention plugin.**
The `httpcore5` force lives in `bootstrap/build.gradle` only, which is why
`:adapters:github` still locks 5.4.2. Rather than copy the block into a second
module, the force block (httpcore5, httpcore5-h2, httpclient5) moves into
`build-logic` and is applied to every module's test classpaths from
`test-conventions`, whose whole purpose is the shared test stack.
*Rationale:* directly serves FR4, NFR-R2, M3 and M5 — the override reaches every
module that resolves the artifact, including modules that do not exist yet, and
there is exactly one place to bump. WireMock/Testcontainers are the only sources
of these artifacts and they are test-scope everywhere.
*Alternative rejected:* duplicating the block in `adapters/github/build.gradle`
— two places to keep in step, and the third module to grow a WireMock suite
reintroduces the bug.
*Alternative rejected:* a build-wide `allprojects { }` force in the root script —
the project deliberately has no cross-project configuration; convention plugins
are its mechanism (design D9 of `split-into-modules`).
*Trade-off:* modules with no HTTP-components on their classpath carry an inert
`force`. `force` on an absent module is a no-op, so the cost is a line of build
logic, not a resolution effect.

**D4 — `log4j` is pinned to the minimal patch (2.25.5) on the BOM's line, as a
production constraint.** GHSA-qv9r affects `log4j-api` on
`:bootstrap`'s `runtimeClasspath` / `productionRuntimeClasspath`, so NFR-S1
forbids suppressing it. `log4j-to-slf4j` moves with it — the pair must stay on
one version, as `logback-core`/`-classic` already do in the same file. The
version goes in `gradle/libs.versions.toml` (FR6) and is applied via
`constraints {}`; if the BOM turns out to pin `log4j` `strictly`, it falls back
to `force` per D2 with the reason recorded next to it.
*Rationale:* a patch bump on the line Boot 4.1.0 already manages is the smallest
credible delta on a production logging bridge; the newest line (2.26.1) is a
minor-version move whose compatibility with Boot 4.1.0's logging wiring nobody
has reason to have exercised (resolves Q2).
*Alternative rejected:* 2.26.1 — larger surface, no additional advisory cleared.
*Alternative rejected:* waiting for a Spring Boot 4.1.x patch that manages it —
NG2, and the gate stays red meanwhile.

**D5 — The buildscript classpath is pinned through `gradle.properties`.**
`buildscriptHttpclient5Version` joins the existing
`buildscriptHttpcore5Version` / `buildscriptJacksonVersion` /
`buildscriptCommonsLang3Version`, forced from the `buildscript {}` blocks of the
root and `:bootstrap` (FR3, FR6).
*Rationale:* the version catalog is not visible inside `buildscript {}` — the
project's established workaround, already documented in `gradle.properties`.
*Alternative rejected:* a literal in the build script — defeats M3's
single-source rule.

**D6 — The accepted-risk entries are re-reviewed and re-dated, not reopened.**
Each Jetty/Handlebars entry keeps its rationale, refreshed against today's
findings (Jetty 11 max on Maven Central is 11.0.26; WireMock 4.x is at
`4.0.0-beta.38`), with `ignoreUntil` set to **2026-11-01** — far enough to not
churn, near enough to force a WireMock 4.x re-check (resolves Q3). Every entry
restates its classpath scope (UX2, FR8).
*Alternative rejected:* an open-ended ignore — violates NFR-S2.

**D7 — The local reproduction command is documented in README's "Building"
section**, next to the existing dependency-locking note, as the two are used
together (FR9, UX4):
`./gradlew check --write-locks && osv-scanner scan source --config=osv-scanner.toml -r ./`.
*Rationale:* the note that lockfiles "feed OSV-Scanner" already lives there;
splitting the workflow across two documents is how the local step gets missed.
*Alternative rejected:* a Gradle task wrapping the scanner — adds a toolchain
dependency (a Go binary) to `check` for something CI already owns.

## Risks / Trade-offs

- **`--config` shadows per-directory configs silently** → the workflow carries a
  comment stating that the root file is the only one consulted, so a future
  per-module `osv-scanner.toml` is not added in false confidence.
- **The `httpclient5` bump breaks a WireMock or Testcontainers test path** →
  patch-level within 5.6.x; caught by `./gradlew check` (M4) before the lock
  state is committed. Fallback: pin 5.6.3 rather than the newest 5.6.4.
- **The `log4j` bump disturbs Boot's logging bridge** → patch-level within
  2.25.x; the existing logging-level specs assert on captured Logback output and
  will fail if the bridge misbehaves (M4).
- **The convention-plugin force reaches modules that never resolve these
  artifacts** → inert by construction; the plugin comment says so, so a reader
  does not mistake it for a dependency declaration.
- **A stale lockfile hides an override that stopped applying** → NFR-R2's
  detection point is exactly the committed lock state; the task list regenerates
  locks and greps the result for the pinned versions rather than trusting build
  script intent.
- **Suppressions expire during a quiet period and turn the gate red on an
  unrelated push** → accepted deliberately (NFR-S2); an expiring suppression is
  the mechanism, and the date is chosen to land when WireMock 4.x is plausibly
  stable.
