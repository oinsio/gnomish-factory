# Tasks: fix-image-declared-volumes

Sequencing: implement and sync **before** `add-sandbox-hardening` (its
provisioning snapshots inherit base-image `VOLUME` declarations and its guard
interception mode assumes an ephemeral mitmproxy confdir — both are covered
only once this change is in). No other active change touches
`GuardCommands`, `DockerCommands` or `ContainerMaterializer`.

## 1. The owner and its value type (FR1, FR2, FR3, NFR-R1, NFR-R2, D2, D4, D5)

- [ ] 1.1 Add `DeclaredVolumeOverrides` (value type) in `sandbox/docker`: immutable
      ordered path list plus the fixed `64m` bound, `argv()` rendering one
      `--mount type=tmpfs,destination=<p>,tmpfs-size=64m` per path, an `isEmpty()`
      and a `describe()` for the log line (`none` when empty). Verify:
      `DeclaredVolumeOverridesSpec` — rendering for zero, one and two paths;
      ordering is the image's; `describe()` for both states.
- [ ] 1.2 Add the static resolver `DeclaredVolumeOverrides.resolve(DockerCli, String
      image, Set<String> explicitDestinations)`: runs `image inspect -f '{{json
      .Config.Volumes}}' <image>`, parses `null`/`{}`/`{"/p":{}}` with the module's
      Jackson, subtracts `explicitDestinations`, fails on non-zero exit or any other
      shape with an exception carrying the image reference and the runtime's stderr
      (through `LogText`, per `logging.md`). Verify: `DeclaredVolumeOverridesSpec`
      against a fake `DockerCli` — data table over `null`, `{}`, one path, two paths,
      declared path equal to an explicit destination (subtracted), non-zero exit
      (throws), malformed JSON (throws). No daemon.
- [ ] 1.3 Add the inspect argv builder to `DockerCommands` (`inspectImageVolumes(image)`)
      so the resolver issues no literal argv. Verify: `DockerCommandsSpec` pins the
      exact argv.

## 2. Wire every `run` builder and call site (FR3, FR4, D3, D6, single-owner table)

- [ ] 2.1 Introduce the `ContainerRunSpec` parameter object (key, image, runtime,
      limits, enforceDiskQuota, workingCopy, ownership, overrides) and change
      `DockerCommands.runContainer` to take it, appending `overrides.argv()` after
      the working-copy `-v`/`-w` pair. Delete the old seven-argument signature.
      Verify: `DockerCommandsSpec` "FR3 run container" features updated to pass the
      spec object; a new feature asserts two override fragments appear after the
      working-copy mount and none when the value is empty; the module compiles with
      no other caller of the old signature (`grep -rn "runContainer(" sandbox`).
- [ ] 2.2 Change `GuardCommands.runGuard` to take `DeclaredVolumeOverrides` and append
      its fragments after the `:ro` config mount; delete the old signature. Verify:
      `GuardCommandsSpec` (or the existing guard argv spec) asserts the fragment
      position and the unchanged mitmdump tail.
- [ ] 2.3 `ContainerMaterializer.create`: resolve the overrides via the owner with
      `explicitDestinations = {WORKING_COPY}` immediately before building the run
      argv, wrapped so a resolver failure surfaces through `management(...)`'s
      naming convention (container name in the message, D4). Verify:
      `ContainerTaskExecutionEnvironmentUnitSpec` (fake `DockerCli`, the existing materialize coverage) — the inspect call precedes the
      run call; the run argv carries the fragments; a failing inspect throws the
      infrastructure-failure exception naming `gnomish-box-<key>` and issues no run.
- [ ] 2.4 `EgressGuard.create`: resolve with `explicitDestinations = {CONFIG_MOUNT}`
      before `runGuard`; a resolver failure throws `GuardUnavailableException` (D4).
      Verify: `EgressGuardSpec` — inspect precedes run; failure path names the guard
      container and issues no run.
