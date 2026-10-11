# Design: add-doctor-command

## Context

Driven by FR1–FR7 of the proposal. The checks `doctor` reports already have
owners on the startup path, most of them shaped as "decide, log, throw":

- `GitVersionCheck.verify()` (`:adapters:git`) runs `git --version`, logs
  `[GF…] STARTUP_GIT_VERSION_REFUSED` at ERROR and throws below the floor;
  `ManualRunRunner` calls it before any subcommand dispatch.
- `OperatorConfigLoader` (`add-project-registry`) collects violations and throws
  `ConfigurationViolationsException` before any bean.
- `ProjectRegistry` (`add-project-registry`) resolves `--dir` or refuses.
- `PipelineLoader` (`:adapters`) loads the pipeline law and runs the startup
  validators (`AgentSettingsValidator`, `ExternalCheckSeamValidator`, …),
  returning located errors.
- `BoundTracker.credentialEnvVars()` and `CheckEquipment.credentialNames(…)`
  declare the secrets; `SecretsProvider.find` resolves them.
- `BindingResolver` (`:sandbox:core`) decides each stage's binding;
  `DockerRuntimeProbe.dockerAvailable()` probes the daemon.
- `ImageFacts` / `ImageContractCheck` (`add-sandbox-base-image`) inspect the
  sandbox image and compare its contract label, throwing on mismatch.
- `FactoryVersion` (`add-release-pipeline`) reports the product version.
- Nothing today locates the agent CLI binary before a round fails to start it,
  and nothing knows the pre-registry folder names except the release notes.

## Decisions

**D1 — Assess, then refuse: one decision per check.** Each owner whose only
form refuses gains a non-refusing `assess(...)` returning a verdict value
(`ok`/`warn`/`fail`, detail as `UntrustedText` or factory prose, fix); its
refusing form becomes "assess, then log and throw on `fail`" and keeps its
current log line and exception. Applied to `GitVersionCheck`,
`OperatorConfigLoader` (a collect mode that publishes the violations as a bean
for the `doctor` subcommand instead of throwing), and `ImageContractCheck` (FR4,
FR7).
*Rationale:* if `doctor` re-implemented a decision, the two would drift — the
exact class `manual-sync-pairs.md` exists to prevent; with the refusing form
calling `assess`, they cannot.
*Alternative rejected:* `doctor` catching the refusing form's exception — the
refusal logs an ERROR (`GitVersionCheck`), a line that would then describe a
refusal that never happened, and the loader's exception would abort the Spring
start that `doctor` itself runs inside.

**D2 — `doctor` is dispatched before the git floor refusal.** The subcommand
dispatch in `ManualRunRunner` checks for `doctor` before calling
`gitVersionCheck.verify()`; `OperatorConfigLoader` recognizes `doctor` and runs
in collect mode (FR7). Every other subcommand keeps today's order.
*Alternative rejected:* a separate `main` path for `doctor` outside Spring — the
checks need the same beans (secrets provider, bindings, pipeline loader) the
commands use.

**D3 — The check list is one ordered list of small check classes in
`:bootstrap`.** `DoctorCheck` (id, name, `Verdict run(DoctorContext)`) with one
class per FR1 item, each a thin adapter over its owner; `DoctorContext` carries
the resolved project (or its absence, FR2) and the beans. Checks that need a
project are skipped with a reason when there is none. Container-bound checks
run when `BindingResolver` binds any stage to `container`; the agent-CLI check
when any stage binds `host`. `--online` adds `TrackerReadProbe`, using the
tracker's existing read-only resolution (FR1–FR3).
*Alternative rejected:* a plugin SPI for checks — no second contributor exists;
a `ServiceLoader` seam can be added when a plugin needs a check.

**D4 — Two small new owners.** `AgentCliBinary.locate(binary, path)`
(`:adapters:agent`) resolves `factory.agent-cli-binary` against the host `PATH`
of the child environment the host adapter composes, and `AgentCredentials`
(`:adapters:agent`) reports which of the AI-seam credential names are present
(names only). `FactoryHome.legacyFolders()` (`:application`) lists the old
`logs/gnomish.log`, `serve/`, `worktrees/` and `secrets/` entries at the home
root. Each is the only code that knows its answer, so `doctor` reads no
environment and lists no folder name itself (FR4).
*Alternative rejected:* a `which` subprocess from `doctor` — a subprocess and a
`PATH` read in the doctor package, both of which M2 forbids.

**D5 — Output through the console owner; JSON through the machine path.**
`DoctorReport` renders one line per verdict with `ok`/`warn`/`fail`, name,
detail (`forConsole()` for captured text) and fix, then a summary; `--json`
maps the same verdicts to a document (`version`, `checks[{id, status, detail,
fix}]`) printed with `printMachine` (FR5, NFR-O1). Exit code: 0 with no `fail`,
1 otherwise (FR6). No secret value is ever a verdict field — the secrets check
reports the name and the places searched (NFR-S1).

### Sync surfaces

Sync surfaces: none — this change adds no parallel implementation: every
doctor check calls the owner the startup path calls, and the owners' refusing
forms are rebuilt on the same `assess` (D1). It touches no declared pair.

### Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `GitVersionCheck.assess` | git floor verdict (`GitVersionVerdict`) | `GitVersionCheck.verify` (startup, via `ManualRunRunner`), `GitFloorDoctorCheck` | the inline decision in `verify()` moves into `assess`; `verify` keeps only log-and-throw | `GitVersionCheckSpec`: `verify` refuses exactly when `assess` fails, over one data table |
| `OperatorConfigLoader` (collect mode) | configuration violations (`List<ConfigurationViolation>`) | the startup refusal, `ConfigurationDoctorCheck` | none: `doctor` reads the violations bean, never the files | `DoctorBoundarySpec` (below); M1 table row |
| `ImageContractCheck.assess` | contract verdict (`ImageContractVerdict`) | the environment builder's refusal, `SandboxImageDoctorCheck` | the inline comparison moves into `assess` | spec: refusal iff `assess` fails |
| `ProjectRegistry`, `PipelineLoader`, `BoundTracker` + `CheckEquipment` + `SecretsProvider`, `BindingResolver`, `DockerRuntimeProbe`, `FactoryVersion` | their existing values | the startup path, the matching doctor checks | none | M1 table row per check |
| `AgentCliBinary`, `AgentCredentials`, `FactoryHome.legacyFolders` (new) | binary location, present credential names, legacy entries | the matching doctor checks | none — no other code answers these | `DoctorBoundarySpec` |

`DoctorBoundarySpec` in `:bootstrap` (M2): no source in the doctor package
references `ProcessBuilder`, `Runtime.exec`, `System.getenv`,
`System.getProperty`, a YAML or JSON parser, or `Files.read*`; it asserts every
doctor source was scanned. The M1 identity table runs each check's fixture
through both the startup path and `doctor` and asserts the same verdict.

## Risks / Trade-offs

- [`doctor` runs inside a Spring start that the configuration loader would
  normally abort] → the collect mode is chosen by subcommand before anything
  else is read; the M1 row for configuration pins it.
- [An image present locally differs from what a pull would fetch] → `doctor`
  reports the local image only and says so; pulling is a side effect (NFR-R1).
- [`--online` spends a tracker request] → one read, opt-in (FR3).
- [New checks added later bypass the owner rule] → `DoctorBoundarySpec` fails on
  the first subprocess or environment read in the package.

## Migration Plan

None. `doctor` is additive; the refusing forms keep their messages, codes and
log lines.
