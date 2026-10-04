# Tasks

Independent of `add-project-registry` in code; the first tag is pushed only
after `fix-operator-blockers` and `add-project-registry` are merged. Root `check`
green after every section.

## 1. Product version (design D1, D2) — single-owner row

- [x] 1.1 Add `product-version-conventions` in `build-logic`: sets
      `project.version` from `-PreleaseVersion` or `0.0.0-dev`; apply it in
      `:bootstrap` only; enable Spring Boot `buildInfo()` there with
      `excludes = ['time']` so no build timestamp lands in the jar (FR3,
      NFR-R1; design D2). Verify in two places, since `build-logic` has no
      Boot plugin on its classpath and cannot build a boot jar:
      `ReleaseVersionSpec` (TestKit in `build-logic`) applies the convention to
      a miniature project and asserts `project.version` is `9.9.9` with
      `-PreleaseVersion=9.9.9` and `0.0.0-dev` without, and that a sibling
      module keeping its own `version` is unchanged in both; `BuildInfoSpec`
      (`:bootstrap`, over the built jar at `e2e.jarPath`, with the version
      passed as a system property from `verification.gradle`) asserts
      `build-info.properties` holds `build.version` equal to it and no
      `build.time` key — red with the exclusion removed.
- [x] 1.2 Add `FactoryVersion` (`:application`): reads
      `META-INF/build-info.properties` once, `0.0.0-dev` when absent; Spock spec
      for present/absent/malformed. Route `ObservabilityAssembly`'s
      `InstanceInfo` through it and delete `resolveFactoryVersion()` (FR6).
      Verify `git grep -n getImplementationVersion -- '*/src/main/*'` is empty
      (M4).
- [x] 1.3 `FactoryVersionBoundarySpec` in `:bootstrap`: no production source
      calls `getImplementationVersion` (the manifest attribute of
      `library-conventions` stays as a documented exemption). Verify red with a
      planted call.

## 2. `gnomish --version` (design D3)

- [x] 2.1 The entry point prints `FactoryVersion` through the console owner and
      exits 0 when the sole argument is `--version`, before Spring starts
      (FR6). Verify a `FactoryApplicationSpec` feature spawning the packaged jar
      with `--version` from a non-registered directory: output equals the build
      version, exit 0, no log file created. Extend `CliEntrypointContractSpec`
      (`:bootstrap`), which pins the command lines that reach no parser: a
      feature for the sole `--version` (prints the version, no `UsageException`),
      and a feature for `--version` with another token (`run --version`,
      `--version --dir=.`) that still reaches the `run` parser and is rejected as
      an unknown option — the `cli-arguments` delta's two new scenarios.

## 3. Distribution archive and launcher (design D4, D5)

- [x] 3.1 Commit `bootstrap/src/dist/bin/gnomish` (POSIX `sh`, mode 0755):
      single jar in `../lib`, Java from `JAVA_HOME` else `PATH`, feature version
      via `java -XshowSettings:properties -version`, refusal below 25 with the
      UX2 message, `GNOMISH_JAVA_OPTS`, `exec … "$@"` (FR5, NFR-P1: one
      `java -XshowSettings:properties -version` probe, then `exec`). Verify
      `LauncherScriptSpec` runs it under `sh` with fake `java` binaries
      reporting 21, 25 and 26 and a fake jar that echoes its arguments: 21
      refused with the message, 25/26 accepted, arguments passed unchanged.
- [x] 3.2 Apply `distribution` in `:bootstrap` with `distributionBaseName =
      'gnomish'` and, on `distTar`, `compression = GZIP` and `archiveExtension =
      'tar.gz'` (the defaults yield an uncompressed `bootstrap-<version>.tar`,
      design D4): `lib/gnomish-<version>.jar` from `bootJar`, `bin/`,
      `src/dist/README.md`, root `LICENSE`/`NOTICE`, `docs/examples/` →
      `share/examples/` (sandbox recipe excluded there),
      `docs/examples/sandbox-image/` → `share/sandbox-image/` (FR4). Verify
      `DistributionLayoutSpec` asserts the archive is named
      `gnomish-<version>.tar.gz` and unpacks to `gnomish-<version>/`, lists the
      tar's entries against the layout, and asserts no committed copy of the
      recipe exists outside `docs/examples/`.
