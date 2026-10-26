# Design: fix-docker-exec-env-argv

## Context

See `proposal.md` — Why. The seam today, in `sandbox/docker` (signatures as of 2026-10-26, after
`fix-terminal-receipt-convergence`, `make-pipe-reads-non-parking`, `own-git-invocation-policy` and
`add-sandbox-base-image`):

- `DockerCommands.exec(String key, String workdir, Map<String,String> env, StdinSource stdin,
  List<String> argv)` (`DockerCommands.java:131`) is a pure static builder returning `List<String>`;
  it renders every map entry as two argv elements, `-e` and `NAME=value`, and adds `-i` exactly
  when `stdin` carries input (design D20 of `fix-terminal-receipt-convergence`).
- `DockerCli.start(List<String> args, StderrPolicy stderr, StdinSource stdin)` (`DockerCli.java:151`)
  builds a `ProcessBuilder` from the binary plus the argv, applies the stderr policy and hands the
  builder to the launch owner, `captureRunner.launch().start(builder, stdin)`. It never touches
  `environment()`, so the docker client inherits the whole factory process environment (with any
  exported credential in it) **and** gets the values a second time on its command line.
- Two callers go through `start`: `ContainerTaskExecutionEnvironment.exec`
  (`ContainerTaskExecutionEnvironment.java:140-142`, the only one whose map is non-empty —
  `allowlist.compose(List.of(), command.env())`) and `ContainerFileChannel.run`
  (`ContainerFileChannel.java:172-173`, serving `putFile` and `readFile`, `Map.of()`). A third
  caller of the builder, the materializer's scratch `mkdir`
  (`ContainerMaterializer.java:146-152`), goes through `management(...)` → `DockerCli.run`
  (`ContainerMaterializer.java:194-195`): a bounded management command, not a streaming exec.
  `HostExecHandle` receives the started `Process` and starts nothing.
- The host twin, `HostTaskExecutionEnvironment` (`HostTaskExecutionEnvironment.java:120-121`),
  clears `environment()` and fills it from the same composed map. Nothing changes there.
- `ChildEnvAllowlist` (`:sandbox:core`, `ChildEnvAllowlist.java:40-46`) is a final class built by
  `ChildEnvAllowlist.of(passthrough, credentialNames, ChildEnvValues)`; it holds the declared
  credential **names** (the tracker's and the check providers') privately, resolves values only
  through the `ChildEnvValues` role interface, and exposes no accessor for either today
  (`tighten-spec-contract`, finding F107).
- `ContainerTaskExecutionEnvironment(BoxEquipment equipment, String key, BoxGitLink link,
  ChildEnvAllowlist allowlist, String projectId)` — five parameters; `ContainerFileChannel` is a
  record `(DockerCli docker, String key, Path workingCopy, Path scratch)` the environment builds.
- Constraints that shape the fix: `AgentCredentialSeamBoundarySpec` lets only `AgentAiSeam` spell
  the AI credential names under `adapters/` and `sandbox/`; `SecretsPortBoundarySpec` lets only
  `EnvFileSecretsProvider` call `System.getenv` in production; `DockerCli.start` is out-of-process
  and cannot be mutation-tested; stand-ins are committed presets (ADR 0015) — a spec never writes
  an executable or carries shell text; a child's input is a file or nothing (`lock-scope.md`,
  design D20 of `fix-terminal-receipt-convergence`); `process-invariants.md` forbids "extracted
  for file size" static helpers that re-take an origin's fields as parameters.

External facts the design rests on: the docker CLI resolves a value-less `-e NAME` from its own
environment (documented for `docker run`; `exec` shares the flag parser); the GitHub Actions runner passes `-e NAME` to `docker exec`
without clearing the client's environment; the exposure class is CWE-214.

## Goals / Non-Goals

**Goals:** a design where the value-bearing form cannot be written again — by parameter type, by
one producer for argv-plus-environment, and by a whole-tree scan — while the docker client keeps
every non-credential variable its own setup may need.

