# Proposal: add-release-pipeline

Can land in parallel with `add-project-registry`; the first release is cut only
after `fix-operator-blockers` and `add-project-registry` are merged. Followed
by `add-sandbox-base-image` (which adds the image to the release) and
`add-doctor-command`.

## Why

Operators are to install the factory on their own machines, bringing their own
Java 25 and git. Today there is nothing to install: the only way to get the
factory is to clone the repository and build it, the README's run instructions
point at a jar path that does not exist (`build/libs/*.jar` — the jar lives in
`bootstrap/build/libs/`), every module carries the placeholder version
`0.1.0-SNAPSHOT`, and the `gnomish` command the guides use exists only as a
hand-written script on the operator stand. There is also no answer to "which
version am I running": the version recorded in the serve snapshot and ledger is
read from the `:application` module's manifest, i.e. the internal placeholder.

Agreed in the 2026-09-27 design session: the first consumer is an operator
installing the factory; a release is started by a human pushing a version tag
(a release bot may replace this later); the product version comes from that
tag; the first release is `0.1.0`; the plugin contract keeps its own version.

## What Changes

- **ADDED**: a release workflow triggered by a `vX.Y.Z` tag: checks the tag,
  checks that the tagged commit is on `main` with a green CI run, builds the
  distribution with the version taken from the tag, verifies it, and publishes a
  GitHub Release with the archives, a checksum file, a software bill of
  materials and a build provenance attestation.
- **ADDED**: a distribution archive (`.tar.gz` and `.zip`):
  `gnomish-X.Y.Z/{bin/gnomish, lib/gnomish-X.Y.Z.jar, share/examples/,
  share/sandbox-image/, LICENSE, NOTICE, README.md}`.
- **ADDED**: the `bin/gnomish` launcher: finds Java, refuses a Java older than
  25 with a plain message, passes every argument through unchanged.
- **ADDED**: `gnomish --version`, printing the product version.
- **ADDED**: one product version: taken from the tag in the release build,
  `0.0.0-dev` in any other build; stamped into the boot jar and read by every
  place that reports the factory version.
- **MODIFIED**: the quality-gates "Reproducible build" requirement gains output
  reproducibility: every archive task in the build (jars included) writes a
  fixed entry order and no timestamps, so two builds of one commit are
  byte-identical.
- **MODIFIED**: the licensing capability's distributed-artifact list gains the
  distribution archive.
- **MODIFIED**: the four check workflows (`ci.yml`, `license-gate.yml`,
  `osv-scan.yml`, `gitleaks.yml`) run on branch pushes only; a tag push starts
  no second `check`, so the release preflight finds exactly one run per commit.
- **ADDED** (docs): README "Install" section; developer guide "Releasing"
  section, including the tag-protection rule the maintainer sets once.

## Capabilities

### New Capabilities

- `release-distribution`: the product version, the distribution archive and
  launcher, `--version`, the release workflow and its supply-chain outputs.

### Modified Capabilities

- `project-licensing`: "Every distributed artifact carries the license and
  notice" — the distribution archive carries the root files too.
- `quality-gates`: "Continuous integration" and "Security scanning" — "every
  push" becomes "every branch push"; a tag push starts no check workflow.
  "Reproducible build" — beyond pinned inputs, the build's archive outputs are
  byte-identical across builds of one commit (fixed entry order, no
  timestamps, no build time in the boot jar).
- `cli-arguments`: "Unknown options are usage errors" — the sole token
  `--version` joins the empty command line as the only command lines that reach
  no subcommand parser; `--version` with any other token is still parsed and
  rejected.

## Goals

- **G1**: an operator with Java 25 and git installs the factory by downloading
  one archive, verifying it, and putting `bin/` on `PATH`.
- **G2**: a maintainer releases by pushing one tag; nothing else is manual.
- **G3**: every running factory can say which release it is, and that answer is
  the tag it was built from.

## Non-Goals

- **NG1**: a release bot (release-please), a `CHANGELOG.md` file, automatic
  version bumps.
- **NG2**: a bundled Java runtime, native images, Homebrew or SDKMAN packages,
  a factory Docker image.
- **NG3**: publishing the plugin contract to a Maven repository; its version
  stays in its own build file and is untouched by the product version.
- **NG4**: the sandbox base image (`add-sandbox-base-image`) and `gnomish
  doctor` (`add-doctor-command`).
- **NG5**: a `.gnomish/` project template or `gnomish init`.
- **NG6**: re-running the full mutation gate in the release workflow — it
  trusts the green CI run of the tagged commit.

## Users & Scenarios

- **U1**: an operator downloads `gnomish-0.1.0.tar.gz` and `SHA256SUMS` from the
  release page, checks the sum (and optionally `gh attestation verify`),
  unpacks, adds `bin/` to `PATH`, runs `gnomish --version` → `0.1.0`.
- **U2**: an operator on Java 21 runs `gnomish` and is told Java 25 or newer is
  required and which `java` was found.
- **U3**: the maintainer merges to `main`, waits for CI, pushes `v0.2.0`; the
  release appears with notes generated from the merged pull requests.