- [x] 3.3 Reproducible archives: `java-conventions` states explicitly
      `preserveFileTimestamps = false`, `reproducibleFileOrder = true` and fixed
      file (`0644`) and directory (`0755`) permissions on every
      `AbstractArchiveTask` — Gradle 9's defaults, written down so a property
      override or a default change cannot silently undo them (NFR-R1, design
      D5). Verify `ReproducibleArchiveSpec` (TestKit in `build-logic`): a
      miniature project applying `java-conventions` and `distribution` builds
      its `distTar` twice and the SHA-256 sums are equal, and the launcher-like
      file set to `0755` on a child spec keeps that mode; the red case is the
      miniature project opting out with `preserveFileTimestamps = true`, which
      must yield differing sums (removing the explicit settings cannot be the
      red case: the defaults already hold). The real
      archives' identity (M2) is checked by the release workflow's second build
      in task 4.1 (design D5), not by a unit spec — building the whole module
      tree under TestKit would need Boot and every module.
- [x] 3.4 CycloneDX Gradle plugin 3.x via the version catalog (2.x fails
      under Gradle 9 with the configuration cache, design D7), applied to the
      root project by a new `sbom-conventions` plugin in `build-logic` — never
      to `:bootstrap`, where Spring Boot would embed the timestamped SBOM in the
      jar; `bootstrap/packaging.gradle` scopes `cyclonedxDirectBom` to
      `runtimeClasspath` and turns off `bootJar.includeTools` (Boot's jarmode
      tools come from its plugin classpath, not a dependency, so no SBOM names
      them). Regenerate lockfiles and `verification-metadata.xml`.
      Verify `./gradlew :bootstrap:cyclonedxDirectBom` writes
      `build/reports/cyclonedx-direct/bom.json`; `BootJarSbomSpec` (`:bootstrap`,
      split from `DistributionLayoutSpec`, which is at the file-size limit)
      asserts every library in the boot jar's `BOOT-INF/lib/` is an SBOM
      component (red with `includeTools` on) and that the boot jar has no
      `META-INF/sbom/` entry and no `Sbom-Location` manifest attribute
      (red with the plugin applied in `:bootstrap`); run it twice with the project's default
      `gradle.properties` (configuration cache and parallel on) and assert the
      first run stores a configuration cache entry and the second reuses it
      with no cache problem reported; `./gradlew check` stays green with the
      cache on, and the OSV and license gates stay green.

## 4. Release workflow (design D6)

- [x] 4.0 Narrow the check workflows to branch pushes: `ci.yml`,
      `license-gate.yml`, `osv-scan.yml` and `gitleaks.yml` change `push:` to
      `push: branches: ['**']`, each header comment saying why a tag push must
      not start them (FR2, NFR-C1; design D6). Verify with `actionlint` and a
      spec in `:bootstrap` (`CheckWorkflowTriggerSpec`) that parses every
      workflow under `.github/workflows/` whose `on.push` exists and asserts it
      carries a `branches` filter unless it is `release.yml`.
- [x] 4.1 Add `.github/workflows/release.yml` with the nine steps of design D6,
      least-privilege permissions, actions pinned like `ci.yml`, a 30-minute
      timeout, `--prerelease` for suffixed versions (FR1, FR2, FR7, FR8,
      NFR-S1, NFR-S2, NFR-R1, NFR-R2, NFR-O1). Step 4 builds `distTar` a second
      time after `:bootstrap:clean` and fails on differing SHA-256 sums (M2) —
      the archives only, never the SBOM, which carries its generation time;
      it publishes the SBOM as `gnomish-<version>-cyclonedx.json`; step 8 runs
      `actions/attest` for provenance and again in SBOM mode, both with the two
      archives as subjects (design D6, D7);
      step 6 runs `check-distribution-terms.sh archive <unpacked-dir>` from the
      repository root (task 4.4). Verify automatically: `actionlint` on the
      file, `ReleasePreflightScriptSpec` (task 4.2) for the gate steps, and the
      `DistributionTermsScriptSpec` feature of task 4.4 asserting the workflow
      invokes the `archive` mode; the workflow's first live run is the first
      release (task 6.3), not a verification step of this task.
