# Design: add-doctor-command

## Context

Driven by FR1–FR8 of the proposal. The checks `doctor` reports already have
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
- `BoundTracker.credentialEnvVars()` and `CheckProviderSeam.credentialEnvVars(…)`
  declare the secrets; `SecretsProvider.find` resolves them.
- `BindingResolver` (`:sandbox:core`) decides each stage's binding; the startup
  path reaches the daemon through the port `ContainerRuntimeProbe` (adapter
  `DockerRuntimeProbe::dockerAvailable`).
- `ServePreconditions` (`:application`, `add-sandbox-base-image` D17, ADR 0019)
  is the startup owner of the operator preconditions: it runs the clone's
  ref-format check (`TaskBranchGit.requireRefFormat`) and, for a boxed plan, the
  port `SandboxImageReadiness.assess(image)` before the first claim, and
  `OperatorPreconditionRefusals.kindOf` classifies the four refusals
  (`CLONE_REF_FORMAT`, `CONTAINER_RUNTIME`, `BOX_GIT_FLOOR`, `IMAGE_CONTRACT`).
  Its Docker adapter `DockerImageReadiness` reads `ImageFacts` and calls the
  package-private `ImageContractCheck.require`, then `BoxGitFloor.require`,
  which starts one helper container (`docker run --rm --network none <image>
  git --version`). NG10 of `add-sandbox-base-image` hands this change the
  non-refusing form of that assessment.
- `FactoryVersion` (`add-release-pipeline`) reports the product version.
- The host launch owner in `:subprocess` already resolves every command's first
  word before any process exists: `GroupedLaunch.start` calls
  `ExecutableLookup.resolve(name, directory, path)`, the one `execvp`-shaped
  rule (absolute `PATH` entries only, a regular executable file, the `#!`
  interpreter chain followed; D19 of `fix-terminal-receipt-convergence`), but
  only in its refusing form. Nothing knows the pre-registry folder names except
  the release notes.
- Nothing reads the `core.hooksPath` line earlier factory versions wrote into
  the clone: `own-git-invocation-policy` removed its writer (`harden`) and
  leaves the line for the operator to remove (its UX2).

## Decisions

**D1 — Assess, then refuse: one decision per check.** Each owner whose only
form refuses gains a non-refusing `assess(...)` returning a verdict value
(`ok`/`warn`/`fail`, detail as `UntrustedText` or factory prose, fix); its
refusing form becomes "assess, then log and throw on `fail`" and keeps its
current log line and exception. Applied to `GitVersionCheck` and
`OperatorConfigLoader` (a collect mode that publishes the violations as a bean
for the `doctor` subcommand instead of throwing) (FR4, FR7). The operator
preconditions already have a non-refusing owner, `ServePreconditions`, whose
checks throw without logging and leave the line to the deciding layer (ADR 0019,
decisions 1 and 4); `doctor` reads them through it, one verdict per kind, as its
per-kind recheck already does, and never reaches `ImageContractCheck` or the
ref-format check directly. Its one side effect is split off: the port
`SandboxImageReadiness` gains the contract inspection alone (one `ImageFacts`
read, no container) beside the box git floor probe, and its `assess(image)`
stays both halves in order, so `ServePreconditions.assess` and its
`DaemonConditions` rechecks keep running both. `doctor` reports the contract
from the inspection and names the box git floor as not probed, since the probe
is a container start (NFR-R1, NFR-P1, NG1).
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
when any stage binds `host`. `--online` adds `TrackerReadProbe`, which asks
the tracker adapter's own read-only probe (D7), never the read-only tracker
resolution of `board` (FR1–FR3).
*Alternative rejected:* a plugin SPI for checks — no second contributor exists;
a `ServiceLoader` seam can be added when a plugin needs a check.

