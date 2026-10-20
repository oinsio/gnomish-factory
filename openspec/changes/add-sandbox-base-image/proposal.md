# Proposal: add-sandbox-base-image

Sequenced after `add-release-pipeline` (it adds the image to the release
workflow and tags it with the product version). `add-doctor-command` follows
and reads the contract label this change introduces.
Sequenced after `tighten-spec-contract` too: this change's MODIFIED block of
`sandbox-egress` ("Image carries the egress plumbing") is layered on that
change's text, which must sync first.

## Why

Container mode is the default binding, so every operator needs a sandbox image.
Today they build one from the reference recipe in `docs/examples/sandbox-image/`
— and in doing so copy the factory's own contract by hand: the `gnome` user with
uid 1000, the root-owned control surfaces, the proxy variables pointing at the
guard's alias and port, the CA seam, the Maven settings. The operator stand did
exactly that and its Dockerfile has already drifted from the recipe. When the
factory's contract changes, nothing tells an operator that their image is
stale; the failure shows up as a broken round.

Two more hand-kept copies exist inside the repository: the guard's alias and
port are spelled in the recipe and in `GuardCommands`, held together only by a
comment; and a test image (`JvmProxyEgressImage`) re-types the recipe's JVM
proxy plumbing on its own base.

Agreed in the 2026-09-27 design session: a versioned base image on ghcr.io plus
the recipe as an example built on it; the base is Ubuntu LTS, carries no agent
CLI (vendor terms) and no project toolchain; toolchains are grafted in with
`COPY --from` or installed; the image states its contract version and the
factory checks it.

## What Changes

- **ADDED**: `ghcr.io/oinsio/gnomish-sandbox-base` — Ubuntu 24.04 with `git`,
  `curl`, CA certificates, the `gnome` user (uid 1000) owning `/gnomish/**`,
  root-owned `/etc/gnomish` (Maven settings, CA drop folder) and agent-policy
  folder, the guard proxy environment, and two helper commands:
  `gnomish-trust-ca` (imports dropped CA certificates into the system store and,
  when a JDK is present, the JVM trust store) and `gnomish-verify-image`
  (build-time contract check).
- **ADDED**: the image carries the label `io.gnomish.image-contract=<N>`; the
  factory refuses to start a box from an image whose label is missing or names
  another contract version, naming the fix.
- **ADDED**: one source file for the values the image and the factory must agree
  on — contract version, guard alias, guard port — read by the image build and
  by the factory.
- **ADDED**: the release workflow builds and pushes the base for `linux/amd64`
  and `linux/arm64`, tagged `X.Y.Z` (never re-pushed) and `X.Y` (moves), with a
  provenance attestation; a weekly workflow rebuilds `X.Y` of the newest release
  to pick up Ubuntu security updates; both scan the image and publish the
  results.
- **MODIFIED**: the reference recipe becomes `FROM` the base: JDK and Node
  grafted or installed, the agent CLI, `gnomish-trust-ca`, and a final
  `gnomish-verify-image`.
- **MODIFIED**: the container E2E layer builds its images from the locally
  built base instead of re-typing the recipe.

## Capabilities

### New Capabilities

- `sandbox-base-image`: the base image's content and contract, its helper
  commands, its label and tags, its publication and rebuild.

### Modified Capabilities

- `sandbox-egress`: "Image carries the egress plumbing" — the plumbing comes
  from the base image; the recipe layers on it.
- `execution-environment`: adds "The box image declares a matching contract
  version".

## Goals

- **G1**: an operator's Dockerfile contains only their toolchain and agent CLI;
  every factory contract item comes from `FROM`.
- **G2**: an image built for another contract version is refused before any
  round, with a message saying how to rebuild.
- **G3**: the base image receives Ubuntu security updates weekly without a
  factory release.

## Non-Goals

- **NG1**: toolchain flavours of the base (JDK, Node variants) — the operator
  grafts them.