- **U4**: the maintainer pushes `v0.2` by mistake; the workflow fails before
  building, naming the expected shape.

## Requirements

### Functional

- **FR1**: the release workflow SHALL run only for tags matching
  `vMAJOR.MINOR.PATCH` with an optional pre-release suffix, and SHALL fail
  before building for any other `v*` tag.
- **FR2**: the release workflow SHALL fail before building unless the tagged
  commit is reachable from `main` and its CI workflow run concluded
  successfully.
- **FR3**: the release build SHALL take the product version from the tag
  (without the `v`); every other build SHALL use `0.0.0-dev`. Only the product
  artifact takes this version; the plugin contract keeps the version declared in
  its own build file.
- **FR4**: the build SHALL produce `gnomish-<version>.tar.gz` and
  `gnomish-<version>.zip` with the layout in What Changes; `share/sandbox-image/`
  SHALL be copied from `docs/examples/sandbox-image/` at build time, never kept
  as a second copy in the repository.
- **FR5**: `bin/gnomish` SHALL locate Java through `JAVA_HOME` or `PATH`,
  refuse a Java whose feature version is below 25 with a message naming the
  found binary and its version, accept extra JVM options from
  `GNOMISH_JAVA_OPTS`, and pass all arguments to the factory unchanged — it
  SHALL NOT inject factory options.
- **FR6**: `gnomish --version` SHALL print the product version and exit 0; the
  serve snapshot and ledger SHALL record the same version.
- **FR7**: the release workflow SHALL publish the two archives, a `SHA256SUMS`
  file covering them, a CycloneDX SBOM of the boot jar as a separate asset (never
  embedded in the jar), and for each archive a build provenance attestation and
  an attestation binding the SBOM to it; the release notes SHALL be
  `docs/releases/<tag>.md` when that file exists, otherwise generated from the
  merged pull requests.
- **FR8**: before publishing, the workflow SHALL unpack the built archive, run
  `bin/gnomish --version` and fail unless it prints the tag's version.

### Non-Functional

- **NFR-S1**: the release workflow's token SHALL have only the permissions it
  uses (`contents: write`, `id-token: write`, `attestations: write`, and
  `actions: read` for the CI-run query), and actions
  SHALL be pinned as in the existing workflows.
- **NFR-S2**: the archive's jar SHALL carry `META-INF/LICENSE` and
  `META-INF/NOTICE` identical to the root files, and the archive root SHALL
  carry both files, verified in the workflow by the existing distribution-terms
  script.
- **NFR-R1**: two builds of the same commit with the same version SHALL produce
  byte-identical jars and archives.
- **NFR-R2**: a failed release run SHALL leave no release, no partial asset
  list and no moved tag; re-running it for the same tag SHALL be safe.
- **NFR-O1**: every failure of the tag or CI checks SHALL name the check and the
  fix in the job log.
- **NFR-C1**: the release workflow SHALL NOT rerun `check`; its budget is one
  distribution build (target under 10 minutes).
- **NFR-P1**: the launcher's Java check SHALL add no more than one short JVM
  start.

## Operator Experience Criteria

- **UX1**: the README "Install" section fits on one screen: download, verify,
  unpack, `PATH`, `gnomish --version`.
- **UX2**: the Java refusal reads like: `gnomish: Java 25 or newer is required;
  found 21.0.4 at /usr/bin/java (set JAVA_HOME to a Java 25 installation)`.

## Success Metrics

- **M1**: pushing `v0.1.0` on a green `main` commit produces a release with 4
  assets (2 archives, `SHA256SUMS`, SBOM) and, per archive, one provenance and
  one SBOM attestation, without any manual step.
- **M2**: building the archive twice from the same commit yields identical
  SHA-256 sums.
- **M3**: `gnomish --version` from the downloaded archive prints exactly the tag
  version, checked by the workflow itself (FR8).
- **M4**: `git grep getImplementationVersion -- '*/src/main/*'` is empty.

## Open Questions

None: the tag shape, the version source, the first version (`0.1.0`) and the
plugin-contract separation were decided in the design session.

## Impact

- `.github/workflows/release.yml` (new); `ci.yml`, `license-gate.yml`,
  `osv-scan.yml`, `gitleaks.yml` (push trigger narrowed to branches); `build-logic` (a product-version
  convention, reproducible-archive settings, and an SBOM convention applied to
  the root project); `bootstrap/build.gradle`
  (distribution, build info); new `bootstrap/src/dist/` (launcher, archive
  README); `:application` (`FactoryVersion`, `ObservabilityAssembly`);
  `Subcommand` / the entry point (`--version`).
- New build dependency: the CycloneDX Gradle plugin (lockfile and verification
  metadata updated).
- Docs: `README.md`, `docs/guides/developer-guide.md`, new `docs/releases/`,
  `docs/glossary.md` (a "Release and distribution" section).
- Sync order: this change's `quality-gates` delta MODIFIES "Continuous
  integration" and syncs **before** `scope-pit-locally`, whose delta is layered
  on the text written here (see `.claude/rules/delta-specs.md`, overlapping
  MODIFIED requirements).
