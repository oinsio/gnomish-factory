# Proposal: add-doctor-command

Sequenced last: after `add-project-registry` (registry, configuration loader),
`add-release-pipeline` (product version) and `add-sandbox-base-image` (image
contract label). It reports on what those changes introduce.
Also sequenced after `tighten-spec-contract`: the MODIFIED block of `operator-configuration`
("Violations stop startup with one complete report") is layered on that change's text, which
must sync first; the MODIFIED block of `git-transfer` ("Git version floor at startup") is
layered on the text of `own-git-invocation-policy` (floor 2.56.0). Also sequenced after
`fix-terminal-receipt-convergence` (design D19): the host process-group launcher helper that
`doctor` checks is introduced there. Also sequenced after `own-git-invocation-policy`: that
change stops writing `core.hooksPath` into the operator's clone and leaves the line earlier
versions wrote for the operator to remove; `doctor` names it (FR8, UX2 there).

## Why

Setting up the factory touches many independent things: Java, git at or above
the version floor, Docker when a stage is container-bound, a sandbox image built
on the right base, the guard image, the agent CLI and its credential, the
tracker token, a registered project with a valid configuration, a pipeline that
loads. Today each of them surfaces one at a time, as a refusal at the moment a
command first needs it — so an operator fixes one problem per run, and some
(a missing agent credential, a stale image) show up only after a task has been
claimed. The operator stand wrote its own warnings into its launcher script for
exactly this reason.

A diagnostics command (`flutter doctor`, `brew doctor`) answers "is this
machine ready for this project?" in one run, listing every problem with its
fix. Almost every check it needs already exists in the factory; what is missing
is a way to ask them without stopping at the first refusal.

## What Changes

- **ADDED**: `gnomish doctor [--dir=<clone>] [--online] [--json]` — runs every
  readiness check, prints one line per check (`ok`, `warn`, `fail`) with the
  detail and the fix, exits 0 when nothing failed and 1 otherwise.
- **ADDED**: each existing check gains a non-refusing assessment that its
  refusing form is built on, so `doctor` and the real startup path share one
  decision per check.
- **MODIFIED**: `doctor` runs where startup would refuse — git below the floor,
  a configuration with violations — and reports the refusal instead.
- **ADDED**: the tracker adapter SPI (`TrackerAdapterFactory`) gains a default
  read-only probe, the one request `--online` makes; the GitHub adapter answers
  it with the label listing its startup provisioning begins with, extracted as
  the non-refusing assessment that provisioning is built on (design D7).

## Capabilities

### New Capabilities

- `operator-doctor`: the `doctor` subcommand, its check list, output and exit
  codes.

### Modified Capabilities

- `git-transfer`: "Git version floor at startup" — `doctor` reports the
  assessment instead of refusing.
- `operator-configuration` (added by `add-project-registry`): "Violations stop
  startup with one complete report" — `doctor` lists the violations instead of
  stopping.
- `github-tracker`: "Idempotent label provisioning as startup smoke test" —
  provisioning is built on a non-refusing listing assessment (D7); the
  assessment itself is the added requirement "Read-only label probe" in the
  same capability, so the existing requirement keeps one behavior.

## Goals

- **G1**: one command tells an operator everything that would stop the factory
  on this machine for this project, with the fix for each.
- **G2**: `doctor` never disagrees with the real startup path: the same
  decision code answers both.

## Non-Goals

- **NG1**: starting a box, running the egress self-check or an agent round —
  `doctor` inspects, it does not execute gnome work. This includes the one
  helper container the startup path starts to read the box image's git version
  (`add-sandbox-base-image`): `doctor` does not probe the box git floor and says
  so in the sandbox image line; `serve`, `take` and `run` still refuse on it
  before any work.
- **NG2**: fixing anything automatically.
- **NG3**: diagnosing the target project's toolchain inside the image.
- **NG4**: new checks with no existing owner beyond detecting the pre-registry
  folder layout and detecting the `core.hooksPath` leftover of earlier factory
  versions (FR8).
- **NG5**: verifying that the tracker token can write. GitHub offers no
  read-only way to evaluate a write (no `can-i`; scope headers exist for classic
  tokens only, a fine-grained token reports its permissions only on a 403), so
  the probe confirms reading and says so; a read-only token is caught by the
  startup provisioning, as today. Pickup condition: GitHub exposes a
  permission-evaluation endpoint, or an operator reports `doctor` green and
  `take` refused on the token's first write more than once.

## Users & Scenarios

- **U1**: a new operator runs `gnomish doctor --dir=~/src/widgets` before the
  first `take` and gets a list: git ok, Docker ok, sandbox image missing (with
  the build command), tracker token missing (with the file to create).
- **U2**: an operator upgraded the factory; `doctor` reports the sandbox image
  was built for contract 1 while the factory needs 2.
- **U3**: CI of an operator's infrastructure runs `gnomish doctor --json` and
  fails the deployment on a non-zero exit.
- **U4**: with `--online`, `doctor` confirms the tracker token can read the
  configured repository.

## Requirements

### Functional

