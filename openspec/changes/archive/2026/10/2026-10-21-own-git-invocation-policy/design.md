# Design: own-git-invocation-policy

## Context

Driven by FR1–FR10, NFR-R1, NFR-R2, NFR-S1, NFR-S2 and UX3 of the proposal. The principle that
outlives this change goes into ADR 0012 ("the clone's configuration is untrusted input, not
storage"). This file records only the local decisions.

The factory starts git from exactly two places today: `GitProcessRunner` (`:adapters:git`) and
`GitExec` (`:gitobjects`); both hand their `ProcessBuilder` to the `:subprocess` launch owner,
`ProcessLaunch` (design D19 of fix-terminal-receipt-convergence). A third place owns flags without
launching anything: `GitTransfer` (`:gittransfer`), built by its own `fetch`/`harvest`/`clone`
factories and by `GitPush` for origin's two pushes (design D13 of tighten-spec-contract), which the
runner and the seed helper script render. Their policies differ:

| Launcher / owner | Hooks | Signing, fsmonitor, replace refs | Maintenance | Inherited `GIT_CONFIG_*` | Global/system config |
|---|---|---|---|---|---|
| `GitProcessRunner`, string entry | local config decides | local config decides | on | inherited | ambient |
| `GitExec` (plumbing) | off, by its own `-c core.hooksPath=/dev/null` literal (design D19 of add-sandbox-core, since 2026-08-16) | local config decides | on | inherited | `/dev/null` |
| `GitTransfer` (fetch, clone) | local config decides | local config decides | off (`-c`) | stripped | ambient for origin, isolated for others |
| `GitTransfer` built by `GitPush` (push) | local config decides (`pre-push` runs) | local config decides | on | stripped | ambient |

Verified on git 2.55 while preparing this change:

- `-c core.hooksPath=/dev/null` suppresses a hook planted through local config, the
  `reference-transaction` hook of `update-ref` included — which is why `GitExec` already carries
  that one literal; nothing else of the set.
- `-c maintenance.auto=false` stops `git commit` from spawning `maintenance run --auto --detach`.
- Without `core.useReplaceRefs=false`, a local `refs/replace/` entry changes what `cat-file` and
  `log` return for a commit id.
- `branch <n> refs/remotes/origin/<n>` writes `branch.<n>.*` under `branch.autoSetupMerge`'s
  default. If that write fails on the lock, the ref is created anyway and the command exits 1. The
  factory no longer runs that form: since design D9 of fix-terminal-receipt-convergence
  `TaskBranchAdoption` creates the local branch by one `update-ref --stdin` transaction, which
  writes no configuration. The key stays in the set as a pre-emption, so no future `branch`
  invocation can reintroduce the write.

Verified on git 2.56.0 on 2026-10-20, after the first eight pairs were implemented:

- A hook defined in the clone's local configuration — `hook.<name>.command` with
  `hook.<name>.event=reference-transaction`, a mechanism git added in 2.54 — runs three times in
  one `update-ref` that carries all eight pairs: `core.hooksPath=/dev/null` governs the hook
  directory only. The same hook also runs on `branch`, `add -A` (`post-index-change`) and
  `worktree add`.
- `-c hook.reference-transaction.enabled=false` (the event-level switch, documented in the 2.56
  `git-hook` page: "no hooks fire for that event") stops it; so does the per-name
  `hook.<name>.enabled=false`, which the factory cannot use, since the attacker picks the name.
  The release notes of 2.55 announce only parallel configuration hooks, so whether 2.55 already
  honours the event-level switch is not verified.

## Decisions

**D1 — The owner lives in `:gittransfer`, as `GitInvocation`.** A JDK-only value class holds two
things: the command-scope pairs, and the environment variables every git process has removed.
It also builds the full command line (D2). `GitTransfer` composes it.
*Rationale:* `:gittransfer` is already the JDK-only leaf both sides of the tree reach for git
policy. The module-layering spec forbids `:gittransfer` from acquiring a dependency, so the base
policy cannot sit in a lower leaf that `:gittransfer` would depend on; it has to sit in
`:gittransfer` itself. `:gitobjects` gains one edge, to `:gittransfer`. Extraction then moves
three JDK-only modules instead of two: the D13 argument of `bound-subprocess-commands`, applied
again.
*Alternative rejected:* a new leaf `:gitinvocation` below `:gittransfer`. It names the concept
more precisely, but it breaks the "gittransfer acquires no dependency" property that keeps the
seed helper and the git adapter from pulling each other's edges.
*Alternative rejected:* `GitObjects.open` receives the pairs from its caller. That leaves
`gitobjects` free of the edge, but the value then travels as a `List<String>` any caller can pass
empty. That is the escape hatch `implementation.md` item 3 forbids.

**D2 — The owner builds the whole command line.** The single entry point is
`GitInvocation.commandLine(gitBinary, args)`. It returns the binary, then the policy pairs, then
the caller's arguments. Both launchers pass what it returns straight to `ProcessBuilder`, and
their private `commandLine` builders are deleted. `GitTransfer.argv()` begins with the same
pairs for every construction site — the `fetch`/`harvest`/`clone` factories and `GitPush.closing`
and `GitPush.branchOnly`, whose `start()` opens with the owner's pairs; the compact constructor
asserts this, so a transfer built any other way cannot exist. `commandLine` is idempotent on the
policy: when `args` already opens with the owner's pairs, as every `GitTransfer.argv()` does, it
adds nothing. The runner's string, typed-transfer and ref-transaction entries therefore all reach
one `execute` and the same `commandLine`, the owner has one producer, and a transfer carries the
pairs once. Classification
(`isRepoLevelMutating`, `GitNetworkCommands`) keeps running on the caller's arguments. For
transfers it already skips leading `-c` pairs.
*Rationale:* when the owner produces the command line, a launcher cannot forget the policy. It
would have to stop calling the owner, and that is what the gate in D7 detects.
*Alternative rejected:* expose the pairs as a constant and have each launcher concatenate them.
That is three concatenations to keep in step, which is the hand-synchronization this change
removes.

**D3 — Set contents and values.**

- `core.hooksPath=/dev/null`
- `commit.gpgSign=false`
- `tag.gpgSign=false`
- `core.fsmonitor=false`
- `branch.autoSetupMerge=false`
- `maintenance.auto=false`
- `gc.auto=0`
- `core.useReplaceRefs=false`
- `hook.<event>.enabled=false` for every hook event the githooks(5) page of the floor release
  (2.56, D11) lists, client and server side — `applypatch-msg` through `post-index-change`, and
  `pre-receive`, `update`, `proc-receive`, `post-receive`, `post-update`. `GitInvocation` holds
  the event list as one constant beside the other pairs.

Removed from every environment: `GIT_CONFIG_PARAMETERS` and `GIT_CONFIG_COUNT`.

*Rationale for the event switches:* `core.hooksPath=/dev/null` empties the hook directory but
says nothing about hooks defined in the configuration (Context, verified on 2.56). Only the
event-level switch is under the factory's control: the per-name one needs a name the factory
cannot know. Both stay — the directory key disables the classic hooks, the switches disable the
configured ones. The switches are listed per event, not derived at runtime: the list is read
from the documentation of the floor release, and an event git adds later is a new entry, like
any other key (G3). A configured hook named after an event (`hook.pre-commit.event`) makes git
fail with a fatal error by design; that is a loud refusal of the command, not a hook that runs,
and is accepted.

`GitTransfer.CONFIG_KEYS` drops its own `maintenance.auto`/`gc.auto` (now inherited from the
base), and its `STRIPPED` list drops the two configuration variables for the same reason (FR4:
no key may diverge).

`GitExec` keeps its stronger global/system isolation as its own addition. This is not a sync
pair: nothing else is expected to match it.

*Rationale for `/dev/null`:* no filesystem state is needed. The current empty directory under
`.git` is itself writable by anyone who can write `.git/config`, so a hook dropped into it runs
despite the "hardening". Gitaly and the Jenkins git plugin use the same value.

*Rationale for leaving keys out:*
- `credential.helper`, `core.sshCommand` and `url.*` stay ambient (ADR 0008, NFR-S2).
- `core.pager` and `core.editor` are never consulted without a terminal.
- `diff.external` is not used by any factory `diff` invocation. If one appears later, the new
  key is added to the owner.

*Alternative rejected:* `GIT_CONFIG_GLOBAL=/dev/null` for the runner. Origin needs the
operator's credentials.

**D4 — The factory writes no repository configuration.**

- **Removed:** `FactoryCloneHardening`, `FactoryCloneHardeningException`, and the configuration
  write inside `GitTaskBranches.harden`, which is the escape hatch.
- **Kept, renamed:** `harden` runs two steps today, `refFormat.require(cloneDir)` and then the
  write (`GitTaskBranches.java:80-85`). The first is the reftable-backend precondition of
  `tighten-spec-contract` (NFR-S2, `FactoryCloneRefFormat`), and the call sites rely on its
  position: "a refused clone gets no worktree-registry write" (`TakeFreshClaim`, `GitModeRunner`).
  So the port method `TaskBranchGit.harden` becomes `TaskBranchGit.requireRefFormat(Path)`,
  `GitTaskBranches` keeps only `refFormat.require` in it, the `RemoteOutageSignalingTaskBranchGit`
  decorator passes it through, and each of the seven call sites calls it where it called `harden`.
  The rename makes the compiler visit every call site. `FactoryRegisterOwnerSpec`'s pin of
  `new FactoryCloneRefFormat(` to `GitTaskBranches.java` and `TouchpointFetch.java` is unchanged.
- **Runner refusal:** the runner's string entry refuses `config` unless the arguments carry a read
  form (`--get`, `--get-all`, `--get-regexp`, `--get-urlmatch`, `--list`/`-l`, or the `get`/`list`
  subcommands). It also refuses `remote add|rename|remove|rm|set-head|set-branches|set-url`. The
  new `RepositoryConfigWriteException` names the subcommand and `GitInvocation`, mirroring
  `UnownedTransferException`.
- **`branch` is not refused by flag.** The factory uses only `branch <name> <start>`, and
  `branch.autoSetupMerge=false` makes that form write nothing. The byte-identical
  `.git/config` assertion in the identity spec (D8) catches any other form that writes.

*Note for `add-doctor-command`:* under the policy, `config --get core.hooksPath` reports the
command-scope value `/dev/null`. The doctor check for the stale line (UX2) must read with
`--local`.

*Alternatives rejected, from the steelman of the local fix:*

- **Keep `harden`, serialize it under `CloneMutationLock` and compare before writing.** It is
  about five lines and fixes the red spec today, and that is its whole strength. It breaks on
  this project's constraints:
  - the lock is in-process only, so the operator's git, husky, detached maintenance and a
    second instance still race;
  - compare-before-write has a check-then-act window;
  - a persisted value can be undone after the check, and nothing re-asserts it until the next
    claim.

  What we borrow from it: the single owner. The lock-scope and resource-serializing reasoning of
  `CloneMutationLock` stays exactly as it is.
- **Harden once at startup.** This is weaker than today, because the value is undone for the
  daemon's whole lifetime.
- **Retry on lock error.** It hides the race. `CloneMutationLock` exists precisely instead of a
  retry loop.
- **Rely on `core.configLockTimeout` (git 2.56: retry on `config.lock`) instead of deleting the
  write.** Its only effect would be to retry a write this change deletes; it masks the race
  instead of removing it, and does nothing for the read side. The floor does rise to 2.56.0, but
  for the event-level hook switch (D3, D11), not for this retry, which the factory never needs.

**D5 — The container resume path is pinned, not changed.** The "ref created, exit 1" failure of
`git branch <n> refs/remotes/origin/<n>` already disappeared when `TaskBranchAdoption` (design D9
of fix-terminal-receipt-convergence) replaced that form with one `update-ref --stdin` transaction,
so NFR-R2 is met by landed code. This change adds the pin: a spec on the adversarial posture
(`autoSetupMerge=always` planted) drives `ContainerResumeBranch.ensureLocalBranch` over a
remote-only branch and asserts that the branch exists, the adoption is reported and `.git/config`
is byte-identical. It is expected green before this change's code lands; its value is that a
future return to `git branch` fails it. *Alternative rejected:* a `--no-track` flag at any future
`branch` call site — a second, local statement of a policy the owner already carries
(`branch.autoSetupMerge=false`, D3).

**D6 — Adversarial local-configuration posture in the shared clone fixture.**

- **What `initWorkingRepo` plants,** after its own setup:
  - a hook directory whose `pre-commit`, `post-checkout` and `reference-transaction` hooks
    write a marker file and exit 1;
  - `commit.gpgSign=true` with a `gpg.program` that writes a marker and exits 1;
  - `core.fsmonitor` naming a marker-writing command;
  - `branch.autoSetupMerge=always`.

  The replace-ref vector is planted by the identity spec itself; the fixture cannot know which
  commit to target.
- **Failures are loud.** Because every planted program fails, any factory path that runs without
  the policy turns an existing spec red — the "adversarial by default" rule of `testing.md`,
  extended from refs and global config to local config.
- **Fixture writes.** Configuration writes from test code no longer pass the runner, which now
  refuses them: the fixture's own (`remote add`, the plants themselves) and the roughly 70 calls in
  about 44 test files that run `config <key> <value>`, `remote add` or `remote set-url` through
  `GitProcessRunner` today — `LocalBoxEnvironment` (`config gc.auto 0`), `KillPointWorlds`,
  `PushBlackout`, `E2eGitTree`, the Gitea end-to-end fixtures and some 25 specs of `adapters/git`.
  Every one goes through a test-only `FixtureGitConfig` writer in `:test-fixtures` (task 4.1 carries
  the grep). The gate in D7 scans production sources only.
- **Opt-out by name.** A spec that needs a plain clone asks for `initPlainWorkingRepo`.
- **Plumbing path.** `LocalGitRepoFixture` (`:gitobjects`) gets the hook plant for the
  `update-ref` path.

*Alternative rejected:* a single dedicated adversarial spec only. It proves the paths it lists
and none added later — the lesson recorded for `BareGitRepoFixture`'s ref posture.

**D7 — Enforcement: `GitLaunchBoundarySpec` in `:bootstrap`** (ArchUnit over all production
classes).

1. The set of classes depending on `java.lang.ProcessBuilder` equals an allowlist, each entry
   with its reason:
   - `GitProcessRunner` (`:adapters:git`), `GitExec` (`:gitobjects`): the two git launchers, the
     only builders of a git command line;
   - `DockerCli` (`:sandbox:docker`): the docker client;
   - `HostTaskExecutionEnvironment` (`:sandbox:docker`): gnome-product processes, not factory git;
   - `StderrPolicy` (`:sandbox:core`): applies a stderr redirect to a builder an adapter built
     (design D18 of fix-terminal-receipt-convergence), constructs none;
   - `CaptureRunner`, `ProcessLaunch`, `GroupedLaunch` (`:subprocess`): the launch owner and its
     helpers — they take a builder a caller constructed, prefix the launcher, set its stdin and
     start it (design D16, D19, D20 of fix-terminal-receipt-convergence); none names a binary.
     `StdinSource` is not an entry: it names what the child reads and holds no `ProcessBuilder`
     reference (the name appears only in its javadoc); `ProcessLaunch` applies it to the builder,
     so an entry for it would fail the equality as stale.

   Asserting equality, rather than a subset, makes a new spawn site fail until it is classified.
   The gate asks "who builds a command line" and nothing else: who may call `start()` is
   `LaunchOwnerGateSpec`'s question (FR18 of fix-terminal-receipt-convergence, NG14 of the same:
   the launch owner alone), and which packages may touch `ProcessBuilder` at all is
   `ProcessSpawnBoundarySpec`'s. The three gates overlap on no assertion.
