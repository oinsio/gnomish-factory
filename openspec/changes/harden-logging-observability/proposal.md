# Proposal: harden-logging-observability

## Why

A full logging audit (August 2026: four code audits across every module, a canonical
best-practices review, and a survey of comparable orchestrators — Kubernetes,
Temporal, CI runners, Airflow, Stripe) found that the factory's one job for its log —
tracing degrade paths in an unattended, concurrent daemon — is served by a channel
that is simultaneously **lossy** (swallowed failures, stack traces amputated by
`e.toString()`, GitHub retries and judge infrastructure failures entirely invisible)
and **noisy** (WARN on recovered/normal flow, unbounded WARN repetition in poll
loops, per-object INFO on every sweep tick). Task lifecycle has no anchor lines: no
"claim acquired", no serve start/stop, no container create/dispose — a post-mortem
cannot reconstruct a timeline. There is no written level policy, so every new emitter
decides by taste and the defects regrow. Untrusted text (LLM output, subprocess
stderr) is interpolated into log lines unsanitized at ~12 sites, allowing forged
log records via embedded newlines. Test runs pollute the operator's production log
file (observed live: Gradle test-worker stack traces in `~/.gnomish/logs/`).

Adjacent to the pure logging findings, the same audit surfaced a small class
of correctness bugs with the identical root shape — a git invocation's
evidence consumed without checking the invocation succeeded: the round
boundary tamper-check silently passes when its diff fails, blank branch tips
get recorded as attempt commits, and a failed branch enumeration renders as
"no tasks". They are fixed here because the remedy (verify the evidence,
classify the failure) is inseparable from the observability contract.

The audits reduced ~60 findings to four root causes — no written level policy,
"best-effort" not implying "leave a trace", no owner for repeat suppression, and an
unclosed untrusted-text loop — which this change closes systemically: policy as a
durable ADR + rule, mechanisms with single owners, and mechanical gates so the
defect classes cannot return.

## What Changes

- **ADDED** `factory-logging` capability: the written logging policy (level
  semantics, best-effort-must-log, untrusted-text choke point, throwable handling,
  repeat suppression, MDC context contract) plus its owner mechanisms: `AnchorLog`
  (task/daemon lifecycle anchor vocabulary), the canonical per-task summary line,
  the repeat suppressor, and the `LogText` sanitizer entry point.
- **MODIFIED** `factory-serve`: deterministic shutdown ordering — one owned shutdown
  sequence (drain → context close → logging stop) replacing today's race between the
  serve hook and Spring Boot's auto-registered hook; serve start/stop anchor lines.
- **MODIFIED** `sandbox-lifecycle`: no silent sweep skips — every enumerated object
  ends in a verdict (`SKIPPED_NO_VERDICT` instead of a logless `Optional.empty()`),
  and verdict log level follows verdict category (degraded sweeps become visible at
  WARN, steady-state drops to DEBUG).
- **MODIFIED** `manual-run`: the canonical end-of-task summary line is emitted in
  manual mode via an engine-event accumulator (manual run is the debugging mode; it
  must show where work stalls).
- **MODIFIED** `module-layering`: a new dependency-free leaf module `logtext`
  (precedent: `atomicfile`) holds the shared untrusted-text sanitizer, repeat
  suppressor, and MDC helper, reachable from `application`, adapters, and
  sandbox modules alike.
- **MODIFIED** `git-task-persistence`: boundary verification gains a third
  outcome — cannot-verify (a failed git probe never passes as clean, aborts as
  infrastructure without blaming the gnome); tip resolutions on durable paths
  refuse blank results instead of recording them.
- **MODIFIED** `task-inspection`: a failed branch enumeration fails
  `gnomish status` list mode with the git evidence instead of printing an
  empty "no tasks" table.
- Noise fixes across modules per the audit: level demotions (recovered transients,
  per-tool-call INFO, per-probe self-check chatter), duplicate-per-path removal,
  suppressed poll-loop repetition.
- Gap fixes across modules per the audit: silent-degradation sites get logs (GitHub
  retry visibility, judge `CannotVerify` paths, egress refusals in HTTP checks,
  stale-claim reaping, container lifecycle, git task-lifecycle commits, dashboard
  degradations, abort-facts fallback).
