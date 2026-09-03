# Tasks: wire-host-mid-round-push

## 1. Host round source seam (adapters/agent)

- [ ] 1.1 Add the public factory for the host round source (D2, e.g.
      `CliStageExecutor.hostRounds(clock, childEnv)`) returning exactly what the host
      convenience constructor builds today, and route that constructor through it; verify:
      existing `adapters/agent` specs stay green and a new spec asserts the factory's rounds
      behave identically to the host constructor's (environment root, decision env fragment,
      no-op `roundListener`) — FR2.

## 2. Per-round push decorator (adapters/git)

- [ ] 2.1 TDD the decorating `RoundEnvironmentSource` (D1, working name `MidRoundPushRounds`):
      delegates `openRound` and every `Round` method to the host source, overrides
      `roundListener()` with a fresh `MidRoundPushListener` per round built from the request
      (worktree root from `DirectoryWorkspace`, taskId from `TaskContext`, stage name, attempt
      as round, `TaskIdSanitizer.branchName`); verify: Spock spec on a local bare repo — a
      commit between two progress events is pushed, delegated methods pass through, and the
      wiring adds no git invocations beyond the listener's own per-event `rev-parse` (assert
      the runner's invocation count per progress event) — FR1, FR2, NFR-P1.
- [ ] 2.2 Share one `RepeatSuppressor` across the decorator's rounds (D4) with the same
      rationale comment shape `SandboxRoundEnvironmentSource.harvestSuppressor` carries;
      verify: spec — a tip-resolution failure spanning two rounds logs one WARN edge, not one
      per round — NFR-O1.
- [ ] 2.3 Listener-contract preservation spec at the decorator level: a failing `rev-parse`
      or failing push inside `onProgress` never throws out of the round and never changes the
      round's outcome; verify: spec green — NFR-R1.

## 3. Attachment point (application + bootstrap)

- [ ] 3.1 Add the optional host-git piece to `RunAssembly` (D3, `withHostGitPush(...)`
      mirroring `withSandbox`), realize it in `ManualRunAssembly`, and consume it in
      `ExecutorAdapterSelector` only when `sandbox == null` (comment records that sandbox wins
      by construction); verify: bootstrap/application specs — piece attached → CLI executor
      built with the decorated rounds; piece absent → previous host construction byte-for-byte
      — FR3.
- [ ] 3.2 Build the piece in `bootstrap` (decorator over `hostRounds`, `GitProcessRunner` from
      `factoryProperties.gitNetworkTimeout()` as sibling factories do) and attach it from the
      git-mode host control flows — `GitModeRunner`, `GitResumeRunner`, and the host take
      execution — never from in-place mode; verify: specs per runner assert the attachment,
      and an in-place-mode spec asserts no piece and no push attempt — FR1, FR3, M2.

## 4. Documentation and sync surfaces

- [ ] 4.1 Replace `MidRoundPushListener`'s "section 4's job" javadoc sentence with the real
      wiring point (the decorator + attachment), keeping the `Kept in sync with` marker
      accurate; verify: `grep -rn "section 4" adapters/git/src/main` is empty — FR4.
- [ ] 4.2 Confirm the manual-sync-pairs obligations recorded in design.md (no mirrored edits
      on the harvest side; host/container runner pairs gain only the host-side attachment);
      verify: `grep -rn "Kept in sync with" src/main` still enumerates both listener ends and
      `/review-artifacts`-style read of the registry finds no undeclared pair.

## 5. Integration verification

- [ ] 5.1 Integration spec on a local bare remote (M1): git-mode host run, gnome commits
      mid-round via a scripted tool event, next progress event observed → remote tip equals
      the new commit before the round closes; verify: spec green.
- [ ] 5.2 Healthy-run silence: the integration run of 5.1 with a reachable remote produces
      zero WARN/ERROR after startup; verify: log-capture assertion in the same spec — UX1.
- [ ] 5.3 Run `./gradlew check` for every touched module (`adapters:agent`, `adapters:git`,
      `application`, `bootstrap`) including the 100% mutation gate; verify: BUILD SUCCESSFUL
      — M3.
