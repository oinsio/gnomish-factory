# Design: add-sandbox-base-image

## Context

Driven by FR1–FR10 of the proposal. Current state:

- `docs/examples/sandbox-image/Dockerfile` builds `FROM eclipse-temurin:21-jdk-noble`
  and carries the whole contract inline (user, `/gnomish/**`, `/etc/gnomish`,
  `/etc/claude-code/managed-settings.json`, proxy `ENV`, CA import with
  `keytool`, Maven settings), plus Node and the agent CLI.
- `GuardCommands.PROXY_ALIAS = "gnomish-guard"` and `PROXY_PORT = 8080` are
  spelled again as the recipe's `PROXY_HOST`/`PROXY_PORT` build-argument
  defaults; a comment is the only link.
- `DeclaredVolumeOverrides.resolve` runs `docker image inspect -f
  '{{json .Config.Volumes}}'` for the sandbox image and the guard image before a
  box is created (`DockerCommands` line 183).
- `EnvironmentSelfCheck` runs inside a fresh box: the user id and the egress
  probes (the runtime half of the contract).
- Test images: `JvmProxyEgressImage` re-types the recipe's JVM proxy plumbing on
  `eclipse-temurin:21-jdk-noble`; `DockerImageBuilder` builds test images for the
  container suites.
- The release workflow from `add-release-pipeline` runs on `v*` tags.

## Decisions

**D1 — The base lives in `sandbox-image/` at the repository root.** Files:
`Dockerfile`, `bin/gnomish-trust-ca`, `bin/gnomish-verify-image`,
`image-contract.properties`. The recipe in `docs/examples/sandbox-image/` keeps
its place (operators copy from there; the release archive ships it) and becomes
`FROM ghcr.io/oinsio/gnomish-sandbox-base:${GNOMISH_BASE_TAG}` (FR1, FR9).
*Rationale:* the base is product source that CI builds; the recipe is an
example. Keeping them apart keeps the archive's `share/sandbox-image/` an
example only.
*Alternative rejected:* the base under `docs/examples/` — documentation paths
would become build inputs.

**D2 — One source for the cross-medium values: `image-contract.properties`.**
It holds `contract-version`, `guard-alias`, `guard-port`. The image build reads
it into build arguments (the Dockerfile declares them with no defaults and fails
on an empty one); Gradle copies it into `:sandbox:docker`'s resources, where
`ImageContract` loads it once; `GuardCommands` takes alias and port from
`ImageContract` (FR6).
*Rationale:* the alias and port pair has two ends in two media with no shared
classpath; adding the contract version would make it three values held by
comments. One file read by both sides removes the pair instead of declaring it.
*Alternative rejected:* a declared pair with an identity spec
(`manual-sync-pairs.md`, "no shared classpath" table, the `AdversarialGitConfig`
precedent) — acceptable by the rule, but a third value is arriving and the file
costs less than three synchronized spellings.

**D3 — One image inspection yields volumes and labels: `ImageFacts`.**
`DockerCommands` gains a single inspect of `{{json .Config}}`; `ImageFacts`
parses declared volumes and labels from it. `DeclaredVolumeOverrides` takes
`ImageFacts` instead of issuing its own inspect; `ImageContractCheck` compares
the label with `ImageContract.version()` and throws a configuration error with
the FR5 message (FR5, NFR-R1, NFR-O1). The check runs only for the sandbox
image, not the guard image.
*Alternatives rejected:* a second `docker image inspect` for labels (a runtime
call per box for data the first call already fetched); checking inside the box
(needs a container start, and a stale image could fail earlier in stranger
ways).

**D4 — Build-time and runtime checks split by what can change.**
`gnomish-verify-image` owns the filesystem contract (tools, ownership, write
permissions) — facts fixed at build time. `EnvironmentSelfCheck` keeps owning
the user a process runs as and the egress route — facts only a running box
shows. No item is checked by both (FR3).
*Alternative rejected:* also checking `id -u` in the verify script — it runs as
whatever `USER` the build is at, so it would test the build step, not the image.

