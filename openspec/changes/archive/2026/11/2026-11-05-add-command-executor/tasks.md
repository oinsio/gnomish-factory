# Tasks: add-command-executor

## 1. Domain model and manifest validation

- [x] 1.1 Add `ExecutorType.COMMAND` and extend `StageDefinition.Executor` with an
  optional command (absent for the other types) carried as a distinct value type — a
  `CommandLine` record wrapping the non-blank string — not a third `String` beside `model`
  and `name` (the transposition hazard of `process-invariants.md`); verify with a
  failing-then-green Spock spec on the record's invariants, and extend the closed-enum
  feature of `StageDefinitionSpec` with `COMMAND` (FR1, FR2)
- [x] 1.2 Rewrite `StageSanityRule`: model required non-blank for `API`/`AGENT_CLI`
  (`PROGRAM` stays exempt, as built), located error when present on `COMMAND`; `command`
  required non-blank on `COMMAND`, located error when present on an `api`, `agent-cli` or
  `program` stage; judge model rule unchanged; verify with data-driven specs covering all
  four new error scenarios plus the accepted command-stage case (FR2, UX2)
- [x] 1.3 Update `ApiExecutorRule`'s message to name `agent-cli`, `program` and `command`
  as the supported types; verify its spec asserts the new message and still rejects
  `api` stages (NG5)

## 2. Wire schema and mapping

- [x] 2.1 Add `command` to `StructuralValidation.EXECUTOR_TYPES`, add `command` to
  `ExecutorDto`, and map the token in `StageDefinitionMapper.mapExecutorType`; verify
  unknown tokens still produce located errors and a valid command manifest loads
  (FR1, FR2)
- [x] 2.2 Write the data-driven executor-type round-trip spec iterating every
  `ExecutorType.values()` constant through wire token and back, pinning
  unknown-token behavior — per testing.md's wire-vocabulary rule (design D2)
- [x] 2.3 Add command-settings validation beside `AgentSettingsValidator`: recognized
  keys = `{roundTimeout}`, reusing the existing `roundTimeout` well-formedness check;
  verify specs cover unknown key, malformed and well-formed `roundTimeout` (number and
  ISO string), each as a located error naming stage and key (FR3, UX2)
- [x] 2.4 Route every opaque settings or params map through one copy owner, `OpaqueSettings.copy`,
  that reports a key with no value as a located error at `<where>.<key>` instead of failing the
  load with an exception; verify by `OpaqueSettingsSpec` and loader specs over executor, judge and
  declaration settings (UX2, design D10)

## 3. Engine contract check (no engine change)

- [x] 3.1 Verify by an engine spec driven with a fake executor returning the command
  executor's three statuses (`completed`, `failed/quality` with its reason as the one
  finding, `failed/infrastructure`) that the engine's existing classifier burns, records
  and feeds forward exactly as define-executor-contract specifies — the second attempt's
  request carries the first's finding — and that no source file under `domain/engine`
  changes in this change (`git diff --stat`), which is also the evidence of design D7: no
  new durable step (FR6, FR7, NFR-R1, NFR-R2, NG6)

## 4. Shared command-run core

- [x] 4.1 Generalize `CommandProcessRunner` into the shared command-run core (command
  string + env fragment + timeout in; exit code, bounded tail, termination kind out), a
  public type of `:adapters` consumed by `ShellCommandCheckRunner`; verify the extraction
  is behavior-preserving: every existing `ShellCommandCheckRunner`/`CommandProcessRunner`
  spec stays green unchanged (design D2)
- [x] 4.2 Generalize `ProgramStageExecutor` (`:adapters:agent`, `program/`) over an executor
  factory — round environment in, contract `Executor` out — so one round wrapper (open,
  probe, run, close, denials, `ProgramResultMapping.toEngine`) serves a program and the
  command built-in; the `program` factory constructs exactly what the wrapper constructed;
  verify every existing `ProgramStageExecutor` spec stays green unchanged (design D2, the
  round-wrapper surface; single-owner table)

## 5. Command stage executor

