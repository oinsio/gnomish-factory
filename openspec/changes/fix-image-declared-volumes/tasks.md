# Tasks: fix-image-declared-volumes

Sequencing: implement and sync **before** `add-sandbox-hardening` (its
provisioning snapshots inherit base-image `VOLUME` declarations and its guard
interception mode assumes an ephemeral mitmproxy confdir — both are covered
only once this change is in). Other active changes that touch the same files,
and the order agreed with each:

- `own-git-transfer-argv` (task 5.1 rewrites `DockerSeedCloneCommand.seedClone`'s
  clone line) — **after** this change; it relies on the `run --rm` pin task 2.5
  adds to `DockerCommandsSpec`.
- `add-parameter-count-gate` (task 2.3 brings `ContainerMaterializer.create`
  and `reattach` under the limit) — **after** this change; `ContainerRunSpec`
  (task 2.1) already takes `runContainer` under the limit, `create` gains no
  parameter here and stays that change's offender.
- `add-subprocess-access-log` (task 7.1 changes `DockerCommands.exec`) and
  `type-untrusted-text` (D5 rewraps the exception messages at
  `ContainerMaterializer:83,134` and three `EgressGuard` sites) — independent
  by line; either order, merged by hand.

## 1. The owner and its value type (FR1, FR2, FR3, NFR-R1, NFR-R2, D2, D4, D5)

