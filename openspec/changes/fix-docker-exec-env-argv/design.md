# Design: fix-docker-exec-env-argv

## Context

See `proposal.md` — Why. The seam today, in `sandbox/docker`:

- `DockerCommands.exec(key, workdir, Map<String,String> env, interactive, argv)` is a pure static
  builder returning `List<String>`; it renders every map entry as two argv elements, `-e` and
  `NAME=value`.
- `DockerCli.start(List<String> args, boolean mergeStderr)` builds a `ProcessBuilder` from the
  binary plus the argv and never touches `environment()`, so the docker client inherits the whole
  factory process environment (with any exported credential in it) **and** gets the values a second
  time on its command line.
- Four callers: `ContainerTaskExecutionEnvironment.exec` (the only one whose map is non-empty —
  `allowlist.compose(List.of(), command.env())`), `ContainerFileChannel.putFile`/`readFile` and
  `ContainerMaterializer.create` (scratch `mkdir`), each with `Map.of()`.
- The host twin, `HostTaskExecutionEnvironment`, clears `environment()` and fills it from the same
  composed map. Nothing changes there.
- `ChildEnvAllowlist` (`:sandbox:core`, a record) already holds `credentialNames()`: the tracker's
  and the check providers' declared credential variables, assembled at the composition root.
- `ContainerTaskExecutionEnvironment`'s constructor has seven parameters; the compile-time gate
  (`ParameterCountLimit`) forbids an eighth.
- Constraints that shape the fix: `AgentCredentialSeamBoundarySpec` lets only `AgentAiSeam` spell
  the AI credential names under `adapters/` and `sandbox/`; `DockerCli.start` is out-of-process
  and cannot be mutation-tested; `process-invariants.md` forbids "extracted for file size" static
  helpers that re-take an origin's fields as parameters.

External facts the design rests on: the docker CLI resolves a value-less `-e NAME` from its own
environment and leaves the variable unset when the name is absent there (documented for `docker
run`; `exec` shares the flag parser); the GitHub Actions runner passes `-e NAME` to `docker exec`
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
NFR-S4).** The pure builder renders `-e NAME` per name, in the set's iteration order (a
`LinkedHashMap.keySet()` view or a `SequencedSet` copy, so the flags keep the composed order the
debug log shows). The method's parameter type is the enforcement: nothing that reaches it carries a
value, so the "render the value" line has nowhere to come from. *Rationale:* the escape-hatch item of
`implementation.md` — a `Map` parameter on a builder that must not use the values is a review
obligation; a `Set` is a compiler one. *Alternative rejected:* keep the `Map` and ignore the values —
the next author "helpfully" restores them, and only a spec notices.

**D2 — One launch value, argv derived from its own environment (FR3, NFR-S4).** A new record in
`sandbox.environment`, `DockerExecLaunch(List<String> argv, Map<String,String> environment,
Set<String> withheld)`, is the only thing `DockerCli.start` accepts; the old
`start(List<String>, boolean)` overload is deleted. It is produced by one instance,
`ContainerExecLaunches` (fields: `key`, `workdir`, `withheld`), whose one method
`launch(Map<String,String> env, boolean interactive, List<String> argv)` calls
`DockerCommands.exec(key, workdir, env.keySet(), interactive, argv)` and pairs the result with the
same map. Because the argv's names are computed from the map's keys inside the producer, the
identity "flag names == delivered keys" holds by construction; `DockerExecLaunchIdentitySpec` asserts
it over a data table of maps, including empty and credential-shaped ones. *Rationale:* the
single-owner rule — the design claims two values are one, so one producer makes both and the seam's
parameter type refuses anything else. `ContainerExecLaunches` is an instance holding the equipment
(`process-invariants.md`, fields not parameters), built once per environment from `docker`, `key`
and `allowlist.credentialNames()` inside `ContainerTaskExecutionEnvironment`'s constructor — no new
constructor parameter, so the seven-parameter gate is untouched. *Alternative rejected:* a second
`DockerCli.start(args, env)` overload beside the old one — the old one is the hatch; and a static
`DockerCommands.launch(...)` returning the pair — pure is fine for argv, but the withheld set would
then be re-supplied by every caller (the fields-into-parameters failure the invariants rule names).