- **NG2**: shipping an agent CLI in any published image.
- **NG3**: a blocking vulnerability gate on the base image: Ubuntu often has
  unfixed advisories; results are published, not gated, in this change.
- **NG4**: isolating the agent CLI from settings in the working copy
  (tracked separately); the base keeps the root-owned managed-settings file as
  today.
- **NG5**: non-Ubuntu bases; an operator building their own image may set the
  label after passing `gnomish-verify-image`.

## Users & Scenarios

- **U1**: an operator writes `FROM ghcr.io/oinsio/gnomish-sandbox-base:0.1`,
  grafts Temurin 25 and Node, installs the agent CLI, ends with
  `RUN gnomish-trust-ca && gnomish-verify-image`, and the build succeeds.
- **U2**: the same operator accidentally makes `/etc/gnomish` writable; the
  build fails in `gnomish-verify-image` naming the path.
- **U3**: after upgrading to a factory with contract version 2, the operator's
  old image is refused at box start with "image built for contract 1, this
  factory needs 2 — rebuild FROM gnomish-sandbox-base:<X.Y>".
- **U4**: a week after a release, `docker pull …:0.1` returns a base with
  current Ubuntu packages; `…:0.1.0` is unchanged.

## Requirements

### Functional

- **FR1**: the base image SHALL provide the contract of the sandbox guide:
  `git` and `curl`, user `gnome` uid 1000 owning `/gnomish/work` and
  `/gnomish/scratch`, root-owned `/etc/gnomish` and agent-policy folder, the
  proxy environment for curl-class tools, JVM tools and Maven, and end with
  `USER gnome`.
- **FR2**: `gnomish-trust-ca` SHALL import every certificate in the CA drop
  folder into the system trust store and, when `keytool` is on `PATH`, into the
  JVM trust store; with an empty folder it SHALL succeed and change nothing.
- **FR3**: `gnomish-verify-image` SHALL check the filesystem half of the
  contract (tools present, ownership and write permissions of `/gnomish/**`,
  `/etc/gnomish` and the agent-policy folder) and fail naming every violation.
- **FR4**: the image SHALL carry `io.gnomish.image-contract=<N>` and the OCI
  labels for version, source and revision.
- **FR5**: before starting a box, the factory SHALL read the image's labels and
  refuse a missing or different contract version with a message naming both
  versions and the base tag to rebuild from.
- **FR6**: the contract version, the guard alias and the guard port SHALL come
  from one source file read by the image build and by the factory; neither the
  Dockerfile nor Java source SHALL spell them.
- **FR7**: the release workflow SHALL build the base for `linux/amd64` and
  `linux/arm64`, push `X.Y.Z` and `X.Y`, attest provenance, and publish a scan
  report; a tag `X.Y.Z` already present SHALL NOT be overwritten.
- **FR8**: a scheduled weekly workflow SHALL rebuild the newest release's
  source and push only its `X.Y` tag, with attestation and scan report.
- **FR9**: the reference recipe SHALL build `FROM` the base of the factory
  version it ships with and end with `gnomish-trust-ca` and
  `gnomish-verify-image`.
- **FR10**: the container E2E layer SHALL build its test images from the base
  built from the repository's Dockerfile.

### Non-Functional

- **NFR-S1**: no published image SHALL contain an agent CLI or a credential.
- **NFR-S2**: the base's control surfaces SHALL be root-owned and not writable
  by `gnome` (the existing read-only-surfaces requirement, now provided by the
  base).
- **NFR-S3**: the workflows pushing images SHALL hold `packages: write` only in
  the job that pushes.
- **NFR-R1**: the contract check SHALL add no container start: it reuses the
  image inspection the factory already performs.
- **NFR-O1**: every refusal (contract mismatch, verify failure) SHALL name the
  item, the expected value and the fix.
- **NFR-C1**: the weekly rebuild is one multi-arch build per week.
- **NFR-P1**: the base SHALL stay small: no build tools, no documentation, apt
  lists removed (target under 150 MB per architecture).

## Operator Experience Criteria