- [ ] 2.5 Old-way sweep and exemption pin: `grep -rn '"run"' sandbox/docker/src/main`
      lists exactly `runGuard`, `runContainer`, `seedClone`; the first two take the
      value, `seedClone` is exempt (D6). Verify: `DockerCommandsSpec` pins that
      `seedClone` argv starts `['run', '--rm', ...]`; report the grep and its three
      hits in the task's report per `implementation.md`.

## 3. Observability (NFR-O1, UX1, D7)

- [ ] 3.1 Extend the "container environment {} created for branch {} (image {})" INFO
      line with `; declared volumes made ephemeral: {}` using `describe()`; add the
      DEBUG equivalent in `EgressGuard.create`. Verify: `ContainerTaskExecutionEnvironmentUnitSpec`
      log capture asserts the anchor names both paths for a two-path image and
      `none` for an image with no declared volumes; `LogContractGateSpec` stays
      green (no code on INFO/DEBUG).

## 4. Identity spec on the real medium (FR5, M1, G1)

- [ ] 4.1 Add the fixture image `gnomish-sandbox-volume-test` beside `GitSandboxImage`
      (same JVM-memoized builder): the git-capable image plus `VOLUME ["/cache",
      "/gnomish/work"]` — one path outside and one equal to the working copy.
      Verify: the image builds in the Docker-gated suite's `setupSpec`.
- [ ] 4.2 Add `ContainerDeclaredVolumesSpec` in `adapters/git` (Docker-gated,
      `@IgnoreIf` like its siblings): snapshot `docker volume ls -q`, materialize
      from the fixture image, `exec` a write into `/cache` (succeeds) and assert
      `/cache` is a tmpfs mount inside the box (`mount | grep /cache`), assert the
      working copy is still the named factory volume (not tmpfs), dispose, and
      assert the volume-name set equals the snapshot. Verify: spec green against a
      live daemon; add it to the module's `excludedTestClasses` with the
      out-of-process rationale per `testing.md`.
- [ ] 4.3 In the same spec, a second feature over the default guard image
      (`SandboxProperties.DEFAULT_GUARD_IMAGE`): create and dispose a guard,
      assert the anonymous-volume set is unchanged. Verify: green on a live
      daemon; this is the regression the change was opened for.
- [ ] 4.4 Measure M1: run `:adapters:git:test --tests '*Container*'` and the three
      bootstrap E2E specs named in the proposal with `docker volume ls -qf
      dangling=true | wc -l` before and after; record both numbers in the task
      report (expected: equal).

## 5. Documentation and glossary (FR6, UX2, M2)

- [ ] 5.1 `docs/glossary.md`, Sandbox section: add **Declared volume** — a path an
      image's Dockerfile marks with `VOLUME`; the factory makes every such path
      ephemeral unless it mounts it explicitly. Verify: entry present, wording
      matches the spec.
- [ ] 5.2 `docs/guides/operator-guide-sandbox.md`: a short section stating that
      declared volumes are ephemeral and bounded (64 MB), why (no anonymous
      volumes), the two alternatives for content that must persist (bake into the
      image; keep under the working copy), and the `docker volume ls -qf
      dangling=true` one-liner with the "may include other projects" warning.
      Verify: section present; `docs/examples/sandbox-image/README.md` cross-links
      it.
- [ ] 5.3 M2 gate: `grep -rn "/home/mitmproxy\|\.mitmproxy" sandbox/docker/src/main`
      returns nothing; `grep -rn "runContainer(\|runGuard(" sandbox/docker/src/main`
      shows only the new signatures. Verify: both greps recorded in the report.

## 6. Build gates

- [ ] 6.1 `./gradlew :sandbox:docker:check` green: Spotless, Error Prone/NullAway,
      JaCoCo, PIT at 100% for the new class (the resolver's daemon hand-off is a
      decision-free line covered by 4.2 — if PIT reports an unkillable
      "call removed" mutant there, apply `@DoNotMutate` with the out-of-process
      rationale naming `ContainerDeclaredVolumesSpec`; otherwise no exemption).
- [ ] 6.2 `./gradlew :adapters:git:test :bootstrap:test --tests '*Container*'`
      green against a live daemon, then recommend the commit message per
      `process-invariants.md`.