- [x] 1.1 Add `DeclaredVolumeOverrides` (value type) in `sandbox/docker`: immutable
      ordered path list plus the fixed `64m` bound and `1777` mode, `argv()` rendering
      one `--mount type=tmpfs,destination=<p>,tmpfs-size=64m,tmpfs-mode=1777` per path
      (D5: the runtime never copies the image directory's owner onto a tmpfs, so
      the mode is what gives the image's non-root user its write access back),
      an `isEmpty()`
      and a `describe()` for the log line (`none` when empty). Verify:
      `DeclaredVolumeOverridesSpec` — rendering for zero, one and two paths;
      ordering is the image's; `describe()` for both states.
- [x] 1.2 Add the static resolver `DeclaredVolumeOverrides.resolve(DockerCli, String
      image, Set<String> explicitDestinations)`: runs `image inspect -f {{json
      .Config.Volumes}} <image>` (the template is one argv element — no shell
      quotes, they would become part of the template), parses `null`/`{}`/`{"/p":{}}`
      with the module's Jackson, subtracts `explicitDestinations`, fails on non-zero
      exit or any other shape with an exception carrying the image reference and the
      runtime's stderr (through `LogText`, per `logging.md`). Verify:
      `DeclaredVolumeOverridesSpec` against a fake `DockerCli` — data table over
      `null`, `{}`, one path, two paths, declared path equal to an explicit
      destination (subtracted), non-zero exit (throws), malformed JSON (throws); and
      NFR-R2: two resolutions over the same fake answer and destinations are equal
      and render identical argv in the image's declaration order. No daemon.
- [x] 1.3 Add the inspect argv builder to `DockerCommands` (`inspectImageVolumes(image)`)
      so the resolver issues no literal argv. Verify: `DockerCommandsSpec` pins the
      exact argv.

## 2. Wire every `run` builder and call site (FR3, FR4, D3, D6, single-owner table)

- [x] 2.1 Introduce the `ContainerRunSpec` parameter object (key, image, runtime,
      limits, enforceDiskQuota, workingCopy, ownership, overrides) and change
      `DockerCommands.runContainer` to take it, appending `overrides.argv()` after
      the working-copy `-v`/`-w` pair. Delete the old seven-argument signature.
      Verify: `DockerCommandsSpec` "FR3 run container" features updated to pass the
      spec object; a new feature asserts two override fragments appear after the
      working-copy mount and none when the value is empty; NFR-S1: every override
      fragment is `type=tmpfs`, carries `tmpfs-mode=1777`, and carries no
      `source=`/`src=` and no volume name,
      so the only host-backed or named mounts in the argv are the factory's own
      explicit ones; the module compiles with no other caller of the old signature
      (`grep -rn "runContainer(" sandbox`).
- [x] 2.2 Change `GuardCommands.runGuard` to take `DeclaredVolumeOverrides` and append
      its fragments after the `:ro` config mount; delete the old signature. Verify:
      `GuardCommandsSpec` (or the existing guard argv spec) asserts the fragment
      position, the `tmpfs-mode=1777` field, and the unchanged mitmdump tail.
- [x] 2.3 `ContainerMaterializer.create`: resolve the overrides via the owner with
      `explicitDestinations = {WORKING_COPY}` immediately before building the run
      argv, wrapped so a resolver failure surfaces through `management(...)`'s
      naming convention (container name in the message, D4). Verify:
      `ContainerTaskExecutionEnvironmentUnitSpec` (fake `DockerCli`, the existing materialize coverage) — the inspect call precedes the
      run call; exactly one `image inspect` is issued per `create` (NFR-C1); the
      run argv carries the fragments; a failing inspect throws the
      infrastructure-failure exception naming `gnomish-box-<key>` and issues no run.
- [x] 2.4 `EgressGuard.create`: resolve with `explicitDestinations = {CONFIG_MOUNT}`
      before `runGuard`; a resolver failure throws `GuardUnavailableException` (D4).
      Verify: `EgressGuardSpec` — inspect precedes run; failure path names the guard
      container and issues no run.
- [x] 2.5 Old-way sweep and exemption pin: `grep -rn '"run"' sandbox/docker/src/main`
      lists exactly `runGuard`, `runContainer`, `seedClone`; the first two take the
      value, `seedClone` is exempt (D6). Verify: `DockerCommandsSpec` pins that
      `seedClone` argv starts `['run', '--rm', ...]`; report the grep and its three
      hits in the task's report per `implementation.md`.

## 3. Observability (NFR-O1, UX1, D7)

- [x] 3.1 Extend the "container environment {} created for branch {} (image {})" INFO
      line with `; declared volumes made ephemeral: {}` using `describe()`; add the
      DEBUG equivalent in `EgressGuard.create`. Verify: `ContainerTaskExecutionEnvironmentUnitSpec`
      log capture asserts the anchor names both paths for a two-path image and
      `none` for an image with no declared volumes; `LogContractGateSpec` stays
      green (no code on INFO/DEBUG).

## 4. Identity spec on the real medium (FR5, M1, G1)

- [x] 4.1 Add the fixture image `gnomish-sandbox-volume-test` beside `GitSandboxImage`
      (same JVM-memoized builder): the git-capable image plus a marker file baked
      at `/cache/baked.txt` and then `VOLUME ["/cache", "/gnomish/work"]` — one
      path outside and one equal to the working copy, the outside one carrying
      image content so the "hidden under tmpfs" behaviour is observable.
      Verify: the image builds in the Docker-gated suite's `setupSpec`.
- [x] 4.2 Add `ContainerDeclaredVolumesSpec` in `adapters/git` (Docker-gated,
      `@IgnoreIf` like its siblings): snapshot `docker volume ls -q`, materialize
      from the fixture image (its `/cache` owned by the image's non-root user, as a
      real image would arrange a path it means its user to write), `exec` a write
      into `/cache` (succeeds only because of `tmpfs-mode=1777` — the runtime mounts
      the tmpfs `root:root`, D5; this assertion is the runc-floor gate), assert
      `/cache` is a tmpfs mount inside the box
      (`mount | grep /cache`), assert `/cache/baked.txt` is absent (image content
      under a declared path is hidden — the spec scenario "Image content under a
      declared path is not visible in the box"), assert the working copy is still
      the named factory volume (not tmpfs), dispose, and assert the volume-name
      set equals the snapshot. Verify: spec green against a live daemon; add it to
      the module's `excludedTestClasses` with the out-of-process rationale per
      `testing.md`.
- [x] 4.3 In the same spec, a second feature over the default guard image
      (`SandboxProperties.DEFAULT_GUARD_IMAGE`): create and dispose a guard,
      assert the anonymous-volume set is unchanged. Verify: green on a live
      daemon; this is the regression the change was opened for.
- [x] 4.4 Measure M1: run `:adapters:git:test --tests '*Container*'` and the three
      bootstrap E2E specs named in M1 of the proposal
      (`ContainerModePipelineE2ESpec`, `TakeContainerLifecycleE2ESpec`,
      `ContainerLifecycleCoverageGapsE2ESpec`) with `docker volume ls -qf
      dangling=true | wc -l` before and after; record both numbers in the task
      report (expected: equal).

## 5. Documentation and glossary (FR6, UX2, M2)

- [x] 5.1 `docs/glossary.md`, Sandbox section: add **Declared volume** — a path an
      image's Dockerfile marks with `VOLUME`; the factory makes every such path
      ephemeral unless it mounts it explicitly. Verify: entry present, wording
      matches the spec.
- [x] 5.2 `docs/guides/operator-guide-sandbox.md`: a short section stating that
      declared volumes are ephemeral, empty and bounded (64 MB), why (no anonymous
      volumes), that content the image ships under a declared path is not visible
      in the box, that the path is `root`-owned, world-writable with the sticky bit
      (`1777`) and `noexec,nosuid,nodev` (so binaries the image kept there do not
      run), that runc ≥ 1.1.8 is required for the mode to take effect, the two
      alternatives for content that must persist or ship with
      the image (bake it under a path the image does not declare; keep it under
      the working copy), and the `docker volume ls -qf dangling=true` one-liner
      with the "may include other projects" warning.
      Verify: section present; `docs/examples/sandbox-image/README.md` cross-links
      it.
- [x] 5.3 M2 gate (non-regression: the grep is already empty today, G2 keeps it so):
      `grep -rn "/home/mitmproxy\|\.mitmproxy" sandbox/docker/src/main` returns
      nothing; `grep -rn "runContainer(\|runGuard(" sandbox/docker/src/main`
      shows only the new signatures. Verify: both greps recorded in the report.

## 6. Build gates

- [x] 6.1 `./gradlew :sandbox:docker:check` green: Spotless, Error Prone/NullAway,
      JaCoCo, PIT at 100% for the new class (the resolver's daemon hand-off is a
      decision-free line covered by 4.2 — if PIT reports an unkillable
      "call removed" mutant there, apply `@DoNotMutate` with the out-of-process
      rationale naming `ContainerDeclaredVolumesSpec`; otherwise no exemption).
- [x] 6.2 `./gradlew :adapters:git:test :bootstrap:test --tests '*Container*'`
      green against a live daemon, then recommend the commit message per
      `process-invariants.md`.