**D4 — Small new owners, and the launch owner's two assessments.**
`AgentCredentials` (`:adapters:agent`) reports which of the AI-seam credential
names are present (names only). `FactoryHome.legacyFolders()` (`:application`)
lists the old `logs/gnomish.log`, `serve/`, `worktrees/` and `secrets/` entries
at the home root. The host launch owner of `fix-terminal-receipt-convergence`
(D19 there; the only code that knows which helper the platform needs — `setsid`,
or macOS's own Perl — that the group sweep signals through `/bin/kill` on both,
and how a command's first word resolves) gains two non-refusing verdicts its
launching form is built on (D1), exposed as a public seam of `:subprocess`:
`assessHelper()`, and `assessExecutable(name, directory, path)` built on
`ExecutableLookup.resolve`. The agent-CLI check hands the latter
`factory.agent-cli-binary` and the child `PATH` the host adapter composes (the
value `GroupedLaunch.start` would read), so `doctor` reads no `os.name`, probes
no `PATH` and holds no lookup rule itself. Each is the only code that knows its
answer, so `doctor` reads no environment and lists no folder name itself (FR4).
*Alternative rejected:* a `which` subprocess from `doctor` — a subprocess and a
`PATH` read in the doctor package, both of which M2 forbids. A separate
`AgentCliBinary.locate` in `:adapters:agent` — a second implementation of the
`execvp` rule beside `ExecutableLookup`, which would drift from it (a relative
`PATH` entry, a script whose interpreter is absent).

**D5 — Output through the console owner; JSON through the machine path.**
`DoctorReport` renders one line per verdict with `ok`/`warn`/`fail`, name,
detail (`forConsole()` for captured text) and fix, then a summary; `--json`
maps the same verdicts to a document (`version`, `checks[{id, status, detail,
fix}]`) printed with `printMachine` (FR5, NFR-O1). Exit code: 0 with no `fail`,
1 otherwise (FR6). No secret value is ever a verdict field — the secrets check
reports the name and the places searched (NFR-S1).

**D6 — The hooks-path leftover is read by `:adapters:git`, with `--local`.**
Factory versions before `own-git-invocation-policy` wrote
`core.hooksPath=<clone>/.git/gnomish-empty-hooks` into the clone's own
configuration; that change stops writing it and removes nothing (its UX2), so
`doctor` names the line (FR8). A new owner in `:adapters:git`,
`HooksPathLeftover.assess(clone)`, runs `git config --local --get
core.hooksPath` through the factory's `GitRunner` and returns `warn` with the
fix `git config --local --unset core.hooksPath` only when the value ends in
`/gnomish-empty-hooks`, `ok` otherwise. `--local` is required: the runner
applies the invocation policy, whose command-scope `core.hooksPath=/dev/null`
an unscoped `--get` would report (design of `own-git-invocation-policy`, its
note for this change). The suffix test keeps an operator's own hooks path out
of the report. `doctor` holds no git argv itself (FR4, M2).
*Alternative rejected:* removing the line automatically — `doctor` has no side
effects (NFR-R1, NG2), and the operator may rely on it.

**D7 — The online probe is the provisioner's assessment, reached through the
SPI.** `TrackerWiring.resolveReadOnly` and `boardReader` build the adapter
through `TrackerAdapterFactory.create`, and the GitHub `create` always runs
`GithubLabelProvisioner.provision` first: one `GET /repos/{o}/{r}/labels`, then a
`POST` for every missing label. A probe built on it would write to a repository
that lacks the factory's labels and spend two requests, against NFR-R1 and FR3.
So D1 is applied to the provisioner: `GithubLabelProvisioner.assess(owner, repo,
labelDefs)` makes the listing request alone and returns a verdict (the
repository is readable, with the configured labels still missing; or a refusal
carrying the HTTP class, the cause startup names — a 404 is "not found or not
visible to this token", GitHub returns it for both — and, on a 403, the
`X-Accepted-GitHub-Permissions` header verbatim, GitHub's own diagnostic for a
fine-grained token); `provision` becomes "assess, then create the missing
labels, then refuse on a failed verdict" with its current exception and message.
The SPI `TrackerAdapterFactory` gains a default method `probeRead(secrets,
config)` returning the probe verdict; the default (the in-memory adapter) makes
no call and reports the probe as not applicable, and the GitHub adapter overrides
it with `assess`. `TrackerWiring` relays it as the one credential seam, the same
way it relays `refuseForeignRef` — the existing precedent of a read-only SPI
operation that takes the secrets and makes one `GET`. The doctor line reports
reading confirmed, the labels startup will create, and that write access is not
verified (NG5); the probe never retries a 401. The api addition is a compatible
default method, re-baselined under the japicmp gate (`plugin-api-contract`,
"Surface growth is additive and re-baselined").
*Rationale:* the probe is the first request startup makes, classified by the
same code, so `doctor` and startup cannot disagree on the tracker (G2) — the
`--dry-run=server` shape, which predicts the real operation, rather than a
whoami probe, which succeeds while the real operation is denied.
*Alternatives rejected:* the read-only tracker resolution as D3 first said — it
writes labels and makes two or more requests (NFR-R1, FR3). A `GET
/repos/{owner}/{repo}` probe through `GithubForeignRepoCheck` — one read too, but
a second decision on the repository's visibility beside the startup smoke test,
an undeclared pair that drifts from it (G2). A read-only mode of `create()`
that skips provisioning, probing with `listReady(1)` — one to two reads (a
comments fetch per ready issue), and a behaviour change for `board` and
`dashboard` and their spec. Dropping the probe (alternative zero) — U4 unmet,
and a bad token caught only by the first `take` or `serve`.

### Sync surfaces

Sync surfaces: none — this change adds no parallel implementation: every
doctor check calls the owner the startup path calls, and the owners' refusing
forms are rebuilt on the same `assess` (D1); the agent-CLI location is the
launch owner's own `ExecutableLookup` rule (D4), not a second resolver. It
touches no declared pair.

### Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `GitVersionCheck.assess` | git floor verdict (`GitVersionVerdict`) | `GitVersionCheck.verify` (startup, via `ManualRunRunner`), `GitFloorDoctorCheck` | the inline decision in `verify()` moves into `assess`; `verify` keeps only log-and-throw | `GitVersionCheckSpec`: `verify` refuses exactly when `assess` fails, over one data table |
| `OperatorConfigLoader` (collect mode) | configuration violations (`List<ConfigurationViolation>`) | the startup refusal, `ConfigurationDoctorCheck` | none: `doctor` reads the violations bean, never the files | `DoctorBoundarySpec` (below); M1 table row |
| `ServePreconditions` (per-kind verdict) over `SandboxImageReadiness` (contract inspection split from the box git floor probe) | operator precondition verdict per `DaemonConditionKind`; image contract verdict (no container) | `ServePreconditions.assess` (startup), its `DaemonConditions` rechecks, `SandboxImageDoctorCheck` | none: the port's `assess(image)` keeps both halves, so startup and rechecks are unchanged; `doctor` never reaches `ImageContractCheck` | `ServePreconditionSpellingSpec` (the owner adds no spelling); `DoctorBoundarySpec`; M1 table row: startup refuses on `IMAGE_CONTRACT` exactly when the inspection fails |
| `TaskBranchGit.requireRefFormat` via `ServePreconditions` (`CLONE_REF_FORMAT`) | clone ref-backend verdict | `ServePreconditions.assess`, the take/run bootstraps that call it, `RefFormatDoctorCheck` | none | M1 table row: `take` refuses exactly when the doctor check fails |
| `ProjectRegistry`, `PipelineLoader`, `BoundTracker` + `CheckProviderSeam` + `SecretsProvider`, `BindingResolver`, `ContainerRuntimeProbe`, `FactoryVersion` | their existing values | the startup path, the matching doctor checks | none | M1 table row per check |
| the host launch owner's `assessExecutable` (`:subprocess`, built on `ExecutableLookup.resolve`) | executable verdict (resolved absolute path, or the reason the name does not resolve) | `GroupedLaunch.start` (the launching form's refusal), `AgentCliDoctorCheck` | none — the launching form is built on it | `DoctorBoundarySpec`; launcher spec: the launch refuses exactly when `assessExecutable` fails; M1 table row |
| `AgentCredentials`, `FactoryHome.legacyFolders` (new) | present credential names, legacy entries | the matching doctor checks | none — no other code answers these | `DoctorBoundarySpec` |
| `HooksPathLeftover.assess` (new, `:adapters:git`, D6) | hooks-path leftover verdict (`ok`, or `warn` with the unset line) | `HooksPathDoctorCheck` | none — no other code answers this (the factory no longer writes the line) | `DoctorBoundarySpec`; owner spec over a real clone: `warn` only for a local value ending in `/gnomish-empty-hooks`, `ok` for unset and for an operator's own path, with the policy in force |
| the host launch owner's `assessHelper` (`:subprocess`, from `fix-terminal-receipt-convergence` D19) | launcher helper verdict (present, or the missing tool — the platform's launcher or `/bin/kill` — and the package to install) | the launching form's refusal (FR21 there), `LauncherHelperDoctorCheck` | none — the launching form is built on it | `DoctorBoundarySpec`; M1 table row: the launch refuses exactly when `assessHelper` fails |
| `GithubLabelProvisioner.assess` (D7), reached through `TrackerAdapterFactory.probeRead` and relayed by `TrackerWiring` | tracker read-probe verdict (readable with the missing label names; or refused with the HTTP class, the cause startup names and, on 403, the accepted-permissions header) | `GithubLabelProvisioner.provision` (startup, through `create`), `TrackerReadProbe` (`doctor --online`) | the listing request and its status classification move out of `provision` into `assess`; `provision` keeps create-and-refuse; `TrackerReadProbe` never calls `create` or the read-only tracker resolution | `GithubLabelProvisionerSpec`: `provision` refuses exactly when `assess` fails, over one data table of statuses; the probe spec: on a WireMock repository without the labels, `doctor --online` makes exactly one `GET` and no `POST`; `DoctorBoundarySpec` |

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
- [`doctor` does not probe the box image's git floor, so an image whose git is
  below the floor passes `doctor` and is refused by `serve`/`take` at startup]
  → the sandbox image line names the floor as not probed and why (NG1); the
  startup refusal still runs before any claim.
- [`--online` spends a tracker request] → one read, opt-in, the request
  startup makes first anyway (FR3, D7).
- [A token that can read but not write passes the probe, and startup's first
  write refuses] → the probe line says write access is not verified and names
  the labels startup will create; a fine-grained token's permissions are
  reported when GitHub refuses (NG5, D7).
- [New checks added later bypass the owner rule] → `DoctorBoundarySpec` fails on
  the first subprocess or environment read in the package.

## Migration Plan

None. `doctor` is additive; the refusing forms keep their messages, codes and
log lines.
