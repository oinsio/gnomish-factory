# Tasks: add-subprocess-access-log

Sequenced after `harden-logging-observability` lands `:logtext` and the
Logback hardening (proposal — Impact). Group 1 is the format owner; nothing
in groups 3–4 may start before 1 is green (design D1). Groups 7–10 (secrets
at source, bounded waits, judge WARN, spawn gate) are independent of the
emitter and may run in parallel with groups 1–5.

## 1. Emitter and redactor in `:logtext`

- [ ] 1.1 TDD (red first): `AccessRecordSpec` pinning the line schema (FR1, FR5, design
      D7): OTel names (`process.executable.name`, `process.command_args`,
      `process.exit.code`, `error.type` on non-exited outcomes), `schema_version`,
      RFC3339 UTC `ts`, `duration_ms`, `termination`, `family`, `cwd`, and the
      correlation keys from a supplied MDC copy; a generated execution id; the
      environment family additionally carries the mechanism token (`host` |
      `container`), the container identity where one exists, and env variable names
      only (FR9); exit code present only for `exited`; valid single-line JSON
      (embedded quotes/newlines in argv escaped). Verify: spec fails against a stub,
      then passes after 1.4.
- [ ] 1.2 TDD (red first): `ArgvRedactorSpec` pinning the redaction layers in order
      (FR4, NFR-S1, design D4): declared exact secret values replaced everywhere in
      argv; `scheme://userinfo@` structural net; env-span second net rewriting any
      `-e NAME=value` / `--env NAME=value` span value-less with a placeholder;
      redaction precedes truncation and the hash (the truncated record's hash equals
      the hash of the full *redacted* argv). Verify: red.
- [ ] 1.3 TDD (red first): truncation scenarios in `AccessRecordSpec` (FR6): over-budget
      argv keeps a bounded prefix plus marker plus SHA-256 of the full redacted argv;
      at-budget argv passes verbatim with no marker; budget is a named constant with a
      derivation comment. Verify: red.
- [ ] 1.4 Implement in `:logtext`: the access-record value, the redactor, and the emitter
      writing one INFO line per record through the dedicated logger name (design D1, D5),
      including the emitter-owned termination vocabulary `exited | timed_out |
      interrupted | start_failed` (design D10). Traceability: `Implements FR1, FR3, FR4,
      FR5, FR6, FR9, FR11 of add-subprocess-access-log`. Verify: 1.1–1.3 green;
      `:logtext` dependency gate still shows slf4j-api only (module-layering delta
      scenario).
- [ ] 1.5 Round-trip wire-vocabulary spec (FR5, FR11, design D8, testing.md rule):
      data-driven over **every** `Termination` constant and every `ExecHandle.Wait`
      variant into the emitter's tokens and back — iterate `values()`, no hand-listed
      subset — pinning `start_failed` as the sole emitter-owned token mapping from no
      constant, and add the
      `Kept in sync with` javadoc markers on both ends of the declared pair
      (emitter vocabulary ↔ `subprocess` `Termination` mapping sites). Verify: spec green;
      `grep -rn "Kept in sync with"` lists both ends.
