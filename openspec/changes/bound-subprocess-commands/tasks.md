## 0. Sequencing preconditions

- [ ] 0.1 Confirm `fix-lifecycle-push` is committed (it touches the same `adapters/git` region);
      this change's implementation starts only on top of it.

## 1. The `subprocess` module — red specs first

- [ ] 1.1 Create the `subprocess` leaf module: `settings.gradle`, build conventions, PIT wiring;
      zero dependencies — no Spring, no slf4j, no domain (design D9, FR9, NFR-S3, M5).
- [ ] 1.2 Supervisor spec, stall: a fake binary that holds stdout open forever returns within a
      short injected deadline with `TIMED_OUT` — wall clock under 2× the deadline (FR3, NFR-R1, M1).
- [ ] 1.3 Supervisor spec, tree kill: the fake binary spawns its own child; after a deadline kill
      neither survives, asserted via `ProcessHandle` (FR3, NFR-R2, G5, M2).
- [ ] 1.4 Supervisor spec, grace: a binary that exits promptly on SIGTERM is not force-killed and
      still reports `TIMED_OUT`; the grace and the re-snapshot are covered (design D3).
- [ ] 1.5 Supervisor spec, drains: >64 KiB on both streams completes with both captured in full;
      on the kill path the drain join is bounded — a straggler holding the pipe does not block the
      return (design D2, FR2).
- [ ] 1.6 Supervisor spec, interrupt: the package-private seam drives the interrupt path
      deterministically to `INTERRUPTED` with the tree killed (design D10, FR6).
- [ ] 1.7 Implement the primitive and the capture runner to green; document the module's neutrality
      contract in its `package-info` (design D9, D10).
- [ ] 1.8 Add the glossary entry for *subprocess supervisor* (`docs/glossary.md`), per the
      domain-terminology rule.

## 2. Git: bounded network commands on the shared mechanics

- [ ] 2.1 `GitProcessRunnerBoundedNetworkSpec` (red): a fake git that answers `rev-parse` instantly
      and stalls on `push`; `run` returns within a short injected deadline with `TIMED_OUT`; a
      local command against the same fake git is NOT bounded; normal-path exit code / stdout /
      stderr byte-identical (FR1, FR5, NFR-R3, M1).
- [ ] 2.2 Add `GitCommandResult.Termination { EXITED, TIMED_OUT, INTERRUPTED }` as a fourth record
      component with a 3-argument constructor defaulting to `EXITED`; every existing construction
      site and spec compiles untouched (design D6, NFR-R3).
- [ ] 2.3 Add the `isNetwork(args)` classifier — `fetch`, `push`, `ls-remote`, `clone`,
      `remote update` — sharing the `-c` skip with the mutation classifier; unit-spec the table
      (design D1, FR1).
- [ ] 2.4 Rebase `GitProcessRunner.execute` onto the capture runner: network commands carry the
      deadline, local ones do not; the stderr credential scrub stays the single choke point,
      including for partial output of a killed process (design D2, D10, FR2, NFR-S2).
- [ ] 2.5 Return `INTERRUPTED` (flag restored) instead of exit `-1` (design D6, FR6).
- [ ] 2.6 Prefix network invocations with `-c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60`;
      set `GIT_SSH_COMMAND` (`BatchMode=yes`, `ConnectTimeout=10`, `ServerAliveInterval=15`,
      `ServerAliveCountMax=4`) only when the operator has not set it; spec the injected argv and
      the do-not-clobber rule (design D5, FR4, NFR-S1).

## 3. Git callers stop confusing outcomes

- [ ] 3.1 `ParkDeliveryFenceSpec` (red): an interrupted push spends no re-attempt and yields no
      `origin is behind` note; a timed-out push spends no re-attempt and yields the note only
      after a confirming bounded re-check of the remote tip; an unanswerable re-check reports
      "could not be verified" (design D7, FR7, UX2, M3).
- [ ] 3.2 `RemoteAttemptDelivery`: map `INTERRUPTED` and `TIMED_OUT` to `CannotVerify`
      (infrastructure), never a quality failure; no re-attempt on either (design D7, FR7).
