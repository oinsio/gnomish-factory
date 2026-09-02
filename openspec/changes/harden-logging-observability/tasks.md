# Tasks: harden-logging-observability

Sequenced after `harden-task-branch-contract` archives (Impact — same-file
overlap; the summary vocabulary is defined against post-harden `TakeResult`).

## 1. Policy and foundations

- [x] 1.1 Write `docs/adr/0004-logging-policy.md` (D1, FR1): level semantics,
      best-effort-must-log, one-failure-one-log, retention rationale, accepted
      deviations (domain port-failure logs — NG3, unstructured text log);
      verify: ADR reviewed against design D1 content, referenced by later tasks.
- [x] 1.2 Write `.claude/rules/logging.md` emitter checklist (FR1) with
      path-trigger for Java sources; verify: rule lists level table, throwable
      rule, untrusted-text rule, suppression rule, MDC rule.
- [x] 1.3 Add glossary entries (anchor line, canonical task summary, repeat
      suppression / edge logging, log text sanitization) to `docs/glossary.md`;
      the canonical-task-summary entry distinguishes the log-plane summary
      line from the ledger's `runSummary` record (D3 naming note); the
      log-text-sanitization entry distinguishes the log-line choke point
      from the plugin-boundary findings sanitizer (D5 — two trust
      boundaries, one shared character vocabulary); verify:
      each term used by later code/javadoc matches the entry.
- [x] 1.4 Create the `:logtext` leaf module (D5, module-layering delta):
      `LogText` (control/ANSI strip, newline flattening incl. `U+2028`/
      `U+2029`, length cap), `RepeatSuppressor` (keyed edge state, D4 API),
      `MdcAwareThread` helper (D10); slf4j-api only; unit specs for each
      incl. newline-forgery and cap scenarios; verify:
      `./gradlew :logtext:check` green, dependency gates pass with the new
      module in every consumer's allowed list.
- [x] 1.5 Declare the sanitizer pair (D5, D8): `Kept in sync with` markers on
      `LogText` and `FindingsSanitizer` (which stays self-contained in
      `gnomish-plugin-api` — its production code, public API, and japicmp
      baseline untouched), a registry row in
      `.claude/rules/manual-sync-pairs.md` naming the narrow invariant
      (ANSI/control stripping table + tail-cap semantics; newline handling
      deliberately differs), and a data-driven equivalence spec feeding one
      adversarial corpus (CR/LF, `U+2028`/`U+2029`, ANSI CSI/OSC, NUL, DEL,
      C1 range, overlong input) to both sanitizers, asserting equivalent
      neutralization of the shared subset (test scope only — no production
      edge); migrate `StatusLineFormatter`'s hand-rolled
      `strip(...)`-plus-flatten to `LogText` as its first in-place consumer;
      verify: corpus spec green against both ends, markers greppable at both
      ends, `gnomish-plugin-api` dependency set unchanged.

## 2. Logback configuration and test isolation

- [x] 2.1 Pin UTF-8 on all three encoders and add
      `${GNOMISH_LOG_LEVEL:-INFO}` root-level substitution (D7, FR10); extend
      `LogbackConfigSpec` to assert charset and the override; verify: spec
      green, DEBUG run needs no rebuild (`LoggingLevelSpec`-style assertion).
- [x] 2.2 Wrap FILE in `AsyncAppender` (`discardingThreshold=0`,
      `neverBlock=false`, commented queue size), consoles stay synchronous
      (D7, NFR-P1); verify: `LogbackConfigSpec` asserts the async wrapper and
      thresholds.
- [x] 2.3 Add `logback-test.xml` to every module whose tests boot a Spring
      context, routing to console/build-dir only (FR11, M4); verify: a spec or
      build check asserts `~/.gnomish/logs` gains no lines during
      `./gradlew check` (e.g. marker-file assertion in a bootstrap spec).
- [x] 2.4 Move `LogCaptureSupport` to `test-fixtures`, keep level
      save/restore, document it in `.claude/rules/logging.md` as the assertion
      idiom (NG5: no bulk migration); verify: the two existing consumers plus
      all specs added by this change use it.
- [x] 2.5 Add the `component` MDC key to the pattern and set it at daemon
      worker starts (janitor, reaper, snapshot, sweep, heartbeat) (D10, FR8);
      verify: pattern spec updated; a daemon-thread spec asserts the key.

## 3. Shutdown ordering (factory-serve delta)

- [x] 3.1 Disable Spring's auto shutdown hook and give run/take/dashboard the
      shared ordered exit path (work → context close → log stop) (D6);
      verify: per-command spec asserts context closes and the async appender
      flushes on normal exit.
- [x] 3.2 Extend the serve hook to own drain → context close → logging stop on
      both drain and forever paths (D6, FR9, M5); verify: shutdown-ordering
      spec — terminal slot lines logged during drain are present after the
      sequence; second run of the sequence is a no-op (NFR-R1).
