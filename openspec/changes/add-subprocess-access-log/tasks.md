# Tasks: add-subprocess-access-log

Sequenced after `harden-logging-observability` lands `:logtext` and the
Logback hardening (proposal — Impact). Group 1 is the format owner; nothing
in groups 3–4 may start before 1 is green (design D1).

## 1. Emitter and redactor in `:logtext`

- [ ] 1.1 TDD (red first): `AccessRecordSpec` pinning the line schema (FR1, FR5, design
      D7): OTel names (`process.executable.name`, `process.command_args`,
      `process.exit.code`, `error.type` on non-exited outcomes), `schema_version`,
      RFC3339 UTC `ts`, `duration_ms`, `termination`, `family`, `cwd`, and the
      correlation keys from a supplied MDC copy; exit code present only for `exited`;
      valid single-line JSON (embedded quotes/newlines in argv escaped). Verify: spec
      fails against a stub, then passes after 1.4.
- [ ] 1.2 TDD (red first): `ArgvRedactorSpec` pinning the three redaction layers in order
      (FR4, NFR-S1, design D4): env-value spans logged as `NAME=<placeholder>`; declared
      exact secret values replaced everywhere in argv; `scheme://userinfo@` structural
      net over the rendered argv; redaction precedes truncation and the hash (the
      truncated record's hash equals the hash of the full *redacted* argv). Verify: red.
- [ ] 1.3 TDD (red first): truncation scenarios in `AccessRecordSpec` (FR6): over-budget
      argv keeps a bounded prefix plus marker plus SHA-256 of the full redacted argv;
      at-budget argv passes verbatim with no marker; budget is a named constant with a
      derivation comment. Verify: red.
- [ ] 1.4 Implement in `:logtext`: the access-record value, the redactor, and the emitter
      writing one INFO line per record through the dedicated logger name (design D1, D5),
      including the emitter-owned termination vocabulary `exited | timed_out |
      interrupted`. Traceability: `Implements FR1, FR3, FR4, FR5, FR6 of
      add-subprocess-access-log`. Verify: 1.1–1.3 green; `:logtext` dependency gate still
      shows slf4j-api only (module-layering delta scenario).
- [ ] 1.5 Round-trip wire-vocabulary spec (FR5, design D8, testing.md rule): data-driven
      over **every** `Termination` constant and every `ExecHandle.Wait` variant into the
      emitter's tokens and back — iterate `values()`, no hand-listed subset — and add the
      `Kept in sync with` javadoc markers on both ends of the declared pair
      (emitter vocabulary ↔ `subprocess` `Termination` mapping sites). Verify: spec green;
      `grep -rn "Kept in sync with"` lists both ends.
- [ ] 1.6 Best-effort guarantee (NFR-R1): spec asserting a throwing sink (appender/logger
      failure simulated at the emitter's seam) returns control without propagating, and
      the emission failure itself is traced per the logging policy. Verify: spec green.

## 2. Sink configuration in `bootstrap`

- [ ] 2.1 Logback config (FR7, design D5, D6): route the dedicated logger to a JSONL
      file appender — additivity off, async no-discard, daily UTC rolling
      `access-YYYY-MM-DD.jsonl` with a bounded history cap — layered onto the hardened
      config from `harden-logging-observability`. Verify: a bootstrap spec (or config
      test) shows an emitted record lands in the access file and not in the human log
      or on the console.
- [ ] 2.2 Test isolation (FR7, M4): extend `logback-test.xml` so suite runs write zero
      access records to the operator's log directory. Verify: run a spec that emits and
      assert the operator directory gains no access file.

## 3. Direct emission sites

- [ ] 3.1 TDD (red first) then wire `GitProcessRunner` (FR1, FR2, FR4): emit one record
      per invocation at the point where `startedAt`/`Termination` already exist
      (GitProcessRunner.execute), family `git`, declaring credential-bearing remote-URL
      secret values where the adapter holds them; spec drives success, timeout, and
      interrupt outcomes and asserts exactly one record each. Verify: spec green;
      existing `GitProcessRunner` specs unchanged.
- [ ] 3.2 TDD (red first) then wire `DockerCli` (FR1, FR2, FR4): emit at the existing
      `capture` measurement point, family `docker`, passing the `-e NAME=value` env
      spans for elision (design D4); spec asserts no env value ever appears in the
      record, including `ANTHROPIC_AUTH_TOKEN`-shaped entries. Verify: spec green.
- [ ] 3.3 `:gitobjects` observer hook (FR2, design D3): TDD (red first) a JDK-only
      functional observer interface on the module's public entry point receiving
      executable, args, git-dir, start, duration, exit code, and termination as JDK
      types; default no-op; `GitExec` reports through it on every outcome. Verify: spec
      green; `GitObjectsBoundarySpec` / dependency gate proves the production dependency
      set is unchanged (module-layering delta scenario).
- [ ] 3.4 Bootstrap wiring for 3.3 (FR2, FR3): the factory-side observer implementation
      forwards to the `:logtext` emitter with family `gitobjects`; wired wherever
      bootstrap constructs the gitobjects entry points. Verify: an integration spec
      through the wired assembly shows a gitobjects plumbing run appending one record.

## 4. `AuditedEnvironment` decorator

- [ ] 4.1 TDD (red first): `AuditedEnvironmentSpec` in `:sandbox:docker` (FR1, FR2, FR8,
      design D2) — `exec` wraps the returned handle; when the wait resolves as
      `Exited`/`TimedOut`/`Interrupted` exactly one record is emitted with family
      `environment`, `ExecHandle.startedAt()` as the start, the measured duration, and
      the MDC copy captured at `exec` time (assert cross-thread resolution keeps the
      launch-time `taskId`/`stage`/`attempt`); all other port methods delegate
      untouched. Verify: red, then green after 4.2.
- [ ] 4.2 Implement `AuditedEnvironment` (decorator precedent `LeasedEnvironment` /
      `SelfCheckedEnvironment`) and add it to the environment assembly for **both**
      host and container adapters at the same layer the existing decorators are applied.
      Traceability: `Implements FR1, FR2, FR8 of add-subprocess-access-log`. Verify:
      4.1 green; existing environment specs unchanged.

## 5. Security and coverage verification

- [ ] 5.1 Fake-secret leak scans (NFR-S1, M2): per-seam specs injecting known fake
      credentials — AI-seam token through the container exec path, a
      `https://token@host` remote URL through the git path — then scanning every
      emitted record for zero occurrences and placeholder presence. Verify: specs green.
- [ ] 5.2 E2E family coverage (M1): extend the existing E2E run to assert the access
      file contains records from all four families (`git`, `docker`, `environment`,
      `gitobjects`) and that an agent-spawned in-box process leaves no record (NFR-S2).
      Verify: E2E assertion green.
- [ ] 5.3 Honest scope statement (NFR-S2): the capability's operator-facing
      documentation (emitter/package javadoc plus the docs page that describes the
      observability files) states factory-issued-only coverage, names the in-box
      bounding mechanisms (egress guard, boundary checks, ff-only harvest), and the
      operational-not-forensic stance. Verify: statements greppable in docs and javadoc.

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
      covers FR1–FR8, NFR-R1, NFR-S1, NFR-S2, NFR-P1.