- [ ] 3.3 `BestEffortPush`, `LifecyclePush`, `PushBestEffort*` decorators: distinct WARN text per
      termination — a timeout names elapsed and configured deadline, an interrupt names
      interruption, neither logs `push failed`; none throw (FR8, NFR-O1, NFR-O2, UX3).
- [ ] 3.4 Grep the adapter for remaining `exitCode() != 0` branches that must consider
      `termination()`; fix or record why the plain test stays correct.

## 4. Docker and the execution environment

- [ ] 4.1 `DockerCli` stall spec (red): a fake docker binary that stalls on `run` returns within a
      short injected deadline as a distinct timed-out outcome; concurrent drains cover the >64 KiB
      case (design D11, FR10, M4).
- [ ] 4.2 Move `DockerCli.run` onto the capture runner with `factory.docker-command-timeout`;
      daemon-unreachable classification unchanged; interrupt is a named outcome (FR6, FR10).
- [ ] 4.3 Add `ExecHandle.Wait.Interrupted`; migrate `HostExecHandle` wait/kill onto the primitive —
      the timeout kill becomes a tree kill (red spec: a fake agent CLI spawns a child; after round
      timeout neither survives) (design D11, FR11, G5).
- [ ] 4.4 `ContainerFileChannel`: wait/kill through the primitive; interrupt is a named outcome,
      not `-1` (FR11).

## 5. Command checks bounded

- [ ] 5.1 Red spec: a `command` check that never exits returns within a short injected timeout as a
      quality failure whose findings carry the captured tail (design D12, FR12, UX4, M4).
- [ ] 5.2 `CommandProcessRunner`/`ShellCommandCheckRunner`: wait via `waitForExitOrTimeout` with
      `factory.check-command-timeout`; expiry classification and NFR-O1 check-id logging (FR12).

## 6. Remaining migrations

- [ ] 6.1 `GitExec` onto the primitive, behavior-preserving: stdout cap, stdin feed, hermetic env,
      interrupt exception contract unchanged; its interrupt-path spec moves to the supervisor's
      seam; `gitobjects` build gains its one dependency (design D9, D13, FR13, NFR-R3).
- [ ] 6.2 `RealProcessTreeKiller` onto the primitive's kill discipline, gaining the reap (D14, FR14).

## 7. Configuration and operator surface

- [ ] 7.1 Add `factory.git-network-timeout` (300 s), `factory.docker-command-timeout` (300 s),
      `factory.check-command-timeout` (30 min) to `FactoryProperties` — `Duration`, rejected when
      non-positive, javadoc and spec coverage mirroring `agentCliTailDrainGrace` (design D8, FR5).
- [ ] 7.2 Wire them at the composition-root construction sites (`ContainerRunSupportFactory`,
      `SandboxLifecyclePassFactory`, `ManualRunConfiguration`, check runner assembly).
- [ ] 7.3 Document all three in the installation-properties table of
      `docs/guides/operator-guide-run.md`: what each timeout WARN looks like, how to raise values
      on a slow link, and the sshd-alive-but-wedged-backend caveat of D5 (UX1, UX3, UX4).

## 8. Gates

- [ ] 8.1 `./gradlew check` full run green: `:subprocess`, `:gitobjects`, `:adapters:git`,
      `:sandbox:docker`, `:adapters`, `:application`, `:bootstrap` (M6).
- [ ] 8.2 Mutation score 100% in every touched module; no new `excludedClasses` /
      `excludedTestClasses`; the per-module timing-race `@DoNotMutate` copies
      (`GitProcessRunner.waitFor`, `DockerCli.waitFor`, `HostExecHandle`, `GitExec.await`) are
      deleted, replaced by the supervisor's one driven seam (M5, M6, `.claude/rules/testing.md`).
- [ ] 8.3 Dependency gates prove `:subprocess` depends on nothing (NFR-S3, M5).
- [ ] 8.4 Verify traceability: every FR/NFR/UX referenced by at least one code comment or spec
      description (`.claude/rules/traceability.md`).