**D3 — The client environment is subtractive: inherited minus declared credentials plus the
composed map (FR4, NFR-S2).** Applied in one pure function, `DockerClientEnvironment.compose
(Map<String,String> inherited, DockerExecLaunch launch)` → a new map: copy `inherited`, remove
`launch.withheld()`, put all of `launch.environment()`. `DockerCli.start` does
`builder.environment().clear(); builder.environment().putAll(compose(System.getenv(), launch))`.
The withheld set is `ChildEnvAllowlist.credentialNames()` — tracker and check credentials — nothing
more (proposal NG3 explains why the AI-seam names are not in it). *Rationale:* the docker client's
needs are install-specific (`HOME` for contexts and `config.json`, `PATH`, the `DOCKER_*` family,
`SSL_CERT_FILE`/`DOCKER_CERT_PATH`, `HTTP(S)_PROXY`/`NO_PROXY` for a remote daemon, `SSH_AUTH_SOCK`
for `ssh://` hosts, `XDG_*`); an allowlist that misses one breaks an operator with no useful error,
while the credential names are a closed set the run already declares. The GitHub Actions runner
takes the same posture. *Alternative rejected:* the cleared allowlist D11 of `add-subprocess-access-
log` describes — right for the in-box child (the host adapter does it), wrong for a client whose
variable set the factory does not own; and no subtraction at all — leaves `GNOMISH_GITHUB_TOKEN`
in the environment of a process that may load docker CLI plugins and credential helpers.

**D4 — Management commands keep their inherited environment in this change (proposal Q1, NG2).**
`DockerCli.run` is untouched. *Rationale:* those commands carry no `-e` and forward nothing into a
box; changing their environment widens the change to the sweep and the runtime probe, which
construct their own `DockerCli` without a run's allowlist. The re-scoped FR15 of
`add-subprocess-access-log` extends `DockerClientEnvironment` to them in one place later.
*Alternative rejected:* a `withheld` field on `DockerCli` applied to every command — it forces the
sweep and probe constructors to pass a set they do not have, so they would pass an empty one, and an
empty default is exactly the hatch this change removes elsewhere.

**D5 — The pure parts carry the mutation gate; the spawn line is exempt as it is today (M5).**
`DockerCommands.exec`, `DockerExecLaunch`, `ContainerExecLaunches.launch` and
`DockerClientEnvironment.compose` are in-process and fully specified. `DockerCli.start` remains the
one-line hand-off it is now; its new behaviour is observed through `DockerCliSpec` against a fake
docker binary whose script prints selected variables of its own environment
(`FakeDockerBinary` stays the one writer of that binary). *Rationale:* `testing.md` — the decision
(what to withhold, what to overlay) is extracted and unit-tested; the spawn holds no decision.

**D6 — Enforcement is a whole-tree scan in `:bootstrap` (NFR-S1, M2).** `DockerExecArgvBoundarySpec`
(precedent: `AgentCredentialSeamBoundarySpec`, `ClaimlessGitBoundarySpec`) scans production sources
under `sandbox/` with comments stripped and asserts: the string literal `"-e"` appears only in
`DockerCommands.java`; `new ProcessBuilder` appears only in `DockerCli.java` and
`HostTaskExecutionEnvironment.java`; the scan reached the tree. *Rationale:* the sandbox tree is the
only one that spawns docker; the spec is the "enforced by" cell that keeps D1/D2 true after the
change. `add-subprocess-access-log` FR13 later widens `ProcessSpawnBoundarySpec` to an enumerated
whitelist over the whole codebase; this scan is its sandbox-local predecessor and folds into it
then. *Alternative rejected:* ArchUnit on `ProcessBuilder` dependencies alone — it cannot see a
string literal, and the literal is the leak.

