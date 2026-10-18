# Design: own-git-invocation-policy

## Context

Driven by FR1–FR8, NFR-R1, NFR-R2, NFR-S1 and NFR-S2 of the proposal. The principle that
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
asserts this, so a transfer built any other way cannot exist. The runner's typed transfer entry
therefore does not prefix the pairs a second time. Classification
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

Removed from every environment: `GIT_CONFIG_PARAMETERS` and `GIT_CONFIG_COUNT`.

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

- **Removed:** `FactoryCloneHardening`, `FactoryCloneHardeningException`, `GitTaskBranches.harden`
  and the port method `TaskBranchGit.harden`, which is the escape hatch. The compiler then forces
  the seven call sites out.
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
- **Raise the git floor to 2.56.0 for `core.configLockTimeout`** (default 1000 ms of retry on
  `config.lock`). Its only effect would be to retry a write this change deletes. It masks the
  race instead of removing it, it does nothing for the read side (hooks, signing, replace refs),
  and it forces every operator onto a very recent git: distribution LTS releases ship older
  ones. The floor stays where `tighten-spec-contract` put it (2.51.0, design D13 as amended
  2026-10-13).

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
   - `CaptureRunner`, `ProcessLaunch`, `GroupedLaunch`, `StdinSource` (`:subprocess`): the launch
     owner and its helpers — they take a builder a caller constructed, prefix the launcher, set its
     stdin and start it (design D16, D19, D20 of fix-terminal-receipt-convergence); none names a
     binary.

   Asserting equality, rather than a subset, makes a new spawn site fail until it is classified.
   The gate asks "who builds a command line" and nothing else: who may call `start()` is
   `LaunchOwnerGateSpec`'s question (FR18 of fix-terminal-receipt-convergence, NG14 of the same:
   the launch owner alone), and which packages may touch `ProcessBuilder` at all is
   `ProcessSpawnBoundarySpec`'s. The three gates overlap on no assertion.
2. Both git launchers call `GitInvocation.commandLine`.
3. Seeded scenarios prove the detector fires; `GitTransferBoundarySpec` is the model.

The seed helper's shell script is already covered: `GitTransferBoundarySpec` forbids transfer
literals, and its only git command is the owner's `GitTransfer.clone`, which carries the pairs
by D2's constructor invariant.

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

  Both ends of every pair lose the call, and the three markers that name it are reworded in the
  same task, so no end describes a step the other no longer performs.
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
| `GitInvocation` (`:gittransfer`) | The full git command line (`List<String>`, produced only by `GitInvocation.commandLine(gitBinary, args)`) and the environment-removal set. The list is a primitive because `ProcessBuilder` consumes exactly that; the owner is enforced by being the only producer (gate item 2), not by the type. | `adapters/git/.../GitProcessRunner.java` (`execute`); `gitobjects/.../GitExec.java` (`run`); `gittransfer/.../GitTransfer.java` (`fetch`, `harvest`, `clone`) and `gittransfer/.../GitPush.java` (`closing`, `branchOnly`, through `start()`), and through them the runner's typed entry and `sandbox/docker/.../DockerSeedCloneCommand.java` | `GitProcessRunner.commandLine` and `GitExec.commandLine` (private builders) deleted, `GitExec`'s own `-c core.hooksPath=/dev/null` literal with the latter; `GitTransfer.CONFIG_KEYS` loses `maintenance.auto`/`gc.auto`; `GitTransfer.STRIPPED` loses the two configuration variables. Old-way grep: `commandLine(` defined outside `GitInvocation`, and `"gc.auto=0"`/`"maintenance.auto=false"`/`"core.hooksPath=` literals outside it — all expected empty | `GitLaunchBoundarySpec` (D7); `GitTransfer` compact-constructor invariant (D2) |
| "No repository-configuration write" (runner refusal plus the owner's `branch.autoSetupMerge=false`) | none — the absence of a write, checked on the medium | Previous writers: `adapters/git/.../FactoryCloneHardening.java` (removed); `adapters/git/.../GitTaskBranches.java` `harden` (removed); `application/.../port/git/TaskBranchGit.java` `harden` (removed); the decorator `application/.../app/serve/RemoteOutageSignalingTaskBranchGit.java` `harden` (removed); call sites `TakeFreshClaim`, `TakeContainerFreshClaim`, `TakeResumeBootstrap`, `TakeContainerResumeBootstrap`, `GitModeRunner`, `ContainerGitModeRunner`, `ContainerResumeRunner` (`application/.../app/`, one `git.branches().harden(cloneDir)` each, all removed); `adapters/git/.../TaskBranchAdoption.java` (kept: creates the local branch by `update-ref --stdin`, which writes no configuration) | `git config core.hooksPath` and the port method, both deleted. Old-way grep: `harden(` and `"config"` in production Java — expected empty apart from the refusal classifier. Exemption: the `sandbox/docker/.../DockerSeedCloneCommand.java` script writes five configuration lines in the **box** — `--global --add safe.directory` twice, `user.name`, `user.email`, `gc.auto 0` — into the box's own global file and its fresh seed clone, outside the operator clone and the factory trust domain (NG6) | Runner refusal (FR5); identity spec `GitInvocationIdentitySpec` asserting a byte-identical `.git/config` on the real medium (D8) |

## Risks / Trade-offs

- **Test migration.** About 31 test files run raw git. Those that commit in a fixture clone
  outside the fixture's helpers will now hit a failing planted hook → the task's sweep routes
  them through the fixture helpers or `initPlainWorkingRepo`. Each plain-posture use is named at
  its call site.
- **Operators relying on the persisted `core.hooksPath`.** Their own git has been hook-less
  since they first ran the factory; after upgrading it runs their hooks again. → Release note
  plus the doctor check (UX2), which prints the exact `git config --local --unset` line.
- **The access log sees the pairs.** `add-subprocess-access-log` records argv, so eight pairs
  appear on every record → that change is sequenced after this one and may render the policy as
  one token. Its concern, noted in its proposal.
- **`/dev/null` is POSIX.** The factory already requires a POSIX host (Docker CLI, `sh`
  scripts). A Windows port would change the value in one place.
- **Keys the policy cannot pre-empt** (attribute-selected filter drivers, `info/attributes`,
  alternates) remain open for the host-mode shared `.git` → NG1; this is the ADR 0012 boundary
  statement, handed to `add-sandbox-hardening`.

## Migration Plan

No data migration. Upgrading changes nothing on disk. Rolling back restores the per-claim
write, and that is harmless. The stale `core.hooksPath` line in existing clones is reported by
`doctor` (handed to `add-doctor-command` via `/opsx:update`), never removed automatically.
