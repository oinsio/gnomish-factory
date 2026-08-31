# Tasks: harden-logging-observability

Sequenced after `harden-task-branch-contract` archives (Impact — same-file
overlap; the summary vocabulary is defined against post-harden `TakeResult`).

## 1. Policy and foundations

- [ ] 1.1 Write `docs/adr/0004-logging-policy.md` (D1, FR1): level semantics,
      best-effort-must-log, one-failure-one-log, retention rationale, accepted
      deviations (domain port-failure logs — NG3, unstructured text log);
      verify: ADR reviewed against design D1 content, referenced by later tasks.
- [ ] 1.2 Write `.claude/rules/logging.md` emitter checklist (FR1) with
      path-trigger for Java sources; verify: rule lists level table, throwable
      rule, untrusted-text rule, suppression rule, MDC rule.
- [ ] 1.3 Add glossary entries (anchor line, canonical task summary, repeat
      suppression / edge logging, log text sanitization) to `docs/glossary.md`;
      the canonical-task-summary entry distinguishes the log-plane summary
      line from the ledger's `runSummary` record (D3 naming note); the
      log-text-sanitization entry distinguishes the log-line choke point
      from the plugin-boundary findings sanitizer (D5 — two trust
      boundaries, one shared character vocabulary); verify:
      each term used by later code/javadoc matches the entry.
- [ ] 1.4 Create the `:logtext` leaf module (D5, module-layering delta):
      `LogText` (control/ANSI strip, newline flattening incl. `U+2028`/
      `U+2029`, length cap), `RepeatSuppressor` (keyed edge state, D4 API),
      `MdcAwareThread` helper (D10); slf4j-api only; unit specs for each
      incl. newline-forgery and cap scenarios; verify:
      `./gradlew :logtext:check` green, dependency gates pass with the new
      module in every consumer's allowed list.
- [ ] 1.5 Declare the sanitizer pair (D5, D8): `Kept in sync with` markers on
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

- [ ] 2.1 Pin UTF-8 on all three encoders and add
      `${GNOMISH_LOG_LEVEL:-INFO}` root-level substitution (D7, FR10); extend
      `LogbackConfigSpec` to assert charset and the override; verify: spec
      green, DEBUG run needs no rebuild (`LoggingLevelSpec`-style assertion).
- [ ] 2.2 Wrap FILE in `AsyncAppender` (`discardingThreshold=0`,
      `neverBlock=false`, commented queue size), consoles stay synchronous
      (D7, NFR-P1); verify: `LogbackConfigSpec` asserts the async wrapper and
      thresholds.
- [ ] 2.3 Add `logback-test.xml` to every module whose tests boot a Spring
      context, routing to console/build-dir only (FR11, M4); verify: a spec or
      build check asserts `~/.gnomish/logs` gains no lines during
      `./gradlew check` (e.g. marker-file assertion in a bootstrap spec).
- [ ] 2.4 Move `LogCaptureSupport` to `test-fixtures`, keep level
      save/restore, document it in `.claude/rules/logging.md` as the assertion
      idiom (NG5: no bulk migration); verify: the two existing consumers plus
      all specs added by this change use it.
- [ ] 2.5 Add the `component` MDC key to the pattern and set it at daemon
      worker starts (janitor, reaper, snapshot, sweep, heartbeat) (D10, FR8);
      verify: pattern spec updated; a daemon-thread spec asserts the key.

## 3. Shutdown ordering (factory-serve delta)

- [ ] 3.1 Disable Spring's auto shutdown hook and give run/take/dashboard the
      shared ordered exit path (work → context close → log stop) (D6);
      verify: per-command spec asserts context closes and the async appender
      flushes on normal exit.
- [ ] 3.2 Extend the serve hook to own drain → context close → logging stop on
      both drain and forever paths (D6, FR9, M5); verify: shutdown-ordering
      spec — terminal slot lines logged during drain are present after the
      sequence; second run of the sequence is a no-op (NFR-R1).
