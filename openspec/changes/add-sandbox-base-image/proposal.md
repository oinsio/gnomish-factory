# Proposal: add-sandbox-base-image

Sequenced after `add-release-pipeline` (it adds the image to the release
workflow and tags it with the product version) and after
`own-git-invocation-policy` (both archived): this change takes up NG5 of the
latter — the box's git floor rises to the factory's and the two floors become
one again. `add-doctor-command` follows and reads the contract label this
change introduces; `add-sandbox-hardening` is sequenced after this change too,
since its provisioning container builds on the base image and takes the image
facts this change introduces. Folded in on 2026-10-23, while the change was
in apply: the serve-readiness decisions of that day's defect analysis (FR13–FR18,
design D17–D21, task group 7), because this change already puts the image
contract refusal on the arm those decisions replace and promises in G2 and U3
a refusal "before any round", which `serve` today does not keep.

## Why

Container mode is the default binding, so every operator needs a sandbox image.
Today they build one from the reference recipe in `docs/examples/sandbox-image/`
— and in doing so copy the factory's own contract by hand: the `gnome` user with
uid 1000, the root-owned control surfaces, the proxy variables pointing at the
guard's alias and port, the CA seam, the Maven settings. The operator stand did
exactly that and its Dockerfile has already drifted from the recipe. When the
factory's contract changes, nothing tells an operator that their image is
stale; the failure shows up as a broken round.

Two more hand-kept copies exist inside the repository: the guard's alias and
port are spelled in the recipe and in `GuardCommands`, held together only by a
comment; and a test image (`JvmProxyEgressImage`) re-types the recipe's JVM
proxy plumbing on its own base.

A third drift is about versions: the factory's own git floor is 2.56.0, the
box's is held at 2.51.0 only because every image the box runs installs an older
git from a distribution archive (`own-git-invocation-policy`, design D11). No
distribution archive or PPA ships 2.56.0 today, so the base is the one place
the box's git can be brought to the factory's floor for every operator at once.

Agreed in the 2026-09-27 design session: a versioned base image on ghcr.io plus
the recipe as an example built on it; the base is Ubuntu LTS, carries no agent
CLI (vendor terms) and no project toolchain; toolchains are grafted in with
`COPY --from` or installed; the image states its contract version and the
factory checks it.

A live incident (2026-10-23, the time-zones project, issue #30) showed that
"refused before any round" is not what `serve` does for any of the operator's
preconditions. The daemon claimed the task, then the factory clone's ref
format check refused the operator's `files`-backend clone; the refusal travelled
the arm of the claimed-run dispatch that exists to preserve an exit code, so the
slot ended as an uncaught crash with no ledger line, the claim release was the
GitHub adapter's designed no-op, the task returned to `Ready` only when the
reaper found the claim stale, and the feed claimed it again — a loop with the
lease TTL as its period and the task's `gnomish:working` label as its only
visible trace. The same shape holds for every operator precondition the
factory evaluates per task after the claim: the sandbox plan (image unset,
Docker unreachable), the image contract this change adds, and the box's git
floor. Only the factory's own git version is checked once, before anything is
claimed. The fix belongs here because this change adds one more precondition to
that arm and already promises its refusal before any round.

## What Changes

- **ADDED**: `ghcr.io/oinsio/gnomish-sandbox-base` — Ubuntu 24.04 with a `git`
  at the factory's git floor (built from the pinned upstream release the
  repository's install script names, never the archive's package), `curl`, CA
  certificates, the `gnome` user (uid 1000) owning `/gnomish/**`, root-owned
  `/etc/gnomish` (Maven settings, CA drop folder) and agent-policy folder, the
  guard proxy environment, a root-owned home for `gnome` in which only declared
  subdirectories are writable (design D14), and three helper commands:
  `gnomish-trust-ca` (imports dropped CA certificates into the system store
  and, when a JDK is present, the JVM trust store), `gnomish-home-dir`
  (declares a gnome-writable home subdirectory) and `gnomish-verify-image`
  (build-time contract check).
