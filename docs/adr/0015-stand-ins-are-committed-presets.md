# ADR 0015: Stand-ins Are Committed Presets

Status: accepted (2026-10-10, introduced by `supervise-daemon-loops-and-embed-dashboard`, design
D23–D25; amended the same day by its design D26 after a second review — links are derived, each
behaviour is one preset, no row is dead — and D27 — specs above the git adapter fake its port.
Recorded here because a change's `design.md` archives with the change and governs nothing
afterwards)

## Context

Specs replace `git`, `docker`, the agent CLI and supervised processes with stand-ins: small shell
scripts that refuse one subcommand, answer another, stall, or record their arguments. Until this
decision every spec wrote its own script into its temporary directory, with the scenario's
parameters and the directory's absolute paths baked into the text: 61 scripts in about fifty test
files, four behaviours, no shared spec.

The cost surfaced only when the time of a full local `check` was attributed rather than counted.
PIT took about 90 minutes; in `:adapters:git` the mutation phase consumed 8800 minion-seconds while
the mutants' tests ran for 500–900 seconds, and every sampled minion thread sat in
`ProcessImpl.waitFor` on a stand-in the spec had just written. macOS assesses every new executable
file on its first direct run (`syspolicyd`: Gatekeeper exec evaluation plus the XProtect scan,
cached per file, serialized through one daemon). Measured on the reference machine: a fresh
one-line script 1–4 s under nine parallel minions, its second run 10 ms, `/bin/sh file` 9 ms, a
symbolic link to an already-assessed script 10 ms. Linux pays nothing, so CI never showed it and
the count-based mutation cost report (`kill-expensive-mutants`) reported every module clean.

The decision was reached by asking, twice, why anything is generated per test. Nothing in those
scripts varies per run except where a recording lands.

## Decision

1. **Stand-in scripts are committed, written once, spec'd once.** They live under
   `test-fixtures/src/main/resources/stand-in/`. One table interpreter, `stand-in.sh`, serves
   every stand-in whose behaviour a table can state — `git`, `docker`, the agent CLI, a hook, a
   supervised process that exits, waits or stalls — and reads it from the section named after the
   link it was invoked through: per argv prefix,
   refuse with an exit code and a stderr file, answer with a stdout file, stall, record argv and
   named variables, delay, write a per-run file, export a variable — or the per-run link's own name,
   so one preset serves every value of a parameter (the fake agent's scenario; the subcommand a
   probe refuses or stalls on, named in the match column) — or hand over to the real binary or a
   committed script. A preset that would differ from another in one word takes that word from the
   link's name instead — as a value, as the subcommand matched, or as the answer it prints
   (`data/stderr-fetch#@name`). The few stand-ins whose subject is the operating system's handling of a
   process — a signal ignored, a child forked, a pipe held open — are committed scripts of their
   own under `process/`, one reviewed script per behaviour, because no table states a fork.
2. **Scenario parameters are committed presets; a preset is a section.** A preset is a section
   `[<preset>]` of a table under `tables/`, and nothing else: presets of one group share a table,
   and a table stays within the project's file-size budget. The link that carries the preset's name
   to the interpreter (`$0` is the one channel production leaves: it execs the configured path and
   clears the environment) is made by the owner, `<jvm-dir>/<preset> -> stand-in.sh`, once per JVM
   on first use; the interpreter walks the chain of links to itself, takes the last name before it
   as the preset and its own directory as the library. A link costs what a committed one costs —
   the assessment is keyed by the script's inode — so a committed directory of links was a
   registry with one hand-kept invariant, and is gone; the preset list is
   `grep '^\[' tables/*.params`. Every row of every section is reached by some invocation: the
   library's own spec runs each row by an argv built from its match and fails on a row an earlier
   terminal row shadows. The answers rows print are
   sections of the files under `data/`, shell steps live under `steps/`. A preset contains no
   absolute path and writes nothing into the library — only beside a per-run link, its log or a
   file a `write` row names — so it is read-only, shared by parallel JVMs, and assessed by the
   operating system at most once per checkout. A spec that asserts a refusal text reads it from the
   library, never from a second literal.
