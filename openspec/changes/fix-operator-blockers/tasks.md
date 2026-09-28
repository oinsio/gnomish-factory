# Tasks

Independent of the other active changes; if `remove-interactive-console` or
`make-run-headless` lands first, §5 updates the accepted-option constants of
`ServeArgumentsParser` / `RunArgumentsParser` to match (design, Risks).
`remove-interactive-console` also edits `TakeSlotRunner`'s javadoc; §7 is a
field-level edit and merges with it textually. Root
`check` is green after every section.

## 1. Reproduce before fixing

- [ ] 1.1 Red specs first (TDD): `AgentCommandLineSpec` asserting
      `--permission-mode acceptEdits` + `--strict-mcp-config` for the executor
      and `--permission-mode dontAsk` + `--strict-mcp-config` for the judge
      (FR1–FR3); a `BindingPropertiesBindingSpec` binding
      `factory.bindings.default=host` through a real Spring `Binder` over a
      `MapConfigurationPropertySource` (FR6, M4); a
      `CliArgumentsContractSpec` over `Subcommand.values()` feeding an unknown
      option and a relative `--dir` (FR7, FR8, M2). Verify all three fail for
      the reason the proposal states, and record in the task report what the
      relative `--dir` actually broke in git mode (proposal Q2).

## 2. Permission mode and MCP exclusion (design D1, D2)

- [ ] 2.1 Add package-private `AgentRole` (`EXECUTOR` → `acceptEdits`,
      `JUDGE` → `dontAsk`) in `adapters/agent`; `AgentCommandLine.fromRenderedFlags`
      takes the role and emits `--permission-mode <token>` and
      `--strict-mcp-config` beside the transport flags; class javadoc cites
      FR1–FR4, NFR-C1 of fix-operator-blockers. Verify `AgentCommandLineSpec` iterates
      `AgentRole.values()` and asserts no argv ever contains
      `bypassPermissions` (NFR-S1); PIT 100% on both classes.
- [ ] 2.2 Pass the role from `ExecutorRoundExecution` and
      `JudgeRoundExecution`; delete the role-less `AgentInvocationOptions.render`
      and migrate its specs to the role-specific renderers. Verify
      `grep -rn "AgentInvocationOptions.render(" */src` returns nothing and
      the existing executor/judge specs pass.
- [ ] 2.3 Wire the fake agent's `GNOMISH_FAKE_CAPTURE_ARGV` hook (present in
      `test-fixtures/src/main/resources/fake-agent/fake-agent.sh`, used by no
      spec today) into the `:bootstrap` E2E specs: host mode through the
      fake-agent lifecycle base (`TakeLifecycleReadyToDeliveredSpecBase`),
      container mode through `ContainerModePipelineE2ESpec`, where the capture
      file lives under the bind-mounted working copy and is read back on the
      host after the round (design, Risks). One executor round and one judge
      vote per mode, asserting the four tokens per role (M1); an empty capture
      file fails the feature. Verify the spec fails with 2.1 reverted.
- [ ] 2.4 Pin FR4, which already holds: `AgentSettingsValidator` rejects every
      unrecognized key, so a `permissionMode` key in a stage's `settings` fails
      startup today (spec scenario "Manifest cannot set the permission mode").
      No production edit; add the `AgentSettingsValidatorSpec` row so the
      guarantee cannot be loosened silently. Verify the row is green and cites
      FR4 of fix-operator-blockers.

## 3. Agent credentials through the AI seam (design D3)

- [ ] 3.1 Add `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY` to
      `AgentAiSeam.NAMES`; update its javadoc (FR5). Verify `AgentAiSeamSpec`
      iterates `NAMES` for present/absent values. Add
      `AgentCredentialSeamBoundarySpec` to `:bootstrap` (the module that owns
      source scans, `ClaimlessGitBoundarySpec` precedent): no production file
      under `adapters/` or `sandbox/` other than `AgentAiSeam` spells
      `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`, and the scan asserts it
      visited both trees (single-owner row 2).
- [ ] 3.2 Container-mode spec: with the token in the factory environment and
      an empty `env-passthrough`, the agent round's in-box environment holds
      it and a following `command` check's does not (NFR-S3; spec scenarios
      "Subscription token reaches the box…" and "Agent credential stays out of
      command checks"). Verify red with 3.1 reverted.

## 4. Documented default-binding key (design D4)

- [ ] 4.1 Turn `BindingProperties`'s explicit constructor into a compact
      canonical constructor keeping the `stages` defaulting. Verify
      `BindingPropertiesBindingSpec` (1.1) passes, and that
      `factory.bindings.default-binding` no longer binds (one extra row, so the
      migration note in design is pinned).

