# Design: add-release-pipeline

## Context

Driven by FR1–FR8 of the proposal. Current state:

- `java-conventions` sets `version = '0.1.0-SNAPSHOT'` on every module;
  `:gnomish-plugin-api` overrides it with `version = '0.8.0'` further down its
  own build file, guarded by `verifyPublishedApiVersion` and japicmp.
- `library-conventions` stamps `Implementation-Version: project.version` into
  every library jar's manifest. `ObservabilityAssembly.resolveFactoryVersion()`
  reads `getImplementationVersion()` of its own package, so the snapshot and
  ledger report `:application`'s placeholder, not a product version.
- `:bootstrap` applies the Spring Boot plugin and `distribution-terms-conventions`;
  `bootJar` is the only packaged artifact. No `application` or `distribution`
  plugin, no build info, no `--version`.
- `scripts/check-distribution-terms.sh identity <jar-dir>` already compares a
  jar's `META-INF/LICENSE`/`NOTICE` with the root files byte for byte.
- Existing workflows pin actions by major tag, use least-privilege tokens and
  run `check` on every push (`ci.yml`, about 20 minutes on `main`).

## Decisions

**D1 — The tag is the version; the workflow hands it to Gradle.** The release
workflow validates `GITHUB_REF_NAME` against
`^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$` and
passes `-PreleaseVersion=<tag without v>`. A new `product-version-conventions`
plugin, applied by `:bootstrap` only, sets `project.version` from
`releaseVersion` or `0.0.0-dev` (FR3). Every other module keeps its current
version assignment; `:gnomish-plugin-api` is untouched (NG3).
*Rationale:* the human's tag is the only record of the number, so nothing can
disagree with it; no Gradle plugin reads git, so the build needs no history.
*Alternatives rejected:* computing the version from `git describe` inside Gradle
(needs full history and a plugin; a source archive without `.git` has no
version); a number in `gradle.properties` (a second record that a manual tag
flow would have to keep in step — right for a release bot, NG1).

**D2 — One runtime reader of the version: `FactoryVersion`.** `:bootstrap`
enables Spring Boot's `buildInfo()`, which writes
`META-INF/build-info.properties` (`build.version`) into the boot jar, with the
`time` property excluded (`springBoot { buildInfo { excludes = ['time'] } }`,
verified against Boot 4.1): by default Boot stamps `build.time` with the wall
clock, which would make two builds of one commit differ (NFR-R1, D5).
`FactoryVersion` (`:application`) reads that resource once, answering
`0.0.0-dev` when it is absent (specs running from class folders).
`ObservabilityAssembly` and the `--version` path take it from there (FR6).
*Alternative rejected:* setting `Implementation-Version` on the boot jar's
manifest and keeping `getImplementationVersion()` — the call site reads the
package of whichever nested jar holds the calling class, which is how the wrong
version got reported in the first place.

**D3 — `--version` is handled before Spring starts.** The entry point checks
for a sole `--version` argument, prints `FactoryVersion` through the console
owner and exits 0 — no context, no project resolution, no configuration
(`add-project-registry`'s loader never runs). This is a second command line that
reaches no subcommand parser, beside the empty one — `fix-operator-blockers`'
unknown-option rule says the entrypoint hands *every* non-empty command line to a
parser, so the rule is amended by this change's `cli-arguments` delta, not left
as it is: the exception is the sole token `--version`, and `--version` with any
other token still reaches the `run` parser and is rejected. The second row of
`CliEntrypointContractSpec` (the empty command line) gets a sibling for this one.
*Alternative rejected:* a `version` subcommand inside the Spring run — it would
need a registered project or a special case in the configuration loader.

**D4 — Distribution through Gradle's `distribution` plugin with a committed
launcher.** `:bootstrap` applies `distribution`; the main distribution copies
`bootJar` to `lib/gnomish-<version>.jar`, `src/dist/bin/gnomish` (committed POSIX
`sh`), `src/dist/README.md`, the root `LICENSE`/`NOTICE`, `docs/examples/` minus
the sandbox recipe into `share/examples/`, and `docs/examples/sandbox-image/`
into `share/sandbox-image/` (FR4, NFR-S2). The plugin's defaults name the
archive and its top-level folder after the module (`bootstrap-<version>.tar`,
uncompressed), so the distribution sets `distributionBaseName = 'gnomish'` and
`distTar` sets `compression = GZIP` and `archiveExtension = 'tar.gz'` — the
three settings that turn the defaults into `gnomish-<version>.tar.gz` unpacking
to `gnomish-<version>/`; `DistributionLayoutSpec` asserts the names (FR4). The
launcher finds the single jar in
`../lib`, resolves Java (`$JAVA_HOME/bin/java`, else `java` on `PATH`), reads the
feature version from `java -XshowSettings:properties -version`
(`java.specification.version`), refuses below 25, then
`exec java $GNOMISH_JAVA_OPTS -jar "$jar" "$@"` (FR5, NFR-P1).
*Rationale:* the launcher must stay a pass-through — the operator stand showed
that injecting `--factory.*` defaults collides with Spring joining repeated
options — and must check Java, which Spring Boot's generated `bootStartScripts`
do not.
*Alternatives rejected:* Boot's `bootDistTar` with generated scripts (no Java
check, `APP_HOME` quirks, a second script to patch); a jlink runtime (NG2).