**Non-Goals:** management-command environments, the git client, the AI-seam names on non-agent
execs, `--env-file`, in-box secret files (proposal NG1–NG5). A new module. Any change to
`ChildEnvAllowlist.compose`.

## Decisions

**D1 — Names in, argv out: `DockerCommands.exec` takes a `Set<String>` of variable names (FR1,
NFR-S4).** The signature becomes `exec(String key, String workdir, Set<String> envNames,
StdinSource stdin, List<String> argv)`; the `stdin` parameter and the `-i` rule stay as D20 left
them. The pure builder renders `-e NAME` per name, in the set's iteration order (a
`LinkedHashMap.keySet()` view or a `SequencedSet` copy, so the flags keep the composed order the
debug log shows). The method's parameter type is the enforcement: nothing that reaches it carries a
value, so the "render the value" line has nowhere to come from. *Rationale:* the escape-hatch item of
`implementation.md` — a `Map` parameter on a builder that must not use the values is a review
obligation; a `Set` is a compiler one. *Alternative rejected:* keep the `Map` and ignore the values —
the next author "helpfully" restores them, and only a spec notices.

**D2 — One launch value, argv derived from its own environment (FR3, NFR-S4).** A new record in
`sandbox.environment`, `DockerExecLaunch(List<String> argv, Map<String,String> environment,
Set<String> withheld)`, is the only thing `DockerCli.start` accepts: the seam becomes
`start(DockerExecLaunch launch, StderrPolicy stderr, StdinSource stdin)` and the current
`start(List<String>, StderrPolicy, StdinSource)` is deleted. The value is produced by one instance,
`ContainerExecLaunches` (fields: `key`, `workdir`, `withheld`), whose one method
`launch(Map<String,String> env, StdinSource stdin, List<String> argv)` calls
`DockerCommands.exec(key, workdir, env.keySet(), stdin, argv)` and pairs the result with the same
map. Because the argv's names are computed from the map's keys inside the producer, the identity
"flag names == delivered keys" holds by construction; `DockerExecLaunchIdentitySpec` asserts it
over a data table of maps, including empty and credential-shaped ones. The invariant: one
`ContainerExecLaunches` per container environment, its withheld set fixed when it is built, and no
caller reaches `DockerCommands.exec` beside it (the one exemption is D4's). The withheld set is the
allowlist's declared credential names, read through a names-only accessor
`ChildEnvAllowlist.credentialNames()` that this change adds: names are not values, so the accessor
leaves F107's rule intact (`SecretsPortBoundarySpec` constrains readers of the process environment
and the value source; the allowlist still exposes nothing that resolves a value). The current
constructor already holds `equipment.docker()`, `key` and the `allowlist`, so the instance is built
from what the constructor has — no new constructor parameter. *Rationale:* the single-owner rule —
the design claims two values are one, so one producer makes both and the seam's parameter type
refuses anything else. `ContainerExecLaunches` is an instance holding the equipment
(`process-invariants.md`, fields not parameters). *Alternative rejected:* a second
`DockerCli.start(args, env)` overload beside the old one — the old one is the hatch; a static
`DockerCommands.launch(...)` returning the pair — pure is fine for argv, but the withheld set would
then be re-supplied by every caller (the fields-into-parameters failure the invariants rule names);
and handing the withheld set to `ContainerExecLaunches` by another route than the allowlist (a
constructor parameter from the root) — it duplicates a set the run already declares once.
*Amended 2026-10-26:* the factory does not rely on docker leaving a variable unset when a `-e
NAME` is absent from the client's environment: by construction no argv name is absent there (the
names are the launch map's keys, and `DockerClientEnvironment.compose` lays that map over the
client's environment). A passthrough name absent from the factory's environment is dropped
earlier, by `ChildEnvAllowlist.compose`, and never reaches the argv; the scenario "An absent name
stays unset in the box" and FR2 name that reachable state (premise verified in code by the
implementation audit of 2026-10-26).