- [ ] 1.6 Best-effort guarantee (NFR-R1): spec asserting a throwing sink (appender/logger
      failure simulated at the emitter's seam) returns control without propagating, and
      the emission failure itself is traced per the logging policy. Verify: spec green.

## 2. Sink configuration in `bootstrap`

- [ ] 2.1 Logback config (FR7, UX1, design D5, D6): route the dedicated logger to a JSONL
      file appender — additivity off, async no-discard, daily UTC rolling
      `access-YYYY-MM-DD.jsonl` with a bounded history cap — layered onto the hardened
      config from `harden-logging-observability`. Verify: a bootstrap spec (or config
      test) shows an emitted record lands in the access file and not in the human log
      or on the console.
- [ ] 2.2 Test isolation (FR7, M4): extend `logback-test.xml` so suite runs write zero
      access records to the operator's log directory. Verify: run a spec that emits and
      assert the operator directory gains no access file.

## 3. Direct emission sites

- [ ] 3.1 TDD (red first) then wire `GitProcessRunner` (FR1, FR2, FR4, NFR-O1): emit one
      record per invocation at the point where `startedAt`/`Termination` already exist
      (GitProcessRunner.execute), family `git`, declaring credential-bearing remote-URL
      secret values where the adapter holds them. The existing `startedAt` is a
      monotonic `System.nanoTime()` reading — good for duration only; the record's
      wall-clock start timestamp comes from an injected `Clock` (time injection per
      testing.md). Spec drives success, timeout, and interrupt outcomes and asserts
      exactly one record each. Verify: spec green; existing `GitProcessRunner` specs
      unchanged.
- [ ] 3.2 TDD (red first) then wire `DockerCli` (FR1, FR2, FR4, NFR-O1): emit at the
      existing `capture` measurement point — management commands only, family `docker`
      (design D2, D9: the streaming `start` path is represented by the environment
      family and the file channel, never logged as wrapper argv); the wall-clock start
      timestamp comes from an injected `Clock` (the existing measurement is monotonic,
      duration-only). Spec asserts management records carry no env value. Verify: spec
      green.
- [ ] 3.2a TDD (red first) then wire `ContainerFileChannel` (FR1, FR10, NFR-O1, design
      D2): each `putFile`/`readFile` docker execution emits one docker-family record
      where the channel resolves its wait, using the channel's captured start and the
      MDC captured at call time; a start failure emits `start_failed` (FR11). Verify:
      spec drives success and start-failure outcomes, exactly one record each; the
      timeout outcome joins in 8.1 once the channel's wait is bounded.
- [ ] 3.3 `:gitobjects` observer hook (FR2, NFR-O1, design D3): TDD (red first) a JDK-only
      functional observer interface on the module's public entry point receiving
      executable, args, git-dir, start, duration, exit code, and termination as JDK
      types; default no-op; `GitExec` reports through it on every outcome. `GitExec`
      measures nothing today (its await is unbounded), so start capture and duration
      measurement are added with the hook, on an injected clock per testing.md, JDK
      types only. Verify: spec
      green; `GitObjectsBoundarySpec` / dependency gate proves the production dependency
      set is unchanged (module-layering delta scenario).
- [ ] 3.4 Bootstrap wiring for 3.3 (FR2, FR3): the factory-side observer implementation
      forwards to the `:logtext` emitter with family `gitobjects`; wired wherever
      bootstrap constructs the gitobjects entry points. Verify: an integration spec
      through the wired assembly shows a gitobjects plumbing run appending one record.

## 4. `AuditedEnvironment` decorator

- [ ] 4.1 TDD (red first): `AuditedEnvironmentSpec` in `:sandbox:docker` (FR1, FR2, FR8,
      FR9, NFR-O1, design D2, D9) — `exec` wraps the returned handle; when the wait
      resolves as `Exited`/`TimedOut`/`Interrupted` exactly one record is emitted with
      family `environment`, the logical `ExecCommand` argv, the mechanism token and
      container identity, env variable names only, a generated execution id,
      `ExecHandle.startedAt()` as the start, the measured duration, the exit code
      obtained via `waitForExit()` on a natural exit, and the MDC copy captured at
      `exec` time (assert cross-thread resolution keeps the launch-time
      `taskId`/`stage`/`attempt`); a `ProcessStartException` from the delegate emits
      one `start_failed` record (FR11) and rethrows; all other port methods delegate
      untouched. Verify: red, then green after 4.2.
- [ ] 4.2 Implement `AuditedEnvironment` (decorator precedent `LeasedEnvironment` /
      `SelfCheckedEnvironment`) and apply it **innermost** — directly around the raw
      adapter — at every environment wiring point (design D2, FR12): the container
      environment builder (so `SelfCheckedEnvironment` and `LeasedEnvironment` wrap
      the audited instance), **and** each of the three host environment sources
      (`HostRoundEnvironmentSource`, `HostJudgeEnvironmentSource`,
      `HostCheckEnvironmentSource`), which today return a bare
      `HostTaskExecutionEnvironment` with no decorator layer.
      Traceability: `Implements FR1, FR2, FR8, FR9, FR12 of add-subprocess-access-log`.
      Verify: 4.1 green; existing environment specs unchanged; a wiring assertion (or
      spec per source) shows all four points hand out an audited environment.
- [ ] 4.3 Route the environment self-check through the audited seam (FR12, design D2):
      `EnvironmentSelfCheck` / `EgressSelfCheckProbes` receive the audited environment
      instead of the raw adapter, so the five probe execs emit records. Verify: a spec
      shows a self-check run appends one environment-family record per probe; existing
      self-check specs unchanged.

## 5. Security and coverage verification

- [ ] 5.1 Fake-secret leak scans (NFR-S1, M2): per-seam specs injecting known fake
      credentials — AI-seam token through the container exec path, a
      `https://token@host` remote URL through the git path — then scanning every
      emitted record for zero occurrences and placeholder presence. Verify: specs green.
- [ ] 5.2 E2E family coverage (M1, M5): extend the existing E2E run to assert the access
      file contains records from all four families (`git`, `docker`, `environment`,
      `gitobjects`) including a file-channel docker record and a self-check probe
      record, that no record carries a wrapper `docker exec` argv or an env value, and
      that an agent-spawned in-box process leaves no record (NFR-S2). Verify: E2E
      assertion green.
- [ ] 5.3 Honest scope statement (NFR-S2, NFR-S3, UX2): the capability's operator-facing
      documentation (emitter/package javadoc plus the docs page that describes the
      observability files) states factory-issued-only coverage, names the in-box
      bounding mechanisms (egress guard, boundary checks, ff-only harvest), the
      operational-not-forensic stance, the accepted factory-crash gap with its named
      owners (attempt journal, task branch), the git `ext::` grandchild representation,
      and the host-mode reachability of the origin remote credential (removed in
      container mode). Verify: statements greppable in docs and javadoc.

## 6. Gates and record

- [ ] 6.1 Add the `access log` entry to `docs/glossary.md` (new domain term introduced
      by this change, per process-invariants). Verify: entry present, banned synonyms
      considered.
- [ ] 6.2 Run `./gradlew check` for every touched module (`:logtext`, `:bootstrap`,
      `:adapters:git`, `:sandbox:docker`, `:gitobjects`, `:adapters:agent`) — Spotless,
      Error Prone/NullAway, layering gates, Spock, JaCoCo, PIT at 100% for new classes
      (any exemption justified per testing.md). Verify: BUILD SUCCESSFUL, no new PIT
      survivors.
- [ ] 6.3 Cross-check traceability per `.claude/rules/traceability.md`: every FR/NFR of
      this change greps to at least one implementing entity and one spec. Verify:
      `grep -rn "add-subprocess-access-log" --include=*.java --include=*.groovy .`
      covers FR1–FR16, NFR-R1, NFR-R2, NFR-O1, NFR-S1, NFR-S2, NFR-S3, NFR-P1.

## 7. Secrets at the source, minimized child environments

- [ ] 7.1 TDD (red first) then change `DockerCommands.exec` (FR14, M5, design D11):
      env entries render as value-less `-e NAME` flags; the composed argv never
      contains an env value. Spec over the composed argv asserts value-less flags
      only, including `ANTHROPIC_AUTH_TOKEN`-shaped entries. Verify: spec green.
- [ ] 7.2 TDD (red first) then minimize the docker client's child environment (FR14,
      FR15, design D11): `DockerCli`'s builder clears the inherited environment and
      re-adds exactly what the client needs plus the values behind the value-less
      `-e NAME` flags for `exec`. Spec asserts the docker child env contains no
      tracker/check credential and that a value-less `-e NAME` still delivers the
      value into the box (container E2E covers delivery end-to-end). Verify: specs
      green.
