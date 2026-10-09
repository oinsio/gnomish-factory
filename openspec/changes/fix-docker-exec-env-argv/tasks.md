# Tasks

Groups 1–3 are sequential (each changes a signature the next one consumes). Group 4 is the
whole-tree gate, group 5 the documents, group 6 the integration check. Every task is TDD: the spec
named in it goes red first. No task commits (`process-invariants.md`).

## 1. Names in, argv out (design D1)

- [ ] 1.1 Invert `DockerCommandsSpec` "FR4: exec sets the workdir, per-entry env, and container,
      then appends the argv" (FR1, NFR-S1, M1): the builder is called with a name set and the
      assertion becomes "the elements after each `-e` are exactly the names, in order, and no
      argv element contains `=`"; add a data-driven feature over an empty set, a single name, and
      a credential-shaped name (`ANTHROPIC_AUTH_TOKEN`) asserting the same. Verify: red against
      the current builder.
- [ ] 1.2 Change `DockerCommands.exec` to take `Set<String> envNames` and render `-e NAME` per
      name; update its javadoc (the value travels in the client environment, D1). Verify: 1.1
      green; the compile error at the four call sites is the expected hand-off to group 2 — leave
      them red until 2.x.

## 2. One launch value, one producer (design D2)

- [ ] 2.1 TDD (red first) `DockerExecLaunchIdentitySpec` in `:sandbox:docker` (FR3, NFR-S4,
      spec scenario "Names and values cannot diverge"): over a data table of composed maps —
      empty, one entry, several entries in insertion order, a credential-shaped entry — the names
      after `-e` in `launch.argv()` equal the map's key set in order, `launch.environment()` equals
      the map, and `launch.withheld()` equals the set the producer was built with. Verify: red.
- [ ] 2.2 Implement `DockerExecLaunch` (record: `argv`, `environment`, `withheld`; defensive
      copies in the compact constructor) and `ContainerExecLaunches` (instance: `key`, `workdir`,
      `withheld`; one method `launch(Map<String,String> env, boolean interactive, List<String>
      argv)` that calls `DockerCommands.exec(key, workdir, env.keySet(), …)` and pairs it with the
      map). Verify: 2.1 green.
- [ ] 2.3 Replace `DockerCli.start(List<String>, boolean)` with `start(DockerExecLaunch, boolean)`
      — the old overload is deleted, not deprecated (NFR-S4, M3). Update `HostExecHandle`/
      `ContainerFileChannel`/`ContainerMaterializer`/`ContainerTaskExecutionEnvironment` call
      sites to build launches through one `ContainerExecLaunches` instance created in
      `ContainerTaskExecutionEnvironment`'s constructor from `docker`, `key`, `WORKING_COPY` and
      `allowlist.credentialNames()`, and handed to the channel and the materializer as a
      constructor field (FR3, FR5). Verify: `:sandbox:docker:compileJava` green; grep
      `DockerCommands.exec(` outside `ContainerExecLaunches` and `start(List` under `sandbox/`
      both empty (single-owner table, old-way sweep — record the grep and its hits in the task
      report).
