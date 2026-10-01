# Proposal: add-project-registry

Builds on `fix-operator-blockers` (archived): its argument owner (absolute
`--dir`, unknown-option rejection) and the default-binding requirement it added,
which this change replaces. Lands before `remove-interactive-console` and
`make-run-headless`: they rebase onto the `RegisteredClone` signatures and the
registered-project E2E harness this change introduces.

## Why

The factory is about to be released to operators who install it once and serve
several projects from one host. Today it has nowhere to keep per-project
operator settings, and the operator stand shows the consequence: a launcher
script per project injecting the sandbox image, the egress allowlist, the env
passthrough, the instance name, the log directory and the secrets directory as
command-line options — and working around Spring joining a repeated option with
a comma instead of letting the last one win.

Three concrete gaps:

- **Operator configuration has one level.** `factory.*` properties describe the
  installation, but half of them describe a project: the sandbox image and the
  egress allowlist follow the project's toolchain and hosts,
  `factory.check.github.repo` names one repository, and
  `factory.bindings.stages.<stage>` names stages that exist only in one
  project's pipeline. With two projects on a host, one value cannot serve both.
- **A project has three unrelated names.** Worktrees are keyed by the clone
  directory's name (so `~/work/api` and `~/oss/api` collide), the serve state by
  `factory.instance-name`, and logs share one host-wide file. Nothing ties them
  to the project they belong to.
- **Nothing says which settings may widen the sandbox, and from where.** Any
  property source — a stray environment variable included — can widen the
  egress allowlist; Spring replaces a list from a higher source wholesale; an
  unknown key is silently ignored.

Decisions this proposal builds on (agreed in the 2026-09-27 design session):
operator configuration lives outside the target repository in two levels, a
host file and a per-project file; the layout under `~/.gnomish` is
project-first; a project is identified by explicit registration of its clone
paths; one key vocabulary with a declared level per key; sandbox-boundary keys
only in the project file; a key in the wrong place stops startup with the fix.
There is a single user today, so no migration of the old layout is owed.

## What Changes

- **ADDED**: `GNOMISH_HOME` (default `~/.gnomish`) with one layout owner:
  `factory.yaml` and `secrets/` for the host, `projects/<name>/` holding
  `project.yaml`, `secrets/`, `logs/`, `serve/<instance>/` and
  `worktrees/<clone>/<task>/` per project, `logs/` for commands that run with no
  project.
- **ADDED**: project registration — `gnomish project add <name> --dir=<clone>`,
  `gnomish project list`, `gnomish project show [<name>]` (paths and the
  effective settings with the file and line each came from). Every
  project-scoped subcommand resolves `--dir` to exactly one registered clone and
  refuses an unregistered directory with the command that registers it.
- **ADDED**: operator configuration levels — built-in defaults, `factory.yaml`,
  the project's `project.yaml`, the command line, in rising precedence. Every
  `factory.*` key declares where it may appear: host file only, project file
  only, anywhere, or — for sandbox-boundary keys — the project file and nowhere
  else. A key in a place its level forbids, an unknown key, a `FACTORY_*`
  environment variable, or a configuration file writable by other users stops
  startup with one message listing every violation, its location and its fix.
- **ADDED**: secrets resolve from `projects/<name>/secrets/`, then
  `GNOMISH_HOME/secrets/`, then the environment (`N_FILE`, then `N`); secret
  files must be private to the owner.
- **MODIFIED** **BREAKING**: worktrees move from
  `~/.gnomish/worktrees/<clone-dir-name>/<task>/` to
  `projects/<name>/worktrees/<clone>/<task>/`.
- **MODIFIED** **BREAKING**: the serve observability directory moves from
  `~/.gnomish/serve/<instance-name>/` to `projects/<name>/serve/<instance>/`;
  `factory.instance-name` defaults to `default`; the instance identity the
  tracker sees carries the project name.
- **MODIFIED** **BREAKING**: the log file moves from `~/.gnomish/logs/gnomish.log`
  to `projects/<name>/logs/<instance>.log`; `GNOMISH_LOG_DIR` is replaced by
  `GNOMISH_HOME`.
- **REMOVED** **BREAKING**: `factory.agent-cli-env-passthrough` (documented as
  superseded and ignored).
- **MODIFIED** **BREAKING**: `factory.bindings.*`, `factory.sandbox.image`,
  `factory.sandbox.egress-allowlist` and `factory.sandbox.env-passthrough` are
  read only from the project's `project.yaml`.

## Capabilities

### New Capabilities