- **ADDED**: the image carries the label `io.gnomish.image-contract=<N>`; the
  factory refuses to start a box from an image whose label is missing or names
  another contract version, naming the fix.
- **ADDED**: one source file for the values the image and the factory must agree
  on — contract version, guard alias, guard port — read by the image build and
  by the factory.
- **ADDED**: the release workflow builds and pushes the base for `linux/amd64`
  and `linux/arm64`, tagged `X.Y.Z` (never re-pushed) and, for a final release
  only, `X.Y` (moves; a pre-release never moves it), with a provenance
  attestation; a weekly workflow rebuilds `X.Y` of the highest-versioned final release to
  pick up Ubuntu security updates; both scan the image and publish the
  results.
- **MODIFIED**: the box's git floor becomes the factory's git floor (one
  constant, 2.56.0); every image the floor judges — the base, the test sandbox
  images, the reference recipe — moves to it in the same step.
- **MODIFIED**: the reference recipe becomes `FROM` the base: JDK and Node
  grafted or installed, the agent CLI, `gnomish-trust-ca`, and a final
  `gnomish-verify-image`.
- **MODIFIED**: the container E2E layer (`:adapters:git` and `:bootstrap`)
  builds its images from the locally built base instead of re-typing the
  recipe.
- **MODIFIED**: `serve` assesses the operator's static preconditions at
  startup — the factory clone's ref format, the sandbox plan (image configured,
  runtime reachable), the image contract and the box's git floor — and refuses
  to start, before any claim, with the same message the per-task check prints.
- **MODIFIED**: an operator-precondition refusal met after a claim is a typed
  take result, not a rethrown exception: it reaches every sink (outcome log,
  drain report, run summary, ledger), releases the claim, and `take` exits with
  a dedicated code, 8, in the "tool could not operate" family (the spirit of
  `sysexits` `EX_CONFIG`); `run` maps the same refusals to 8.
- **MODIFIED**: the remote outage gate becomes one of the daemon's
  **conditions**, one owner for all of them: a precondition refusal inside a
  slot or the janitor opens its condition, the feed reads one gate before it
  writes a claim, a cadence probe closes the condition, and the transition is
  logged once; the `remote` snapshot section and the `remoteOutage` ledger line
  generalize to `conditions` and `daemonCondition`.
- **MODIFIED**: a slot that ends in an unclassified crash still writes its
  `taskOutcome` ledger line; the janitor's precondition refusals open their
  condition instead of being logged as a generic failed tick.

## Capabilities

### New Capabilities

- `sandbox-base-image`: the base image's content and contract, its helper
  commands, its label and tags, its publication and rebuild.

### Modified Capabilities

- `sandbox-egress`: "Image carries the egress plumbing" — the plumbing comes
  from the base image; the recipe layers on it.
- `execution-environment`: adds "The box image declares a matching contract
  version"; modifies "The box's git meets the floor before the seed clone" —
  one floor, the factory's; a refused image returns the claimed task through
  the daemon condition, never through the abort accounting; modifies "Runtime
  outage is an infrastructure failure" — a runtime unreachable before any round
  returns the task instead of escalating it.
- `factory-serve`: adds "Serve assesses operator preconditions at startup" and
  "A precondition refusal in a slot or the janitor is a daemon condition";
  modifies "Remote outage gate holds the feed off the tracker" and "Gate
  transitions are the outage's log and snapshot signal" into the daemon
  conditions they become.
- `tracker-take`: modifies "Exit codes by take result" — code 8 for an
  operator-precondition refusal.
- `manual-run`: modifies "Exit codes by outcome family" — code 8 joins the
  below-10 family.
- `serve-observability`: modifies the `taskOutcome` requirement (a crashed slot
  writes its line), the `remote` snapshot section and the `remoteOutage` ledger
  line (generalized to conditions).

## Goals

- **G1**: an operator's Dockerfile contains only their toolchain and agent CLI;
  every factory contract item comes from `FROM`.
- **G2**: an image built for another contract version is refused before any
  round, with a message saying how to rebuild.
- **G3**: the base image receives Ubuntu security updates weekly without a
  factory release.
