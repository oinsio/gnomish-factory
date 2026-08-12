# Change: add-sandbox-core

## Why

Today every gnome-product process — agent-CLI rounds and command checks — runs
directly on the host, inheriting the operator's full filesystem, environment
(with secrets), and open network. Prompt injection is a given (adaptive attacks
succeed >85%), and the factory's purpose is to execute the gnome's untrusted
output (builds, tests, downloaded dependencies). The explore sessions
(2026-07-20…31) closed all research questions, produced a 45-item threat
registry, and confirmed the industry-convergent design: an ephemeral container
per task with filesystem isolation, default-deny egress, env allowlist, and
resource limits — layers that only work together. This change builds that core
(ladder step A); TLS interception/virtual keys (B), Colima VM (C), cloud (D),
and GHA executor (E) follow as separate changes.

## What Changes

- **ADDED**: opaque `TaskExecutionEnvironment` port (materialize / exec
  with stdin / putFile / readFile / harvest / dispose) — the single
  gateway for all processes and all factory↔box file traffic over a
  working copy; host-agnostic contract (streams + git transport, no
  shared-FS assumption); file operations valid only between rounds.
- **ADDED**: two adapters — `host` (current worktree behavior, passport
  honestly declares "isolation: none") and `container` (Docker: full clone in
  a volume, internal-only network, resource limits).
- **ADDED**: egress guard — mitmproxy (non-intercepting SNI mode) outside the
  box, default-deny with operator-owned allowlist, DNS resolved by the guard,
  mandatory fail-closed self-check at environment startup.
- **ADDED**: unified findings funnel for judge/external/command check output:
  schema, size caps, sanitization, fenced tracker publication; the factory
  branches only on verdict/exit code.
- **ADDED**: external-check integrity — a pin-check guard on external
  checks: the check's definition files (law-declared plus
  adapter-contributed paths) byte-identical to the base branch before any
  adapter contact. Platform-authored verdicts become the stated contract
  for external-check adapters, first realized by
  add-external-check-github-actions.
- **ADDED**: `SecretsProvider` port — the single seam for factory-held
  secrets (tracker token today; gateway/depot keys later), with the
  env/file adapter as the sole zero-infrastructure implementation;
  Vault-class and OIDC adapters arrive with later changes.
- **MODIFIED**: agent executor and command-check runner execute exclusively
  through the environment port; child environment is a layered positive
  allowlist — adapter base set + operator passthrough by name +
  factory-set variables, nothing inherited implicitly; prompts travel via
  stdin; the decision file moves into the branch
  (`.gnomish-task/decisions/`).
- **MODIFIED**: git task persistence — clone-instead-of-worktree inside the
  environment + harvest (fixed-refspec fast-forward fetch); resume/salvage
  rebuilt over git transport; factory-side git hardening (bare reads, no
  hooks, no untrusted checkout in factory FS); sandboxed rounds close with
  a snapshot commit, persist state as a separate state commit, and are
  verified by read-back and parent-check at the harvest boundary;
  factory-authored lifecycle commits (`task.json` writes, the Completed
  cleanup) are built factory-side over bare git objects — no checkout,
  no live environment required.
- **MODIFIED**: pipeline config — stage `Mechanism` gains sandbox declarations
  (binding requirements, `requires-fresh`, `verify-in`); repo may only
  tighten, never weaken; pipeline law is read only from the factory clone
  of the base branch, never from the gnome-writable copy.
- **MODIFIED**: tracker token resolution moves behind `SecretsProvider`
  (the env/file adapter backs `GNOMISH_GITHUB_TOKEN`); the serve
  environment cleaner disposes aged environments through the port using
  runtime metadata for age.
- **MODIFIED**: the GitHub Actions external-check adapter becomes
  operator-enableable — the factory constructs it from config and injects
  it into the stage engine behind the pin-check guard; `PollStatus.Pass`
  gains the platform run URL so green checks are auditable from the
  tracker report; the adapter's provisional workspace stand-in is
  replaced by this change's concrete workspace type (closes the Q4/Q5
  leftovers of add-external-check-github-actions).
