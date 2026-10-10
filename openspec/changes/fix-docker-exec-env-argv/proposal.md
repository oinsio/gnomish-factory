# Proposal: fix-docker-exec-env-argv

## Why

In container mode the factory hands every environment entry of an in-box process to the docker
client on its command line, as `-e NAME=value`. The command line of a process is readable by every
local user for as long as the process runs — `ps` on macOS shows other users' arguments, and
`/proc/<pid>/cmdline` on Linux is world-readable unless the operator mounted `/proc` with
`hidepid`. So for the whole duration of every agent round and every judge vote, the agent's own
credentials (`CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, routed through
the AI seam) and every operator passthrough value sit in the host's process table. The 2026-09-30
live run confirmed it: `ps aux | grep 'docker exec'` printed a fine-grained GitHub token and a Claude
OAuth token in clear. This is CWE-214, *Invocation of Process Using Visible Sensitive Information*,
the class microsandbox received CVE-2026-61670 for this year, for the same `--env` shape.

The 2026-09-30 subprocess audit found this to be the **only** argv leak in the codebase: git never
receives a token from the factory on any channel, the seed-clone helper and the `ext::` harvest
transport carry no secret, the host adapter already passes its child environment through
`ProcessBuilder.environment()` after clearing it, and no log writes a full argv. The fix is one
seam. It is already designed as decision D11 / FR14 of the active `add-subprocess-access-log`
(created 2026-08-30, no task started), but it sits in that change's seventh task group behind
observability work and behind `own-git-invocation-policy` in the queue. A security defect with a
public exploit path does not wait two large changes; this change lifts the fix out and lands it
first. The files it touches overlap neither queued change.

## What Changes

- **MODIFIED** `execution-environment`, requirement *Layered positive environment allowlist*: the
  composed child environment reaches the box through environment channels only. The container
  adapter passes each entry as a value-less `-e NAME` flag and delivers the value through the docker
  client process's own environment, which the docker CLI resolves into the box. No environment
  value appears on any spawn argv on the host, in either mode.
- **ADDED** `execution-environment`, requirement *Container exec launch is one value*: the argv
  and the client environment of one `docker exec` are produced together, from one composed map, by
  one owner, so the flag names and the delivered values cannot diverge. The docker client's
  environment for an exec is the factory's inherited environment with every declared credential
  name removed and the composed map laid on top — subtraction, not a cleared allowlist.
- **ADDED** to the durable record: `docs/adr/0011-secret-channels-to-child-processes.md`, stating
  the principle both this change and the queued ones rest on: a secret never travels on a command
  line; a child's environment is composed by the factory, and a factory client's inherited
  environment has the declared credentials subtracted.
- **Queue effect** (recorded here, applied by `/opsx:update` on the other changes after this one
  lands): `add-subprocess-access-log` drops tasks 7.1 and 7.2 and its `execution-environment`
  delta sentence on argv, re-layering its remaining delta over this change's text; its argv
  redactor stays as the second net. Task 7.3 (the git client's environment) belongs to
  `GitInvocation`, the single owner `own-git-invocation-policy` introduces, and moves there in the
  same subtractive form.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `execution-environment`: *Layered positive environment allowlist* is MODIFIED with the
  "environment channels only, never argv" clause and its scenario; a new requirement *Container
  exec launch is one value* is ADDED for the one-owner launch pair and the subtractive client
  environment (added rather than folded into *Container adapter*, which `add-sandbox-hardening`
  already MODIFIES). The allowlist requirement is also MODIFIED by the active
  `add-subprocess-access-log`; this change is sequenced **before** it, so that delta re-layers
  over this change's text (see `delta-specs.md`, overlapping MODIFIED).

## Goals

- **G1** After this change, no process the factory spawns on the host carries an environment
  value in its argv: a scan of the composed `docker exec` argv finds no `-e` element containing
  `=`, and the host adapter is unchanged.
- **G2** The in-box process observes exactly the environment it observes today: the same names, the
  same live values, delivered by the docker CLI's documented resolution of a value-less `-e NAME`
  from its own environment.
- **G3** The names on the argv and the values in the docker client's environment are one value by
  construction: a single owner produces both from one composed map, and no caller can hand the
  docker seam an argv without its environment.
- **G4** A declared tracker or check credential exported in the factory's environment never reaches
  the docker client process spawned for an exec, even though that process inherits the rest of the
  factory's environment.
- **G5** The property is pinned by the build, not by review: a spec on the argv shape, a spec on
  the delivered environment, and an architecture scan over the sandbox tree.

## Non-Goals

- **NG1** The git client's environment (`GitProcessRunner`, `GitExec` inherit the factory
  environment today). It has a single owner arriving in `own-git-invocation-policy`; the
  subtraction belongs there, not in a second place here.
- **NG2** The docker client's environment for **management** commands (`run`, `inspect`, `rm`,
  `network`, `volume`, guard commands). They carry no `-e` and forward nothing into a box; their
  inherited environment stays as it is, and the follow-up that minimizes it is FR15 of
  `add-subprocess-access-log`, re-scoped after this change.
- **NG3** Subtracting the AI-seam names (`CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_*`) from the docker
  client's environment on execs that do not carry them (command checks, in-box git, probes). Only
  `AgentAiSeam` may spell those names under `adapters/` and `sandbox/`
  (`AgentCredentialSeamBoundarySpec`), and the docker CLI forwards no inherited variable without a
  matching `-e`, so they stay on the host. The follow-up is the same FR15.
- **NG4** `--env-file`: workable, but the secret lands on disk for the life of the file, and the
  file needs creation, permissions and crash-safe deletion for no gain over client-environment
  delivery.
- **NG5** Secrets as files inside the box (`/run/secrets` style). The agent CLIs read environment
  variables, so a file would need an in-box wrapper to load it, and every in-box process already
  sees the round's environment; no exposure shrinks.
- **NG6** The seed-clone helper's read-only view of the factory clone, whose `.git/config` may
  hold a token the operator embedded in the origin URL. That is the documented host-side posture
  (NFR-S3 of `add-subprocess-access-log`), not an argv leak.
- **NG7** The access log, its redactor, and the widened spawn-boundary gate of
  `add-subprocess-access-log` (its FR13): the redactor remains that change's second net over an
  argv this change has already made secret-free.
- **NG8** A `hidepid` recommendation to operators as the fix. It mitigates on Linux only and only
  where the operator applied it; the defect is the factory's.

## Users & Scenarios

- **U1 Operator on a shared workstation or CI host**: another local account runs `ps aux` during
  a round. Before: two live tokens in the output. After: `docker exec -w /gnomish/work -i -e
  GH_TOKEN -e CLAUDE_CODE_OAUTH_TOKEN … claude -p …` — names only.
- **U2 Operator with a non-default docker setup** (Colima, a remote context over `ssh://`, TLS
  certificates, a proxy to the daemon): the factory works exactly as before, because the docker
  client keeps `HOME`, `PATH`, `DOCKER_*`, certificate and proxy variables from the factory's
  environment; only declared credential names are removed.
