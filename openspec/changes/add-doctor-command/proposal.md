# Proposal: add-doctor-command

Sequenced last: after `add-project-registry` (registry, configuration loader),
`add-release-pipeline` (product version) and `add-sandbox-base-image` (image
contract label). It reports on what those changes introduce.
Also sequenced after `tighten-spec-contract`: the MODIFIED blocks of `operator-configuration`
("Violations stop startup with one complete report") and `git-transfer` ("Git version floor at
startup") are layered on that change's text, which must sync first. Also sequenced after
`fix-terminal-receipt-convergence` (design D19): the host process-group launcher helper that
`doctor` checks is introduced there.

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

## Goals

- **G1**: one command tells an operator everything that would stop the factory
  on this machine for this project, with the fix for each.
- **G2**: `doctor` never disagrees with the real startup path: the same
  decision code answers both.

## Non-Goals

- **NG1**: starting a box, running the egress self-check or an agent round —
  `doctor` inspects, it does not execute gnome work.
- **NG2**: fixing anything automatically.
- **NG3**: diagnosing the target project's toolchain inside the image.
- **NG4**: new checks with no existing owner beyond locating the agent CLI
  binary and detecting the pre-registry folder layout.

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
  registration of `--dir`; the pipeline law loads and validates; secrets the
  tracker and the checks declare; agent credential presence when a stage is
  container-bound; Docker reachable when a stage is container-bound; the
  sandbox image present with a matching contract version; the guard image
  present (warn when absent — it is pulled on first use); the agent CLI binary
  locatable when a stage is host-bound; the host process-group launcher helper
  present when a stage is host-bound (`setsid` on Linux, `/usr/bin/perl` with
  `POSIX::setsid` on macOS), and the process group sweep's `/bin/kill` on both
  (`fix-terminal-receipt-convergence`, FR21, D19), with the fix naming the package to install; leftovers of the pre-registry folder
  layout (warn).
- **FR2**: without `--dir` and outside a registered clone, `doctor` SHALL run
  only the host-level checks and say that project checks were skipped and why.
- **FR3**: `--online` SHALL add a read-only tracker probe using the configured
  credential; without it `doctor` SHALL make no network call.
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

- **M1**: for each of the 13 checks, a spec shows `doctor` and the real startup
  path reach the same verdict on the same fixture (one data table).
- **M2**: an architecture spec finds no `ProcessBuilder`, `System.getenv`, or
  YAML parsing in the `doctor` package.
- **M3**: on the operator stand, a deliberately broken setup (old image, missing
  token, unregistered clone) is reported in one `doctor` run with three `fail`
  lines.

## Open Questions

None.

## Impact

- `:bootstrap`: the `doctor` subcommand, its check list and renderer; the
  subcommand dispatch runs `doctor` before the git floor refusal.
- Owners gaining a non-refusing assessment: `GitVersionCheck` (`:adapters:git`),
  `OperatorConfigLoader` (`:bootstrap`), `ImageContractCheck`
  (`:sandbox:docker`); `FactoryHome` gains legacy-layout detection;
  `:adapters:agent` gains agent-CLI binary location; the host launch owner in
  `:subprocess` (`fix-terminal-receipt-convergence`, D19) gains a non-refusing
  assessment of its platform helper.
- Docs: `README.md` (install → `gnomish doctor`), operator guides.