- **UX1**: the recipe README shows a complete operator Dockerfile of at most
  about 25 lines on top of the base.
- **UX2**: the contract refusal fits on two lines and contains a ready-to-use
  `FROM` line.

## Success Metrics

- **M1**: the reference recipe's Dockerfile shrinks to toolchain + agent CLI +
  the two helper calls; no contract item remains in it.
- **M2**: `git grep -e '"gnomish-guard"' -e 'PROXY_PORT = ' -- '*/src/main/*'`
  and a search for the literal alias in Dockerfiles return nothing.
- **M3**: the container E2E suites pass against the locally built base with the
  `JvmProxyEgressImage` recipe copy deleted.
- **M4**: a release produces the base under both tags for both architectures,
  with an attestation that `gh attestation verify oci://…` accepts.

## Open Questions

Base distribution, tag scheme and the non-blocking scan were decided in the
design session and in this proposal's non-goals. Three questions are open,
raised by the task 9.13f report of `tighten-spec-contract` (2026-10-13); they
are recorded here without a decision.

- **Q1 — The base's git is below the factory's floor.** The box's seed clone
  reads the factory clone with the image's own git, and the factory clone is
  on the reftable ref backend, so the image's git must be at least 2.51.0
  (`GitVersion.FLOOR`, raised from 2.45.1 by task 9.13h of `tighten-spec-contract`; `BoxGitFloor` refuses an image below it before any seed
  clone). Ubuntu 24.04 packages git 2.43, which cannot open a reftable store
  (`unknown repository extension found: refstorage`; both features of
  `JvmProxyEgressE2ESpec` failed this way on a noble-based image). The base as
  specified here (`ubuntu:24.04`, `apt-get install git`) would be refused by
  the factory it ships with. Where the base's git comes from (the `git-core`
  PPA, a source build, another base) and what that does to NFR-P1, to the
  weekly rebuild (G3: a package source outside Ubuntu) and to the "Ubuntu
  24.04" requirement is undecided.
  **Raised by `own-git-invocation-policy` (2026-10-21):** that change moves the
  factory's own git floor to 2.56.0 and holds the box's apart at 2.51.0
  (its NG5, design D11); this change raises the box's floor to 2.56.0 and joins
  the two floors again. The bar for the base is therefore git 2.56.0, which no
  Ubuntu archive ships today (26.04: 2.53.0), so the `git-core` PPA or a source
  build is no longer optional, and the same bar applies to the test sandbox
  images and the reference recipe (see tasks, "Carried from
  own-git-invocation-policy").
- **Q2 — Which other image dependencies carry a version requirement.** git is
  the first tool whose version the factory's own mechanics constrain; the
  image contract lists tools by presence only. The other tools the factory
  drives inside the box (curl for the egress self-check probes, the shell the
  seed helper and the self-check run in, the CA tooling behind
  `gnomish-trust-ca`, `keytool`) and the age of the distribution itself need
  the same review before the contract text is fixed.
- **Q3 — How the factory checks that the box carries the versions it needs.**
  Today `BoxGitFloor` starts a one-shot container per materialize
  (`git --version`), which NFR-R1 does not account for; `gnomish-verify-image`
  (FR3) checks presence, not versions; the contract label names a contract
  version, not tool versions. Whether the label implies the floors, whether
  the build-time check and the box-start check read the floors from one
  source (the file of design D2), and what an operator's custom image (NG5)
  must prove, is to be designed.

## Impact

- New `sandbox-image/` folder (Dockerfile, helper scripts, contract source
  file); `.github/workflows/release.yml` (new image job) and a new
  `sandbox-image-rebuild.yml`.
- `:sandbox:docker`: `GuardCommands`, `DeclaredVolumeOverrides`,
  `DockerCommands`, the environment builder; a generated contract resource.
- Tests: the container E2E image builders in `:adapters:git`.
- Docs: `docs/examples/sandbox-image/` (Dockerfile, README),
  `operator-guide-sandbox.md`, `docs/glossary.md` (image contract).