- **G4**: the factory has one git floor again; an image built `FROM` the base
  meets it without the operator installing a git.
- **G5**: nothing a round writes outside the working copy can change what
  later rounds of the same box read as shell startup or as top-level
  configuration in the gnome's home.
- **G6**: a `serve` started against an operator precondition it cannot meet
  claims nothing: it exits at startup naming the precondition and the fix.
- **G7**: a precondition refusal met after a claim costs the task nothing and
  the operator one log line: no abort, no backoff, no fuse charge, no
  re-claim loop; the daemon stops claiming until the precondition holds again.

## Non-Goals

- **NG1**: toolchain flavours of the base (JDK, Node variants) — the operator
  grafts them.
- **NG2**: shipping an agent CLI in any published image.
- **NG3**: a blocking vulnerability gate on the base image: Ubuntu often has
  unfixed advisories; results are published, not gated, in this change.
- **NG4**: isolating the agent CLI from settings in the working copy
  (tracked separately, NG1 of `fix-operator-blockers`, sequenced after the
  agent backend SPI design); the base's root-owned managed-settings file sets
  only `allowManagedHooksOnly` (D14), which also stops hooks from the working
  copy's settings, and nothing else of them.
- **NG5**: non-Ubuntu bases; an operator building their own image may set the
  label after passing `gnomish-verify-image`, and their image's git must still
  meet the floor.
- **NG6**: version floors for tools other than git, and a version check of the
  box beyond `git --version` (Q2, Q3). Picked up by the change that gives a
  second in-box tool a mechanic the factory's own code depends on (a curl
  feature the probes need, a `keytool` flag `gnomish-trust-ca` needs).
- **NG7**: the content of `~/.claude` (the agent CLI's `CLAUDE.md`, `rules/`,
  `settings.json` `env` and `permissions`), which the CLI must be able to write
  and so stays gnome-writable under D14. Owned by D4 of
  `harden-verification-integrity` (a factory-owned config directory per round,
  `CLAUDE_CONFIG_DIR`). Pickup: the implementation of that D4; until then
  threat row #5 records the remainder.
- **NG8**: the content of the declared toolchain directories (`~/.gradle/init.d`,
  `~/.gradle/gradle.properties`, an `.npmrc` under a declared directory) and
  processes a round leaves running in the box — both persist into later rounds
  of one box. Closed by a reset at the round boundary (clear the declared
  directories except a closed list of caches; end every box process but the
  keep-alive), a single-owner mechanism of its own with its own kill-window
  analysis. Pickup: the change that adds a reset of the box at the round
  boundary — the first change that takes threat row #39 inside a segment; until
  then threat row #39 records the remainder. (A project setup script run in a
  container `FROM` the base, as `add-sandbox-hardening` plans, meets the
  root-owned home and declares its toolchain directories with
  `gnomish-home-dir`, design D14, Risks; it resets nothing at the round
  boundary.)
- **NG9**: whether a fresh `take` may adopt this instance's own live claim on
  the task (`GithubClaimLease.claim` does today, which is why the incident's
  log showed two "claim acquired" lines seven seconds apart with no new claim
  comment). Its intent must be verified before it is changed. Pickup: the
  change that gives a fresh claim a fencing identity of its own (the
  `ClaimIdentity` of `add-claim-return`), which is the first change that can
  tell "my live claim" from "my stale one" without a time.
- **NG10**: a non-refusing assessment of the same preconditions for an
  operator's `doctor` command. Owned by `add-doctor-command`, which builds its
  assessment on the startup owner this change introduces (design D17) rather
  than a second spelling of the checks.
- **NG11**: the fenced immediate return of a released claim (`returnToReady`).
  Owned by `add-claim-return` (its task 4.3 switches the release this change's
  typed result performs); until it lands, the release stays the lease path's
  (the reaper returns the task after TTL), and the daemon condition — not the
  release — is what stops the re-claim loop.

## Users & Scenarios

- **U1**: an operator writes `FROM ghcr.io/oinsio/gnomish-sandbox-base:0.1`,
  grafts Temurin 25 and Node, installs the agent CLI, ends with
  `RUN gnomish-trust-ca && gnomish-verify-image`, and the build succeeds.