- [ ] 3.3 Introduce the volatile shutdown-phase flag; classify shutdown-caused
      child deaths and worker interrupts (slot crash boundary,
      `InstanceHeartbeat.onWorkerDeath`, subprocess supervisors' reports) as
      WARN-without-stack during the phase (D6, factory-serve delta scenarios);
      verify: specs for "shutdown-caused death is not an alarm" and for an
      independent ERROR staying ERROR.
- [ ] 3.4 Rename the shutdown reason wire-safe: signal-initiated stop reports
      a signal reason for SIGINT and SIGTERM alike without breaking snapshot
      readers; verify: snapshot round-trip spec still green.

## 4. Anchors and canonical summary

- [ ] 4.1 Implement `AnchorLog` (D2, FR2) with `claimAcquired`,
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
- [ ] 4.2 Define `TaskSummary` and the single renderer in `AnchorLog` (D3,
      FR3); verify: renderer spec covers every outcome family incl.
      post-harden quarantine.
- [ ] 4.3 Serve/take assembler: map `TaskOutcomeLine` → `TaskSummary` at the
      slot write point, replacing/enriching the existing terminal lines
      (levels per outcome preserved; infra-abort double-log demoted per audit
      G9); verify: slot spec asserts one summary per terminal result incl.
      crash boundary.
- [ ] 4.4 Manual-run assembler: `SummaryAccumulatorListener` over engine
      events emitting on `TaskFinished` (manual-run delta); verify: manual-run
      spec asserts the summary for delivered/escalated/aborted terminals.
- [ ] 4.5 Declare the assembler sync pair (D8): `Kept in sync with` markers at
      both ends, registry row in `.claude/rules/manual-sync-pairs.md`, and a
      data-driven spec asserting equivalent summaries from equivalent facts;
      verify: `grep -rn "Kept in sync with" src/main` lists both ends.
- [ ] 4.6 Remote anchors: INFO at container create/reattach/dispose
      (`ContainerMaterializer`, `ContainerEnvironmentDisposal` — dispose-step
      failures gain step label + environment key) and at git lifecycle-commit
      choke points (`GitTaskRepository.commitWith`,
      `TaskLifecycleCommitWriter.build`), mirrored across the
      attempt-persistence pair (D8); verify: specs assert one line per
      lifecycle transition; both pair ends carry markers.

## 5. Repeat suppression and noise demotions

- [ ] 5.1 Route the poll/retry flood sites through `RepeatSuppressor` (D4,
      FR4): workflow-run poll, first-push retry loop (+ taskId threading),
      mid-round harvest poll (mirror check on the round-environment-source
      pair, D8); verify: data-driven spec — N consecutive failures emit 1
      site-level line + roll-up, recovery emits one line (UX3).
- [ ] 5.2 GitHub retry visibility (FR5): Resilience4j `onRetry`/`onError`
      listeners logging attempt, wait, and exhaustion; verify: WireMock spec
      asserts retry lines on 429/5xx sequences.
- [ ] 5.3 Sandbox local aggregates (D4): guard-denial parse loop counts drops
      and emits one keyed WARN per read (key threaded into `GuardDenialLog`);
      scratch-tree deletion counts failures into one WARN; the
      `ContainerFileChannel` truncation WARN gains the environment key;
      verify: specs assert single aggregate line for multi-failure input and
      the key on the truncation line.
- [ ] 5.4 Level demotions per audit (FR12): recovered-transient and
      first-attempt WARNs → INFO (park fence, remote attempt delivery, foreign
      repo rename), per-tool-call INFO → DEBUG, self-check per-probe INFO →
      DEBUG with enriched aggregate, reconciliation/convergence chatter →
      DEBUG (incl. `Reaper` sweep-page-filled and its convergence-under-
      contention lines — the stale-claim WARN stays), per-poll finished-task
      decline latched (first INFO, repeats DEBUG), findings-file habit WARN →
      DEBUG, duplicate-per-path collapses
      (origin reconciliation, remote delivery, first push, dispose vs verdict);
      verify: each demotion's spec updated deliberately (no blanket edits) —
      the task's diff references the audit rationale per site.
- [ ] 5.5 Sweep verdict levels by category + no-silent-skip
      (sandbox-lifecycle delta): reader inspect failures emit
      `SKIPPED_NO_VERDICT`; `Slf4jSweepVerdictListener` grades levels
      (steady-state DEBUG, actions INFO, skipped WARN); verify: delta
      scenarios "Unreadable object still gets a verdict" and "Quiet tick,
      loud degradation" as specs; M1 baseline check on a healthy E2E tick.

## 6. Silent-degradation gap fixes (FR5)

- [ ] 6.1 Judge infrastructure failures: one WARN in the judge round's
      `cannotVerify` exit covering all six paths; verify: spec per failure
      class asserts the line.
- [ ] 6.2 Guarded HTTP checks: WARN on egress refusal and redirect-bound
      refusal; command-check start failures and environment-unavailable paths
      log with the check identity; findings-reader warnings gain the check
      identity; the env-file secrets warning names the variable (never the
      value); verify: specs assert attribution fields.
- [ ] 6.3 Tracker degradations: abort-facts fallback WARN (fuse under-count
      consequence named), claim-comment delete failure WARN, stale-claim
      removal + index repair INFO with converge-abort DEBUG, malformed
      factory-authored marker WARN; verify: specs per site.
- [ ] 6.4 Git adapter degradations: task-branch listing failure WARN, usage
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
- [ ] 6.5 Dashboard and observability readers: malformed-vs-missing
      distinction (snapshot WARN/DEBUG, render cycle WARN with throwable,
      board cache DEBUG with throwable, sweep-action instant DEBUG); empty
      token-usage extraction WARN; docker runtime probe fallback INFO; guard
      cursor-unreadable DEBUG; egress-guard repair sub-step DEBUG; verify:
      specs per site.

## 7. MDC completeness and throwable convention

- [ ] 7.1 Apply `MdcAwareThread` to the logging virtual-thread hops
      (`ChildProcessStdin`, `ContainerFileChannel` pump, `ExecPipeDrain`)
      (D10, FR8); check whether `GithubWorkflowRunPoll` lines land with empty
      MDC and apply the same fix at that thread boundary if so; verify: specs
      assert taskId on helper-thread lines (`StreamDrainSpec` precedent) and
      on workflow-poll lines.
- [ ] 7.2 Clear `stage`/`attempt` at the four thread boundaries that clear
      `taskId` (backstop to the `TaskFinished` clear); verify: MDC spec kills
      the leak scenario (run ends without `TaskFinished`).
- [ ] 7.3 Reaper/janitor per-task work wrapped in
      `MDC.putCloseable("taskId", …)` (FR8, UX2); verify: spec asserts a
      taskId grep finds reap decisions.
- [ ] 7.4 Fix all exception-interpolation sites to trailing-throwable form —
      23 at the August 2026 audit; the gate, not the count, is normative
      (including the two `getMessage()` sites that can print `null` and the
      sole eager-render `log.info(render(...))` site) (FR7); verify: gate
      task 8.1 passes with zero exemptions for these.
- [ ] 7.5 Route the ~12 untrusted-text log sites through `LogText` (FR6):
      decision-file raw content (capped), stream-json raw-event DEBUG
      (bounded shape), git/docker stderr sites, in-container self-check
      output, progress-listener event payloads shrunk to type names with
      listener identity split across the emitter/composite pair; verify:
      injection spec (newline + ANSI payload renders one inert line) per
      representative site.

## 8. Convention gates

- [ ] 8.1 Source-scan gate spec (D9, FR7, M2): log calls carrying an
      exception must pass it as the trailing argument — regex over `src/main`
      with an inline-comment exemption idiom; verify: gate green after 7.4,
      seeded violation fails.
- [ ] 8.2 Untrusted-text gate (D9, FR6): known untrusted expressions
      (`stderr()`, agent payload identifiers) in log-call argument position
      only inside `LogText.*` wrappers; verify: gate green after 7.5, seeded
      violation fails.
- [ ] 8.3 ArchUnit: `LoggerFactory` absent from `domain` beyond the four
      allowed classes (ADR deviation list); verify: rule green, adding a
      logger to a fifth domain class fails.

## 9. Exit-code verification fixes (FR13, D11)

- [ ] 9.1 `RoundBoundaryCheck`: check the diff invocation's exit code and
      introduce the cannot-verify outcome routed to the round's
      infrastructure-failure path; mirror the three-outcome rule onto
      `HarvestedBoundaryCheck`, place `Kept in sync with` markers at both
      ends, and update the registry row's invariant; verify: specs — failed
      diff aborts as infrastructure with no attempt burned and no violation
      attributed; seeded tamper still violates; both media covered (M6).
- [ ] 9.2 `EnvironmentAttemptPersistence` + `EnvironmentRoundSnapshot`:
      verify tip resolutions, fail the persist with git evidence on non-zero
      exit or blank output; confirm the host twin `GitAttemptPersistence`
      obeys the same rule (mirror obligation); verify: specs — no record with
      a blank tip is ever created; failed persist follows the existing
      infrastructure handling (M6).
- [ ] 9.3 `MidRoundHarvestListener`: a failed tip resolution skips the
      observation (never reported as tip moved/lost), logging via the
      suppressor path from 5.1; verify: spec — failed resolution changes no
      harvest decision.
- [ ] 9.4 `TaskBranchLister`: enumeration failure fails `gnomish status` list
      mode with the git evidence (task-inspection delta scenario); per-branch
      degradation unchanged; verify: spec — non-zero `for-each-ref` yields a
      command error, healthy-branch listing specs stay green (M6).

## 10. Verification and audit closure

- [ ] 10.1 M1: healthy-cycle E2E asserts zero WARN/ERROR after startup
      (serve tick + task round with all-green checks).
- [ ] 10.2 M3: coverage check — every FR5 enumeration item maps to a spec
      (grep table in the task's verification notes).
- [ ] 10.3 M5: kill-during-drain spec — SIGTERM mid-drain, assert terminal
      lines + summaries + stopping anchor present in the file.
- [ ] 10.4 UX2: lifecycle-grep spec — a full task run's log filtered by taskId
      reconstructs claim → events → summary in order.
- [ ] 10.5 Sync-pair audit closure (D8): every pair row touched by this change
      carries markers at both ends or a stated no-mirror rationale; registry
      updated (rows with markers removed per the rule); verify:
      `grep -rn "Kept in sync with" src/main` output reviewed against D8's
      table.
- [ ] 10.6 Full `./gradlew check` green including PIT gates for new classes
      (`LogText`, `RepeatSuppressor`, `AnchorLog`, assemblers, suppressor
      call sites), and M4 re-verified (no operator-log pollution).