- [x] 4.2 Extract the tag-shape and CI-status checks into
      `scripts/release-preflight.sh` (like `check-distribution-terms.sh`) so a
      spec drives the red paths: malformed tag, commit not on `main`, CI failed,
      CI in progress, no `main` push run for the commit. The CI query filters by
      `--commit $SHA --branch main --event push` (design D6). Verify
      `ReleasePreflightScriptSpec` in `:bootstrap` with a stubbed `gh` and a
      local repository; one feature feeds the stub a green `main` push run plus
      an `in_progress` run for the same SHA on another ref and asserts the
      preflight passes, red before the branch/event filter.
- [x] 4.4 Add the `archive <dir>` mode to `scripts/check-distribution-terms.sh`:
      `cmp` of `<dir>/LICENSE` and `<dir>/NOTICE` with the root files, then
      `identity <dir>/lib`; every failure is a `::error` naming the file; usage
      line and header comment updated (NFR-S2; design D6 step 6). Verify
      `DistributionTermsScriptSpec` (`:bootstrap`): passes on an unpacked folder
      whose copies equal the root files; fails naming the file when a copy
      differs by one byte, when a copy is missing, and when `lib/` holds no jar
      — and a feature asserting `release.yml` invokes the `archive` mode, as the
      existing FR7 feature does for the license workflow.
- [x] 4.3 Add `docs/releases/v0.1.0.md` with the first release's notes,
      including the operator steps from `add-project-registry`'s migration plan
      (register clones, move secrets, delete the old `~/.gnomish` folders) and
      `fix-operator-blockers`' `default-binding` note. Verify the file exists
      and names each step.

## 5. Docs

- [x] 5.1 `README.md` "Install" section (download, `sha256sum -c`, optional
      `gh attestation verify`, unpack, `PATH`, `gnomish --version`) replacing
      the `java -jar build/libs/*.jar` lines; `bootstrap/src/dist/README.md`
      with the same steps offline (UX1). Verify
      `git grep -n "build/libs/\*.jar" -- README.md docs` is empty.
- [x] 5.2 `developer-guide.md` "Releasing": merge → wait for green CI → tag →
      push; what each preflight failure means; the tag ruleset and the `gh api`
      call that sets it (design D8). Verify the section names every failure the
      preflight script prints.
- [x] 5.3 `docs/glossary.md`: add a "Release and distribution" section
      (introduced by `add-release-pipeline`) with entries for **product
      version** (the version of the factory as a product, taken from the release
      tag or `0.0.0-dev`; *Not:* the plugin-contract version; *Never:* build
      version, app version), **distribution archive** (the `gnomish-<version>`
      `.tar.gz`/`.zip` the release publishes; *Never:* bundle, tarball, dist),
      **launcher** (`bin/gnomish`, the committed POSIX script that checks Java
      and passes every argument through; *Never:* start script, wrapper script)
      and **release notes file** (`docs/releases/<tag>.md`, the hand-written
      body of a release; *Never:* changelog). `add-sandbox-base-image` and
      `add-doctor-command` build on these terms, so the entries land here
      (`process-invariants.md`, "No jargon"). Verify `grep -n "Release and
      distribution" docs/glossary.md` finds the section and each of the four
      terms is bold-defined under it.

## 6. Wrap-up

- [x] 6.1 Traceability: every FR/NFR/UX named by a spec, javadoc or script
      comment. Verify `git grep -n "of add-release-pipeline"` against the
      proposal's IDs.
- [x] 6.2 Root `./gradlew check` green. Verify the exit code.
      Added during apply (agreed with the maintainer): the `:application` mutation
      gate, re-run because `ObservabilityAssembly` changed, reported
      `TerminalWriteRetry.next`'s `* 2 → / 2` mutant as TIMED_OUT — the shared
      `VirtualTimeRetries.terminalWrite()` fixture looped forever once the backoff
      reached zero under a permanent outage (`FinishEffectSpec`,
      `GuardedParkLogSpec`). The fixture now uses `BudgetedVirtualSleeper`, which
      turns that loop into a KILLED. Pre-existing, independent of this change's
      requirements.
- [ ] 6.3 First release (M1, M3, human step — the only manual step of this
      change): after the prerequisite changes are merged, push `v0.1.0` on a
      green `main`; a maintainer who wants a rehearsal first pushes a throwaway
      `v0.0.1-rc.1` on a fork and deletes it afterwards. Verify the release page
      holds the four assets and, per archive, a provenance and an SBOM
      attestation, and `gh attestation verify` passes for the tar; attach the run link to the task report.
      Not verified in this change: left open on purpose. The first release is
      verified in `fix-docker-exec-env-argv` instead.