## 5. One argument owner (design D5)

- [ ] 5.1 Add `ArgumentsParsingSupport.projectDir(args)` (absolute +
      normalized, working directory when absent) and its sibling
      `requiredProjectDir(args)` (same resolution, the existing "`--dir` is
      required" usage error when absent); route `run`, `take`, `serve`,
      `board`, `dashboard` through the first and `status`, `usage` through the
      second, so no subcommand's requirement set changes (design D5); add a
      compact-constructor absoluteness check to the `dir` component of
      `RunArguments`, `TakeArguments`, `ServeArguments`, `StatusArguments`,
      `BoardArguments`, `DashboardArguments`, `UsageArguments` (FR7). Verify
      `grep -n "Path.of(" application/src/main/java/com/github/oinsio/gnomish/app/*ArgumentsParser.java`
      shows no `--dir` read, the relative-dir rows of
      `CliArgumentsContractSpec` pass, and the "Status still requires the
      directory" scenario has a row for `status` and `usage`.
- [ ] 5.2 Add `ArgumentsParsingSupport.rejectUnknownOptions(args, subcommand,
      accepted, positionalHints)`; each parser declares its accepted set as one
      constant (`take`/`serve` keep their run-only flags as known so their
      specific refusals still win) and calls it first (FR8, NFR-R1, NFR-O1,
      UX2). Verify `CliArgumentsContractSpec`: unknown option rejected for
      every `Subcommand`, dotted options and Spring Boot's `--debug`/`--trace`
      pass, the `status --task` message names the positional task id.
- [ ] 5.3 Pin "rejected before side effects": a `take` invocation with an
      unknown option makes no tracker call (in-memory tracker records none)
      and creates no branch. Verify with a spec in the `take` command's suite.
- [ ] 5.4 Single-owner sweep report (`implementation.md`): list the grep run
      for `Path.of(` over the parsers and for `containsOption`/`getOptionNames`
      over `app/`, each hit and what happened to it; attach to the task report.

## 6. Documentation and real-CLI verification (design D6)

- [ ] 6.1 `operator-guide-sandbox.md`: new "Agent authentication in the box"
      section (token variables, `claude setup-token`, provider host on the
      egress allowlist, no passthrough needed, passthrough widens reach);
      `docs/examples/sandbox-image/README.md`: matching paragraph;
      `operator-guide.md`: deliver-stage `gh` note recommending a fine-grained
      contents + pull-requests token under `GH_TOKEN`;
      `operator-guide-dashboard.md`, `operator-guide.md` (the `board` example,
      line ~497) and `operator-guide-run.md` (the resume line, ~80): examples in
      `--key=value` form; `docs/reference-pipelines.md`: drop the "known gaps"
      bullet naming the `gnomish`/`claude-gnome` wrappers, keep the `GH_TOKEN`
      bullet; remove any other agent-CLI wrapper advice under `docs/` (FR9,
      UX1, UX3). Verify
      `git grep -n -E -- "gnomish [a-z]+ .*--(out|dir|task|resume|slots|limit) [^-=\`]" -- docs README.md`
      is empty and `grep -rn -i "wrapper" docs` shows no agent-CLI wrapper
      advice (the Gradle-wrapper and ssh-wrapper mentions in the developer and
      run guides stay).
- [ ] 6.2 Paid smoke layer (real CLI, excluded from `check`): one executor
      round edits a file with the stock `claude` binary; one judge vote whose
      prompt invites a sub-agent — record whether `dontAsk` denied it
      (proposal Q1, design Risks), whether the judge round prompted for
      anything at all (design D1, the rejected `--permission-prompts none`),
      and whether a duplicated `--permission-mode` honours the last value
      (design, Migration Plan). Verify the run log is
      attached to the task report; a denied edit fails the task.

## 7. Safe publication of the slot runner's late sinks (design D7)

- [ ] 7.1 Make `TakeSlotRunner`'s `drainReport`, `ledgerWriter` and
      `runSummaryAccumulator` fields `volatile`; the class javadoc names the
      forcing reason per field (the assembly cycle for the ledger writer, the
      drain path for the other two) and cites FR10, NFR-R2 of
      fix-operator-blockers. Verify
      `grep -nE "private (volatile )?@Nullable (DrainReport|TaskOutcomeLedgerWriter|RunSummaryAccumulator)" application/src/main/java/com/github/oinsio/gnomish/app/serve/TakeSlotRunner.java`
      shows three `volatile` lines, and `ServeRuntimeWiringSpec` ("a delivered
      task leaves its taskOutcome line in the ledger"), `ServeShutdownWiringSpec`
      and `TakeSlotRunnerSpec` pass unchanged. No new spec: a missing
      happens-before edge cannot be made to fail deterministically in a
      unit spec, and the ledger-line effect is already pinned end to end.

## 8. An interrupt is a stop, not an outage (design D8, D9)

- [ ] 8.1 Red specs first (TDD), each verified to fail for the reason the
      proposal states (FR11, FR12, NFR-R3, NFR-O2, M5):
      `GithubHttpClientSpec` — WireMock with a fixed response delay, the
      calling thread interrupted mid-request: WireMock records exactly one
      request, the failure is `GithubCallInterruptedException` (not
      `GithubHttpException`), the thread's interrupt is set;
      a `GithubTracker` write interrupted the same way does not surface a
      `TrackerUnavailableException`;
      `GithubWorkflowRunPollSpec` — a workflow-runs query that throws
      `GithubCallInterruptedException`: `poll` propagates it, returns no
      `CannotVerify`, and writes no outcome line; control row with
      `GithubHttpException` still yields `CannotVerify`;
      `ReaperSpec` — a tracker stub that sets the interrupt and throws: no
      WARN captured, `forgetAll` and `onListingFailed` not called; control
      row without the interrupt keeps the `REAPER_SWEEP_LISTING_FAILED` WARN;
      `FinishedDeclineSpec` — two finished tasks, the first decline throws
      with the interrupt set: no WARN, the second decline never called;
      `TrackerHealthTrackerSpec` — an interrupted failure leaves
      `consecutiveFailures` unchanged and is rethrown unchanged; control row
      counts it;
      `HeartbeatBeaterSpec` — a `heartbeat` that throws with the interrupt set
      returns `UNCONFIRMED` with no WARN and no suppressor bookkeeping; control
      row keeps the `HEARTBEAT_BEAT_FAILED` WARN.
- [ ] 8.2 Adapter side (D8): add `GithubCallInterruptedException` to the
      shared HTTP core (javadoc cites FR11 of fix-operator-blockers); `doSend`
      throws it with the interrupt restored, `send` rethrows it unwrapped;
      `GithubWorkflowRunPoll.poll` keeps catching only `GithubHttpException |
      GithubWorkflowRunInfrastructureException`, so the cancellation propagates
      (design D8) — its javadoc says so; update `GithubRetryConfig` and
      `package-info` javadoc. Verify the 8.1
      adapter rows pass, the existing retry rows of `GithubHttpClientSpec`
      pass unchanged, and `onInterrupted` keeps its `@DoNotMutate` reason or
      loses the annotation if the new spec kills its mutants (PIT 100%).
- [ ] 8.3 Catch sites (D9): `Reaper.reapOnce`, `FinishedDecline.declineObserved`,
      `TrackerHealthTracker.call` and `HeartbeatBeater.beat` read their
      thread's interrupt in the failure branch. Verify the 8.1 rows pass; then
      the single-owner sweep (`implementation.md`):
      `git grep -n "catch (RuntimeException" -- 'application/src/main' 'gnomish-plugin-api/src/main'`,
      each hit that wraps a tracker call on a thread the stop interrupts
      (feed, reaper, heartbeat) listed with its outcome — routed, already
      compliant (`FeedOutageRetry`), or out of reach of the stop with the
      reason; `InstanceHeartbeat.tickGuarded` is answered explicitly (design,
      Risks).
- [ ] 8.4 The observed stop, end to end on a real thread: extend
      `StandingReaperResilienceSpec` — a tracker whose `listOpen` blocks on a
      latch and honours the interrupt the way the GitHub adapter does;
      `stop()` while the tick waits; assert the worker ends and no WARN or
      ERROR line was captured (U5, G4). Verify red with 8.3's `Reaper` edit
      reverted.

## 9. Wrap-up

- [ ] 9.1 Traceability: every FR/NFR/UX of the proposal is named by at least
      one spec or javadoc (`traceability.md`). Verify with
      `grep -rn "of fix-operator-blockers" --include='*.java' --include='*.groovy' .`
      against the proposal's ID list. Includes FR11, FR12, NFR-R3, NFR-O2
      of §8.
- [ ] 9.2 Root `./gradlew check` green (PIT 100% on touched classes). Verify
      the command's exit code.
- [ ] 9.3 Operator-stand acceptance (M3, human step): on the reference stand,
      delete the agent-CLI wrapper, drop the `agent-cli-binary` and binding
      workarounds from its launcher, run one task in host mode and one in
      container mode. Verify both reach `Finished` or a quality escalation,
      never a permission-denied round.
      Then start `serve` idle and stop it with Ctrl+C: verify the log after
      the serve-stopping anchor holds no WARN, ERROR or stack trace (G4).
