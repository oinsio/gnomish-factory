# Tasks

Starts after `add-release-pipeline` is merged (its release workflow gains the
image job). Root `check` green after every section; container suites run where
Docker is available.

## 1. Contract source (design D2) — single-owner row 1

- [ ] 1.1 Add `sandbox-image/image-contract.properties` (`contract-version=1`,
      `guard-alias=gnomish-guard`, `guard-port=8080`); `:sandbox:docker` copies
      it into its resources; add `ImageContract` loading it once (FR6). Spock
      spec for load and malformed file. Verify PIT 100%.
- [ ] 1.2 `GuardCommands` takes alias and port from `ImageContract`; delete the
      two literals. Verify the guard specs pass and
      `git grep -n -e '"gnomish-guard"' -e 'PROXY_PORT = ' -- '*/src/main/*'`
      is empty (M2).
- [ ] 1.3 `ImageContractSourceSpec` in `:bootstrap`: resource equals the source
      file; no Java source or Dockerfile in the repository spells the alias, the
      port or a literal contract label value; asserts every scanned file was
      reached. Verify red with a planted literal.

## 2. The base image (design D1, D4, D5)

- [ ] 2.1 `sandbox-image/Dockerfile` on `ubuntu:24.04`: `git curl
      ca-certificates`, user `gnome` uid 1000 (renaming an existing uid 1000),
      `/gnomish/{work,scratch}`, root-owned `/etc/gnomish/{maven,ca}` and
      `/etc/claude-code/managed-settings.json`, proxy `ENV` from build args
      with no defaults (empty arg fails the build), Maven settings with the
      mirror build arg, OCI and contract labels, `USER gnome` (FR1, FR4,
      NFR-S1, NFR-P1). Verify a local build and `docker image inspect` shows the
      labels and a size under 150 MB.
- [ ] 2.2 `sandbox-image/bin/gnomish-trust-ca` (FR2) and
      `sandbox-image/bin/gnomish-verify-image` (FR3), POSIX `sh`, installed
      root-owned 0755 in `/usr/local/bin`. Verify `BaseImageHelpersSpec`
      (Docker-gated): trust-ca with an empty folder is a no-op, with a
      certificate and a grafted JDK reaches both stores; verify-image passes on
      the base and fails listing each violation when `/etc/gnomish` is made
      writable and when `curl` is removed.

## 3. Contract check at box start (design D3) — single-owner row 2

- [ ] 3.1 `DockerCommands`: replace the volumes inspect with one
      `image inspect -f '{{json .Config}}'`; add `ImageFacts` (declared volumes
      and labels) and switch `DeclaredVolumeOverrides.resolve` to take it.
      Verify the existing declared-volume specs pass unchanged and a spec
      counts exactly one inspect per image per box.
- [ ] 3.2 Add `ImageContractCheck` in the environment builder for the sandbox
      image only: missing or different label → configuration error with the
      FR5 message and a `FROM …:<X.Y>` line (product version from
      `add-release-pipeline`'s `FactoryVersion`; `0.0.0-dev` names the local
      build instead) (FR5, NFR-O1, UX2). Verify the three
      execution-environment scenarios.

## 4. Recipe and tests (design D7)

- [ ] 4.1 Rewrite `docs/examples/sandbox-image/Dockerfile` `FROM` the base
      (`GNOMISH_BASE_TAG` build arg), JDK grafted from
      `eclipse-temurin:25-jdk-noble`, Node from the official tarball, the agent
      CLI, `RUN gnomish-trust-ca && gnomish-verify-image`; README with the
      complete operator Dockerfile and the pinning advice (`X.Y.Z` or digest)
      (FR9, UX1). Verify the recipe builds against the local base (M1).
- [ ] 4.2 E2E base fixture builds `sandbox-image/Dockerfile` once per run with
      arguments from `image-contract.properties`; `DockerImageBuilder` builds
      `FROM` it; delete `JvmProxyEgressImage` (FR10). Verify the container E2E
      suites pass (M3) and `git grep -n JvmProxyEgressImage` is empty.