- **FR1**: `gnomish doctor` SHALL run these checks, each reported as `ok`,
  `warn` or `fail`: factory version and Java runtime (info); git version floor;
  configuration (every violation of the configuration report); project
  registration of `--dir`; the factory clone's ref backend (reftable), through
  the ref-format check the startup assessment runs; the pipeline law loads and
  validates; secrets the
  tracker and the checks declare; agent credential presence when a stage is
  container-bound; Docker reachable when a stage is container-bound; the
  sandbox image present with a matching contract version (the box image's git
  floor is named as not probed, NG1); the guard image
  present (warn when absent — it is pulled on first use); the agent CLI binary
  locatable when a stage is host-bound; the host process-group launcher helper
  present when a stage is host-bound (`setsid` on Linux, `/usr/bin/perl` with
  `POSIX::setsid` on macOS), and the process group sweep's `/bin/kill` on both
  (`fix-terminal-receipt-convergence`, FR21, D19), with the fix naming the package to install; leftovers of the pre-registry folder
  layout (warn).
- **FR2**: without `--dir` and outside a registered clone, `doctor` SHALL run
  only the host-level checks and say that project checks were skipped and why.
- **FR3**: `--online` SHALL add exactly one read-only tracker probe using the
  configured credential: the adapter's own probe, which for GitHub is the label
  listing the startup provisioning makes first, so the probe fails exactly when
  startup's first request would and names the same cause; the line SHALL say
  that write access is not verified. Without `--online` `doctor` SHALL make no
  network call.
- **FR4**: every check SHALL delegate its decision to the component the real
  startup path uses; `doctor` SHALL NOT run a subprocess, read an environment
  variable or parse a configuration file itself.
- **FR5**: output SHALL be one line per check with its status, name, detail and
  — for `warn` and `fail` — the fix; `--json` SHALL print the same results as a
  JSON document with stable check ids.
- **FR6**: the exit code SHALL be 0 when no check failed and 1 when at least one
  failed; a warning SHALL NOT fail.
- **FR7**: `doctor` SHALL run when git is below the floor and when the
  configuration has violations, reporting both instead of refusing.
- **FR8**: on a registered clone, `doctor` SHALL report the `core.hooksPath`
  line that factory versions before `own-git-invocation-policy` wrote into the
  clone's own configuration: it SHALL read the clone's local configuration
  (`--local`), since under the factory's git invocation policy an unscoped read
  reports the command-scope value `/dev/null`; it SHALL report `warn` with the
  fix `git config --local --unset core.hooksPath` only when the local value ends
  in `/gnomish-empty-hooks`, and `ok` otherwise — a hooks path the operator set
  is not the factory's. Nothing is removed automatically (NG2). Traces UX2 of
  `own-git-invocation-policy`.

### Non-Functional

- **NFR-R1**: `doctor` SHALL have no side effects: no claim, branch, worktree,
  box, pull, or file write outside its own log line.
- **NFR-S1**: no secret value SHALL appear in the output — only the secret's
  name and where it was (or was not) found.
- **NFR-O1**: detail text captured from tools (git, docker) SHALL leave its
  carrier through the console exit.
- **NFR-P1**: offline `doctor` SHALL finish in under 5 seconds on a machine
  with Docker running (no image pulls, no container starts).
- **NFR-C1**: none — no paid call.

## Operator Experience Criteria

- **UX1**: the output reads top to bottom as a checklist; every `fail` line
  carries a command or file path that fixes it.
- **UX2**: a fully healthy setup prints only `ok` lines and a one-line summary.

## Success Metrics

- **M1**: for each of the 14 checks of FR1, a spec shows `doctor` and the real
  startup path reach the same verdict on the same fixture (one data table). The
  hooks-path check of FR8, which no startup path decides, is pinned by its
  owner's spec instead.
- **M2**: an architecture spec finds no `ProcessBuilder`, `System.getenv`, or
  YAML parsing in the `doctor` package.
- **M3**: on the operator stand, a deliberately broken setup (old image, missing
  token, unregistered clone) is reported in two `doctor` runs, each `fail` line
  with its fix. On the registered clone, the image without the factory's image
  contract and the missing tracker token give two `fail` lines. With `--dir`
  naming an unregistered clone, the registration gives one `fail` line naming
  the `project add` line, and every project check is `skipped`: no project
  resolves, so the image and the token cannot be judged there, and no single
  run can show all three.

## Open Questions

None.

## Impact

- `:bootstrap`: the `doctor` subcommand, its check list and renderer; the
  subcommand dispatch runs `doctor` before the git floor refusal.
- Owners gaining a non-refusing assessment: `GitVersionCheck` (`:adapters:git`),
  `OperatorConfigLoader` (`:bootstrap`), the startup owner of the operator
  preconditions (`ServePreconditions`, `add-sandbox-base-image`) with the
  sandbox image readiness port split into its contract inspection and its box
  git floor probe (`:application`, `:sandbox:docker`); `FactoryHome` gains
  legacy-layout detection; `:adapters:agent` gains the agent credential names;
  the host launch owner in `:subprocess` (`fix-terminal-receipt-convergence`,
  D19) gains non-refusing assessments of its platform helper and of a
  command's executable; `:adapters:git` gains the read of the `core.hooksPath`
  leftover (FR8).
- Docs: `README.md` (install → `gnomish doctor`), operator guides.
