# Tasks: add-command-executor

## 1. Domain model and manifest validation

- [ ] 1.1 Add `ExecutorType.COMMAND` and extend `StageDefinition.Executor` with an
  optional command field (blank for agent types); verify with a failing-then-green
  Spock spec on the record's invariants (FR1, FR2)
- [ ] 1.2 Rewrite `StageSanityRule`: model required non-blank for `API`/`AGENT_CLI`,
  located error when present on `COMMAND`; `command` required non-blank on `COMMAND`,
  located error when present on agent types; judge model rule unchanged; verify with
  data-driven specs covering all four new error scenarios plus the accepted
  command-stage case (FR2, UX2)
- [ ] 1.3 Update `ApiExecutorRule`'s message to name `agent-cli` and `command` as the
  supported types; verify its spec asserts the new message and still rejects `api`
  stages (NG5)

## 2. Wire schema and mapping

- [ ] 2.1 Add `command` to `StructuralValidation.EXECUTOR_TYPES`, add `command` to
  `ExecutorDto`, and map the token in `StageDefinitionMapper.mapExecutorType`; verify
  unknown tokens still produce located errors and a valid command manifest loads
  (FR1, FR2)
- [ ] 2.2 Write the data-driven executor-type round-trip spec iterating every
  `ExecutorType.values()` constant through wire token and back, pinning
  unknown-token behavior — per testing.md's wire-vocabulary rule (design D2)
- [ ] 2.3 Add command-settings validation beside `AgentSettingsValidator`: recognized
  keys = `{roundTimeout}`, reusing the existing `roundTimeout` well-formedness check;
  verify specs cover unknown key, malformed and well-formed `roundTimeout` (number and
  ISO string), each as a located error naming stage and key (FR3, UX2)

## 3. Engine: executor-reported quality failure

- [ ] 3.1 Add the sealed `ExecutionResult.Failed(usage, trace, findings)` variant and
  the `RoundExecution` switch arm mapping it to a recorded quality-failure round
  (synthetic non-Pass result joining `priorFailures`, verify chain not invoked);
  verify with engine specs: attempt burned, feedback carried to the next request,
  no check port invoked (FR6, NFR-R1)
- [ ] 3.2 Extend engine specs for the unchanged machinery around `Failed`: strict
  persistence ordering (persist → AttemptFinished → next AttemptStarted), engine
  events emitted, exhaustion escalates `AttemptsExhausted` with full history, and
  telemetry recorded on the failed round (NFR-R2, NFR-O1)

## 4. Shared command-run core

- [ ] 4.1 Generalize `CommandProcessRunner` into the shared command-run core (command
  string + env fragment + timeout in; exit code, bounded tail, termination kind out)
  consumed by `ShellCommandCheckRunner`; verify the extraction is behavior-preserving:
  every existing `ShellCommandCheckRunner`/`CommandProcessRunner` spec stays green
  unchanged (design D2)

## 5. Command stage executor

- [ ] 5.1 Implement `CommandStageExecutor` on the shared core over the round
  environment: `sh -c` via `TaskExecutionEnvironment.exec`, layered env allowlist, no
  `GNOMISH_DECISION_FILE`, `roundTimeout` resolved via the shared `RoundTimeout`
  shapes; verify with specs: exit 0 → `Completed`, env composition, decision variable
  absent, stray decision file ignored (FR4, FR5, NFR-S1, NG1)
- [ ] 5.2 Implement the failure classification: exit ≠ 0 → `Failed` with one finding
  naming the exit code and the sanitized bounded output tail as details
  (`FindingsSanitizer`); spawn failure and exits 126/127 → infrastructure; timeout →
  process tree killed, infrastructure; verify each with specs including an
  ANSI-noise output case and a virtual-time timeout case (FR6, FR7, NFR-O1, UX1)
- [ ] 5.3 Verify zero-token telemetry by spec: a command round records an empty
  per-model token map and empty trace, wall time is recorded, and cumulative task
  totals are unchanged (NFR-C1)

## 6. Dispatch and wiring

- [ ] 6.1 Add the type-keyed dispatching `StageExecutor` and assemble it in
  `ExecutorAdapterSelector` (interactive substitution wraps the agent branch only);
  update the selector's javadoc; verify with specs: mixed pipeline dispatches per
  stage, interactive mode still substitutes agent stages while the command stage runs
  its command (FR8, design D6)

## 7. Integration, docs, and gates

- [ ] 7.1 Add a host-mode and a container-mode integration spec running a pipeline
  with a real command stage (modifies the working copy, then a failing-then-passing
  retry): harvest/attempt commits carry the command's effects, findings reach the
  next attempt, zero model invocations for the stage (FR9, M1, M2, G2)
- [ ] 7.2 Update `docs/glossary.md`: the Executor entry gains `command` (a declared
  shell command run in the task environment), disambiguated from the `command`
  verify check; verify by reading the rendered entry (process rule: glossary in the
  same change)
- [ ] 7.3 Run the traceability check: `grep` every FR/NFR/UX of this change against
  code, specs, and tests; then run `./gradlew check` on affected modules and confirm
  100% mutation score for new production code (M3)