**D5 — `gnomish-trust-ca` replaces the inline CA loop.** It reads
`/etc/gnomish/ca/*.{pem,crt}`, installs them for `update-ca-certificates`, and
imports each into the JVM trust store with `keytool -cacerts` when `keytool` is
on `PATH` (FR2). The operator runs it after grafting a JDK; the base runs it at
its own build for the system store.
*Alternative rejected:* an `ONBUILD` trigger — runs at the wrong moment (before
the operator grafts the JDK) and surprises readers of the child Dockerfile.

**D6 — Publishing: a job in the release workflow plus a weekly workflow.**
The release workflow gains an `image` job (needs the preflight job), with
`packages: write`, `id-token: write`, `attestations: write` in that job only
(NFR-S3): `docker/setup-qemu-action`, `docker/setup-buildx-action`,
`docker/login-action` to ghcr.io, a check that `X.Y.Z` does not exist
(`docker manifest inspect`), `docker/build-push-action` for both platforms with
`X.Y.Z` and `X.Y` tags and OCI labels, `actions/attest-build-provenance` with
`push-to-registry`, and `osv-scanner scan image` uploading SARIF without failing
the job (FR7, NG3). `sandbox-image-rebuild.yml` runs weekly: finds the newest
`v*` release, checks out that tag, builds and pushes only `X.Y` the same way
(FR8).
*Alternative rejected:* re-pushing `X.Y.Z` weekly — a release tag must keep the
digest the release notes and attestations name.

**D7 — Test images build on the local base.** A test fixture builds
`sandbox-image/Dockerfile` once per suite run (build arguments from
`image-contract.properties`) and `DockerImageBuilder` builds test images `FROM`
that local tag; `JvmProxyEgressImage` is deleted (FR10).

### Sync surfaces

The guard alias and port are an undeclared pair today (recipe build-argument
defaults ↔ `GuardCommands` constants, linked only by a comment), and
`JvmProxyEgressImage` is an undeclared test copy of the recipe's JVM plumbing.
This change removes both rather than declaring them: D2 gives the three shared
values one source file, D7 deletes the test copy. It adds no parallel
implementation: build-time and runtime checks split the contract without
overlap (D4).

### Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `sandbox-image/image-contract.properties` → `ImageContract` (`:sandbox:docker`) | contract version, guard alias, guard port (`ImageContract`) | `GuardCommands`, `ImageContractCheck`, `sandbox-image/Dockerfile` (build args), the release `image` job, `sandbox-image-rebuild.yml`, the E2E base fixture | `GuardCommands.PROXY_ALIAS`/`PROXY_PORT` literals; the recipe's `PROXY_HOST`/`PROXY_PORT` defaults (the recipe inherits the base's `ENV`) | `ImageContractSourceSpec` in `:bootstrap`: the resource equals the source file; no Java source or Dockerfile in the repository spells the alias, the port or `io.gnomish.image-contract=<digit>` |
| `ImageFacts` (`:sandbox:docker`) | the image's declared volumes and labels (`ImageFacts`, one inspect) | `DeclaredVolumeOverrides` (sandbox and guard image), `ImageContractCheck` | the `{{json .Config.Volumes}}` inspect command in `DockerCommands` is replaced by the `{{json .Config}}` one | parameter type: `DeclaredVolumeOverrides.resolve` takes `ImageFacts`; a spec counts one inspect call per box creation |

## Risks / Trade-offs

- [Operators without the label (custom non-Ubuntu images) are refused] → the
  guide says how to set the label after passing `gnomish-verify-image` (NG5);
  the refusal names it.
- [Multi-arch builds under QEMU are slow] → the base is small (no toolchain);
  the image job runs in parallel with the distribution job.
- [The weekly rebuild changes `X.Y` under running operators] → operators who
  want stability pin `X.Y.Z` or a digest; the guide says so.
- [Contract version bumps force every operator to rebuild] → that is the point;
  bumps are rare and named in release notes.
- [ghcr.io package visibility defaults to private] → a one-time repository
  setting, recorded in the developer guide's releasing section.

## Migration Plan

Operators rebuild their image `FROM` the base using the new recipe. Until then
the factory refuses their old image with the rebuild line (FR5). The operator
stand's Dockerfile is rewritten as part of acceptance.