## 5. Publishing (design D6)

- [ ] 5.1 Release workflow `image` job: QEMU, buildx, ghcr login, refuse an
      existing `X.Y.Z`, build-push `linux/amd64,linux/arm64` with `X.Y.Z` and
      `X.Y`, attest with `push-to-registry`, `osv-scanner scan image` SARIF
      upload non-blocking; permissions in this job only (FR7, NFR-S3). Verify
      `actionlint` and a dry run on a test tag; attach the run link.
- [ ] 5.2 `sandbox-image-rebuild.yml`: weekly schedule plus manual dispatch;
      newest `v*` release → checkout tag → build-push `X.Y` only → attest →
      scan (FR8, NFR-C1). Verify `actionlint` and one manual dispatch run;
      `:X.Y.Z` digest unchanged afterwards.

## 6. Docs and wrap-up

- [ ] 6.1 `operator-guide-sandbox.md`: build your image `FROM` the base,
      the contract refusal and its fix, pinning, custom images and the label;
      `docs/glossary.md`: image contract, base image; developer guide: the
      one-time ghcr.io visibility setting. Verify the guide's example matches
      the recipe.
- [ ] 6.2 Traceability: every FR/NFR/UX named by a spec, javadoc or script
      comment. Verify `git grep -n "of add-sandbox-base-image"` against the
      proposal's IDs.
- [ ] 6.3 Root `./gradlew check` green. Verify the exit code.
- [ ] 6.4 Operator-stand acceptance (human step): rewrite the stand's
      Dockerfile `FROM` the base and run one container-mode task. Verify the
      box starts and the task reaches a terminal outcome.

## Carried from tighten-spec-contract

- `tighten-spec-contract` (task 9.13f, D13 as amended 2026-10-13) raised the box image's git floor
  to 2.45.1, and task 9.13h to 2.51.0 (the factory's own floor): the factory clone is on the reftable ref backend, the box's seed clone reads it with
  the image's git, and `BoxGitFloor` refuses an older git at materialize (GF163). Noble ships git
  2.43, so the base planned on `eclipse-temurin:25-jdk-noble` (design, task on line 58) would be
  refused; revise the base (the reference recipe moved to `eclipse-temurin:21-jdk-resolute`, whose
  archive ships git 2.53, above 2.51) through `/opsx:update` before this change is applied.

## Carried from own-git-invocation-policy

- `own-git-invocation-policy` (design D11, NG5; 2026-10-21) raised the factory's git floor to
  2.56.0 — the event-level hook switch `hook.<event>.enabled` its invocation policy needs is
  documented and verified there — and kept the box's floor at 2.51.0 as a second constant in
  `GitVersion`, read by `BoxGitFloor`, because every image the box checks installs an older git.
  This change raises the box's floor to 2.56.0 and folds the two constants back into one, in the
  same task that moves every image the floor judges, so no image is refused in between:
  - the base image: git 2.56.0 or newer (Ubuntu 26.04's archive ships 2.53.0: the `git-core` PPA
    or a source build);
  - the test sandbox images on `alpine:3` (3.24.1 installs 2.54.0) — `GitSandboxImage`,
    `EgressProbeImage`, `DeclaredVolumeSandboxImage`, `ContainerReadOnlySurfacesSpec`,
    `FakeAgentSandboxImage` — and `JvmProxyEgressImage` on `eclipse-temurin:21-jdk-resolute`
    (2.53.0) (`alpine:edge` installs 2.56.0; measured 2026-10-20);
  - the reference recipe `docs/examples/sandbox-image/` and `docs/guides/operator-guide-sandbox.md`.

  Revise the base's design and tasks through `/opsx:update` before this change is applied; the
  joined floor's spec text is the `execution-environment` requirement "The box's git meets the
  floor before the seed clone", whose Notes then name one floor again.