- Logback config hardening: async FILE appender (no-discard) with owned flush at
  shutdown, UTF-8 charset pinned, runtime log-level override without rebuild,
  `logback-test.xml` so test runs stop writing to the operator's log.
- Mechanical conventions: throwable as trailing argument everywhere (26 sites),
  MDC propagation to virtual threads, `stage`/`attempt` cleared at thread
  boundaries, `component` MDC key for daemon threads — each backed by a build gate
  or contract spec so it stays fixed.

## Goals

- **G1** A post-mortem over the rolling log alone can reconstruct any task's
  lifecycle: claim → stages → attempts → terminal outcome with duration and cost.
- **G2** The operator console (WARN+) carries only actionable signal: a healthy
  serve run produces no WARN; a persistent fault produces a bounded, deduplicated
  series, not a flood.
- **G3** Every degrade path leaves a trace: no swallowed failure, no silently
  degraded result, no invisible retry storm.
- **G4** The defect classes cannot silently return: level policy is written and
  citable; throwable handling and untrusted-text routing are mechanically gated.
- **G5** Log content is trustworthy: no forged records via untrusted newlines, no
  unbounded payloads, no test output in the production log file.

## Non-Goals

- **NG1** A general "check every git exit code" sweep beyond the enumerated
  verification/persistence/inspection sites of FR13 — read-only probes whose
  failure is benign (e.g. cleanup-commit history checks with idempotent
  consumers) get a diagnostic log only, not new behavior.
- **NG2** Structured (JSON) output for the human log file — the JSON
  ledgers/snapshots remain the machine-readable plane; the log stays greppable text.
- **NG3** Routing the four domain-layer port-failure logs through a new
  `EngineEvent.PortFailed` variant — recorded in the ADR as a known acceptable
  deviation; revisit only if the domain must become framework-free.
- **NG4** Per-task/per-attempt agent transcript files (the CI-runner two-plane
  split for LLM output) — a future change; this change only keeps agent output
  from entering factory log lines unsanitized.
- **NG5** Consolidating the 49 hand-rolled test log-capture blocks in one sweep —
  the shared helper becomes the rule and new/touched specs migrate; a bulk
  migration is not forced.
- **NG6** Remote log shipping, per-task factory log files, numeric verbosity
  levels — surveyed and rejected for a single-process CLI factory.

## Users & Scenarios

- **U1 Operator running `gnomish serve`**: sees a quiet console while healthy; on a
  fault, sees one WARN naming the fault and a recovery line when it clears; on
  SIGTERM, finds the drain summary and every in-flight task's terminal line in the
  log — none lost to the shutdown race.
- **U2 Operator debugging with `gnomish run`/`take`** (manual run is the debugging
  mode): sees where a round stalls, gets the canonical summary line telling what
  the task cost and where it ended; can raise verbosity via an environment
  variable without a rebuild.
- **U3 Post-mortem investigator** (human or AI) grepping the rolling file days
  later: `grep taskId=<id>` yields the full lifecycle including claim, reaping
  decisions about that task, and the terminal summary.
- **U4 Contributor adding a log line**: has a one-page rule stating which level to
  use, how to pass the throwable, and how to route untrusted text; the build fails
  if the conventions are violated.

## Requirements

### Functional

- **FR1** A logging-policy ADR SHALL define: level semantics as required reader
  reaction (ERROR = operator must act; WARN = operator should look, persistent WARN
  = act; INFO = lifecycle anchors and state changes; DEBUG = diagnosis), the
  best-effort-must-log rule, the log-or-rethrow boundary rule (one failure logged
  once, at the layer that decides), the log-as-expendable / ledger-as-durable
  retention rationale, and the accepted deviations (domain port-failure logs,
  unstructured text log). A companion `.claude/rules/logging.md` SHALL carry the
  emitter checklist.
- **FR2** A single owner class (`AnchorLog`) SHALL define the operator-plane anchor
  vocabulary: claim acquired, serve started/stopping (with config summary), task
  summary. Both claim paths call the same method. Remote-module anchors (container
  created/reattached/disposed, git task-lifecycle commits at their choke points)
  follow the ADR policy as plain INFO in their own modules.