**D3 — The client environment is subtractive: inherited minus declared credentials plus the
composed map (FR4, NFR-S2).** Applied in one pure function, `DockerClientEnvironment.compose
(Map<String,String> inherited, DockerExecLaunch launch)` → a new map: copy `inherited`, remove
`launch.withheld()`, put all of `launch.environment()`. `DockerCli.start` applies it to the
builder's own copy of the inherited environment — `Map<String,String> env = builder.environment();
Map<String,String> composed = compose(Map.copyOf(env), launch); env.clear(); env.putAll(composed)`
— and never calls `System.getenv` (`SecretsPortBoundarySpec`). The withheld set is the allowlist's
declared credential names — tracker and check credentials — nothing more (proposal NG3 explains why
the AI-seam names are not in it). *Rationale:* the docker client's needs are install-specific
(`HOME` for contexts and `config.json`, `PATH`, the `DOCKER_*` family, `SSL_CERT_FILE`/
`DOCKER_CERT_PATH`, `HTTP(S)_PROXY`/`NO_PROXY` for a remote daemon, `SSH_AUTH_SOCK` for `ssh://`
hosts, `XDG_*`); an allowlist that misses one breaks an operator with no useful error, while the
credential names are a closed set the run already declares. The GitHub Actions runner takes the same
posture. *Alternative rejected:* the cleared allowlist D11 of `add-subprocess-access-log` describes —
right for the in-box child (the host adapter does it), wrong for a client whose variable set the
factory does not own; no subtraction at all — leaves `GNOMISH_GITHUB_TOKEN` in the environment of a
process that may load docker CLI plugins and credential helpers; and reading `System.getenv()` in
`DockerCli` — a second reader of the process environment, which the secrets boundary refuses.

**D4 — Management commands keep their inherited environment in this change; the scratch `mkdir`
is one of them (proposal Q1, NG2, FR5).** `DockerCli.run` is untouched. The materializer's scratch
`mkdir` runs through `management(...)` → `docker.run` (`ContainerMaterializer.java:146-152,
194-195`): a bounded command under the shared supervisor, reported through `DockerCommandReport`,
not a streaming exec. It keeps that path, builds its argv with `DockerCommands.exec(key,
WORKING_COPY, Set.of(), StdinSource.NONE, …)` — so it carries no `-e` flag — and keeps the
management commands' inherited client environment. It is the single-owner table's one exemption
from `ContainerExecLaunches`, named there with this reason; the delta's scenario "Scratch directory
creation carries no environment flag" states exactly that. *Rationale:* the `mkdir` forwards
nothing into the box, and the only thing a launch would add to it is a client-environment
subtraction that FR15 of `add-subprocess-access-log` extends to every management command in one
place later; changing `run` here widens the change to the sweep and the runtime probe, which
construct their own `DockerCli` without a run's allowlist. *Alternatives rejected:* a `withheld`
field on `DockerCli` applied to every command — it forces the sweep and probe constructors to pass
a set they do not have, so they would pass an empty one, and an empty default is exactly the hatch
this change removes elsewhere; and moving the `mkdir` onto `start` as a launch — it would lose the
management bound and the command report for a secret-free command, to buy nothing.

**D5 — The pure parts carry the mutation gate; the spawn line is exempt as it is today (M5).**
`DockerCommands.exec`, `DockerExecLaunch`, `ContainerExecLaunches.launch`,
`DockerClientEnvironment.compose` and `ChildEnvAllowlist.credentialNames` are in-process and fully
specified. `DockerCli.start` remains the one-line hand-off it is now; its new behaviour is observed
through `DockerCliSpec` against a committed stand-in preset (ADR 0015): a new section
`[docker-record-env]` of `test-fixtures/src/main/resources/stand-in/tables/docker.params` whose one
row is `* record GNOMISH_GITHUB_TOKEN JAVA_HOME DOCKER_HOST` followed by `* exit 0`, reached through
`StandIn.recording(dir, 'docker-record-env')`; the interpreter writes `VAR=[value]` or `VAR=unset`
per name into the link's `.log`, which the spec reads through `StandIn.log`. The preset is covered
by `StandInLibrarySpec` like every other; the spec carries no shell text and writes no executable.
*Rationale:* `testing.md` — the decision (what to withhold, what to overlay) is extracted and
unit-tested; the spawn holds no decision; and a stand-in is prepared, not generated.
*Alternative rejected:* a per-spec fake docker script printing its environment — the generated
stand-in ADR 0015 removed, which `StandInOwnerSpec` now fails.