- [ ] 7.3 TDD (red first) then minimize the git client's child environment (FR15,
      design D11): `GitProcessRunner`'s builder clears the inherited environment and
      re-adds only what git needs (credential-helper resolution included — keep
      `HOME`/`PATH`-class variables, document the retained set in place). Spec asserts
      no AI or check credential reaches the git child env. Verify: spec green;
      existing git adapter specs unchanged.

## 8. Bounded waits (NFR-R2, M6, design D12)

- [ ] 8.1 TDD (red first) then bound `ContainerFileChannel`: both `putFile` and
      `readFile` waits take the configured docker-command timeout; expiry follows the
      channel's failure handling and emits `timed_out` (with 3.2a). Spec on virtual
      time proves the deadline expires instead of hanging. Verify: spec green.
- [ ] 8.2 TDD (red first) then bound the self-check probes and in-box service git
      commands: `EnvironmentSelfCheck` / `EgressSelfCheckProbes` and
      `InBoxGitCommand`-class callers use `waitForExitOrTimeout` with a named bound
      instead of unbounded `waitForExit`. Spec on virtual time per seam. Verify:
      specs green; any wait left unbounded carries a written justification at the
      call site.
- [ ] 8.3 `DockerRuntimeProbe` uses the operator-configured docker-command timeout
      instead of the default (NFR-R2). Verify: construction-site spec or assembly
      assertion shows the configured value reaches the probe.

## 9. Judge WARN through the choke point (FR16, design D13)

- [ ] 9.1 TDD (red first) then switch `JudgeVerdictExtractor`'s raw-message WARN to
      `LogText.forLog` (strip, cap, flatten), and reconcile the site's javadoc with
      ADR 0004. Verify: a spec asserts a multi-line model message produces one log
      record.
- [ ] 9.2 Extend the untrusted-log-text gate so this shape cannot recur: a
      `FindingsSanitizer.` callee inside a log argument outside the findings funnel
      fails the gate. Verify: gate spec red on the old code shape, green after 9.1.

## 10. Spawn-boundary gate and honest seam wording (FR13, design D14)

- [ ] 10.1 Widen `ProcessSpawnBoundarySpec` to the enumerated whitelist of allowed
      spawn sites (the two environment adapters, `DockerCli`, `CaptureRunner`,
      `GitExec`, `ContainerFileChannel`); any other production `ProcessBuilder`
      reference fails the build. Verify: gate green on the tree; a deliberate
      violation in a scratch spec goes red.
- [ ] 10.2 Narrow the sole-seam wording where it lives in code: the
      `TaskExecutionEnvironment` and `HostTaskExecutionEnvironment` javadoc names the
      two disclosed bypasses (container file channel; git `ext::` grandchild, killed
      and reaped through the supervised git tree). Verify: wording greppable; matches
      the `execution-environment` delta's modified sole-seam requirement.
