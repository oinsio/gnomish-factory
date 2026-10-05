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
- [ ] 1.3 Update `ApiExecutorRule`'s message to name `agent-cli`, `program` and `command`
  as the supported types; verify its spec asserts the new message and still rejects
  `api` stages (NG5)

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

## 3. Engine contract check (no engine change)

- [ ] 3.1 Verify by an engine spec driven with a fake executor returning the command
  executor's three statuses (`completed`, `failed/quality` with one finding,
  `failed/infrastructure`) that the engine's existing classifier burns, records and
  feeds forward exactly as define-executor-contract specifies, and that no source file
  under `domain/engine` changes in this change (`git diff --stat`) (FR6, FR7, NG6)

## 4. Shared command-run core

- [ ] 4.1 Generalize `CommandProcessRunner` into the shared command-run core (command
  string + env fragment + timeout in; exit code, bounded tail, termination kind out)
  consumed by `ShellCommandCheckRunner`; verify the extraction is behavior-preserving:
  every existing `ShellCommandCheckRunner`/`CommandProcessRunner` spec stays green
  unchanged (design D2)

## 5. Command stage executor

- [ ] 5.1 Implement `CommandStageExecutor` as a built-in `Executor` of the contract
  (`describe` with role `transform`, `start`) on the shared core over the round
  environment: `sh -c` via `TaskExecutionEnvironment.exec`, layered env allowlist, no
  `GNOMISH_DECISION_FILE`, `roundTimeout` resolved via the shared `RoundTimeout`
  shapes; verify with specs: exit 0 → `completed`, env composition, decision variable
  absent, stray decision file ignored (FR4, FR5, NFR-S1, NG1)
- [ ] 5.2 Implement the status mapping: exit ≠ 0 → `failed/quality` with one finding
  naming the exit code and the sanitized bounded output tail as details
  (`FindingsSanitizer`); spawn failure and exits 126/127 → `failed/infrastructure`;
  timeout → process tree killed, `failed/infrastructure`; verify each with specs
  including an ANSI-noise output case and a virtual-time timeout case; run the
  conformance kit against the built-in (FR6, FR7, FR8, NFR-O1, UX1)
- [ ] 5.3 Verify zero-token telemetry by spec: a command round records an empty
  per-model token map and empty trace, wall time is recorded, and cumulative task
  totals are unchanged (NFR-C1)

## 6. Dispatch and wiring

- [ ] 6.1 Register `CommandStageExecutor` under the built-in name `command` in the
  `ExecutorRegistry` of define-executor-contract; add no selector arm; verify with
  specs on the real wiring: a mixed pipeline resolves the command stage to the
  built-in, interactive mode still substitutes agent stages while the command stage
  runs its command (FR8, design D6)

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