**D6 — Enforcement is a whole-tree scan in `:bootstrap` (NFR-S1, M2).** `DockerExecArgvBoundarySpec`
(precedent: `AgentCredentialSeamBoundarySpec`, `ClaimlessGitBoundarySpec`) scans production sources
under `sandbox/` with comments stripped and asserts: the string literal `"-e"` appears only in
`DockerCommands.java`; `new ProcessBuilder` appears only in `DockerCli.java` and
`HostTaskExecutionEnvironment.java` (true today: `DockerCli.java:201`,
`HostTaskExecutionEnvironment.java`); the scan reached the tree. *Rationale:* the sandbox tree is
the only one that spawns docker; the spec is the "enforced by" cell that keeps D1/D2 true after the
change. `add-subprocess-access-log` FR13 later widens `ProcessSpawnBoundarySpec` to an enumerated
whitelist over the whole codebase; this scan is its sandbox-local predecessor and folds into it
then. *Alternative rejected:* ArchUnit on `ProcessBuilder` dependencies alone — it cannot see a
string literal, and the literal is the leak.

**D7 — The principle goes to an ADR, the operator guide gets one paragraph (UX2, UX3).**
`docs/adr/0017-secret-channels-to-child-processes.md` records: a secret never travels on a command
line (argv is world-readable; environment is owner-readable; a file is permission-readable; a
child's standard input is a 0600 file in the launch owner's input directory for the launch window,
inherited by the child and never a pipe — design D20 of `fix-terminal-receipt-convergence`, ADR
0018 — so it is permission-readable for that window, not private); a gnome-product child's
environment is composed by the factory from the three allowlist layers; a factory client's
inherited environment has the declared credentials subtracted; the consequences for `--env-file`
and in-box secret files. Both queued changes reference it instead of restating. The sandbox
operator guide's "Environment passthrough" section gains the posture paragraph. *Rationale:*
`design-decisions.md` — a decision that applies beyond the change belongs in `docs/adr/`; the stdin
wording is the statement `fix-terminal-receipt-convergence` (design D20, Risks) left for this
change to make. *Alternative rejected:* "stdin is private" — true of a pipe, which the factory no
longer uses, and false of the file it uses instead.

### Sync surfaces

`HostTaskExecutionEnvironment` and `ContainerTaskExecutionEnvironment` are the two media of the
rule "the composed environment reaches the child through environment channels only". They are
**not** a new manual-sync pair: the rule they share is the spec requirement, the shared abstraction
is `ChildEnvAllowlist.compose` (one formula, two callers), and after this change each medium does
one thing the other cannot (`environment()` on the child; `-e NAME` plus the client environment).
No declared pair in `manual-sync-pairs.md` is touched. `DockerCliSpec` drives the committed
stand-in presets of `docker.params` through `StandIn.docker` and `StandIn.recording` (ADR 0015);
the new preset is one more section of that table, covered by `StandInLibrarySpec`, and the
`ScriptedGit` ↔ `stand-in.sh` pair's invariant (the argv-prefix match rule) is not changed by a row
that matches `*`.

### Single-owner mechanisms