- **REMOVED**: nothing; host mode remains an explicit operator choice.

## Capabilities

### New Capabilities

- `execution-environment`: the `TaskExecutionEnvironment` port and its host
  and container adapters; adapter capability passports; ownership asymmetry
  and fail-closed reconciliation; environment lifecycle bound to stage
  segments; freshness knobs; env allowlist; resource limits; orphan cleanup;
  the factory↔environment channel (exec with stdin, putFile/readFile) with
  inert-data guarantees.
- `sandbox-egress`: the guard proxy, internal-only network, operator
  allowlist, DNS policy, startup self-check, denial observability.
- `verification-hardening`: the unified findings funnel (schema, caps,
  sanitization, fenced publication), the external-check pin-check guard,
  untrusted-content materialization invariants.
- `secrets-provider`: the port for named factory secrets — pluggable
  adapters invisible to consumers, env/file default, fail-closed
  resolution, no secret ever logged or passed into a box.

### Modified Capabilities

- `agent-executor`: agent rounds run via `exec()` of the task environment
  with an allowlisted environment instead of a host `ProcessBuilder` with
  inherited env; prompts via stdin; decision requests persist in the branch.
- `git-task-persistence`: working copy becomes a full clone inside the task
  environment; branch leaves via harvest; resume/salvage operate over git
  transport; factory git never executes hooks and never checks out gnome
  branches into factory-owned filesystem; sandboxed rounds use the
  snapshot-first two-commit protocol with harvest-boundary integrity;
  lifecycle commits (task context, decisions, outcome, cleanup) are
  factory-side bare-object commits.
- `pipeline-config`: stage manifests declare sandbox needs and freshness
  requirements; validation enforces "repo can only tighten"; pipeline law
  binds per invocation (factory clone of the base branch in git modes;
  workspace snapshot in in-place mode).
- `manual-run`: the command-check runner and the findings-file protocol
  operate through the bound task environment with an allowlisted child
  env; `files_exist` verifies the attempt commit in sandboxed mode;
  `run` prints a worktree path only in host mode — sandboxed working
  copies are named by task environment, never by host path.
- `tracker-take`: revocation salvage, push, and keep semantics route
  through the task environment port, matching git-task-persistence
  (salvage in-box + harvest, factory-side push, kept = stopped
  container with volume and network).
- `github-tracker`: the token is resolved through `SecretsProvider` by
  name instead of a direct env read; the credential name can never be
  allowlisted into a child environment.
- `factory-serve`: the aged-environment cleaner disposes through the
  port; age for sandboxed environments comes from runtime object
  metadata, not file mtimes.
- `stage-engine`: `PollStatus.Pass` gains an optional platform run URL,
  preserved by the engine into the recorded check result so reporting
  publishes it the way a failing check's findings travel today.
- `github-external-check`: the adapter becomes operator-enableable —
  constructed from factory config (base-URL key, `SecretsProvider`-resolved
  token) and injected into the stage engine behind the pin-check guard;
  Pass verdicts carry the authoritative run's URL; the adapter-local
  `GithubCheckWorkspace` stand-in is replaced by this change's concrete
  workspace type carrying the attempt commit.

## Goals

- G1: every gnome-product process can run inside a container that sees only
  the task's working copy, an allowlisted environment, and a single network
  route to the guard.
- G2: protection is fail-closed: sandbox or guard unavailable, self-check
  failed, or needs/passport mismatch → the task does not start (infrastructure
  failure); never a silent fallback to weaker isolation.
- G3: the port contract is host-agnostic, so future adapters (Colima VM,
  k8s, microVM) are adapter swaps, not redesigns.
- G4: host execution remains available as an explicit operator opt-in for
  trusted environments; its isolation mechanics are unchanged (worktree
  working copy, local processes, single-commit rounds, no-op harvest),
  while mode-independent process-discipline hardening (FR9, FR15–FR17,
  FR19, FR23, FR24) applies in host mode too.

## Non-Goals

- NG1: TLS interception, auth-header injection, virtual-key gateway, L7 rules
  — change B (this change only bakes the CA seam and picks an
  upgrade-compatible proxy).