**D7 — The principle goes to an ADR, the operator guide gets one paragraph (UX2, UX3).**
`docs/adr/0011-secret-channels-to-child-processes.md` records: a secret never travels on a command
line (argv is world-readable; environment is owner-readable; a file is permission-readable; stdin is
private); a gnome-product child's environment is composed by the factory from the three allowlist
layers; a factory client's inherited environment has the declared credentials subtracted; the
consequences for `--env-file` and in-box secret files. Both queued changes reference it instead of
restating. The sandbox operator guide's "Environment passthrough" section gains the posture
paragraph. *Rationale:* `design-decisions.md` — a decision that applies beyond the change belongs in
`docs/adr/`.

### Sync surfaces

`HostTaskExecutionEnvironment` and `ContainerTaskExecutionEnvironment` are the two media of the
rule "the composed environment reaches the child through environment channels only". They are
**not** a new manual-sync pair: the rule they share is the spec requirement, the shared abstraction
is `ChildEnvAllowlist.compose` (one formula, two callers), and after this change each medium does
one thing the other cannot (`environment()` on the child; `-e NAME` plus the client environment).
No declared pair in `manual-sync-pairs.md` is touched. `DockerCliSpec`/`FakeDockerBinary` already
carry a "Kept in sync with" marker; the new fake-binary script joins that declared pair without
changing its invariant.

### Single-owner mechanisms

| Owner                                                                                                                                                    | Value (type)                                                                                       | Consumers                                                                                                                                                                          | Old way removed                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Enforced by                                                                                                                                                                                                   |
|----------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ContainerExecLaunches.launch` (one instance per `ContainerTaskExecutionEnvironment`, fields `key`, `workdir`, `withheld = allowlist.credentialNames()`) | `DockerExecLaunch` — argv + environment + withheld names, argv derived from the environment's keys | `ContainerTaskExecutionEnvironment.exec` (the composed map); `ContainerFileChannel.putFile` and `readFile` (empty map); `ContainerMaterializer.create` scratch `mkdir` (empty map) | `DockerCommands.exec(…, Map<String,String> env, …)` returning a bare `List<String>` — signature changed to `Set<String>` names; `DockerCli.start(List<String>, boolean)` — deleted; the four direct `docker.start(DockerCommands.exec(...))` call chains — replaced by `docker.start(launches.launch(...))`. Old-way grep: `DockerCommands.exec(` outside `ContainerExecLaunches`, and `start(List` / `start(DockerCommands` anywhere under `sandbox/` — both expected empty after the change | parameter types (`Set<String>` on the builder, `DockerExecLaunch` on the seam); `DockerExecArgvBoundarySpec` in `:bootstrap` (D6); identity spec `DockerExecLaunchIdentitySpec` for the by-construction claim |
| `DockerClientEnvironment.compose`                                                                                                                        | `Map<String,String>` — the docker client's environment for one exec                                | `DockerCli.start` (the only caller)                                                                                                                                                | `ProcessBuilder`'s implicit inheritance for `start` — `start` now clears and fills `environment()` from the owner. Old-way grep: `environment()` under `sandbox/docker/src/main` — expected exactly the host adapter's two lines and `DockerCli.start`                                                                                                                                                                                                                                        | `DockerExecArgvBoundarySpec` pins the two `ProcessBuilder` sites; `DockerCliSpec` observes the effect on the fake binary                                                                                      |

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
- [The value still sits in the factory JVM's and the docker client's environment, readable by the
  same user] → that is the intended residual (owner-readable, the same exposure the factory process
  has itself); the ADR states it so nobody mistakes this change for a secrets vault.
- [Two specs pinned the old shape and are rewritten, not merely deleted] → `DockerCommandsSpec`
  "FR4: exec sets the workdir, per-entry env…" and `ContainerTaskExecutionEnvironmentUnitSpec`
  "FR9: exec passes exactly the composed allowlist as --env entries…" are inverted in place so the
  same features keep guarding the seam.
- [`add-subprocess-access-log` and `add-sandbox-hardening` deltas drift from the new stable text]
  → proposal "Queue effect" and the delta preamble name the re-layering; the memory note for the
  session records the order.

## Migration Plan

No data, no configuration, no operator action. Deploy is the next release; rollback is the previous
build. Behaviour in the box is byte-identical; the only observable change is on the host's process
table.