- [x] 5.1 Implement `CommandStageExecutor` in `:adapters:agent` beside `program/` as a
  built-in `Executor` of the contract (`describe` with role `transform`, `start`, and
  `poll` answering `failed/infrastructure` — a command round is never pending) on the
  shared core over the round environment: `sh -c` via `TaskExecutionEnvironment.exec`,
  layered env allowlist, no `GNOMISH_DECISION_FILE`, `roundTimeout` resolved via the shared
  `RoundTimeout` shapes; verify with specs: exit 0 → `completed`, env composition, decision
  variable absent, stray decision file ignored, `poll` never pending (FR4, FR5, NFR-S1, NG1)
- [x] 5.2 Implement the status reporting: exit ≠ 0 → `failed/quality` whose `reason` names
  the exit code and carries the sanitized bounded output tail (`FindingsSanitizer`) — the
  one finding the contract's mapping (`ProgramResultMapping`, task 4.2) hands the engine;
  spawn failure and exits 126/127 → `failed/infrastructure`; timeout → process tree
  killed, `failed/infrastructure`; verify each with specs including an ANSI-noise output
  case and a virtual-time timeout case; run the conformance kit against the built-in in
  process through `BuiltInCandidate` (FR6, FR7, FR8, NFR-O1, UX1)
- [x] 5.3 Verify zero-token telemetry by spec: a command round reports usage with provenance
  `REPORTED`, an empty per-model map and an empty trace (design D8), the built-in's describe
  declares `reportsUsage = true`, wall time is recorded, and cumulative task totals, cost
  completeness included, are unchanged (NFR-C1)

## 6. Dispatch and wiring

- [x] 6.1 Register the `command` built-in as one entry of `ExecutorRegistry` at wiring
  (`ExecutorRegistry.of` takes it beside the law's declarations), built by the shared round
  wrapper of task 4.2 over `CommandStageExecutor`; verify with specs on the real wiring: a
  mixed pipeline resolves the command stage to the built-in while the agent stage still
  runs through the `agent-cli` arm, and a law declaring an executor named `command` is refused
  when the registry is built (FR8, design D6, D9)
- [x] 6.2 Add the `COMMAND` arm to `ExecutorDispatch` (`:bootstrap`): `case COMMAND ->
  registry.resolve(<the built-in's name>)`, the one type-to-name mapping (design D6,
  single-owner table); `ExecutorDeclaration.Type` keeps `PROGRAM` only; bring its javadoc and
  `ExecutorRegistry`'s ("add-command-executor registers command here") to what is built; a
  spec on the real wiring shows a `command` stage never reaches the agent arm (FR8, D6)

## 7. Integration, docs, and gates

- [x] 7.1 Add a host-mode and a container-mode integration spec running a pipeline
  with a real command stage (modifies the working copy, then a failing-then-passing
  retry): harvest/attempt commits carry the command's effects, findings reach the
  next attempt, zero model invocations for the stage (FR9, NFR-R1, M1, M2, G2)
- F82 (carried from `tighten-spec-contract`): the container chain takes `BoxEquipment` —
  `ContainerEnvironments`, `ContainerEnvironmentBuilder` and `ContainerTaskExecutionEnvironment`
  take the installation's one `BoxEquipment` plus the task's own inputs (`BoxGitLink`,
  `ChildEnvAllowlist`, the project identity, the key), never a loose `DockerCli`, pinned by
  `ContainerEquipmentSplitSpec`. 7.1's container-mode spec builds its environment through that
  value (`BoxEquipmentFixture` in `:test-fixtures`), and the command executor reaches the box only
  through `TaskExecutionEnvironment.exec` (design D2), adding no equipment parameter.
- [x] 7.2 Update `docs/glossary.md`: the Executor entry gains `command` (a declared
  shell command run in the task environment), disambiguated from the `command`
  verify check; verify by reading the rendered entry (process rule: glossary in the
  same change)
- [x] 7.3 Run the traceability check: `grep` every FR/NFR/UX of this change against
  code, specs, and tests; then run `./gradlew check` on affected modules and confirm
  100% mutation score for new production code (M3)