- NG2: `.gnomish/` setup-script surface and post-setup snapshot cache —
  change B; here the operator supplies the image in factory config.
- NG3: Colima VM, cloud/k8s, and GHA executors — changes C/D/E.
- NG4: artifact depot (Nexus-class) — separate change; here only the seam:
  registry addresses in baked configs are parameters, not hardcoded.
- NG5: Docker-inside-the-sandbox (Testcontainers in-box) — documented ladder
  (CI `external` check as step 0) plus the `factory.sandbox.runtime` knob;
  no sysbox/kubedock integration now. Ladder step 1 — factory-run
  neighbor-service stacks from a filtered declaration (no privileged, no
  host mounts outside the workspace, no published ports) — is not built
  here either: change D owns it end-to-end, shipping one declaration
  with two realizations (pods in the task namespace; factory-run
  containers in the task's internal network for the container adapter).
- NG6: multi-provider model routing — falls out of the change-B gateway.
- NG7: server-side provider tools (web_search / web fetch executed by the
  provider's infrastructure, threat #45) — stripping them requires the
  change-B gateway; this change only documents the open threat and the
  interim operator guidance (disable provider-side web tools for factory
  keys where the provider allows).
- NG8: no real external-check platform adapter — this change builds the
  pin-check guard and states the adapter contract; the first platform
  adapter (GitHub Actions) is add-external-check-github-actions.

## Users & Scenarios

- U1: operator on a dev machine (macOS/Linux, Docker present) binds stages to
  the container adapter; tasks run isolated with no extra ceremony.
- U2: operator in a trusted environment (or without Docker) explicitly binds
  host mode and keeps today's workflow — worktree, local processes, zero
  env configuration for typical projects — under the same
  process-discipline hardening as sandboxed modes.
- U3: reviewer reads the task report and sees guard denials and pin-check
  failures as findings — a signal the gnome may have been manipulated.
- U4: any factory instance resumes or salvages an interrupted sandboxed task
  from the task branch alone.

## Requirements

### Functional

- FR1: a `TaskExecutionEnvironment` port SHALL own the working-copy
  lifecycle and the factory↔environment channel:
  `materialize(branch, commit?)` (an optional factory-chosen commit
  pins the working copy at that point of the task branch instead of
  its tip — the operation fresh-box verification and `--discard-work`
  build on), `exec(cmd, env, stdin?)` (optional stdin content, streamed
  stdout/stderr + exit code), `putFile(path, content)` and
  `readFile(path, sizeCap)` at factory-chosen paths, `harvest()`,
  `dispose()`; file operations SHALL be valid only between rounds, never
  mid-round; the contract SHALL NOT assume a filesystem shared between
  factory and environment.
- FR2: a host adapter SHALL implement the port over the existing worktree +
  `ProcessBuilder` mechanics; its passport SHALL declare no isolation.
- FR3: a container adapter SHALL implement the port as: full `git clone
  --no-hardlinks` from the factory's local clone into a task volume (no
  remote credentials or server address inside), one container per
  environment from the operator-configured image, `exec` into it, and
  disposal of container + volume + network as one operation.
- FR4: all gnome-product processes — agent-CLI rounds, CLI judge votes,
  and command checks — SHALL execute exclusively through the port; no code
  path may spawn them directly on the host.
- FR5: harvest SHALL fetch the task branch from the environment with a
  factory-fixed refspec, fast-forward-only, `--no-recurse-submodules`; the
  factory SHALL push to the real remote outside the environment.
- FR6: resume and salvage SHALL work over the port for any factory instance:
  a task interrupted mid-round is harvestable, and a new environment is
  materialized from the branch state alone.
- FR7: the container's only network route SHALL be the guard; the guard
  SHALL enforce a default-deny allowlist owned by operator config and
  resolve DNS itself (no direct DNS from the box).
- FR8: every materialized sandboxed environment SHALL pass a
  machine-verifiable self-check before its first gnome-product process
  (the self-check probes themselves run via `exec()`) — round
  environments and fresh-box verification/judge environments alike (direct egress fails;
  non-allowlisted via guard is denied; allowlisted passes; isolation
  mechanism is the expected one); any failure SHALL be an
  infrastructure failure: no gnome-product process executes in the
  environment — the task does not start, or the affected check/vote
  cannot be obtained.
- FR9: the child environment of every `exec()` SHALL be a layered
  positive allowlist, nothing inherited implicitly: the adapter's base
  set (host: a fixed documented minimum — `PATH`, `HOME`, `TMPDIR`,
  locale, `TERM`, `USER`, `SHELL`, no agent sockets; container: empty —
  the image supplies runtime env), plus operator-configured passthrough
  variables (exact names only, no patterns; values read from the factory
  environment at exec time), plus factory-set protocol variables;
  credential names SHALL be refused in passthrough as a configuration
  error; the applied allowlist SHALL be observable as names (never
  values) in debug logs; AI access enters only through the
  base-url/auth-token seam.
- FR10: the container adapter SHALL apply operator-configured resource
  limits: CPUs, memory, PID count, disk (volume size).
- FR11: startup SHALL find and remove orphaned task containers, volumes, and
  networks by factory-owned labels.
- FR12: sandbox binding SHALL be a stage-`Mechanism` attribute; an
  environment SHALL live for a contiguous segment of equally-bound stages;
  segment change = harvest → dispose → materialize.
- FR13: freshness SHALL be controllable: stages may declare
  `requires-fresh`; command checks may declare `verify-in: fresh-box`
  (materialized from the attempt commit); defaults are reuse-segment and
  same-box.
- FR14: the factory SHALL reconcile repo-declared needs against the bound
  adapter's passport and refuse fail-closed on mismatch; repo declarations
  may only tighten.
- FR15: all check findings (judge/external/command) SHALL pass one funnel:
  schema-validated verdicts, size caps, control-character sanitization
  before logs, fenced "untrusted machine output" publication to the
  tracker; factory control flow SHALL depend only on verdict/exit code.
  Artifact content passed to a judge SHALL be wrapped in hard delimiters
  as data, never interleaved with instructions; vote counts and majority
  aggregation stay as specified by the stage engine. In sandboxed mode
  judge votes SHALL run in a fresh environment materialized from the
  attempt commit, so a gnome-poisoned round box cannot grade itself.
- FR16: every external check SHALL be guarded by a pin-check performed by
  a guard component wrapping any `ExternalCheckClient`, before any adapter
  contact: the check's definition files — the union of pin paths declared
  in the stage law and paths contributed by the adapter — SHALL be
  byte-identical to the base branch, else quality failure with the diff as
  findings and the adapter never invoked. An adapter contributing no paths
  and a declaration naming none make the pin vacuously pass (the
  interactive client). Consuming only platform-authored verdicts is the
  stated contract on external-check adapters, realized by
  add-external-check-github-actions.
- FR17: untrusted branch content SHALL materialize only inside task
  environments; the factory SHALL read gnome branches as bare git objects,
  SHALL set an empty `core.hooksPath` on every factory-managed copy, and
  SHALL confine any model-output file writes to the working copy (resolved
  paths only, `.git/**` forbidden).
- FR18: all factory-held secrets SHALL be resolved through a
  `SecretsProvider` port (named lookup, adapter invisible to consumers,
  fail-closed on missing values, values never logged); the env/file
  adapter SHALL be the default and only implementation in this change.
- FR19: pipeline law — `.gnomish/` manifests, stage instructions, and
  judge acceptance criteria — SHALL be bound per invocation: read at
  invocation start from the factory-owned clone of the base branch
  (tracker-driven and git modes) or from the workspace at startup (the
  git-less in-place mode), then frozen for the invocation's lifetime,
  including in-process resume; control files and judge criteria SHALL be
  read from the law source, never from the gnome-writable working copy at
  use time; a contract test SHALL enforce the source in git modes.
- FR20: injection-persistence surfaces inside the box SHALL be read-only
  for the gnome: agent-CLI configuration, shell rc files, and the baked
  proxy/CA/build configs; writes to them SHALL fail.
- FR21: in sandboxed mode a round SHALL close with a snapshot commit of
  the working tree inside the environment, harvested before verification;
  verification SHALL judge the attempt (snapshot) commit — builtin checks
  read it as bare git objects, fresh-box checks and judge votes
  materialize from it, external checks poll CI runs of exactly that
  pushed commit; delivery of the attempt commit to the remote SHALL be
  a verified precondition of every external check — the factory
  re-attempts the push, and a commit that cannot be delivered SHALL
  classify the check as an infrastructure failure (cannot verify),
  never as a poll-timeout quality failure; state files SHALL then be
  persisted as a separate state commit. Host mode SHALL keep the
  existing single round commit.
- FR22: at the harvest boundary the factory SHALL verify that the
  harvested state commit's parent is the snapshot commit and that
  factory-authored files (state, trace) are byte-identical to what it
  wrote (read-back); any mismatch SHALL abort the task as a boundary
  violation.
- FR23: the decision request SHALL live in the branch at
  `.gnomish-task/decisions/<stage>-a<attempt>.json` — the single
  gnome-writable path under `.gnomish-task/` — carried by the
  snapshot/salvage commit so any instance can resume a pending
  escalation; files not matching the current stage and attempt SHALL be
  ignored; the git-less in-place mode keeps the temp-file transport.
- FR24: round and judge prompts SHALL be delivered to the agent CLI via
  stdin, never as an argv argument, in all modes.
- FR25: in sandboxed mode every factory-authored lifecycle commit — task
  branch creation with `task.json`, the resume decision, the task
  outcome, and the Completed cleanup commit — SHALL be created
  factory-side over bare git objects in the factory clone: no working
  copy, no checkout, no hook execution; the branch ref SHALL advance
  atomically only if the tip is unchanged (a concurrently moved tip
  fails the write, never force); recording an outcome SHALL NOT require
  a live environment — it works with the box stopped, disposed, or
  quarantined after a violation.
- FR26: the factory SHALL construct the GitHub Actions external-check
  adapter from factory config — a base-URL config key and a token
  resolved by name through `SecretsProvider` — and inject it into the
  stage engine wrapped by the pin-check guard, making the adapter
  operator-enableable; the adapter SHALL read the attempt commit from
  this change's concrete workspace type, replacing its adapter-local
  `GithubCheckWorkspace` stand-in.

### Non-Functional

- NFR-S1: no real secret SHALL exist inside the box: no tracker token, no
  push credentials, no host env; guard and factory config SHALL be
  unreachable from inside.
- NFR-S2: the gnome SHALL NOT be able to alter its own cage: sandbox/guard
  config lives outside; pipeline law is read from the factory clone, never
  from the gnome-writable copy.
- NFR-S3: content originating inside an environment SHALL be inert data
  for the factory: never executed or interpolated into commands,
  refspecs, or filesystem paths; never materialized as files on
  factory-owned filesystem (bytes in memory, parse, discard); read only
  at factory-chosen paths with size caps.
- NFR-R1: guard or Docker outages SHALL surface as infrastructure failures
  (Resilience4j retries, no stage attempt burned), consistent with existing
  failure classes.
- NFR-R2: disposal and orphan cleanup SHALL be idempotent and crash-safe:
  a factory killed at any point leaves nothing a restart cannot reclaim.
- NFR-O1: every guard denial SHALL be logged (metadata only, no bodies) and
  attached to the task report as findings; self-check results and
  environment lifecycle events SHALL be logged.
- NFR-O2: a passing external check SHALL surface the platform run URL in
  the tracker report through the same check-result channel a failing
  check's findings use today (`PollStatus.Pass` gains the field to carry
  it), so a green check is auditable without leaving the tracker.