- **FR3** Every task leaving the factory SHALL produce one canonical summary line:
  outcome, stage, attempts, wall time, token usage — in serve/take rendered from
  the `TaskOutcomeLine` facts at the ledger write point, in manual run assembled by
  an engine-event accumulator listener; one renderer for all modes. The line is
  emitted from a path that fires on crash-shaped exits too.
- **FR4** A single repeat-suppression owner SHALL provide edge logging for
  poll/retry loops: first occurrence (or reason change) at the site's level,
  repeats at DEBUG, a periodic roll-up with a count, and a recovery line. The known
  flooding sites (dead-container harvest poll, workflow-run poll, guard denial
  parse loop, scratch-tree deletion, first-push retry) route through it or through
  an aggregate counter.
- **FR5** Silent-degradation sites identified by the audit SHALL log: GitHub API
  retry/backoff/exhaustion events; every judge `CannotVerify` exit; egress refusals
  in guarded HTTP checks; stale-claim removal and index repair; abort-facts
  fallback to `none()`; claim-comment delete failure; container
  create/reattach/dispose outcomes including per-step dispose failures with
  environment key; git lifecycle commits; worktree removal failures; dashboard
  ledger/snapshot degradations distinguishing missing from malformed; empty
  token-usage extraction.
- **FR6** Untrusted text (agent/LLM output, subprocess stderr, tracker-sourced
  strings, in-container command output) SHALL enter log lines only through the
  sanitizer choke point: control/ANSI stripping, newline flattening (no multi-line
  forgery), and a length cap. The choke point builds on the existing
  `FindingsSanitizer` primitives (shared abstraction, not a second sanitizer).
- **FR7** Every log call site carrying an exception SHALL pass it as the trailing
  throwable argument (26 interpolating sites fixed; the 3 `getMessage()` sites that
  can print `null` included), enforced by a build gate.
- **FR8** MDC context SHALL be complete and leak-free: the capture/apply/clear
  pattern applied to every logging virtual-thread hop; `stage`/`attempt` cleared at
  the same thread boundaries that clear `taskId` (not only on `TaskFinished`); a
  `component` MDC key identifies daemon threads (janitor, reaper, snapshot, sweep,
  heartbeat); reaper/janitor per-task decisions carry `taskId` in MDC so
  `grep taskId=` finds them.
- **FR9** Serve shutdown SHALL be a single owned sequence: Spring Boot's automatic
  shutdown hook disabled, the serve hook ordering drain → context close → logging
  stop (flushing the async file appender), so terminal lines and summaries survive
  SIGTERM/SIGINT. During the shutdown phase, child-process death and daemon-thread
  interrupts are classified as shutdown-caused and logged without stack traces.