2. Both git launchers call `GitInvocation.commandLine`.
3. Seeded scenarios prove the detector fires; `GitTransferBoundarySpec` is the model.

The seed helper's shell script is already covered: `GitTransferBoundarySpec` forbids transfer
literals, and its only transfer is the owner's `GitTransfer.clone`, which carries the pairs
by D2's constructor invariant; the box-side `config`, `remote remove` and `reset` lines are the
box's git (NG6, D10 row 2).

**D8 — Identity and concurrency specs.**

- **Identity spec (`GitInvocationIdentitySpec`, `:adapters:git`, real git, adversarial
  posture).** It drives the git operations of the factory's own flows:
  - commit in a worktree (`GitAttemptPersistence`);
  - lifecycle commit (`GitTaskRepository`);
  - `update-ref --stdin` through `RefTransaction` (`FactoryRegister`, the register's writer);
  - a bare-object commit (`gitobjects` `CommitBuilder`);
  - `worktree add`;
  - branch creation from a tracking ref (`ContainerResumeBranch` over `TaskBranchAdoption`);
  - a push of the task branch to a bare origin (`GitPush.branchOnly`), with an executable
    `pre-push` hook planted;
  - a state read under a planted replace ref.

  It asserts no marker exists and `.git/config` is byte-identical (FR8, M4).
- **Concurrency spec (`ConcurrentCloneConfigSpec`, `:adapters:git`).** Eight threads start
  together on a latch and each runs the fresh-claim git sequence (branch create, worktree add,
  commit) on one clone. It asserts that no result carries a configuration-lock error and the
  config is unchanged (NFR-R1).
- **The temporary `@Retry`** on `TakeSlotRunnerContainerConcurrencySpec` is removed. The spec
  must pass 20 consecutive runs (M3).

**D9 — Sync surfaces.**

- **Touched pairs.** The change touches four declared pairs, and the mirrored edit is in scope
  for each:
  - `TakeFreshClaim` ↔ `TakeContainerFreshClaim` — both markers say "harden, resolve+refresh…";
  - `TakeResumeBootstrap` ↔ `TakeContainerResumeBootstrap` — "both must harden the clone";
  - `GitModeRunner` ↔ `ContainerGitModeRunner` — "harden the clone's branches";
  - `ContainerResumeRunner` ↔ `GitResumeRunner` — no marker wording mentions harden, but
    `ContainerResumeRunner` calls it directly while `GitResumeRunner` reaches it through
    `TakeResumeBootstrap`.

  Both ends of every pair keep the call under its new name, `requireRefFormat` (D4), and the
  three markers that name `harden` are reworded to that step in the same task, so no end
  describes a step the other no longer performs.
- **Collapsed rule.** The change also collapses a rule that exists three times — the
  configuration policy of `GitProcessRunner` (none), `GitExec` (global/system isolation) and
  `GitTransfer` (transfer set). By the rule of three in `manual-sync-pairs.md` this is a
  **shared abstraction**, `GitInvocation` (D1, D2), not a declared pair.
- **Rejected:** declaring `GitExec` ↔ `GitProcessRunner` as a pair. A third implementation
  already exists, so the rule forbids it.

No row of the `manual-sync-pairs.md` registry is touched.

**D10 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|---|---|---|---|---|
| `GitInvocation` (`:gittransfer`) | The full git command line (`List<String>`, produced only by `GitInvocation.commandLine(gitBinary, args)`) and the environment-removal set. The list is a primitive because `ProcessBuilder` consumes exactly that; the owner is enforced by being the only producer (gate item 2), not by the type. | `adapters/git/.../GitProcessRunner.java` (`execute`); `gitobjects/.../GitExec.java` (`run`); `gittransfer/.../GitTransfer.java` (`fetch`, `harvest`, `clone`) and `gittransfer/.../GitPush.java` (`closing`, `branchOnly`, through `start()`), and through them the runner's typed entry and `sandbox/docker/.../DockerSeedCloneCommand.java` | `GitProcessRunner.commandLine` and `GitExec.commandLine` (private builders) deleted, `GitExec`'s own `-c core.hooksPath=/dev/null` literal with the latter; `GitTransfer.CONFIG_KEYS` loses `maintenance.auto`/`gc.auto`; `GitTransfer.STRIPPED` loses the two configuration variables. Old-way grep: `commandLine(` defined outside `GitInvocation`, and `"gc.auto=0"`/`"maintenance.auto=false"`/`"core.hooksPath=` literals outside it — all expected empty. `core.hooksPath=` inside the in-box scripts of `EnvironmentAttemptPersistence`, `EnvironmentRoundSnapshot` and `EnvironmentSalvage` is the box's git (NG6), not a producer of a factory command line | `GitLaunchBoundarySpec` (D7); `GitTransfer` compact-constructor invariant (D2) |
| "No repository-configuration write" (runner refusal plus the owner's `branch.autoSetupMerge=false`) | none — the absence of a write, checked on the medium | Previous writers: `adapters/git/.../FactoryCloneHardening.java` (removed); `adapters/git/.../GitTaskBranches.java` `harden` (write removed; renamed `requireRefFormat`, keeping only the ref-backend check); `application/.../port/git/TaskBranchGit.java` `harden` (renamed `requireRefFormat`); the decorator `application/.../app/serve/RemoteOutageSignalingTaskBranchGit.java` `harden` (renamed, pass-through); call sites `TakeFreshClaim`, `TakeContainerFreshClaim`, `TakeResumeBootstrap`, `TakeContainerResumeBootstrap`, `GitModeRunner`, `ContainerGitModeRunner`, `ContainerResumeRunner` (`application/.../app/`, one `git.branches().harden(cloneDir)` each, each now the ref-backend check at the same position); `adapters/git/.../TaskBranchAdoption.java` (kept: creates the local branch by `update-ref --stdin`, which writes no configuration) | `git config core.hooksPath` deleted; the port method keeps only the ref-backend precondition, under its own name (D4). Old-way grep: `harden(` and `"config"` in production Java — expected empty apart from the refusal classifier. Exemption: the `sandbox/docker/.../DockerSeedCloneCommand.java` script writes five configuration lines in the **box** — `--global --add safe.directory` twice, `user.name`, `user.email`, `gc.auto 0` — into the box's own global file and its fresh seed clone, outside the operator clone and the factory trust domain (NG6) | Runner refusal (FR5); identity spec `GitInvocationIdentitySpec` asserting a byte-identical `.git/config` on the real medium (D8) |
| `GitVersion` (`:gittransfer`) | The two git floors (`GitVersion`): the factory's, 2.56.0, with its reason, and the box's, 2.51.0, with its own (D11) | `adapters/git/.../GitVersionCheck.java` (startup, the factory's floor); `sandbox/docker/.../BoxGitFloor.java` (seed clone, the box's floor) | `BoxGitFloor` stops reading the factory's floor. Old-way grep: a version literal `2, 51` or `2, 56` (or `"2.51`/`"2.56`) in production Java outside `GitVersion` — expected empty | `GitVersionFloorSpec` (`:bootstrap`) for the startup refusal; `BoxGitFloorSpec` for the box's floor; the old-way grep in the task report |

**D11 — Two git floors until the base image catches up.** The factory's git floor rises from
2.51.0 to 2.56.0 (FR10): the event-level hook switch of D3 is documented in 2.56, it was
verified there, and a policy that names a key the installed git ignores neutralizes nothing.
The box's floor (`BoxGitFloor`, the seed clone's check) stays at 2.51.0 and becomes its own
constant in `GitVersion`, keeping today's value and today's reason unchanged, until
`add-sandbox-base-image` raises it to 2.56.0 and joins the two again. The box's git is outside the factory's trust domain (NG6): no factory policy runs
there, so the box needs no event switch.

- *Alternative rejected:* **one floor, raised now.** Every image the box checks would be
  refused: the five test images on `alpine:3` (3.24.1) install git 2.54.0, `JvmProxyEgressImage`
  and the reference recipe on `eclipse-temurin:21-jdk-resolute` get 2.53.0 from the Ubuntu 26.04
  archive (measured 2026-10-20); only `alpine:edge` has 2.56.0. Moving the images is the base
  image change's subject, and doing it here would grow this change past its initiative.
- *Alternative rejected:* **keep 2.51.0 and refuse only 2.54.x.** A special case in the version
  check, and the event switch is not verified on 2.55; a key that might be ignored there is not
  a policy.
- *Alternative rejected:* **2.55.0.** Its release notes announce parallel configuration hooks but
  not the event switch; the floor follows what was verified.
- *Cost:* an operator on a distribution git (Ubuntu 26.04's archive ships 2.53, the `git-core`
  PPA 2.55) needs Homebrew's current git or a source build (UX3). CI and the Linux spec runner
  get theirs from one committed script, `scripts/install-git.sh`, which downloads
  `git-2.56.0.tar.xz` from kernel.org, verifies its pinned SHA-256, builds and installs it; the
  `ci.yml` `build` job and `pitest-nightly.yml` run it (the build cached with `actions/cache`),
  and `scripts/linux-runner/Dockerfile` runs it in one layer (task 8.4). `ubuntu-latest` ships
  2.55.0 (run 39484184528), and the PPA still published only `1:2.55.0` for noble and resolute
  on 2026-10-21 (Launchpad API). The next floor raise bumps the version and checksum in that
  one script (`add-sandbox-base-image`); returning to a package source once one publishes the
  floor is optional.
- *Alternative rejected:* **the `git-core` PPA.** It publishes 2.55.0 only; CI would stay red
  until it caught up, a stall with no owner.
- *Alternative rejected:* **Homebrew on the CI runners and in the runner image.** Heavy inside
  a container, and the version follows Homebrew's latest bottle rather than a pin.
- *Alternative rejected:* **Homebrew on CI, a source build in the runner image.** Two sources
  for one platform: the runner exists to reproduce CI, and two sources drift.

## Risks / Trade-offs

- **Test migration.** About 31 test files run raw git. Those that commit in a fixture clone
  outside the fixture's helpers will now hit a failing planted hook → the task's sweep routes
  them through the fixture helpers or `initPlainWorkingRepo`. Each plain-posture use is named at
  its call site.
- **Operators relying on the persisted `core.hooksPath`.** Their own git has been hook-less
  since they first ran the factory; after upgrading it runs their hooks again. → Release note
  plus the doctor check (UX2), which prints the exact `git config --local --unset` line.
- **The access log sees the pairs.** `add-subprocess-access-log` records argv, so the policy's
  pairs — eight keys plus one switch per hook event, 36 in all on 2.56 (eight plus 28 events) — appear on every record
  → that change is sequenced after this one and may render the policy as one token. Its concern,
  noted in its proposal. The argv itself stays far below any `ARG_MAX`.
- **A git newer than the floor adds a hook event.** The switch list is the floor release's; an
  event added later runs its configured hooks until it is listed → the list is reread whenever
  the floor moves, and the identity spec plants a configured hook on the events the factory's
  commands fire (task 8.1).
- **`/dev/null` is POSIX.** The factory already requires a POSIX host (Docker CLI, `sh`
  scripts). A Windows port would change the value in one place.
- **Keys the policy cannot pre-empt** (attribute-selected filter drivers, `info/attributes`,
  alternates) remain open for the host-mode shared `.git` → NG1; this is the ADR 0012 boundary
  statement, handed to `add-sandbox-hardening`.

## Migration Plan

No data migration. Upgrading changes nothing on disk. Rolling back restores the per-claim
write, and that is harmless. The stale `core.hooksPath` line in existing clones is reported by
`doctor` (handed to `add-doctor-command` via `/opsx:update`), never removed automatically.