**D5 — Reproducible archives are a build-wide convention.** Since Gradle 9.0
every `AbstractArchiveTask` is reproducible by default: fixed entry timestamps,
deterministic entry order, files `0644` and directories `0755`. `java-conventions`
still states the settings explicitly — `preserveFileTimestamps = false`,
`reproducibleFileOrder = true`, fixed file and directory permissions — because a
default can be switched off without touching a build script (the
`org.gradle.archives.use-file-system-permissions` property) and has drifted from
its own documentation before (gradle/gradle#34643, 9.0.0); the explicit lines
are where a reader finds the intent. A child copy spec that sets its own
permissions keeps them, which is how the launcher ships `0755` (D4) (NFR-R1).
The boot jar is written by Boot's own copy action, which stamps every entry
with its fixed 1980-02-01 constant whenever timestamps are not preserved. Archive settings alone do not make the boot jar reproducible: Boot's
build info is content, not metadata, so D2's `time` exclusion is part of this
decision. Verification is split by what each module can build:
`ReproducibleArchiveSpec` (TestKit in `build-logic`) applies `java-conventions`
and `distribution` to a miniature project, builds its `distTar` twice and
compares SHA-256 sums; since the defaults already hold, removing the explicit
settings cannot turn it red, so its red case is the miniature project opting out
with `preserveFileTimestamps = true` — no Boot plugin is on `build-logic`'s classpath, so a
spec there cannot build the real boot jar; `BuildInfoSpec` in `:bootstrap`
opens the built boot jar (`e2e.jarPath`) and asserts `build-info.properties`
holds `build.version` and no `build.time`; the real archives' identity (M2) is
measured by the release workflow, which builds `distTar` a second time and
compares sums before publishing (D6 step 4).

**D6 — The release workflow: gate, build, verify, publish — in one job.**
`.github/workflows/release.yml`, `on: push: tags: ['v*']`, permissions
`contents: write`, `id-token: write`, `attestations: write`, plus `actions: read`
for step 3's run query (NFR-S1). Steps:
(1) tag shape; (2) `git merge-base --is-ancestor $SHA origin/main`; (3) the CI
run for `$SHA` via `gh run list --workflow=ci.yml --commit $SHA --branch main
--event push` must be `completed/success` (FR1, FR2, NFR-O1); (4) `./gradlew
:bootstrap:distTar :bootstrap:distZip :bootstrap:cyclonedxDirectBom
-PreleaseVersion=…`, the SBOM copied aside as `gnomish-<version>-cyclonedx.json`; copy the three outputs aside, then `:bootstrap:clean` and
`:bootstrap:distTar` again with the same version, failing unless both tars have
the same SHA-256 sum (NFR-R1, M2) — the archives only: the SBOM carries its
generation time and is not compared (D7); (5) unpack the tar, run `bin/gnomish --version`, compare
(FR8); (6) `check-distribution-terms.sh archive <unpacked-dir>` from the
repository root (NFR-S2) — the script's new third mode compares the unpacked
`LICENSE` and `NOTICE` with the root files byte for byte and runs `identity` on
`<unpacked-dir>/lib`. The two existing modes cannot prove NFR-S2 here:
`presence` only tests existence, and `identity` compares a jar with the
`LICENSE`/`NOTICE` of the *current directory*, so run inside the unpacked folder
it would compare the jar with the archive's own copies, never with the root;
(7) `sha256sum` → `SHA256SUMS`; (8)
`actions/attest` twice with both archives as subjects — once for build
provenance, once in SBOM mode with `sbom-path` set to the SBOM, so the SBOM is
bound to the archives' digests rather than only published beside them
(`actions/attest-build-provenance` is now a wrapper over `actions/attest`); (9) `gh release create
$TAG` with `--notes-file docs/releases/$TAG.md` if present, else
`--generate-notes`, uploading all files in one call (FR7). Steps 1–3 precede any
build; publishing is the last step, so a failure publishes nothing and a re-run
starts from scratch (NFR-R2). `--draft=false` and `--verify-tag` keep the tag
where the human put it.

*The tag push must not start the check workflows.* `ci.yml`, `license-gate.yml`,
`osv-scan.yml` and `gitleaks.yml` declare `on: push:` with no ref filter, and
GitHub runs such a workflow for every pushed ref, tags included; the run's
`head_sha` is the tagged commit. Left as is, pushing `v0.1.0` would start a
second 20-minute `check` for a commit CI already verified (NFR-C1), and step 3's
query by SHA alone would list that fresh `in_progress` run beside the green
`main` one and refuse every real release (G2, M1). So the same change narrows
the four workflows to `push: branches: ['**']` — every branch, no tag — and
step 3 filters by branch and event as written above, so a run that reached the
commit through any other ref can never be the one it judges. `tags-ignore:
['v*']` was rejected: it keeps every other tag triggering four workflows for no
reader, and its exclusion would have to be kept in step with FR1's pattern.
*Alternatives rejected:* `workflow_run` chained after CI (the run this change
wants is the one CI already produced for the commit on `main`; the check in
step 3 is explicit and names that run); re-running `check` in the release (20+
minutes for a result CI already has, NFR-C1).

**D7 — SBOM from the CycloneDX Gradle plugin, 3.x, kept out of the jar.** The
SBOM is a release asset, `gnomish-<version>-cyclonedx.json`, describing the boot
jar's runtime classpath (FR7); it is never embedded in the jar. *Why not in the
jar:* Spring Boot reacts to the CycloneDX plugin applied to its own project by
routing the aggregate SBOM through `processResources` into
`META-INF/sbom/application.cdx.json` and adding `Sbom-Format`/`Sbom-Location`
to the manifest. The SBOM carries `metadata.timestamp` — cyclonedx-core sets it
to the current time, the plugin offers no override (upstream issue #292, open)
and ignores `SOURCE_DATE_EPOCH` — so an embedded SBOM makes the jar and both
archives differ on every build (NFR-R1, M2); Boot declined an opt-out
(spring-boot#41478), and what embedding serves, the actuator `sbom` endpoint, a
headless CLI does not have. *How:* the plugin is applied to the root project by a
`sbom-conventions` convention plugin in `build-logic`. Applied there, it
registers a `cyclonedxDirectBom` task in every subproject without applying the
plugin class to them (checked in the 3.4.1 source), so Boot's reaction never
fires in `:bootstrap`; `bootstrap/packaging.gradle` scopes that module's task to
`runtimeClasspath`, and sets `bootJar { includeTools = false }`: Boot otherwise
adds `spring-boot-jarmode-tools` from its own plugin classpath — no dependency,
so the one bundled jar the SBOM would not name — for `-Djarmode=tools` layer
extraction, which a CLI shipped as an archive never uses. `:bootstrap:cyclonedxDirectBom` writes
`build/reports/cyclonedx-direct/bom.json`. The SBOM itself is not
byte-reproducible (timestamp, serial number), which is why D6 compares the
archives only. *Alternatives rejected:* embedding and relaxing NFR-R1 for the
jar (breaks M2 and step 4 for an endpoint the factory lacks); stripping the
timestamp and serial number after generation (a hand-written step between two
tasks, a deviation from the format's "unique serial number" SHOULD, and broken
by the first upstream fix of #292); excluding `META-INF/sbom` in `:bootstrap`
(the manifest would still point at a missing file, and every `check` would keep
generating an SBOM it throws away). Versions live in the catalog; lockfiles and `verification-metadata.xml` are regenerated
in the same task. *Configuration cache:* the build runs with
`org.gradle.configuration-cache=true` and `org.gradle.parallel=true`
(`gradle.properties`), and the license-report plugin already forced
`--no-configuration-cache --no-parallel` into its own workflow. Checked
2026-10-03: the plugin's 2.x line reads `Task.project` at execution time and
fails under Gradle 9 with the cache on (upstream issue #659, closed without a
2.x fix); the 3.x line's README states support for the configuration cache,
parallel execution and the build cache, with Gradle 8.4 as its floor. So the
catalog pins 3.x, the workflow's step 4 invokes `:bootstrap:cyclonedxDirectBom` with
no cache flags, and task 3.4 proves it: the task runs green twice with the
cache on, the second run reusing the cache entry. If a later upgrade regresses,
the flags go on that one workflow step, never into `gradle.properties`.
*Alternative rejected:* `syft` on the archive in the workflow — a second tool
outside the build's dependency verification.

**D8 — Tag protection is a repository setting, documented.** The developer
guide's "Releasing" section records the ruleset (only maintainers create `v*`
tags) and the `gh api` call that sets it; code cannot enforce a repository
setting.

### Sync surfaces

Sync surfaces: none — this change adds no parallel implementation and touches no
declared pair. The sandbox recipe is copied into the archive at build time from
its one location; the distribution-terms check gains an `archive` mode that composes the
existing byte comparison and `identity`, not a second implementation of either.

### Single-owner mechanisms

| Owner                                                                               | Value (type)                                      | Consumers                                                                                                                                                         | Old way removed                                                                                                                                                                                                                                                        | Enforced by                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|-------------------------------------------------------------------------------------|---------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `product-version-conventions` → `META-INF/build-info.properties` → `FactoryVersion` | the product version (`FactoryVersion`, read once) | `ObservabilityAssembly` (snapshot and ledger `InstanceInfo`), the `--version` path in the entry point, the distribution file names, the release workflow's step 5 | `ObservabilityAssembly.resolveFactoryVersion()` and its `getImplementationVersion()` call are deleted; the `Implementation-Version` that `library-conventions` stamps on internal modules stays as a module attribute and is exempted (no production code may read it) | `FactoryVersionBoundarySpec` in `:bootstrap`: no production source calls `getImplementationVersion`; identity check: the workflow's step 5 compares the unpacked archive's `--version` with the tag (FR8), and `ReleaseVersionSpec` (TestKit in `build-logic`, no Boot on its classpath) applies `product-version-conventions` to a miniature project and asserts `project.version` is `9.9.9` with `-PreleaseVersion=9.9.9` and `0.0.0-dev` without; `BuildInfoSpec` (`:bootstrap`) reads the built boot jar's `build-info.properties` and asserts `build.version` equals the version the build passed in and `build.time` is absent; `DistributionLayoutSpec` (`:bootstrap`) asserts the archive name carries that version |

## Risks / Trade-offs

- [The CI run for the tagged commit is still in progress when the tag is
  pushed] → step 3 fails with "CI run in progress for <sha>, re-run this
  workflow when it finishes"; re-running is safe (NFR-R2).
- [`gh run list` sees only runs of `ci.yml` on push events for that SHA] → the
  check keys on the workflow file and the SHA, which is exactly how CI runs on
  `main` (push trigger).
- [A pre-release tag such as `v0.2.0-rc.1` is published as a normal release]
  → the workflow passes `--prerelease` when the version has a suffix.
- [macOS `sh` and Linux `dash` differ] → the launcher uses POSIX `sh` only; a
  spec runs it under `sh` against fake `java` binaries printing 21, 25 and 26.
- [New build plugin (CycloneDX) widens the build classpath] → it goes through
  dependency locking and verification metadata like every plugin; OSV scans it.
- [A later change applies the CycloneDX plugin in `:bootstrap` itself] → Boot
  embeds a timestamped SBOM and the jar stops being reproducible;
  `BootJarSbomSpec` asserts the boot jar carries no `META-INF/sbom/` entry
  and no `Sbom-Location` attribute, and the release's double build (step 4)
  fails on it as well.

## Migration Plan

None for users: the first release is the first archive. The README's
`java -jar build/libs/*.jar` instructions are replaced by the install section.