- **FR10** Logback configuration SHALL pin UTF-8 on all encoders, move the FILE
  appender to async with no event discarding, and support a runtime level override
  (environment/property) without rebuild. Logback's own shutdown hook stays absent
  (FR9's sequence owns the stop).
- **FR11** Test runs SHALL NOT write to the operator's log: a `logback-test.xml`
  isolates the suite; the shared log-capture helper (with level save/restore) is
  the documented way specs assert logging.
- **FR13** Evidence-producing git invocations SHALL be verified: the round
  boundary check distinguishes clean / violated / cannot-verify, with
  cannot-verify aborting as an infrastructure failure (no attempt burned, no
  violation attributed) in both boundary media; tip resolutions recorded
  durably or gating recovery refuse blank results and fail with the git
  evidence; read-only polls skip a failed observation rather than interpret
  it; the task-list enumeration failure surfaces as a command error, never an
  empty table.
- **FR12** Noise sites identified by the audit SHALL be releveled: sweep verdict
  level follows category (steady-state DEBUG, actions INFO, `SKIPPED_NO_VERDICT`
  WARN); recovered transients and first-of-two-attempts demoted from WARN;
  per-tool-call and per-probe INFO demoted to DEBUG; duplicate-per-path lines
  collapsed to one owner each.

### Non-Functional Reliability

- **NFR-R1** The shutdown sequence is idempotent and covers both drain and signal
  paths; a second run is a no-op. The async appender configuration never drops
  events while the JVM lives (`discardingThreshold=0`, blocking on full queue).
- **NFR-R2** The repeat suppressor is in-memory per-process state only; a restart
  resets it (first occurrence logs again) — no durable state, no recovery owner.

### Non-Functional Observability

- **NFR-O1** All observability changes are themselves contract-tested: anchor
  lines, summary content, suppression behavior, MDC completeness, and the shutdown
  ordering each have Spock specs asserting emitted events (the existing
  log-capture idiom).

### Non-Functional Security

- **NFR-S1** No secret values, tokens, or credential material in any log line
  (already true — preserved by the sanitizer choke point and asserted where
  practical); log forgery via embedded newlines/ANSI in untrusted text is closed
  at the choke point.

### Non-Functional Performance

- **NFR-P1** Worker (virtual) threads do not block on console/file I/O for INFO
  traffic: INFO volume goes to the async FILE appender; console appenders stay
  synchronous but carry only the post-cleanup WARN+ trickle.

## Operator Experience Criteria

- **UX1** A healthy hour of `serve` produces zero console output after startup;
  the startup banner names instance, WIP limit, grace, and intervals.
- **UX2** `grep taskId=<id>` over the log file reconstructs the task's full story
  in order, ending in one summary line with outcome, duration, attempts, tokens.
- **UX3** A persistent fault (dead Docker, revoked token) appears as one WARN, a
  periodic counted roll-up, and one recovery line — never a per-poll flood.
- **UX4** Ctrl+C / SIGTERM on serve leaves a log that says what was in flight,
  how it stopped, and the drain result — with no stack traces for shutdown-caused
  deaths.

## Success Metrics

- **M1** Zero WARN/ERROR lines during a healthy end-to-end serve cycle (E2E
  asserted); today's baseline is dozens per hour from sweep and poll chatter.
- **M2** 100% of exception-carrying log sites pass the throwable gate (build
  fails otherwise); baseline: 26 violations.
- **M3** 100% of the audit's silent-degradation list emits a log line, each backed
  by a spec (grep-verifiable against the FR5 enumeration).
- **M4** Zero factory log lines written by a full `./gradlew check` run to
  `~/.gnomish/logs/` (asserted by the test-config task).
- **M5** A kill during serve drain loses zero already-emitted terminal lines
  (shutdown-ordering spec; today the Spring hook race can drop all of them).
- **M6** Each FR13 site has a failing-invocation spec proving the new
  behavior (cannot-verify abort, refused blank tip, list-mode error) and a
  spec proving the previous silent path is dead; baseline: all three defect
  classes reproduce today.

## Open Questions

- **Q1** Placement of the repeat suppressor — resolved in design (D5): shared
  leaf module `logtext`; sandbox flood sites use local aggregate counters.
- **Q2** Serve summary assembly — resolved in design (D3): one neutral
  `TaskSummary` value and renderer; two declared-pair assemblers (serve maps
  `TaskOutcomeLine`, manual run accumulates engine events).

## Impact

- **Modules touched**: `application` (anchor log, suppressor, summary, MDC,
  serve shutdown, sweep verdict listener, dashboard readers), `bootstrap`
  (logback config, hook ownership, startup/shutdown lines, `logback-test.xml`),
  `adapters` (git, agent, github, shared check/secrets — demotions, gap logs,
  sanitizer routing), `sandbox` (docker — lifecycle logs, denial-loop
  suppression, MDC on pumps), `domain` (none beyond ADR-recorded status quo),
  new leaf module `logtext`, `build-logic` (gates), `docs/adr` +
  `.claude/rules` + `docs/glossary.md`.
- **Dependencies**: no new runtime dependencies; gates use the existing Error
  Prone / ArchUnit stack.
- **Sequencing**: starts after `harden-task-branch-contract` archives (same-file
  overlap in `adapters/git` and take routing; the summary's outcome vocabulary is
  defined against post-harden `TakeResult`). Independent of
  `fix-denial-attribution-durability` except trivial overlap.
- **Existing sync pairs touched** (mirrored edits in scope): salvage twins,
  attempt-persistence twins, resume-runner twins, round-environment-source twins.