- `project-registry`: the `GNOMISH_HOME` layout, project registration and
  lookup by clone path, the `project` subcommands.
- `operator-configuration`: configuration levels, per-key declared level,
  sandbox-boundary keys, the violation report, file-permission checks.

### Modified Capabilities

- `secrets-provider`: the env/file adapter gains the project and host secret
  directories ahead of the environment.
- `git-task-persistence`: "Worktree lifecycle" — the host-mode worktree path.
- `serve-observability`: "Files live in a per-instance-name directory…" — the
  directory moves under the project.
- `observability/dashboard-page`: the default output path follows it.
- `manual-run`: "Instance-local logging" — the log file location.
- `plugin/adapter-binding-registry`: "The documented default-binding key binds"
  (added by `fix-operator-blockers`) is removed and replaced by "The
  default-binding key binds from the project file" — its command-line scenario
  contradicts the sandbox-boundary level, and a MODIFIED block cannot drop a
  scenario.

## Goals

- **G1**: one host serves several projects with no launcher script: every
  per-project setting lives in that project's file.
- **G2**: an operator reads a project's whole sandbox boundary in one file.
- **G3**: every misplaced, unknown or unsafe setting is reported at startup
  with its location and its fix, all at once.
- **G4**: one project name keys the project's configuration, secrets, logs,
  serve state and worktrees.

## Non-Goals

- **NG1**: changing the sandbox sweep's project identity (the normalized-origin
  digest stays; `factory.sandbox.project-id` stays as a project-level key).
- **NG2**: a `gnomish logs` command and `project remove`; the operator edits or
  deletes the project folder.
- **NG3**: migrating the old `~/.gnomish/{logs,secrets,serve,worktrees}`
  layout; the release notes tell the single existing operator what to delete.
- **NG4**: release packaging and the launcher (`add-release-pipeline`), the
  sandbox base image, `gnomish doctor`.
- **NG5**: the target repository's `.gnomish/config.yaml` — its schema is
  unchanged and it never reads `factory.*` keys.
- **NG6**: splitting "refused in passthrough" from "scrubbed everywhere" for
  credentials.

## Users & Scenarios

- **U1**: an operator registers a clone once
  (`gnomish project add widgets --dir=~/src/widgets`), writes the sandbox image
  and allowlist into `projects/widgets/project.yaml`, and runs `take` from cron
  with only `--dir`.
- **U2**: the same operator registers a second clone of the same repository
  under `widgets`; both share the settings and secrets, each keeps its own
  worktrees.
- **U3**: the operator puts `egress-allowlist` into `factory.yaml` by habit;
  startup stops, names the file and line, says the key is a sandbox-boundary
  key, and names the project files it belongs in.
- **U4**: the operator runs `gnomish take` in an unregistered clone and is told
  the exact `project add` command to run.
- **U5**: the operator asks "why is this value in effect?" and
  `gnomish project show widgets` prints each setting with its file and line.

## Requirements

### Functional

- **FR1**: the factory SHALL keep all operator state under `GNOMISH_HOME`
  (default `~/.gnomish`) in the layout of the What Changes section, computed by
  one owner.
- **FR2**: `gnomish project add <name> --dir=<path>` SHALL register the
  absolute path as a clone of project `<name>`, creating the project folder on
  first use; a path registered to another project, or a name outside
  `[a-z0-9][a-z0-9._-]*`, SHALL be refused.
- **FR3**: every subcommand that takes `--dir` SHALL resolve it to exactly one
  registered clone by exact path, once per process, and every consumer SHALL
  take the clone from that one resolution; an unregistered path SHALL be
  refused naming the `project add` command, and a path inside a registered
  clone SHALL name that clone.
- **FR4**: `gnomish project list` SHALL list projects and their clones;
  `gnomish project show [<name>]` SHALL print the project's paths and every
  effective `factory.*` value with its origin (file and line, command line, or
  built-in default).
- **FR5**: configuration SHALL be assembled from built-in defaults,
  `factory.yaml`, the resolved project's `project.yaml` and the command line,
  later sources overriding earlier ones.
- **FR6**: every `factory.*` key SHALL declare one level: `host` (host file or
  command line), `project` (project file or command line), `any`, or
  `sandbox-boundary` (project file only).
- **FR7**: startup SHALL stop, before any tracker call, branch, worktree or
  box, when a key appears where its level forbids, a key is unknown, a
  `FACTORY_*` environment variable is set, or a configuration file is writable
  by group or others; one report SHALL list every violation with its location,
  the reason and the concrete fix, and the process SHALL exit with the
  usage-error exit code (2) — no new exit code is introduced.