3. **A test never writes an executable file.** It selects a preset by name through the single
   owner fixture, `StandIn` in `:test-fixtures`. The one per-run artefact is a symbolic link to a
   preset, created by that owner for a scenario that records, reads a file the spec writes beside
   the link, or needs a name git chooses (a hook); a stand-in that changes behaviour mid-spec is
   re-pointed at another preset in one rename. A link is not a new executable file.

   A hook preset is linked into a repository under the name git runs it by. Whether git runs it
   depends on the caller's configuration, not the preset's: `GitExec` passes
   `-c core.hooksPath=/dev/null`, and git hands `-c` pairs to the git processes it starts through
   `GIT_CONFIG_PARAMETERS`, so a local push made through it would not run the receiving
   repository's `pre-receive`; the transfer owner (`GitTransfer`) strips that variable, which is
   why a push through the runner reaches a `pre-receive` preset in a bare fixture remote.
   *(Amended 2026-10-20 by ADR 0012, `own-git-invocation-policy`: every factory git process now
   passes `-c core.hooksPath=/dev/null` — the git invocation policy, `GitInvocation` — the runner
   and every push included, and the policy removes `GIT_CONFIG_PARAMETERS` for every factory git
   process, not only transfers. A push still reaches a `pre-receive` preset because git's local
   transport does not hand the pushing process's command-scope configuration to the receiving
   repository, measured on git 2.56.0.)*
4. **A gate keeps it so.** An architecture spec in `:bootstrap` scans every test tree and
   `test-fixtures/src/main` for an executable bit set from code, shebang text and a `chmod`
   granting execute; only named exemptions may match (scripts run inside a container, where Linux
   runs them and a host link would not resolve; specs of shipped scripts, run once per build; a
   gate's own seeded data), each must still match, and the owner must be reached and clean.
5. **A new scenario is a new preset**, with a row in the library's data-driven spec — never shell
   in a spec.
6. **Stand-ins serve the process boundary, not the layer above it.** A class above one of the four
   `ProcessBuilder` owners (`GitProcessRunner`, `GitExec`, `DockerCli`,
   `HostTaskExecutionEnvironment`) is specified against a fake of the port it holds, in process
   (`ScriptedGit` for `GitRunner`); a preset that only scripts the answers such a class receives is
   an in-process fake written as shell and paid for as a process per call per mutant. What keeps a
   stand-in: the specs of those four owners, where the process is the subject; a preset that hands
   part of the argv to the real binary over a real repository; a hook preset, which real git runs
   over a real bare remote; `GitVersionCheck`, which spawns `git --version` itself; and the
   end-to-end layer. `ScriptedGit` matches an argv prefix exactly as the interpreter does, a pair
   declared in `.claude/rules/manual-sync-pairs.md`.

## Consequences

Positive: one reviewed implementation of each stand-in behaviour; the first-run assessment paid
once per script per checkout instead of once per test per mutant; the same mechanism speeds the
ordinary `test` task and PIT's single-threaded coverage phase; scenarios become nameable and
shareable across modules.

Negative: a scenario that genuinely needs new behaviour goes through the library and its spec
rather than a ten-line inline script; the tables are one more place a reviewer reads. Both are the
point.

## Alternatives Considered

In the order the design session went through them:

- **Generated shell per test** (the status quo): pays the assessment on every run; scatters logic.
- **One committed launcher that runs a generated behaviour file through `/bin/sh`**: fast, but
  still generates shell per test, so the logic stays unreviewed; rejected when the question "why
  generate?" was asked.
- **Committed scripts, generated parameter files**: the parameters turned out constant per
  scenario; only the recording location varies, and a link covers it.
- **Hardlinks**: as fast, but one filesystem, one inode, and `$0` cannot tell presets apart.
- **Committed links as the preset registry** (the first form of decision 2): a visible list, but
  one file per preset carrying nothing the table headers do not, kept in step by hand.
- **A sidecar plan file beside the per-run link** (the `bats-mock` shape): carries parameters too,
  but a second artefact kind per test for a need the link's name already covers.
- **An off-the-shelf stand-in library**: every one found selects the binary by `PATH` or the
  environment, which production does not consult, and all but one write an executable per test.
- **Running stand-ins through `/bin/sh` from production**: production executes the operator's
  `git` by path; its argv is not the test's to shape.
- **Machine-level exemptions** (Developer Tools, `spctl`, ad-hoc `codesign`, xattr removal):
  per-developer state, not a build property; `spctl --master-disable` is no longer honoured on
  current macOS; signing and xattrs do not stop the exec evaluation.

## References

- Michael Tsai, "Why some apps sometimes launch extremely slowly", 2025-04-30 — the `syspolicyd`
  scan queue and per-vnode cache.
- Eclectic Light Co., "How does Ventura check the security of known apps and command tools",
  2023-07-05 — first run assessed, later runs tracked.
- `MongLong0214/agent-control-plane` issue #817 — the same symptom in a test suite, fixed with a
  checked-in shim and per-test data.
- cargo-nextest, macOS installation notes — the Developer Tools exemption and its limits.
- `.claude/rules/testing.md`, "Stand-ins are prepared, not generated" and "Diagnosing a slow
  gate" — the working rules this decision produced.