- NFR-P1: environment startup overhead SHALL stay in seconds (negligible
  against round duration); segment reuse SHALL avoid repeated clones.
- NFR-C1: cost controls (virtual-key budgets) are deferred to change B;
  this change SHALL cap findings/log volumes read from checks to bound
  resource abuse.

## Operator Experience Criteria

- UX1: sandbox setup is factory config only: adapter binding, image, limits,
  allowlist — no target-repo changes required to sandbox an existing
  pipeline.
- UX2: a needs/passport mismatch or failed self-check produces one clear
  error naming the unmet need or failed probe, not a mid-task crash.
- UX3: guard denials appear in the task report with host and path — an
  operator can distinguish "tool needs a new allowlist entry" from
  "exfiltration attempt" at a glance.
- UX4: docs ship a reference image recipe (CA baked in, JVM/Gradle proxy
  configs, registry addresses as parameters) and the host-mode passport
  honestly states its risks.
- UX5: docs recommend `verify-in: fresh-box` for the final quality gate
  of a pipeline: the check then runs against exactly what a human will
  merge and doubles as proof that the branch is self-sufficient.
- UX6: host-mode env setup is zero-config for typical projects — the
  built-in base set suffices; an exotic toolchain costs one passthrough
  name per variable, and a missing variable is diagnosable from the
  debug log of applied env names.

