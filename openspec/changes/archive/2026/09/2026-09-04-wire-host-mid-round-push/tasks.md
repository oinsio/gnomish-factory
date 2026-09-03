# Tasks: wire-host-mid-round-push

## 1. Host round source seam (adapters/agent)

- [x] 1.1 Add the public factory for the host round source (D2, e.g.
      `CliStageExecutor.hostRounds(clock, childEnv)`) returning exactly what the host
      convenience constructor builds today, and route that constructor through it; verify:
      existing `adapters/agent` specs stay green and a new spec asserts the factory's rounds
      behave identically to the host constructor's (environment root, decision env fragment,
      no-op `roundListener`) — FR2.

## 2. Per-round push decorator (adapters/git)

- [x] 2.1 TDD the decorating `RoundEnvironmentSource` (D1, working name `MidRoundPushRounds`):
      delegates `openRound` and every `Round` method to the host source, overrides
      `roundListener()` with a fresh `MidRoundPushListener` per round built from the request
      (worktree root from `DirectoryWorkspace`, taskId from `TaskContext`, stage name, attempt
      as round, `TaskIdSanitizer.branchName`); verify: Spock spec on a local bare repo — a
      commit between two progress events is pushed, delegated methods pass through, and the
      wiring adds no git invocations beyond the listener's own per-event `rev-parse` (assert
      the runner's invocation count per progress event) — FR1, FR2, NFR-P1.
- [x] 2.2 Share one `RepeatSuppressor` across the decorator's rounds (D4) with the same
      rationale comment shape `SandboxRoundEnvironmentSource.harvestSuppressor` carries;
      verify: spec — a tip-resolution failure spanning two rounds logs one WARN edge, not one
      per round — NFR-O1.
- [x] 2.3 Listener-contract preservation spec at the decorator level: a failing `rev-parse`
      or failing push inside `onProgress` never throws out of the round and never changes the
      round's outcome; verify: spec green — NFR-R1.

## 3. Attachment point (application + bootstrap)

- [x] 3.1 Add `RunAssembly.withHostGitPush(UnaryOperator<RoundEnvironmentSource>)` (D3,
      mirroring `withSandbox`), realize it in `ManualRunAssembly` with a
      `UnaryOperator.identity()` default (extend `copyWith`), add the method to the
      `RunChainFakes` map fakes, and consume it in `ExecutorAdapterSelector` by applying the
      decoration unconditionally inside the `sandbox == null` branch (comment records that
      sandbox wins by construction); verify: bootstrap/application specs — decoration
      attached → the operator sees the real host rounds and the CLI executor is built over its
      return; decoration absent → the identity default, so the host CLI executor is built exactly
      as before — equivalence follows from the identity default plus `HostRoundsFactorySpec`,
      which pins the factory's rounds against the host constructor's — FR3.
- [x] 3.2 Grow `TaskGit` with the fourth `midRoundPush` component (identity default via a
      three-component convenience constructor, so existing construction sites stay untouched),
      build the real operator only in `ManualRunConfiguration.taskGit(...)` (decorator over the
      host rounds, `GitProcessRunner` from `factoryProperties.gitNetworkTimeout()` as sibling
      factories do), and attach it via `assembly.withHostGitPush(git.midRoundPush())` from the
      git-mode host control flows — `GitModeRunner.run`, `GitResumeContinuation`, and
      `TakeEngineExecution.run` (covering take fresh and resume in one place) — never from
      in-place mode; verify: specs per runner assert the attachment, and `ManualRunRunnerSpec`
      asserts that the shared assembly in-place mode assembles from still carries the identity
      decoration even when the injected `TaskGit` supplies a real one — so no `MidRoundPushRounds`
      and no listener is built outside git mode — FR1, FR3, M2.

## 4. Documentation and sync surfaces

- [x] 4.1 Replace `MidRoundPushListener`'s "section 4's job" javadoc sentence with the real
      wiring point (the decorator + attachment), keeping the `Kept in sync with` marker
      accurate; verify: `grep -rn "section 4" adapters/git/src/main` is empty — FR4.
- [x] 4.2 Confirm the manual-sync-pairs obligations recorded in design.md (no mirrored edits
      on the harvest side; host/container runner pairs gain only the host-side attachment);
      verify: `grep -rn "Kept in sync with" src/main` still enumerates both listener ends and
      `/review-artifacts`-style read of the registry finds no undeclared pair.

## 5. Integration verification

- [x] 5.1 Integration spec on a local bare remote (M1): git-mode host run, gnome commits
      mid-round via a scripted tool event, next progress event observed → remote tip equals
      the new commit before the round closes; verify: spec green.
- [x] 5.2 Healthy-run silence: the integration run of 5.1 with a reachable remote produces
      zero WARN/ERROR after startup; verify: log-capture assertion in the same spec — UX1.
- [x] 5.3 Run `./gradlew check` for every touched module (`adapters:agent`, `adapters:git`,
      `application`, `bootstrap`) including the 100% mutation gate; verify: BUILD SUCCESSFUL
      — M3.