- [ ] 2.4 Invert `ContainerTaskExecutionEnvironmentUnitSpec` "FR9: exec passes exactly the
      composed allowlist as --env entries, with an empty container base" (FR1, FR2, FR3): the
      recording fake now records launches; assert the argv's `-e` names are exactly
      `JAVA_HOME` and `GNOMISH_FINDINGS_FILE`, the launch environment carries their values, and
      the noise key is absent from both. Add a feature for the file channel and the scratch
      `mkdir`: no `-e` on the argv, empty overlay (FR5, spec scenario "Environment-free exec stays
      flag-free"). Verify: both green; every other spec in the module unchanged and green.

## 3. Subtractive client environment (design D3, D5)

- [ ] 3.1 TDD (red first) `DockerClientEnvironmentSpec` in `:sandbox:docker` (FR4, NFR-S2):
      given an inherited map holding a declared credential, a passthrough variable with a stale
      value, and an unrelated `DOCKER_HOST`, and a launch whose environment carries the
      passthrough variable with its live value and whose withheld set names the credential —
      `compose` yields a map without the credential, with the live passthrough value, with
      `DOCKER_HOST` unchanged; an empty launch removes the withheld names and nothing else; the
      input map is not mutated. Verify: red.
- [ ] 3.2 Implement `DockerClientEnvironment.compose(Map<String,String> inherited,
      DockerExecLaunch launch)` (pure, package-private) and make `DockerCli.start` clear the
      builder's `environment()` and fill it from `compose(System.getenv(), launch)`; `run` is
      untouched (D4). Verify: 3.1 green; `DockerCli.start` still holds no decision (javadoc names
      `DockerCliSpec` as its covering suite, per `testing.md` out-of-process delegation).
- [ ] 3.3 Extend `DockerCliSpec` (FR2, FR4, NFR-S2, spec scenario "Declared credential is
      subtracted from the client"): a fake docker script that prints `${GNOMISH_GITHUB_TOKEN:-unset}`,
      `${JAVA_HOME:-unset}` and `${DOCKER_HOST:-unset}`; the spec plants all three in the
      *spec's* launch/inherited inputs by driving `start` with a launch whose environment holds
      `JAVA_HOME` and whose withheld set holds the tracker credential name spelled as a test
      constant (the credential-name boundary spec covers production sources only) — assert the
      fake saw `JAVA_HOME`'s value, `unset` for the credential, and the JVM's own `DOCKER_HOST`
      (or `unset` when the JVM has none, asserted either way against `System.getenv`). Keep the
      "Kept in sync with FakeDockerBinary" marker intact. Verify: green; the module's PIT run
      reports no surviving mutant in `DockerClientEnvironment`, `DockerExecLaunch`,
      `ContainerExecLaunches` or `DockerCommands.exec` (M5).

## 4. Build gate (design D6)

- [ ] 4.1 TDD (red first, by planting a scratch `"-e"` literal in a second sandbox source and a
      scratch `new ProcessBuilder` in a third, then removing them) `DockerExecArgvBoundarySpec`
      in `:bootstrap` `architecture` (NFR-S1, M2, spec scenario "The bare form is
      unconstructible"): scan production sources under `sandbox/` with comments stripped
      (`RepoSourceTree`), assert the scan reached the tree, that `"-e"` occurs only in
      `DockerCommands.java`, and that `new ProcessBuilder` occurs only in `DockerCli.java` and
      `HostTaskExecutionEnvironment.java`; the javadoc names `ProcessSpawnBoundarySpec` as the
      gate `add-subprocess-access-log` FR13 later widens this into. Verify: red with the plants,
      green without; `./gradlew :bootstrap:test --tests '*DockerExecArgvBoundarySpec'` green.

## 5. Durable record and operator guide (design D7)

- [ ] 5.1 Write `docs/adr/0011-secret-channels-to-child-processes.md` (Status accepted,
      2026-09-30, introduced by `fix-docker-exec-env-argv`): context (CWE-214, the four channels
      and who can read each, the 2026-09-30 finding), decision (never argv; gnome-product child =
      composed three-layer environment; factory client = inherited minus declared credentials plus
      the exec overlay; management commands and the git client named as the queued extensions),
      consequences (residual owner-readable exposure stated; `--env-file` and in-box secret files
      rejected with reasons), and the enforcement (parameter types, the boundary scan). Verify:
      file exists; `design.md` D7 and the proposal reference it; no restatement of the ADR's text
      in either.
- [ ] 5.2 Add the posture paragraph to `docs/guides/operator-guide-sandbox.md` "Environment
      passthrough" (UX2, UX3): values reach the box through the docker client's environment,
      never its command line; `ps` during a round shows names only; what the docker client keeps
      from the factory's environment and the one thing it loses (declared credential names).
      Verify: the section reads end-to-end without contradiction to the AI-seam setup paragraph
      that follows it.
- [ ] 5.3 Add the glossary entry **Exec launch** under the sandbox bounded context (`docs/glossary.md`):
      the argv-plus-client-environment pair of one container exec, one value because the flag
      names are derived from the environment; type `DockerExecLaunch`. Verify: entry present;
      code names match (`DockerExecLaunch`, `ContainerExecLaunches`).

## 6. Integration check

- [ ] 6.1 Run the container-mode credential E2E, `ContainerModeAgentCredentialE2ESpec` (NFR-S3,
      M4): the credential-capturing agent binary still observes `CLAUDE_CODE_OAUTH_TOKEN` and
      `ANTHROPIC_API_KEY` in the box and the command check still does not. Verify: green on a
      docker-equipped host (skips cleanly without one; then the result is recorded as
      not-run, not as passed).
- [ ] 6.2 Run `./gradlew :sandbox:docker:check :sandbox:core:check :bootstrap:check` (M4, M5):
      spotless, Error Prone, specs, JaCoCo and PIT green; report the PIT summary for
      `:sandbox:docker`. Then recommend the commit message (subject ≤ 72 chars, trailer with the
      change name and FR1–FR6).
- [ ] 6.3 First release (M1, M3 of `add-release-pipeline`, human step; carried over from task 6.3
      of that change, which left it unverified): after the prerequisite changes are merged, push
      `v0.1.0` on a green `main`; a maintainer who wants a rehearsal first pushes a throwaway
      `v0.0.1-rc.1` on a fork and deletes it afterwards. Verify the release page holds the four
      assets and, per archive, a provenance and an SBOM attestation, and `gh attestation verify`
      passes for the tar; attach the run link to the task report.
