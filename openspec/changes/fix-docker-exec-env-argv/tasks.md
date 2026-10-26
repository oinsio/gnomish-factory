# Tasks

Groups 1–3 are sequential (each changes a signature the next one consumes). Group 4 is the
whole-tree gate, group 5 the documents, group 6 the integration check and the final gate. Every
task is TDD: the spec named in it goes red first. Per task, verification follows
`verification-scope.md`: the touched modules' `spotlessApply` and `compileTestGroovy`, the named
specs, and `pitestVerifyAllKilled -PpitScope=<fqcn,…>` over the task's own production classes; the
root `check` runs once, in 6.2. Scenario markers (`// Scenario: sandbox/execution-environment /
<title>`) are written with the test that exercises each scenario. No task commits
(`process-invariants.md`).

## 1. Names in, argv out (design D1)

- [x] 1.1 Invert `DockerCommandsSpec` "FR4: exec sets the workdir, per-entry env, and container,
      then appends the argv" (FR1, NFR-S1, M1): the builder is called as `exec(KEY,
      '/gnomish/work', <name set>, StdinSource.NONE, argv)` and the assertion becomes "the elements
      after each `-e` are exactly the names, in order, and no argv element contains `=`"; add a
      data-driven feature over an empty set, a single name, and a credential-shaped name
      (`ANTHROPIC_AUTH_TOKEN`) asserting the same. The two features carry the markers for
      "No environment value on any spawn argv" and "Process table during a round shows names
      only" (the composed argv is the command line `ps` shows). Verify: red against the current
      builder.
- [x] 1.2 Change `DockerCommands.exec` to `exec(String key, String workdir, Set<String> envNames,
      StdinSource stdin, List<String> argv)` and render `-e NAME` per name, keeping the `-i` rule
      of D20; update its javadoc (the value travels in the client environment, D1). Verify: 1.1
      green; the compile error at the three call sites (`ContainerTaskExecutionEnvironment.exec`,
      `ContainerFileChannel.run`, `ContainerMaterializer`'s scratch `mkdir`) is the expected
      hand-off to group 2 — leave them red until 2.x.

## 2. One launch value, one producer (design D2, D4)

- [x] 2.1 TDD (red first) `DockerExecLaunchIdentitySpec` in `:sandbox:docker` (FR3, NFR-S4, NFR-R1,
      marker "Names and values cannot diverge"): over a data table of composed maps — empty, one
      entry, several entries in insertion order, a credential-shaped entry — the names after `-e`
      in `launch.argv()` equal the map's key set in order, `launch.environment()` equals the map,
      and `launch.withheld()` equals the set the producer was built with; the empty map yields no
      `-e` at all and no error. Verify: red.
- [x] 2.2 TDD (red first) a names-only accessor `ChildEnvAllowlist.credentialNames()` returning an
      unmodifiable `Set<String>` in `:sandbox:core` (D2): a feature of `ChildEnvAllowlistSpec`
      asserts it returns exactly the declared names of `of(...)` plus `declaring(...)`, and that
      the class still exposes no accessor for its value source. Then implement `DockerExecLaunch`
      (record: `argv`, `environment`, `withheld`; defensive copies in the compact constructor) and
      `ContainerExecLaunches` (instance: `key`, `workdir`, `withheld`; one method
      `launch(Map<String,String> env, StdinSource stdin, List<String> argv)` that calls
      `DockerCommands.exec(key, workdir, env.keySet(), stdin, argv)` and pairs it with the map).
      Verify: 2.1 and the allowlist feature green.
- [x] 2.3 Replace `DockerCli.start(List<String>, StderrPolicy, StdinSource)` with
      `start(DockerExecLaunch, StderrPolicy, StdinSource)` — the old overload is deleted, not
      deprecated (NFR-S4, M3). `ContainerTaskExecutionEnvironment`'s constructor builds one
      `ContainerExecLaunches` from `key`, `WORKING_COPY` and `allowlist.credentialNames()` (no new
      constructor parameter; the constructor is `(BoxEquipment, String key, BoxGitLink,
      ChildEnvAllowlist, String projectId)`) and uses it in `exec`; `ContainerFileChannel`'s record
      takes the launches instance in place of `key` and builds its two execs through it (FR3,
      FR5). The materializer's scratch `mkdir` stays a management command through `docker.run`,
      its argv from `DockerCommands.exec(key, WORKING_COPY, Set.of(), StdinSource.NONE, …)` — the
      D4 exemption. Rewrite `DockerCliSpec`'s existing `start([...])` calls onto launches built
      with an empty map. Verify: `:sandbox:docker:compileJava` green; grep `DockerCommands.exec(`
      outside `ContainerExecLaunches` under `sandbox/*/src/main` — exactly one hit, the
      materializer's; `start(List` and `start(DockerCommands` under `sandbox/` — empty
      (single-owner table, old-way sweep — record the greps and their hits in the task report).
- [x] 2.4 Invert `ContainerTaskExecutionEnvironmentUnitSpec` "FR9: exec passes exactly the
      composed allowlist as --env entries, with an empty container base" (FR1, FR2, FR3, NFR-O1):
      the recording fake now records launches; assert the argv's `-e` names are exactly
      `JAVA_HOME` and `GNOMISH_FINDINGS_FILE`, the launch environment carries their values, the
      noise key is absent from both, and the captured log of the exec holds the applied names and
      no value of the composed map. Add a feature for the file channel — no `-e` on the argv,
      empty overlay (FR5, marker "Environment-free exec stays flag-free") — and one for the scratch
      `mkdir` — the argv the fake's `run` recorded holds no `-e` (FR5, marker "Scratch directory
      creation carries no environment flag"). Verify: all green; every other spec in the module
      unchanged and green.

## 3. Subtractive client environment (design D3, D5)

- [x] 3.1 TDD (red first) `DockerClientEnvironmentSpec` in `:sandbox:docker` (FR4, NFR-S2):
      given an inherited map holding a declared credential, a passthrough variable with a stale
      value, and an unrelated `DOCKER_HOST`, and a launch whose environment carries the
      passthrough variable with its live value and whose withheld set names the credential —
      `compose` yields a map without the credential, with the live passthrough value, with
      `DOCKER_HOST` unchanged; an empty launch removes the withheld names and nothing else; the
      input map is not mutated. Verify: red.
- [x] 3.2 Implement `DockerClientEnvironment.compose(Map<String,String> inherited,
      DockerExecLaunch launch)` (pure, package-private) and make `DockerCli.start` apply it to the
      builder's own `environment()` map — copy, compose, clear, put all — with no `System.getenv`
      call; `run` is untouched (D4). Verify: 3.1 green; `./gradlew :bootstrap:test --tests
      '*SecretsPortBoundarySpec'` green; `DockerCli.start` still holds no decision (javadoc names
      `DockerCliSpec` as its covering suite, per `testing.md` out-of-process delegation).
- [x] 3.3 Add the stand-in preset `[docker-record-env]` to
      `test-fixtures/src/main/resources/stand-in/tables/docker.params` — one row `* record
      GNOMISH_GITHUB_TOKEN JAVA_HOME DOCKER_HOST`, then `* exit 0` — and extend `DockerCliSpec`
      (FR2, FR4, NFR-S2, marker "Declared credential is subtracted from the client"): the spec
      builds `DockerCli` over `StandIn.recording(tempDir, 'docker-record-env')`, drives `start`
      with a launch whose environment holds `JAVA_HOME` and whose withheld set holds the tracker
      credential name spelled as a test constant (the credential-name boundary spec covers
      production sources only), and reads the link's log through `StandIn.log` — asserting
      `JAVA_HOME=[<the launch's value>]`, `GNOMISH_GITHUB_TOKEN=unset`, and `DOCKER_HOST` equal to
      the JVM's own (`unset` when the JVM has none, compared against `System.getenv` in the spec
      only). No shell text in the spec, no executable written (ADR 0015). Verify: green;
      `StandInLibrarySpec` and `StandInRowReachSpec` green with the new preset; the scoped PIT run
      over `DockerClientEnvironment`, `DockerExecLaunch`, `ContainerExecLaunches`,
      `DockerCommands` and `ChildEnvAllowlist` reports no surviving mutant (M5).
      *As built:* `TestEnvironmentHygiene` strips every `GNOMISH_*` from the test JVM, so
      `GNOMISH_GITHUB_TOKEN=unset` holds with or without the subtraction; the preset also records
      `DOCKER_CONFIG`, and a second feature withholds it (always set by `TestDockerEnvironment`,
      which sets `DOCKER_HOST` only when the context names an endpoint; asserted present first) and
      observes it `unset` — it kills the `env.clear()` and `env.putAll` mutants of `DockerCli.start`.

## 4. Build gate (design D6)

- [x] 4.1 TDD (red first, by planting a scratch `"-e"` literal in a second sandbox source and a
      scratch `new ProcessBuilder` in a third, then removing them) `DockerExecArgvBoundarySpec`
      in `:bootstrap` `architecture` (NFR-S1, FR6, M2, marker "The bare form is unconstructible"):
      scan production sources under `sandbox/` with comments stripped (`RepoSourceTree`), assert
      the scan reached the tree, that `"-e"` occurs only in `DockerCommands.java`, and that
      `new ProcessBuilder` occurs only in `DockerCli.java` and `HostTaskExecutionEnvironment.java`
      (the host adapter's site is FR6's evidence that it is unchanged); the javadoc names
      `ProcessSpawnBoundarySpec` as the gate `add-subprocess-access-log` FR13 later widens this
      into. Verify: red with the plants, green without; `./gradlew :bootstrap:test --tests
      '*DockerExecArgvBoundarySpec'` green.

## 5. Durable record and operator guide (design D7)

- [x] 5.1 Write `docs/adr/0017-secret-channels-to-child-processes.md` (Status accepted,
      2026-09-30, introduced by `fix-docker-exec-env-argv`): context (CWE-214, the four channels
      and who can read each — argv world-readable, environment owner-readable, a file
      permission-readable, a child's standard input a 0600 file in the launch owner's input
      directory for the launch window, never a pipe, per design D20 of
      `fix-terminal-receipt-convergence` and ADR 0018 — and the 2026-09-30 finding), decision
      (never argv; gnome-product child = composed three-layer environment; factory client =
      inherited minus declared credentials plus the exec overlay; management commands and the git
      client named as the queued extensions, with `GitInvocation.removedEnvironment()` as the git
      client's one place), consequences (residual owner-readable exposure stated; `--env-file` and
      in-box secret files rejected with reasons), and the enforcement (parameter types, the
      boundary scan). Verify: file exists; `design.md` D7 and the proposal reference it; no
      restatement of the ADR's text in either.
- [x] 5.2 Add the posture paragraph to `docs/guides/operator-guide-sandbox.md` "Environment
      passthrough" (UX2, UX3): values reach the box through the docker client's environment,
      never its command line; `ps` during a round shows names only; what the docker client keeps
      from the factory's environment and the one thing it loses (declared credential names).
      Verify: the section reads end-to-end without contradiction to the AI-seam setup paragraph
      that follows it.
- [x] 5.3 Add the glossary entry **Exec launch** under the sandbox bounded context (`docs/glossary.md`):
      the argv-plus-client-environment pair of one container exec, one value because the flag
      names are derived from the environment; type `DockerExecLaunch`. Verify: entry present;
      code names match (`DockerExecLaunch`, `ContainerExecLaunches`).

## 6. Integration check and final gate

- [x] 6.1 Extend and run the container-mode credential E2E, `ContainerModeAgentCredentialE2ESpec`
      (NFR-S3, FR2, M4): the credential-capturing agent binary still observes
      `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY` in the box and the command check still
      does not; add a passthrough name absent from the factory's environment and assert the agent
      observes it unset, with no error (marker "An absent name stays unset in the box"). Verify:
      green on a docker-equipped host (skips cleanly without one; then the result is recorded as
      not-run, not as passed).
- [x] 6.2 Final gate (M4, M5): one root `./gradlew check` — no `--tests`, no `-PpitScope` — green;
      report the PIT summary for `:sandbox:docker` and `:sandbox:core`. Then recommend the commit
      message (subject ≤ 72 chars, trailer with the change name and FR1–FR6).