| Owner                             | Value (type)                                                                                       | Requires                                                                                                                       | Consumers                                                                                                                                                                                                                                                                                                   | Old way removed                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Enforced by                                                                                                                                                                                                   |
|-----------------------------------|----------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ContainerExecLaunches.launch`    | `DockerExecLaunch` — argv + environment + withheld names, argv derived from the environment's keys | the container key, the in-box working directory and the allowlist's declared credential names; no live box, no network, no I/O | `ContainerTaskExecutionEnvironment.exec` (the composed map); `ContainerFileChannel.run`, serving `putFile` and `readFile` (empty map). **Exemption (D4):** `ContainerMaterializer`'s scratch `mkdir` — a management command through `DockerCli.run`, argv from `DockerCommands.exec` with an empty name set | `DockerCommands.exec(…, Map<String,String> env, …)` returning a bare `List<String>` — signature changed to `Set<String>` names; `DockerCli.start(List<String>, StderrPolicy, StdinSource)` — deleted; the two direct `docker.start(DockerCommands.exec(...))` call chains — replaced by `docker.start(launches.launch(...), …)`. Old-way grep: `DockerCommands.exec(` outside `ContainerExecLaunches` under `sandbox/*/src/main` — expected exactly one hit, the D4 exemption in `ContainerMaterializer.java`; `start(List` and `start(DockerCommands` anywhere under `sandbox/` — expected empty | parameter types (`Set<String>` on the builder, `DockerExecLaunch` on the seam); `DockerExecArgvBoundarySpec` in `:bootstrap` (D6); identity spec `DockerExecLaunchIdentitySpec` for the by-construction claim |
| `DockerClientEnvironment.compose` | `Map<String,String>` — the docker client's environment for one exec                                | the `ProcessBuilder`'s copy of the inherited environment and a `DockerExecLaunch`; nothing else — no `System.getenv`           | `DockerCli.start` (the only caller)                                                                                                                                                                                                                                                                         | `ProcessBuilder`'s implicit inheritance for `start` — `start` now clears and fills `environment()` from the owner. Old-way grep: `builder.environment()` under `sandbox/docker/src/main` — expected exactly the host adapter's two lines and `DockerCli.start` (the `clone.environment()` reads of `DockerSeedCloneCommand` are a `GitTransfer`'s map, not a `ProcessBuilder`'s, and fall outside the pattern)                                                                                                                                                                                    | `DockerExecArgvBoundarySpec` pins the two `ProcessBuilder` sites; `SecretsPortBoundarySpec` keeps `System.getenv` out of it; `DockerCliSpec` observes the effect on the recording stand-in                    |

## Risks / Trade-offs

- [The docker CLI's value-less `-e` resolution differs on some client version] → it is documented
  behaviour shared by `run`, `create` and `exec` since the flag exists; the existing container-mode
  credential E2E (`ContainerModeAgentCredentialE2ESpec`, real daemon, credential-capturing agent
  binary) proves delivery on the CI client, and the sandbox guide names the git/docker floors the
  factory already has.
- [An operator relied on a declared credential reaching a docker CLI plugin or credential helper]
  → nothing in the factory's documented setup does; the guide's new paragraph names the one thing
  the client loses.
- [A future exec caller reaches for `DockerCommands.exec` with a map again] → the parameter is a
  `Set<String>`; and the boundary scan reports any second `"-e"` speller.
- [The scratch `mkdir` and every other management command keep the inherited client environment,
  declared credentials included] → stated in D4 and NG2; it forwards nothing into a box, and the
  subtraction reaches management commands through FR15 of `add-subprocess-access-log`, which
  extends `DockerClientEnvironment` in one place.
- [The value still sits in the factory JVM's and the docker client's environment, readable by the
  same user] → that is the intended residual (owner-readable, the same exposure the factory process
  has itself); the ADR states it so nobody mistakes this change for a secrets vault.
- [Two specs pinned the old shape and are rewritten, not merely deleted] → `DockerCommandsSpec`
  "FR4: exec sets the workdir, per-entry env…" and `ContainerTaskExecutionEnvironmentUnitSpec`
  "FR9: exec passes exactly the composed allowlist as --env entries…" are inverted in place so the
  same features keep guarding the seam.
- [`add-subprocess-access-log` and `add-sandbox-hardening` deltas drift from the new stable text]
  → proposal "Queue effect" and the delta preamble name the re-layering; `add-subprocess-access-log`'s
  delta already carries this change's text verbatim and names it as sequenced before.

## Migration Plan

No data, no configuration, no operator action. Deploy is the next release; rollback is the previous
build. Behaviour in the box is byte-identical; the only observable change is on the host's process
table.