- **U2**: the same operator accidentally makes `/etc/gnomish` writable; the
  build fails in `gnomish-verify-image` naming the path.
- **U3**: after upgrading to a factory with contract version 2, the operator's
  old image is refused at box start with "image built for contract 1, this
  factory needs 2 — rebuild FROM gnomish-sandbox-base:<X.Y>".
- **U4**: a week after a release, `docker pull …:0.1` returns a base with
  current Ubuntu packages; `…:0.1.0` is unchanged.
- **U5**: an operator whose image was built on a distribution git (2.53) and
  not on the base is refused before the seed clone with the command's coded line
  (`[GF171]` for `take`, `[GF172]` for `run`) naming the floor and the base to rebuild from.
- **U6**: an operator starts `serve` on a factory clone whose ref backend is
  `files`; the process exits at startup with the `[GF173]` line and the
  `git refs migrate` remedy, and the tracker shows no claim.
- **U7**: while `serve` runs, the operator's Docker daemon stops; the next slot
  to materialize a box opens the container-runtime condition, the task returns
  to `Ready` with no abort history, the feed claims nothing more, one WARN
  names the condition and the fix, and after the daemon is back the probe
  closes the condition and the next claim proceeds.
- **U8**: the same operator reads the ledger afterwards and finds one
  `daemonCondition` line per episode and one `taskOutcome` line per slot that
  ended, including a slot that crashed.

## Requirements

### Functional

- **FR1**: the base image SHALL provide the contract of the sandbox guide:
  `git` at or above the factory's git floor and `curl`, user `gnome` uid 1000
  owning `/gnomish/work` and `/gnomish/scratch`, root-owned `/etc/gnomish` and
  agent-policy folder, the proxy environment for curl-class tools, JVM tools
  and Maven, and end with `USER gnome`. The git SHALL come from the pinned
  upstream release the repository's one install script names, so a floor raise
  is one edit for the factory and the base alike.
- **FR2**: `gnomish-trust-ca` SHALL import every certificate in the CA drop
  folder into the system trust store and, when `keytool` is on `PATH`, into the
  JVM trust store; with an empty folder it SHALL succeed and change nothing.