- **U3 Gnome inside the box**: `env` inside the round shows the same variables with the same
  values as before this change; passthrough values are still read live at exec time.
- **U4 Security reviewer**: reads one ADR and one spec to learn where a secret may travel to a
  child process, and finds the build gate that fails when a future seam puts one on an argv again.

## Requirements

### Functional

- **FR1** The container adapter SHALL render every entry of the composed child environment into
  the `docker exec` argv as a value-less flag (`-e NAME`), never as `NAME=value`, and SHALL do so
  from the entry **names** alone, so the argv builder cannot be handed a value.
- **FR2** The values SHALL be delivered through the docker client process's own environment for
  that exec, relying on the docker CLI's documented behaviour of resolving a value-less `-e NAME`
  from its environment and leaving the variable unset in the box when the name is absent there.
- **FR3** One owner SHALL produce the argv and the client environment of an exec together, from
  one composed map, as one value; the docker start seam SHALL accept only that value, so an argv
  cannot be started without the environment that completes it.
- **FR4** The docker client's environment for an exec SHALL be the factory's inherited process
  environment with every name the run's child-environment allowlist declares as a credential
  removed, and the composed map laid on top. Nothing else SHALL be removed.
- **FR5** Callers of the exec seam that pass no environment (the container file channel's write and
  read, the materializer's scratch-directory creation) SHALL keep producing an argv with no `-e`
  flag at all and a client environment with no overlay.
- **FR6** The host adapter SHALL stay unchanged: it already clears the child's environment and
  fills it from the same composed map, and it is the medium twin of the container path, not a
  second implementation of this rule.

### Non-Functional Reliability

- **NFR-R1** An exec whose composed map is empty, or whose passthrough names are absent from the
  factory environment, SHALL behave exactly as today: no flag for an absent name, no error.
- **NFR-R2** No new process, file, or retry is introduced; the change is confined to how one
  existing process is started.

### Non-Functional Observability

- **NFR-O1** The existing debug line naming the applied allowlist (names only) remains the record
  of what an exec carried; no log line SHALL gain an environment value, and the docker timeout and
  kill warnings keep naming only the subcommand.

### Non-Functional Security

- **NFR-S1** No environment value SHALL appear in any element of any argv the factory composes for
  the docker client — asserted by a spec over the composed argv with credential-shaped entries and
  by an architecture scan asserting that the only place the sandbox tree spells the `-e` flag is
  the one owner, and the only place it constructs a process is the docker start seam.
- **NFR-S2** A declared tracker or check credential exported in the factory's environment SHALL be
  absent from the docker client's environment on every exec, and a passthrough value SHALL be
  present in it exactly when the exec's composed map carries it — asserted against a fake docker
  binary that prints its own environment.
- **NFR-S3** The values still reach the box: the existing container-mode credential E2E, which
  runs a credential-capturing agent binary in a real box, SHALL pass unchanged on the new
  delivery path.
- **NFR-S4** The change SHALL make the previous form unconstructible rather than discouraged: the
  argv builder's parameter type carries no values, and the docker start seam has no overload that
  takes a bare argv.

### Non-Functional Cost

- **NFR-C1** No token, tracker call, or subprocess is added.

## Operator Experience Criteria

- **UX1** No configuration changes. `factory.sandbox.env-passthrough` keeps its meaning and its
  startup validation; the AI-seam variables keep their setup instructions in the sandbox operator
  guide.
- **UX2** The sandbox operator guide's environment-passthrough section states the posture in one
  paragraph: values travel to the box through the docker client's environment, never on its
  command line, and a `ps` during a round shows names only.
- **UX3** An operator with a custom docker context or daemon address sees no behavioural change;
  the guide names what the docker client keeps from the factory's environment and the one thing it
  loses (declared credential names).

## Success Metrics

- **M1** Composed `docker exec` argv elements containing `=` after `-e`: 0, across every spec that
  drives the container adapter (today: every entry).
- **M2** Production sources under `sandbox/` spelling the `-e` flag: exactly 1 (the owner); under
  `sandbox/docker` constructing a `ProcessBuilder`: exactly 1 (the docker start seam) — both pinned
  by the architecture scan.
- **M3** Docker start seam overloads accepting a bare argv: 0 (today: 1).
- **M4** The container-mode credential E2E and every existing sandbox spec pass; the two specs
  that asserted the `NAME=value` shape are inverted, not deleted.
- **M5** Mutation score of `:sandbox:docker` stays at the module gate after the change.

## Open Questions

- **Q1** Whether the docker client's environment for management commands should adopt the same
  subtraction in this change rather than in the re-scoped FR15 of `add-subprocess-access-log`.
  Decision recorded in design D4: no — it widens the change to seams that carry no `-e`, and the
  same owner can extend to them later in one place.
- **Q2** Whether the operator guide should recommend `hidepid=2` on Linux hosts as defence in
  depth. Left to the guide's hardening section as advice, not as part of this change's contract.