- **FR8**: the env/file secrets adapter SHALL resolve secret `N` from
  `projects/<name>/secrets/N`, then `GNOMISH_HOME/secrets/N`, then `N_FILE`,
  then `N`; a secret file readable or writable by group or others SHALL be
  refused naming the fix.
- **FR9**: host-mode worktrees SHALL live at
  `projects/<name>/worktrees/<clone>/<sanitized-task-id>/`; the worktree
  janitor SHALL sweep only its own clone's folder.
- **FR10**: serve observability files SHALL live in
  `projects/<name>/serve/<instance>/`, the dashboard default output beside
  them; the instance identity SHALL embed the project name.
- **FR11**: the log file SHALL be `projects/<name>/logs/<instance>.log` for a
  project-scoped command and `GNOMISH_HOME/logs/factory.log` otherwise.
- **FR12**: `factory.agent-cli-env-passthrough` SHALL be removed; setting it is
  an unknown-key violation.

### Non-Functional

- **NFR-S1**: sandbox-boundary keys SHALL come from the resolved project's own
  file only — never the host file, the command line, the environment or the
  target repository.
- **NFR-S2**: configuration files writable by group or others, and secret
  files readable or writable by group or others, SHALL fail closed.
- **NFR-R1**: configuration violations SHALL be detected before any side
  effect; a failed `project add` SHALL leave no partial registration (atomic
  file write).
- **NFR-R2**: two clones of one project SHALL never share a worktree folder.
- **NFR-O1**: `project show` SHALL answer "where did this value come from" for
  every effective key.
- **NFR-O2**: every violation line SHALL carry the file and line (or variable
  name), the key, the allowed level and the fix.
- **NFR-P1**: configuration assembly adds no network call; it reads the host
  file and the registered projects' files once per process.
- **NFR-C1**: none — no token or paid call involved.

## Operator Experience Criteria

- **UX1**: a violation report reads like: `factory.sandbox.egress-allowlist is
  not allowed here — found in ~/.gnomish/factory.yaml:7 — it is a
  sandbox-boundary key, read only from a project's own file — move it to
  ~/.gnomish/projects/<name>/project.yaml (registered: widgets, gf-tests)`.
- **UX2**: the unregistered-directory refusal prints a ready-to-paste
  `gnomish project add <suggested-name> --dir=<path>` line.
- **UX3**: the paths the operator uses daily fit in one line:
  `~/.gnomish/projects/widgets/logs/default.log`.

## Success Metrics

- **M1**: the operator stand's launcher script shrinks to the jar invocation:
  zero `--factory.*` options and zero exported path variables.
- **M2**: one data-driven spec asserts a declared level for 100% of the
  `@ConfigurationProperties` fields; a field without one fails the build.
- **M3**: for each level, a spec places one key of that level in each of the
  four places an operator can set it — the host file, the project file, the
  command line and a `FACTORY_*` environment variable — and asserts
  accept/reject exactly as FR6 and FR7 state (16 cells).
- **M4**: `git grep` finds no production read of `user.home` outside the layout
  owner, and no `GNOMISH_LOG_DIR`.

## Open Questions

None open: the level of each existing key is listed in design D5; a key whose
level turns out wrong in use moves by a one-line annotation change.

## Impact

- New JDK-only leaf `:operatorconfig` (level annotation); `:bootstrap` gains the
  configuration loader and the `project` subcommands.
- `:application`: `FactoryPaths`, `ObservabilityPaths`, `TaskWorktreePath`,
  `WorktreeJanitor`, the dashboard and serve commands, `FactoryProperties`,
  `ServeProperties`; `:sandbox:core`: `SandboxProperties`, `BindingProperties`,
  `ResourceLimits`; `:adapters`: `EnvFileSecretsProvider`.
- `bootstrap/src/main/resources`: `logback-spring.xml`, `application.yaml`.
- Test fixtures: the E2E harness and the in-process specs that boot Spring
  register their project through the production registry; about 25 test files
  set affected keys.
- Active changes that rebase onto this one: `remove-interactive-console`
  (its E2E harness and take/resume spec migration) and `make-run-headless`
  (the resume runners); both are brought in step with `/opsx:update` once this
  change is applied.
- Docs: every operator guide that names a `~/.gnomish` path or a `factory.*`
  key; `docs/glossary.md`; new ADR 0011 (operator configuration levels).
- Cut line if the change overruns: the level enforcement (FR6, FR7) moves to its
  own change, leaving FR1–FR5 and FR8–FR12 with levels unenforced.