- **FR3**: `gnomish-verify-image` SHALL check the filesystem half of the
  contract (tools present, ownership and write permissions of `/gnomish/**`,
  `/etc/gnomish`, the agent-policy folder and the gnome's home, and the agent
  policy's managed-hooks-only setting) and fail naming every violation.
- **FR4**: the image SHALL carry `io.gnomish.image-contract=<N>` and the OCI
  labels for version, source and revision.
- **FR5**: before starting a box, the factory SHALL read the image's labels and
  refuse a missing or different contract version with a message naming both
  versions and the base tag to rebuild from.
- **FR6**: the contract version, the guard alias and the guard port SHALL come
  from one source file read by the image build and by the factory; neither the
  Dockerfile nor Java source SHALL spell them.
- **FR7**: the release workflow SHALL build the base for `linux/amd64` and
  `linux/arm64`, push `X.Y.Z` and, for a final release only, `X.Y` (a
  pre-release pushes its full version tag alone), attest provenance, and
  publish a scan report; a tag `X.Y.Z` already present SHALL NOT be overwritten.
- **FR8**: a scheduled weekly workflow SHALL rebuild the source of the final
  (non-draft, non-pre-release) release with the highest version and push only
  its `X.Y` tag, with attestation and scan report.
- **FR9**: the reference recipe SHALL build `FROM` the base of the factory
  version it ships with and end with `gnomish-trust-ca` and
  `gnomish-verify-image`.
- **FR10**: the container E2E layer — the image builders in `:adapters:git`
  and the fake-agent sandbox image in `:bootstrap` — SHALL build its test
  images from the base built from the repository's Dockerfile.
- **FR11**: the box's git floor SHALL be the factory's git floor, one constant
  read by the factory's own git check and by the box's seed-clone check; the
  constant for a separate box floor SHALL be removed in the same step that
  moves every image the floor judges onto the base.
- **FR12**: the base SHALL keep the gnome's home root-owned, with root-owned
  inert shell startup files and no other top-level entry the gnome can write,
  and SHALL provide `gnomish-home-dir`, the one way to declare a gnome-writable
  home subdirectory; the base declares `.claude` and `.cache`.
  `gnomish-verify-image` SHALL fail on an undeclared gnome-writable home entry.
  The agent-policy file SHALL allow managed hooks only, and
  `gnomish-verify-image` SHALL fail when it does not.
- **FR13**: `serve` SHALL assess, at startup and before any tracker claim, every
  operator precondition the factory otherwise evaluates per task after the
  claim and whose answer does not depend on the task — the factory clone's ref
  format, the sandbox plan (image configured, container runtime reachable),
  the image contract (FR5) and the box's git floor (FR11) — through the same
  checks the per-task path runs, and SHALL exit refusing with the check's own
  message; the per-task checks stay in place, since every one of them but the
  image being configured can change while the daemon runs.
- **FR14**: an operator-precondition refusal met after a claim (clone ref
  format, container runtime unreachable, box git floor, image contract) SHALL end the claimed run with a
  typed take result that reaches every sink a terminal result reaches —
  outcome log, drain report, run summary, ledger — and SHALL release the
  claim; it SHALL NOT be rethrown out of the slot, recorded as an abort, or
  charge the task's backoff or fuse. `take` SHALL exit 8 for it and `run`
  SHALL exit 8 for the same refusals; code 4 stays retired.
- **FR15**: the daemon SHALL keep a set of **conditions** with one owner — the
  remote outage gate becomes the first — one per precondition that can change
  while the daemon runs (the remote, the container runtime, the clone's ref
  format, the image contract, the box's git floor). A typed refusal inside a
  slot or the janitor SHALL open its condition; the feed SHALL read one gate
  over all conditions before every claim and claim nothing while any is open;
  slots already working SHALL continue.
- **FR16**: while a condition is open the daemon SHALL probe it on the gate's
  existing growing, jittered, capped cadence by repeating the check that
  opened it, and only a passing probe SHALL close it; a probe SHALL make no
  tracker call and SHALL claim nothing.
- **FR17**: the janitor's branch pass SHALL classify an operator-precondition
  refusal (the container runtime unreachable from the sandbox plan, a clone
  ref-format refusal) as its daemon condition, logged once by the condition,
  never as the loop's generic failed tick; the plan's other usage errors are
  configuration the startup assessment (FR13) already refused.
- **FR18**: a slot whose run ends by an unclassified throwable SHALL still
  append its `taskOutcome` ledger line with `outcome: aborted`, so the ledger
  and the dashboard count every slot that ended.

### Non-Functional

- **NFR-S1**: no published image SHALL contain an agent CLI or a credential.
- **NFR-S2**: the base's control surfaces SHALL be root-owned and not writable
  by `gnome` (the existing read-only-surfaces requirement, FR20 and NFR-S2 of
  `add-sandbox-core`, now provided by the base) — the shell rc files and the
  gnome's home included, not only `/etc/*` (design D14).
- **NFR-S3**: the workflows pushing images SHALL hold `packages: write` only in
  the job that pushes.
- **NFR-R1**: the contract check SHALL add no container start: it reuses the
  image inspection the factory already performs.
- **NFR-R2**: a condition SHALL open only on a positive, typed signal (the
  refusal exception the check itself throws); any other failure stays in the
  crash arm with its abort accounting, so a bug is never mistaken for an
  operator precondition and the feed is never held off the tracker by an
  unclassified failure.
- **NFR-R3**: the startup assessment SHALL be idempotent and SHALL write
  nothing: a `serve` refused at startup leaves the tracker, the clone and the
  container runtime exactly as it found them.
- **NFR-O1**: every refusal (contract mismatch, verify failure) SHALL name the
  item, the expected value and the fix.
- **NFR-O2**: a condition's opening SHALL log one WARN with its operator-event
  code, the condition, the cause and the fix; its closing one INFO with the
  duration and probe count; probes between SHALL be DEBUG with the roll-up the
  gate already keeps. The snapshot's `remote` section generalizes to
  `conditions`, one entry per condition, and the ledger's `remoteOutage` line
  to `daemonCondition`, one line per closed episode naming the condition;
  readers SHALL still accept snapshots and ledgers written before this change.
- **NFR-O3**: `SLOT_CRASHED_UNCAUGHT` (`GF076`) SHALL be logged only for a
  throwable no arm classifies; a classified refusal is never logged under it.
- **NFR-C1**: the weekly rebuild is one multi-arch build per week.
- **NFR-P1**: the base SHALL stay small: no build tools (the git build runs in
  a build stage that the published image does not carry), no documentation,
  apt lists removed (target under 150 MB per architecture, the git install
  included).

## Operator Experience Criteria

- **UX1**: the recipe README shows a complete operator Dockerfile of at most
  about 25 lines on top of the base.
- **UX2**: the contract refusal fits on two lines and contains a ready-to-use
  `FROM` line.
- **UX3**: a `serve` refused at startup prints exactly what the per-task
  refusal would have printed for the same precondition — one message per
  precondition, the fix included — and exits with the code of a startup
  failure; the operator never learns a precondition from a slot crash.
- **UX4**: a condition is readable without logs: the dashboard's conditions
  section names which precondition is open, since when, the last cause and
  the next probe time.

## Success Metrics

- **M1**: the reference recipe's Dockerfile shrinks to toolchain + agent CLI +
  the two helper calls; no contract item remains in it.
- **M2**: `git grep -e '"gnomish-guard"' -e 'PROXY_PORT = ' --
  'sandbox/docker/src/main/*' '*Dockerfile'` returns nothing (the guard's
  temporary directory in `:bootstrap`, named `gnomish-guard` as a path and not
  as an alias, is outside this scan and stays).
- **M3**: the container E2E suites pass against the locally built base with the
  `JvmProxyEgressImage` recipe copy deleted.
- **M4**: a final release produces the base under both tags for both architectures,
  with an attestation that `gh attestation verify oci://…` accepts.
- **M5**: `GitVersion` holds one floor; `git grep -n BOX_FLOOR` returns
  nothing.
- **M6**: the incident replayed on the operator stand (a factory clone on the
  `files` backend, one `Ready` task): `serve` exits at startup, the tracker
  shows zero claims and the task keeps no `gnomish:working` label.
- **M7**: with the clone migrated to `files` under a running `serve` (the
  per-task path), the tracker sees at most one claim and one release per slot
  for the whole episode, the log carries one WARN and one INFO, and `grep
  GF076` over the daemon's log is empty.
- **M8**: `grep -c '"type":"taskOutcome"'` over a drain run's ledger equals the
  number of slots that ended with a final state, a slot killed by an injected
  throwable included.

## Open Questions

Base distribution, tag scheme and the non-blocking scan were decided in the
design session and in this proposal's non-goals. The base's git (formerly Q1,
raised by task 9.13f of `tighten-spec-contract` and by NG5 of
`own-git-invocation-policy`) is decided in design D8 and D9. Two questions stay
open, both deferred by NG6 with its pickup condition.

- **Q2 — Which other image dependencies carry a version requirement.** git is
  the first tool whose version the factory's own mechanics constrain; the
  image contract lists the other tools by presence only. The other tools the
  factory drives inside the box (curl for the egress self-check probes, the
  shell the seed helper and the self-check run in, the CA tooling behind
  `gnomish-trust-ca`, `keytool`) and the age of the distribution itself need
  the same review before a second floor is written.
- **Q3 — How the factory checks that the box carries the versions it needs.**
  Today `BoxGitFloor` starts a one-shot container per materialize
  (`git --version`), which NFR-R1 does not account for; `gnomish-verify-image`
  (FR3) checks presence, not versions; the contract label names a contract
  version, not tool versions. Whether the label implies the floors, whether
  the build-time check and the box-start check read the floors from one
  source, and what an operator's custom image (NG5) must prove, is to be
  designed when a second floor arrives (NG6).

## Impact

- New `sandbox-image/` folder (Dockerfile, helper scripts, contract source
  file); `.github/workflows/release.yml` (preflight becomes its own job; a new
  image job) and a new `sandbox-image-rebuild.yml`; `scripts/install-git.sh`
  gains a consumer (the base's build stage); `scripts/release-preflight.sh`
  also outputs the base tag of the release (`X.Y` for a final release, the full
  version for a pre-release) and gains a `--base-tag <tag>` mode for the weekly
  rebuild, and an `image_tags` output, the image tags a release pushes (design
  D15); three new scripts beside it with their specs, `image-tag-refusal.sh`,
  `latest-final-release.sh` and `check-recipe-stamp.sh`, and
  `image-contract-build-args.sh`, the one translation of the contract file
  into build arguments for both workflows and the E2E fixture (design D16),
  with new `docker`
  and `gh` presets in the committed stand-in library, and an `actionlint` step
  in `ci.yml` (design D15); the base tag is computed for the build by
  `product-version-conventions` and read by `FactoryVersion`, which carries it
  as a new `BaseTag` value (design D11).
- `:sandbox:docker`: `GuardCommands`, `DeclaredVolumeOverrides`,
  `DockerCommands`, `ContainerEnvironmentBuilder`, `BoxGitFloor`,
  `EgressSelfCheckProbes` (`curl -q`, D14); a generated contract resource.
- `sandbox-image/`: the home layout, `bin/gnomish-home-dir`, the managed
  settings, and the home rule of `bin/gnomish-verify-image` (D14). `:gittransfer`: `GitVersion` loses `BOX_FLOOR`.
- Tests: the container E2E image builders in `:adapters:git`
  (`DockerImageBuilder`, `GitSandboxImage`, `EgressProbeImage`,
  `DeclaredVolumeSandboxImage`, `ContainerReadOnlySurfacesSpec`,
  `JvmProxyEgressImage`, `ContainerFreshSegmentSpec`) and
  `FakeAgentSandboxImage` and `BaseImageHelpersSpec` in `:bootstrap`;
  `EnvironmentSelfCheckSpec`;
  `BoxGitFloorSpec`; a new exemption file under
  `config/spec-contract-exemptions/` for the workflow-verified scenarios,
  written at archive (design D10).
- Docs: `docs/examples/sandbox-image/` (Dockerfile, README),
  `operator-guide-sandbox.md` (one floor, `gnomish-home-dir`),
  `docs/sandbox-threat-registry.md` (rows #5 and #39), `docs/glossary.md` (image
  contract, base image), the developer guide's releasing section.
- Serve readiness (FR13–FR18): `:application` — `ClaimedRunFailureDispatch`
  (the four refusals leave the rethrow arm), `SandboxModeSelector` (a typed
  runtime refusal), a new `app/port/run` port for the image's readiness
  (implemented in `:sandbox:docker` over `ImageContractCheck` and `BoxGitFloor`),
  `TakeResult` (a new variant),
  `take/TakeExitCodeMapper` and `RunExitCodeMapper` (code 8),
  `RunExceptionReporting`, `TakeSlotRunner` (the ledger backstop),
  `serve/RemoteOutageGate` and `RemoteOutageProbeSchedule` (generalized to
  conditions), `serve/FeedCycle` (one gate read), `serve/SlotOutcomeLog`,
  `ClaimlessDeliveredCleanup` (classified refusals), `ServeCommand` (the
  startup assessment); `:bootstrap` — the binding of the readiness port beside
  `ContainerRuntimeProbe`; `:serveobservability` — `LedgerJsonMapper`,
  `SnapshotJsonMapper`/`SnapshotJsonReader` and `dashboard/LedgerAggregator`
  (declared pairs, `manual-sync-pairs.md`); `:operatorevent` — new codes for
  the conditions; docs — `operator-guide-serve.md` (startup, exit codes,
  conditions), `operator-guide.md` (`take` exit code 8), `docs/glossary.md`
  (daemon condition; the remote outage gate entry re-pointed).