## Success Metrics

- M1: both adapters pass the same port-level contract spec suite.
- M2: E2E: a pipeline completes in container mode against Gitea, and the
  in-box self-check E2E proves direct egress fails while allowlisted
  traffic passes.
- M3: E2E assertion: the box environment contains no tracker token, no push
  credential, and no host-inherited variable.
- M4: a sandboxed task interrupted mid-round is resumed by a second factory
  instance from the branch alone (existing resume E2E, container mode).

## Open Questions

- Q1: re-verify tool choices at implementation start (mitmproxy SNI mode,
  Docker Sandboxes maturity, sysbox/kubedock state) — the market moves fast;
  the explore ladder carries an explicit review-before-build note.
- Q2: shallow clone for very large repos — defer until a real repo hurts.
- Q3: exact placement of the findings funnel relative to the engine's
  existing verdict flow (engine-side vs adapter-side) — design decision.
- Q4 (carried from add-external-check-github-actions Q4): that change's
  NFR-O1 is only half met — a Pass verdict has no field to carry the
  platform run URL into the tracker report (`PollStatus.Pass` is a bare
  marker; only the Fail leg's finding `details` carries the URL today).
  Fixing it needs a `PollStatus.Pass` domain-model change. Resolved in
  this change: NFR-O2, task 8.4a, stage-engine and github-external-check
  deltas.
- Q5 (carried from add-external-check-github-actions Q5): that change's
  UX2 is unmet — no factory entry point constructs a
  `GithubCheckExternalClient` and injects it into the stage engine (no
  base-URL config key, no analog of `GithubTrackerAdapterFactory` for
  external checks); no configuration enables the GitHub Actions adapter
  today. Task 8.4 here already wires the pin-check guard around every
  `ExternalCheckClient` assembly. Resolved in this change: FR26, task
  8.4, github-external-check delta.
- Q6 (defect found during implementation review): this change's NFR-O1 /
  UX3 report-attachment is only half met — `GuardDenialLog` and
  `EgressGuard.denialFindings()` exist and are tested, but no production
  code reads them, so denials never reach the task report and the
  "Denied exfiltration attempt reaches the report" scenario fails.
  Two structural gaps, not a forgotten call: (a) the
  `TaskExecutionEnvironment` port exposes no denial accessor — only the
  concrete `SelfCheckedEnvironment.guard()` does — so the round/check
  boundaries, which hold the port type, cannot reach denials; (b) the
  report model has no verdict-independent findings slot — findings reach
  state.json/status.json only via a check `Verdict.Fail`, and the round
  verdict is the last check's verdict, so a passing attempt has no field
  to carry a denial and folding denials into a `Verdict.Fail` would flip
  the stage outcome (NFR-O1 is observability, not a gate). Task 6.2
  delegated attachment to task 8.2's funnel, but 8.2 routed only
  judge/external/command findings; the denial channel was never built.
  Fixing it needs a report-model change (a denial-findings field
  independent of the check verdict) — exactly parallel to Q4/NFR-O2/task
  8.4a. Deferred to a separate change; tracker-report reach to be decided
  there.