- [x] 3.3 Introduce the volatile shutdown-phase flag; classify shutdown-caused
      child deaths and worker interrupts (slot crash boundary,
      `InstanceHeartbeat.onWorkerDeath`, subprocess supervisors' reports) as
      WARN-without-stack during the phase (D6, factory-serve delta scenarios);
      verify: specs for "shutdown-caused death is not an alarm" and for an
      independent ERROR staying ERROR.
- [x] 3.4 Rename the shutdown reason wire-safe: signal-initiated stop reports
      a signal reason for SIGINT and SIGTERM alike without breaking snapshot
      readers; verify: snapshot round-trip spec still green.

## 4. Anchors and canonical summary

- [x] 4.1 Implement `AnchorLog` (D2, FR2) with `claimAcquired`,
      `serveStarted`, `serveStopping`, `taskSummary`; call it from both claim
      paths and `ServeCommand` start/stop plus startup-failure logging (the
      `System.err`-only provisioning failure gains a `log.error` with the
      exception); retire the `FactoryApplication` boot DEBUG line — it is
      intentional, not dead: the documented production exercise of the
      SLF4J-to-Logback stack (FR4 of add-manual-run), asserted by
      `LoggingLevelSpec` — so removing it means migrating that role: the
      level-override assertion moves onto the `${GNOMISH_LOG_LEVEL}`
      mechanism from task 2.1, `LoggingLevelSpec` is rewritten/replaced
      accordingly, and the class javadoc's FR4 rationale is updated; verify:
      specs assert the claim anchor precedes engine events and serve
      start/stop lines carry config; the rewritten level-override spec is
      green with the boot DEBUG line gone.
- [x] 4.2 Define `TaskSummary` and the single renderer in `AnchorLog` (D3,
      FR3); verify: renderer spec covers every outcome family incl.
      post-harden quarantine.
- [x] 4.3 Serve/take assembler: map `TaskOutcomeLine` → `TaskSummary` at the
      slot write point, replacing/enriching the existing terminal lines
      (levels per outcome preserved; infra-abort double-log demoted per audit
      G9); verify: slot spec asserts one summary per terminal result incl.
      crash boundary.
- [x] 4.4 Manual-run assembler: `SummaryAccumulatorListener` over engine
      events emitting on `TaskFinished` (manual-run delta); verify: manual-run
      spec asserts the summary for delivered/escalated/aborted terminals.
- [x] 4.5 Declare the assembler sync pair (D8): `Kept in sync with` markers at
      both ends, registry row in `.claude/rules/manual-sync-pairs.md`, and a
      data-driven spec asserting equivalent summaries from equivalent facts;
      verify: `grep -rn "Kept in sync with" src/main` lists both ends.
- [x] 4.6 Remote anchors: INFO at container create/reattach/dispose
      (`ContainerMaterializer`, `ContainerEnvironmentDisposal` — dispose-step
      failures gain step label + environment key) and at git lifecycle-commit
      choke points (`GitTaskRepository.commitWith`,
      `TaskLifecycleCommitWriter.build`), mirrored across the
      attempt-persistence pair (D8); verify: specs assert one line per
      lifecycle transition; both pair ends carry markers.

## 5. Repeat suppression and noise demotions

- [x] 5.1 Route the poll/retry flood sites through `RepeatSuppressor` (D4,
      FR4): workflow-run poll, first-push retry loop (+ taskId threading),
      mid-round harvest poll (mirror check on the round-environment-source
      pair, D8); verify: data-driven spec — N consecutive failures emit 1
      site-level line + roll-up, recovery emits one line (UX3).
- [x] 5.2 GitHub retry visibility (FR5): Resilience4j `onRetry`/`onError`
      listeners logging attempt, wait, and exhaustion; verify: WireMock spec
      asserts retry lines on 429/5xx sequences.
- [x] 5.3 Sandbox local aggregates (D4): guard-denial parse loop counts drops
      and emits one keyed WARN per read (key threaded into `GuardDenialLog`);
      scratch-tree deletion counts failures into one WARN; the
      `ContainerFileChannel` truncation WARN gains the environment key;
      verify: specs assert single aggregate line for multi-failure input and
      the key on the truncation line.
- [x] 5.4 Level demotions per audit (FR12): recovered-transient and
      first-attempt WARNs → INFO (park fence, remote attempt delivery, foreign
      repo rename), per-tool-call INFO → DEBUG, self-check per-probe INFO →
      DEBUG with enriched aggregate, reconciliation/convergence chatter →
      DEBUG (incl. `Reaper` sweep-page-filled and its convergence-under-
      contention lines — its three WARNs stay: sweep-listing failure,
      foreign classification, per-task repair failure), per-poll finished-task
      decline latched (first INFO, repeats DEBUG), findings-file habit WARN →
      DEBUG, duplicate-per-path collapses
      (origin reconciliation, remote delivery, first push, dispose vs verdict);
      verify: each demotion's spec updated deliberately (no blanket edits) —
      the task's diff references the audit rationale per site.
- [x] 5.5 Sweep verdict levels by category + no-silent-skip
      (sandbox-lifecycle delta): reader inspect failures emit
      `SKIPPED_NO_VERDICT`; `Slf4jSweepVerdictListener` grades levels
      (steady-state DEBUG, actions INFO, skipped WARN); verify: delta
      scenarios "Unreadable object still gets a verdict" and "Quiet tick,
      loud degradation" as specs; M1 baseline check on a healthy E2E tick.

## 6. Silent-degradation gap fixes (FR5)

- [x] 6.1 Judge infrastructure failures: one WARN in the judge round's
      `cannotVerify` exit covering all six paths; verify: spec per failure
      class asserts the line.
- [x] 6.2 Guarded HTTP checks: WARN on egress refusal and redirect-bound
      refusal; command-check start failures and environment-unavailable paths
      log with the check identity; findings-reader warnings gain the check
      identity; the env-file secrets warning names the variable (never the
      value); verify: specs assert attribution fields.
- [x] 6.3 Tracker degradations: abort-facts fallback WARN (fuse under-count
      consequence named), claim-comment delete failure WARN, stale-claim
      removal + index repair INFO with converge-abort DEBUG, malformed
      factory-authored marker WARN; verify: specs per site.
- [x] 6.4 Git adapter degradations: task-branch listing failure (DEBUG at the
      site plus the command failure one line down — the decision is reported to
      the operator by `TaskBranchLister`'s throw, so a WARN here would be one
      fault logged twice), usage
      walker failures WARN/DEBUG, snapshot-tip and claim-epoch parse
      anomalies, terminal-commit idempotent skips DEBUG, retry-loop DEBUG in
      `GitInfrastructureRetry`, fetch-failure DEBUG before NotFound
      classification, resume-branch recreation from the origin tracking ref
      INFO (`ContainerResumeBranch` — adopting another instance's work),
      cleanup-commit history probe DEBUG on non-zero exit (`GitShowTip` —
      diagnostic only per NG1, no behavior change), worktree
      removal/salvage-discard failures (mirrored
      across the salvage pair with markers, D8); verify: specs per site; pair
      markers present at both salvage ends.
- [x] 6.5 Dashboard and observability readers: malformed-vs-missing
      distinction (snapshot WARN/DEBUG, render cycle WARN with throwable,
      board cache DEBUG with throwable, sweep-action instant DEBUG); empty
      token-usage extraction WARN; docker runtime probe fallback INFO; guard
      cursor-unreadable DEBUG; egress-guard repair sub-step DEBUG; verify:
      specs per site.

## 7. MDC completeness and throwable convention

- [x] 7.1 Apply `MdcAwareThread` to the logging virtual-thread hops
      (`ChildProcessStdin`, `ContainerFileChannel` pump, `ExecPipeDrain`)
      (D10, FR8); check whether `GithubWorkflowRunPoll` lines land with empty
      MDC and apply the same fix at that thread boundary if so; verify: specs
      assert taskId on helper-thread lines (`StreamDrainSpec` precedent) and
      on workflow-poll lines.
- [x] 7.2 Clear `stage`/`attempt` at the four thread boundaries that clear
      `taskId` (backstop to the `TaskFinished` clear); verify: MDC spec kills
      the leak scenario (run ends without `TaskFinished`).
- [x] 7.3 Reaper/janitor per-task work wrapped in
      `MDC.putCloseable("taskId", …)` (FR8, UX2); verify: spec asserts a
      taskId grep finds reap decisions.
- [x] 7.4 Fix all exception-interpolation sites to trailing-throwable form —
      23 at the August 2026 audit; the gate, not the count, is normative
      (including the two `getMessage()` sites that can print `null` and the
      sole eager-render `log.info(render(...))` site) (FR7); verify: gate
      task 8.1 passes with zero exemptions for these.
- [x] 7.5 Route the ~12 untrusted-text log sites through `LogText` (FR6):
      decision-file raw content (capped), stream-json raw-event DEBUG
      (bounded shape), git/docker stderr sites, in-container self-check
      output, progress-listener event payloads shrunk to type names with
      listener identity split across the emitter/composite pair; verify:
      injection spec (newline + ANSI payload renders one inert line) per
      representative site.

## 8. Convention gates

- [x] 8.1 Source-scan gate spec (D9, FR7, M2): log calls carrying an
      exception must pass it as the trailing argument — regex over `src/main`
      with an inline-comment exemption idiom; verify: gate green after 7.4,
      seeded violation fails.
- [x] 8.2 Untrusted-text gate (D9, FR6): known untrusted expressions
      (`stderr()`, agent payload identifiers) in log-call argument position
      only inside `LogText.*` wrappers; verify: gate green after 7.5, seeded
      violation fails.
- [x] 8.3 ArchUnit: `LoggerFactory` absent from `domain` beyond the four
      allowed classes (ADR deviation list); verify: rule green, adding a
      logger to a fifth domain class fails.

## 9. Exit-code verification fixes (FR13, D11)

- [x] 9.1 `RoundBoundaryCheck`: check the diff invocation's exit code and
      introduce the cannot-verify outcome routed to the round's
      infrastructure-failure path; mirror the three-outcome rule onto
      `HarvestedBoundaryCheck`, place `Kept in sync with` markers at both
      ends, and remove the registry row (per the rule: both ends declared with a
      resolvable `{@link}`); verify: specs — failed
      diff aborts as infrastructure with no attempt burned and no violation
      attributed; seeded tamper still violates; both media covered (M6).
- [x] 9.2 `EnvironmentAttemptPersistence` + `EnvironmentRoundSnapshot`:
      verify tip resolutions, fail the persist with git evidence on non-zero
      exit or blank output; confirm the host twin `GitAttemptPersistence`
      obeys the same rule (mirror obligation); verify: specs — no record with
      a blank tip is ever created; failed persist follows the existing
      infrastructure handling (M6).
- [x] 9.3 `MidRoundHarvestListener` and the host twin `MidRoundPushListener`:
      a failed tip resolution skips the observation (never reported as tip
      moved/lost), logging via the suppressor path from 5.1; verify: specs —
      a failed resolution changes no harvest/push decision and never escapes
      the listener contract.
- [x] 9.4 `TaskBranchLister`: enumeration failure fails `gnomish status` list
      mode with the git evidence (task-inspection delta scenario); per-branch
      degradation unchanged; verify: spec — non-zero `for-each-ref` yields a
      command error, healthy-branch listing specs stay green (M6).

## 10. Verification and audit closure

- [x] 10.1 M1: healthy-cycle E2E asserts zero WARN/ERROR after startup
      (serve tick + task round with all-green checks).
      Done by `HealthyServeCycleLogSpec` (`:bootstrap`): one real
      `serve --drain` pass over a real local git project, a real
      `InMemoryTracker` holding one Ready task and the fake agent binary —
      the ROOT logger is captured for the whole round, the task is asserted
      to have reached `Finished` (a quiet log from a round that never ran
      proves nothing), and no captured event is WARN or above.
- [x] 10.2 M3: coverage check — every FR5 enumeration item maps to a spec
      (grep table in the task's verification notes). See
      *FR5 coverage table* at the end of this file.
- [x] 10.3 M5: kill-during-drain spec — SIGTERM mid-drain, assert terminal
      lines + summaries + stopping anchor present in the file. Done by
      `ServeShutdownWiringSpec`'s "a signal landing mid-drain leaves the
      stopping anchor and the in-flight task's summary in the log file",
      beside its sibling M5 feature: the production async-file appender is
      attached to the JVM's own root logger, the in-flight task's summary is
      written from inside the drain itself (the process-tree kill step), and
      the file is read back after the logging stop — anchor first, summary
      after, the summary carrying its `taskId=` grep key.
- [x] 10.4 UX2: lifecycle-grep spec — a full task run's log filtered by taskId
      reconstructs claim → events → summary in order. Done by
      `HealthyServeCycleLogSpec`'s second feature over the same real round,
      filtering strictly on the `taskId` MDC key. It surfaced one gap and
      closed it: the serve claim anchor was written on the feed thread with
      no task context, so the first line of every task's story was the one
      line a `grep taskId=` missed — `FeedCycle` now emits it inside
      `MdcAwareThread.taskScope`, closed again before the slot launches
      (FR8's "per-task decisions made by daemon components run under that
      task's taskId MDC").
- [x] 10.5 Sync-pair audit closure (D8): every pair row touched by this change
      carries markers at both ends or a stated no-mirror rationale; registry
      updated (rows with markers removed per the rule); verify:
      `grep -rn "Kept in sync with" src/main` output reviewed against D8's
      table. See *Sync-pair closure* at the end of this file.
- [x] 10.6 Full `./gradlew check` green including PIT gates for new classes
      (`LogText`, `RepeatSuppressor`, `AnchorLog`, assemblers, suppressor
      call sites), and M4 re-verified (no operator-log pollution). See
      *What the full check surfaced* at the end of this file.

## 11. Operator-event catalog (FR14, D14)

- [ ] 11.1 Create `logtext.OperatorEvent`: one constant per production
      WARN/ERROR call site (125 at the September 2026 audit), stable `[GFnnn]`
      codes, an accessor rendering the message head; class javadoc states the
      contract (never reuse, additive-only, operator plane only — no INFO/DEBUG
      codes); verify: catalog spec pins code uniqueness and format.
- [ ] 11.2 Retag every production WARN/ERROR call site with its catalog
      constant — mechanical message-head prefix, no level or wording changes;
      the four ADR-exempt `domain` emitters take the literal `[GFnnn]` head
      instead of a `:logtext` edge; verify: existing log-asserting specs stay
      green after adjusting for the head (contains-style asserts need no edit).
- [ ] 11.3 Round-trip spec pinning the `domain` literal heads to their catalog
      entries (the wire-vocabulary spec shape from `.claude/rules/testing.md`);
      verify: removing either side goes red.
- [ ] 11.4 Glossary entries *operator event* and *log contract*; ADR 0004
      amendment: the code-not-prose contract, the grep-from-column-0 migration
      note, and the deliberately-deferred-no-longer status of the runtime gate;
      `.claude/rules/logging.md` checklist gains items 7 (new WARN/ERROR takes
      the next free code) and 8 (and a pinning spec); verify: docs cross-read.

## 12. Pin the 53 dark lines (FR15, D17 — ordered by failure class)

Each task: specs via `LogCaptureSupport` asserting code + level + attribution
key; the definitive per-line map is in *Verification notes → Unpinned-line
map* below.

- [ ] 12.1 Ledger writers (11 lines, worst class — durable-plane loss with no
      trace): `LifecycleLedgerWriter:62`, `RunSummaryLedgerWriter:62`,
      `SweepLedgerWriter:85`, `TaskOutcomeLedgerWriter:68,:79`,
      `LedgerRetentionSweeper:80,:95`, `SnapshotWriteCycle:72,:87`,
      `SnapshotWriter:138`, `DashboardWatchLoop:88`.
- [ ] 12.2 Abort/quarantine protocol (7 ERROR/WARN about lost work):
      `AbortHandler:89,:124,:138`, `TakeQuarantinePark:59,:70`,
      `FinishEffect:75,:83`, `GuardedPark:137,:145`.
- [ ] 12.3 Daemon tick family (persistent-WARN-means-act):
      `Reaper:106`, `WorktreeJanitor:109,:127,:170`, `SandboxLifecycleTick:74`,
      `InstanceHeartbeat:191,:202,:239`, `HeartbeatBeater:35`,
      `StandingReaper:145`, `DirtyNotifier:48`, `FeedOutageRetry:74`.
- [ ] 12.4 Remaining application lines: `OrderedExit:133`,
      `RunExceptionReporting:54`, `TakeBatch:79`, `FinishedDecline:99`,
      `DecisionAck:133`, `RevocationCheckingAttemptPersistence:210`.
- [ ] 12.5 Adapters: `ExecutorRoundExecution:123,:141,:146`,
      `AgentProgressEmitter:93`, `CompositeAgentProgressListener:55`,
      `FindingsFileReader:74,:83,:93`, `PinCheckedExternalCheckClient:98`,
      `ReplicaPairReconciler:136`, `MidRoundPollLog:84` (the dark roll-up
      edge — drive the streak past the threshold in both listener specs).
- [ ] 12.6 Sandbox: `EgressGuard:98`, `GuardDenialReads:92,:96`,
      `HostChannelFiles:72`.
- [ ] 12.7 Drop the *Known gaps* rows this section closes (janitor WARNs) and
      re-run the coverage sweep to confirm 0 unpinned; verify: static gate
      (13.x) green with zero exemptions for these.

## 13. Static log-contract gate (FR16, D15)

- [ ] 13.1 `LogContractGateSpec` in `:bootstrap` architecture, on
      `LogCallSites`/`RepoSourceTree`: every WARN/ERROR site coded; every code
      used by exactly one site; every code present in ≥1 test source; in-place
      exemption `log-contract-exempt: <reason>`; verify: seeded violations
      (missing code, duplicate code, unreferenced code) each fail.
- [ ] 13.2 Wire the scan floors (`KNOWN_*` counters) so an empty scan cannot
      pass; verify: same guard pattern as `ThrowableConventionGateSpec`.

## 14. Runtime log-expectation gate (FR17, D16)

- [ ] 14.1 Global Spock extension in `:test-fixtures`: per-feature root-level
      WARN+ capture over `com.github.oinsio.gnomish.*`; events observed by an
      attached `LogCaptureSupport` count as expected; per-spec allowance
      annotation with a mandatory reason for deliberate non-pinned traversal;
      verify: extension spec — unexpected WARN fails, captured WARN passes,
      allowance passes with reason.
- [ ] 14.2 Land in observing mode; burn down every offender the report names
      (jointly with section 12); verify: observing report empty on full check.
- [ ] 14.3 Flip to enforcing (M8); verify: full `./gradlew check` green in
      enforcing mode; a seeded unexpected WARN in a scratch spec goes red.

## 15. Closure

- [ ] 15.1 M7 sweep: rerun the coverage audit; 125/125 coded and pinned or
      gate-exempted with reasons; verify: grep table appended below.
- [ ] 15.2 Full `./gradlew check` green including both gates and PIT; M4
      re-verified.

## Verification notes

### FR5 coverage table (M3)

This table is the artifact M3 of harden-logging-observability is verified
against: every item of FR5's silent-degradation enumeration, the emitter that
now logs it, and the spec that asserts the line. Regenerate the right-hand column with
`grep -rln "LogCaptureSupport" --include='*.groovy' .`.

| FR5 enumeration item                                         | Emitter                                                                                          | Spec                                                                                                                                                             |
|--------------------------------------------------------------|--------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| GitHub API retry / backoff / exhaustion                      | `github/.../GithubHttpClient` (Resilience4j events)                                              | `GithubRetryVisibilitySpec`                                                                                                                                      |
| Every judge `CannotVerify` exit                              | `agent/.../JudgeRoundExecution`                                                                  | `JudgeCannotVerifyLoggingSpec`                                                                                                                                   |
| Egress refusals in guarded HTTP checks                       | `adapter/check/http/...`, `sandbox/.../EgressGuard`                                              | `HttpCheckRefusalLoggingSpec`, `EgressGuardSpec`                                                                                                                 |
| Stale-claim removal                                          | `github/.../GithubStaleClaimRemoval`                                                             | `GithubStaleClaimRemovalSpec`                                                                                                                                    |
| Index repair                                                 | `github/.../GithubIndexRepair`                                                                   | `GithubIndexRepairSpec`                                                                                                                                          |
| Abort-facts fallback to `none()` (dropped marker)            | `github/.../GithubMarker#warnDropped`                                                            | `GithubMarkerSpec`                                                                                                                                               |
| Claim-comment delete failure                                 | `github/.../GithubClaimLease`                                                                    | `GithubClaimLeaseSpec`                                                                                                                                           |
| Container create / reattach / dispose outcomes               | `sandbox/.../ContainerMaterializer` (create, reattach), `ContainerEnvironmentDisposal` (dispose) | `ContainerTaskExecutionEnvironmentUnitSpec`                                                                                                                      |
| Per-step dispose failure with environment key                | `sandbox/.../ContainerEnvironmentDisposal`                                                       | `ContainerEnvironmentDisposalSpec` ("a swallowed dispose step names …")                                                                                          |
| Git lifecycle commits                                        | `git/.../GitTaskRepository`, `TaskLifecycleCommitWriter`                                         | `GitTaskRepositorySpec`, `GitObjectsTaskRepositorySpec`                                                                                                          |
| Worktree removal failures                                    | `git/.../TaskWorktreeCleanup`, `serve/WorktreeJanitor`                                           | `TaskWorktreeCleanupSpec`; `WorktreeJanitorSpec` covers the disposal decision's `taskId` scope, **not** the janitor's own scan/sanitize WARNs — see *Known gaps* |
| Dashboard ledger/snapshot degradations, missing vs malformed | `dashboard/SnapshotReader`, `DashboardBoardCache`                                                | `SnapshotReaderSpec` (missing → DEBUG, malformed → WARN), `DashboardBoardCacheSpec`                                                                              |
| Empty token-usage extraction                                 | `agent/.../TokenUsageMapper`                                                                     | `TokenUsageMapperSpec`                                                                                                                                           |
| Local resume-branch recreation from the origin tracking ref  | `git/.../ContainerResumeBranch`                                                                  | `ContainerResumeBranchSpec`                                                                                                                                      |
| Env-file secret resolution warnings naming the variable      | `adapter/secrets/EnvFileSecretsProvider`                                                         | `EnvFileSecretsProviderSpec`                                                                                                                                     |

### Sync-pair closure (D8)

`grep -rn "Kept in sync with"` over production sources enumerates eight declared
pairs after this change: the two boundary checks, the two salvages, the two
task-lifecycle commit writers, the two summary assemblers, the two sanitizers,
the two mid-round poll listeners, the two terminal-commit writers
(`CleanupCommit` ↔ `GitObjectsTerminalCommits`), and the two daemon ticks
(`SandboxLifecycleTick` ↔ `WorktreeJanitor`) — plus, in test scope, the two
stalling git fixtures.

Against D8's table, row by row:

- **`RoundBoundaryCheck` ↔ `HarvestedBoundaryCheck`** — mirrored: both apply
  the three-outcome rule, both carry the marker with the invariant spelled out.
  Registry row removed per the rule (both ends declared, `{@link}` resolvable).
- **`WorktreeSalvage` ↔ `EnvironmentSalvage`** — mirrored: the degrade-path
  logs gained task context at both ends; already marker-declared.
- **`GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`** — mirrored:
  both resolve their tip through `VerifiedTip`. Registry row kept (neither end
  carries a marker yet) with its invariant narrowed to what is still
  hand-synced.
- **`TakeResumeRunner` ↔ `TakeContainerResumeRunner`** and the fresh-claim /
  engine-execution / manual-run rows it anchors — **no mirrored change needed**,
  and the reason is better than symmetry: the summary anchor is emitted at one
  choke point per mode family (`TakeSlotRunner` for serve, `TakeDispatcher` for
  take in both host and container mode, `SummaryAccumulatorListener` for the
  manual run), so no per-mode runner grew an anchor call that its twin could
  drift from. Same for the claim anchor: two claim paths, one `AnchorLog` method.
- **`HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource`** — only the
  polling twin needed the harvest-poll suppression; the host twin does not poll,
  so there is no flood shape to mirror (D8's own statement of this row).
- **New: summary assemblers** — markers at both ends, including the one recorded
  asymmetry (`REVOKED` is unreachable from a manual run); registry row removed
  per the rule once both markers landed, since both are in `:application` and
  name each other with a resolvable `{@link}`. `SummaryAssemblerPairEquivalenceSpec`
  is the executable half.
- **`MidRoundHarvestListener` ↔ `MidRoundPushListener`** — mirrored by task 9.3:
  both resolve the tip through `VerifiedTip` and skip the observation on a failed
  resolution, both report it through the same `MidRoundPollLog` suppressor path.
  Markers at both ends; no registry row (neither end predated this change's
  declaration).
- **`CleanupCommit` ↔ `GitObjectsTerminalCommits#cleanUp`** — mirrored by task
  4.6: both media log the FR2 anchor line for the terminal cleanup commit, in the
  same shape used for every other lifecycle transition. Markers added at both ends
  by this change; no registry row.
- **`SandboxLifecycleTick` ↔ `WorktreeJanitor`** — a pre-existing undeclared pair
  this change had to touch on both ends (task 2.5: both daemon loops gained
  `DaemonComponent` framing, so the `component` key lands on both or neither), so
  it was declared. The invariant the markers name is the shared
  immediate-then-cadence loop shape. Markers added at both ends; no registry row.
- **New: sanitizers** — markers at both ends; the row stays in the registry's
  *no shared classpath* section, which is the only navigational index the pair
  can have. `SanitizerPairEquivalenceSpec` feeds one adversarial corpus to both.

### What the full check surfaced (10.6)

`./gradlew check` is green end to end. Getting there closed six findings the
per-module runs had not reached, each recorded because the fix is a decision, not
a typo:

- **Three specs asserted the pre-FR13 silent paths.** `ContainerRunSupportSpec`
  built the container persistence without the task branch (its baseline tip now
  resolves at construction, so that is a legitimate refusal); `ManualRunRunnerSpec`
  and `SubcommandDispatchSpec` drove `status` list mode at a directory git will not
  enumerate, which now fails instead of rendering as a verified "no tasks". All
  three fixtures were corrected to the state the production sequence requires —
  the new behavior is the point of FR13, so the specs, not the behavior, were wrong.
- **Dependency-analysis: `:logtext` is on three modules' api surface.** The
  suppressor is a component of the public `GithubCheckExternalClient` record and a
  constructor parameter of `FinishedDecline`, and `MdcAwareThread.taskScope` hands
  back SLF4J's `MDCCloseable`. `:adapters:git`, `:adapters:github` and
  `:application` now export `:logtext`, and `:logtext` exports `slf4j-api`; the
  build files' comments claiming the opposite were corrected with them.
- **`TakeBatch`'s per-ref MDC clear was unassertable.** The ref's virtual thread
  dies immediately after the `finally`, so no in-process assertion can observe a
  leaked key and the mutation gate reported the clear as a survivor. Replaced the
  two by-name removals with one `MDC.clear()`: the thread's whole context belongs
  to that ref, which is the same discipline `MdcAwareThread`'s framed bodies use
  and covers keys this class has never heard of. The now-unused `taskIdMdcKey`
  parameter left `dispatch` with it.
- **`take`'s summary had no spec.** `TakeSummaryAnchorSpec` drives a real
  delivering run through both `take` entry points (explicit ref, bare queue walk)
  over port fakes and asserts the one canonical line — and, in the bare case, the
  claim anchor that opens the story. A companion assertion pins the wall time as
  elapsed time rather than a raw monotonic reading, at both assembler ends.
- **`CommandExit.start(SpringApplication, String[])` is `@DoNotMutate`**, for the
  out-of-process-delegation reason: its two production arguments register a real
  JVM shutdown hook (unremovable — the registry hands out no handle) and stop the
  real logging system the rest of the JVM's specs write through. The
  `com.github.oinsio.gnomish.e2e.*` suites reach it through the packaged jar; the
  seamed overload carries the mutation gate for everything it wires.

M4 re-verified after the green run: nothing under `~/.gnomish/logs/` was written
during the build.

### Unpinned-line map (FR15 baseline, September 2026 audit)

The definitive 53-row map: every production WARN/ERROR line no spec asserted
at audit time, grouped as the section-12 tasks burn them down. Level is ERROR
where marked, else WARN. Sources: two module-by-module audits over all
`src/main/java` trees, cross-checked against every `src/test/groovy` tree
(assertion-verified, not text-coincidence).

| Cluster | Lines |
|---|---|
| 12.1 writers | `serveobservability/writer/` — LifecycleLedgerWriter:62 (E), RunSummaryLedgerWriter:62 (E), SweepLedgerWriter:85 (E), TaskOutcomeLedgerWriter:68, :79 (E), LedgerRetentionSweeper:80, :95, SnapshotWriteCycle:72, :87, SnapshotWriter:138; dashboard/DashboardWatchLoop:88 |
| 12.2 abort | app/take/ — AbortHandler:89 (E), :124 (E), :138 (E), TakeQuarantinePark:59 (E), :70 (E), FinishEffect:75, :83, GuardedPark:137, :145 |
| 12.3 daemons | app/lease/ — Reaper:106, InstanceHeartbeat:191, :202, :239, HeartbeatBeater:35, StandingReaper:145; app/serve/ — WorktreeJanitor:109, :127, :170, SandboxLifecycleTick:74, DirtyNotifier:48, FeedOutageRetry:74 |
| 12.4 app misc | app/ — OrderedExit:133, RunExceptionReporting:54, TakeBatch:79; app/take/ — FinishedDecline:99, DecisionAck:133, RevocationCheckingAttemptPersistence:210 |
| 12.5 adapters | adapter/agent/ — ExecutorRoundExecution:123, :141, :146, AgentProgressEmitter:93, CompositeAgentProgressListener:55; adapter/check/ — FindingsFileReader:74, :83, :93, PinCheckedExternalCheckClient:98; adapter/git/ — ReplicaPairReconciler:136, MidRoundPollLog:84 (roll-up edge) |
| 12.6 sandbox | sandbox/environment/ — EgressGuard:98, GuardDenialReads:92, :96, HostChannelFiles:72 |

Covered at audit time: 72/125 (domain 4/4; adapters/git 24/26; adapters/github
4/4; the FR5-table lines 100%). The audit's SAFE half is deliberately not
restated here — the gate (13.x) supersedes any static list once it lands.

### Known gaps and debt carried out of this change

Recorded here so the archive states them rather than leaving them to be
rediscovered. None blocks the change; each is a follow-up, not a defect.

- **Eleven production files now sit over the 200-line hard cap
  (`process-invariants.md`).** `TakeSlotRunner` was already over it and grew by
  73 lines here, so it was the one worth splitting now, and was: its
  summary/crash logging cluster moved to `SlotOutcomeLog`, which also gave that
  cluster a same-module unit spec (`SlotOutcomeLogSpec`) instead of only
  :bootstrap coverage. Ten more crossed the cap in this change, by +1…+32 lines
  each, and are debt: `GithubClaimLease` (231), `TakeDispatcher` (227),
  `ShellCommandCheckRunner` (219), `GithubMarker` (216),
  `GithubStaleClaimRemoval` (213), `SlotLedger` (208), `WorktreeJanitor` (206),
  `ServeShutdownWiring` (202), `FeedAutomaton` (202), `StandingReaper` (201).
  Each grew by the degrade-path logging this change exists to add, so no
  responsibility boundary opened up with the growth — which is precisely the
  condition under which `process-invariants.md` says not to split. Splitting
  them needs its own change, driven by responsibilities rather than by the count.
- **`WorktreeJanitor`'s own scan-failure and held-ref-sanitize WARNs have no
  spec.** `WorktreeJanitorSpec` asserts the `taskId` scope around the disposal
  decision, not those two lines; the FR5 table above is corrected to say so.
  *Superseded by section 12.3* — the September 2026 log-contract extension
  (FR14–FR17) closes this row along with the 50 others the full audit found.
- **`FindingsSanitizer`'s ANSI pattern was edited.** Task 1.5 promised the
  module's production code untouched. The edit — `\\]` → `]` inside an
  alternation — is a semantic no-op (`]` outside a character class needs no
  escape) and changes neither behavior nor the japicmp baseline. Recorded
  because the promise was written, not because the character matters.
