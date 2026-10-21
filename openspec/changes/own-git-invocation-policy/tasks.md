# Tasks: own-git-invocation-policy

Sequenced before `add-subprocess-access-log` (same `GitProcessRunner.execute`; proposal,
Impact). Build order: groups 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8, where 2 depends on 1 and 5 needs 2–4
green. Every group lands its own specs and docs. Group 8 was added on 2026-10-21, after groups
1–7 were implemented (design D3, D11): its tasks change code those groups landed.

Group 14 of `supervise-daemon-loops-and-embed-dashboard` has landed (archived 2026-10-11): every
holder of the git runner is typed as the package-private `GitRunner` role (`adapters/git`), and
`GitProcessRunner` is its one production implementation. Group 2 rewrites `GitProcessRunner.execute` only; no holder is
retyped to `GitProcessRunner`, or `GitRunnerBoundarySpec` in `:bootstrap` fails. Specs above the
adapter drive `ScriptedGit`, the in-process fake, not a stand-in binary.

## 1. The owner in `:gittransfer`

- [x] 1.1 TDD (red first): `GitInvocationSpec` pins the owner (FR1, FR2, FR3, design D2, D3):
      - `commandLine(gitBinary, args)` returns the binary, then exactly the eight `-c` pairs of
        D3 in declared order, then the caller's arguments unchanged;
      - an argv that already opens with the pairs is returned with them once (D2, idempotent on
        the policy);
      - the environment-removal set is exactly `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`;
      - no pair names `credential.`, `core.sshCommand` or `url.` (NFR-S2);
      - a blank binary is refused.

      Verify: red against a stub, green after 1.2.
- [x] 1.2 Implement `GitInvocation` (JDK-only; javadoc `Implements FR1, FR2, FR3 of
      own-git-invocation-policy`). Verify: 1.1 green; `:gittransfer:check` green (PIT 100%).
- [x] 1.3 TDD then refactor `GitTransfer` and `GitPush` to compose the owner (FR4, design D2, D3):
      - `argv()` begins with the owner's pairs for every construction site: the
        `fetch`/`harvest`/`clone` factories and `GitPush.closing`/`branchOnly` (their `start()`
        opens with the pairs);
      - the compact constructor refuses an argv that does not begin with them;
      - `CONFIG_KEYS` drops `maintenance.auto`/`gc.auto`;
      - `STRIPPED` drops the two configuration variables and applies the owner's set instead.

      A data-driven spec over every source kind and both pushes asserts that every invocation pair
      is present before the subcommand and that no key has two values. Verify: `GitTransferSpec`,
      `GitPushSpec`, the new spec and `SeedTransferFixture`-based specs are green;
      `ScriptedGitSpec` ("the transfer owner's value is matched and logged by its argv", which
      hand-builds a `GitTransfer` the new invariant refuses) is rebuilt through the owner's
      `fetch` factory and green;
      `DockerSeedCloneCommandSpec`'s rendered script expectation is updated; `:gittransfer:check`
      and `:sandbox:docker:check` are green.

## 2. Both launchers take the owner's command line

- [x] 2.1 TDD then wire `GitProcessRunner` (FR1, FR3, design D2):
      - `execute` builds its `ProcessBuilder` from `GitInvocation.commandLine` and removes the
        owner's environment set;
      - the private `commandLine` is deleted;
      - the typed transfer entry runs the value's argv through the same `commandLine`, which
        adds no second copy of the pairs (D2);
      - classification keeps reading the caller's arguments.

      Spec: a commit with an inherited `GIT_CONFIG_COUNT` pair naming a hook directory runs no
      hook (FR3 scenario). Verify: `:adapters:git:check` green.
- [x] 2.2 TDD then wire `GitExec` (FR1, FR3, design D1, D2):
      - `:gitobjects` gains `implementation project(':gittransfer')` and its layering allowlist
        entry with a comment naming the edge; `:application` gains the reach entry `:gittransfer`
        with a comment naming `:gitobjects` as the edge it arrives through (`:adapters`,
        `:adapters:agent` and `:bootstrap` already list it); `gitobjects/build.gradle`'s header
        comment ("never a factory module") names the second JDK-only leaf;
        `GitObjectsBoundarySpec` needs no edit (its forbidden list does not name the leaf);
      - `run` builds its command line from the owner and removes the owner's environment set;
      - `GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM` isolation is kept;
      - the private `commandLine` is deleted, its `-c core.hooksPath=/dev/null` literal with it.

      Red first on a vector `GitExec` does not close today: `LocalGitRepoFixture` plants a
      `refs/replace/` entry substituting a commit, and a `GitObjects` read spec asserts the original
      object comes back (hooks are already neutralized by the literal being deleted, so a hook
      marker cannot go red). `LocalGitRepoFixture` also plants a `reference-transaction` hook that
      writes a marker and exits 1 as the regression pin for the moved literal. Verify:
      `:gitobjects:check` green, and the layering gate is green in every module.
- [x] 2.3 Old-way sweep for row 1 of the D10 table. Run
      `grep -rn "commandLine(" --include=*.java */src/main */*/src/main` (expected: definitions
      only in `GitInvocation`) and
      `grep -rn '"gc.auto=0"\|"maintenance.auto=false"' --include=*.java . | grep /src/main/`
      (expected: only `GitInvocation`) and
      `grep -rn '"core.hooksPath=' --include=*.java . | grep /src/main/` (expected: empty once
      task 2.2 has run; the unquoted in-box `core.hooksPath=` of the three `Environment*`
      scripts is the box's git, NG6). Record the three commands and their hits in the task report.

## 3. No repository-configuration writes

- [x] 3.1 TDD (red first) then implement the runner refusal (FR5, NFR-O1, design D4). The new
      `RepositoryConfigWriteException` (javadoc traceability) is thrown before launch for:
      - `config` with no read form;
      - `remote add|rename|remove|rm|set-head|set-branches|set-url`.

      Allowed: `config --get|--get-all|--get-regexp|--get-urlmatch|--list|-l`, `config get|list`,
      `remote get-url`. The data-driven spec covers every refused and every allowed form, with
      and without leading `-c` pairs. Verify: `:adapters:git:check` green.
- [x] 3.2 Remove the hardening write, keep the ref-backend check (FR6, design D4, D10 row 2):
      - delete `FactoryCloneHardening`, `FactoryCloneHardeningException` and their spec
        `FactoryCloneHardeningSpec`;
      - rename the port method `TaskBranchGit.harden(Path)` to `requireRefFormat(Path)`;
        `GitTaskBranches.requireRefFormat` keeps only `refFormat.require(cloneDir)`, and
        `RemoteOutageSignalingTaskBranchGit` (the port decorator, `application/.../app/serve/`)
        passes it through;
      - the compiler then forces the rename of the one `git.branches().harden(cloneDir)` call in
        each of `TakeFreshClaim`, `TakeContainerFreshClaim`, `TakeResumeBootstrap`,
        `TakeContainerResumeBootstrap`, `GitModeRunner`, `ContainerGitModeRunner` and
        `ContainerResumeRunner` (`application/.../app/`); each call stays at its position, ahead of
        the first write, and its comment names the backend check, not hooks;
      - update the fakes and specs that stub or assert `harden`: `RunChainFakes`,
        `TakeFreshClaimSpec`, `TakeContainerFreshClaimSpec`, `TakeClaimAndWorkSpec`,
        `GitModeRunnerFreshRunSpec`, `ContainerGitModeRunnerSpec`, `GitTaskBranchesSpec`,
        `TakeContainerResumeRoutingSpec`, `ContainerResumeRoutingSpec`, `AnchorLogSpec`,
        `RemoteOutageSignalingTaskBranchGitSpec` (the decorator's pass-through, under the new
        name) and `RunParkKillPointContainerE2ESpec` (its resume-start reconciliation helper);
      - rewrite feature F122 of `FactoryCloneRefFormatSpec` ("hardening a reftable clone still
        points its hooks at the empty directory") to the ref-backend check only: a reftable clone
        passes `requireRefFormat` and no `core.hooksPath` is written;
      - drop the deleted classes' rows from `UntrustedTextGateSpec` (the
        `FactoryCloneHardening` entry) and `TypedExceptionMessageSpec` (the
        `FactoryCloneHardeningException` case and its import);
      - rewrite `RunnerStartHardeningSpec` so each of its run paths asserts that the backend
        check still runs before the first write and that no configuration is written;
        `FactoryRegisterOwnerSpec` stays green unedited.

      Verify: root `./gradlew check` green.
- [x] 3.3 Reword the sync markers (design D9). Both ends of
      `TakeFreshClaim`↔`TakeContainerFreshClaim`,
      `TakeResumeBootstrap`↔`TakeContainerResumeBootstrap` and
      `GitModeRunner`↔`ContainerGitModeRunner` replace "harden" in the recipe they state by the
      ref-backend check (`requireRefFormat`), in the same wording at both ends. Confirm that `ContainerResumeRunner`↔`GitResumeRunner` needs no
      wording change. Verify: `grep -rn "Kept in sync with" --include=*.java */src/main | grep -i
      harden` is empty.
- [x] 3.4 Old-way sweep for row 2 of the D10 table. Run
      `grep -rn 'harden(\|"config"' --include=*.java */src/main */*/src/main` (expected: only the
      refusal classifier) and `grep -rn "gnomish-empty-hooks\|FactoryCloneHardening" .
      --include=*.java --include=*.groovy` (expected: empty). Confirm the one exemption,
      `DockerSeedCloneCommand`'s in-box `git config gc.auto 0` (NG6), is unchanged. Record the
      commands and hits in the task report.

## 4. Adversarial local-configuration posture

- [x] 4.1 Add `FixtureGitConfig` to `:test-fixtures`, a test-only raw writer for fixture
      configuration setup (design D6). Move every test-side configuration write onto it, because
      the runner now refuses them (FR5): `BareGitRepoFixture.addRemote`, `LocalBoxEnvironment`'s
      `config gc.auto 0`, and every hit of
      `grep -rn "'config', '[a-zA-Z-]\|'remote', 'add'\|'remote', 'set-url'" --include='*.groovy'`
      over the test trees and `test-fixtures/src/main` that runs through `GitProcessRunner`
      (about 70 calls in about 44 files on 2026-10-18: `KillPointWorlds`, `PushBlackout`,
      `E2eGitTree`, the Gitea fixtures, some 25 specs of `adapters/git`); read forms
      (`--get`, `--list`) stay. Record the grep and its hit count in the task report. Verify:
      `:test-fixtures` builds, the existing `addOrigin` specs are green, and the grep shows no
      write through the runner.
- [x] 4.2 Plant the posture in `BareGitRepoFixture.initWorkingRepo` (FR8, design D6):
      - a hook directory with marker-writing `pre-commit`, `post-checkout` and
        `reference-transaction` hooks that exit 1;
      - `commit.gpgSign=true` with a marker-writing `gpg.program` that exits 1;
      - a marker-writing `core.fsmonitor`;
      - `branch.autoSetupMerge=always`.

      Also add `initPlainWorkingRepo` for specs that need a plain clone by name, and an
      `assertNothingPlantedRan(repo)` helper. Update `.claude/rules/testing.md` ("Git fixtures
      are adversarial by default") with the local-config posture. Verify: a fixture spec asserts
      the posture is in force and that the plain variant carries none of it.
- [x] 4.3 Sweep the real-git test suites that now fail on the posture: route them through the
      fixture helpers, or switch them to `initPlainWorkingRepo` with a comment giving the
      reason. Starting set: the files found by `grep -rln "ProcessBuilder\|\.execute()"` over
      the test sources that also run git. Verify: root `./gradlew check` green. List every
      `initPlainWorkingRepo` use and its reason in the task report.

## 5. Identity, concurrency and container-resume specs

- [x] 5.1 `GitInvocationIdentitySpec` (`:adapters:git`, real git, adversarial posture; FR8,
      M4, NFR-S1, design D8). It drives:
      - the worktree attempt commit;
      - the lifecycle commit;
      - `update-ref --stdin` through `RefTransaction` (`FactoryRegister`);
      - a `gitobjects` commit;
      - `worktree add`;
      - branch creation from a tracking ref (`ContainerResumeBranch` over `TaskBranchAdoption`);
      - a push of the task branch to a bare origin (`GitPush.branchOnly`) with an executable
        `pre-push` hook planted;
      - a state-file read with a planted `refs/replace/` substitution.

      It asserts that no marker exists, that the read returns the original content, and that
      `.git/config` is byte-identical. Verify: green, and red with the owner's pairs emptied
      (record the red run in the task report).
- [x] 5.2 Container-resume pin (NFR-R2, design D5). With `branch.autoSetupMerge=always` planted,
      `ContainerResumeBranch.ensureLocalBranch` on a remote-only branch reports the adoption, the
      local ref exists, and `.git/config` is byte-identical. A pin, not a red-first test: it is
      expected green before this change's code lands (`TaskBranchAdoption` already creates the
      branch by `update-ref --stdin`). Verify: green; record that it was green before task 2.1.
- [x] 5.3 `ConcurrentCloneConfigSpec` (`:adapters:git`; NFR-R1, lock-scope rule "Specs"). Eight
      virtual threads released by one latch each run the fresh-claim git sequence (branch create,
      worktree add, commit) on one clone. It asserts that no result carries `could not lock
      config file` and that `.git/config` is byte-identical. Verify: green over 20 local runs.
- [x] 5.4 Remove the temporary `@Retry(count = 2, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)`,
      its `TEMPORARY` comment and the `spock.lang.Retry` import from
      `TakeSlotRunnerContainerConcurrencySpec` "two slots run containers concurrently" (added on
      2026-09-30 to mask the `.git/config` lock race). Keep the per-attempt setup only if it
      still earns its place; otherwise restore the field initializers. Verify: the spec passes
      20 consecutive runs (`--rerun-tasks`) with Docker available (M3). Record the run count
      in the task report.

## 6. Enforcement gate

- [x] 6.1 `GitLaunchBoundarySpec` in `:bootstrap` (FR7, design D7), an ArchUnit spec over all
      production classes:
      - the set of classes depending on `java.lang.ProcessBuilder` equals the allowlist of D7 —
        `GitProcessRunner`, `GitExec`, `DockerCli`, `HostTaskExecutionEnvironment`, `StderrPolicy`,
        `CaptureRunner`, `ProcessLaunch`, `GroupedLaunch` — each entry carrying its
        reason, and the spec's javadoc names what `LaunchOwnerGateSpec` and
        `ProcessSpawnBoundarySpec` own instead; the spec carries no note of a deviation from D7
        (`StdinSource` is not an entry, design D7);
      - `GitProcessRunner` and `GitExec` both call `GitInvocation.commandLine`;
      - seeded scenarios show the detector fails on a synthetic extra spawn site and on a
        launcher that skips the owner.

      Verify: `:bootstrap:test` green; each seeded scenario is red with its seed.

## 7. Docs and hand-offs

- [x] 7.1 Write `docs/adr/0012-git-invocation-policy.md` (0011 is taken by
      `0011-operator-configuration-levels.md`; 0012 is the free number). Content:
      - the clone configuration is untrusted input, not storage;
      - one command-scope policy for every factory git process;
      - the no-write rule;
      - what stays ambient (ADR 0008);
      - the boundary, NG1: attribute-selected filters and alternates in a shared `.git`, with
        git's SECURITY guidance.

      Reference it from `design.md` and ADR 0008. Verify: file present; links resolve.
- [x] 7.2 Update `docs/sandbox-threat-registry.md`:
      - row 38: "core.hooksPath points at an empty location in all factory copies" → "every
        factory git invocation neutralizes hooks by command-scope configuration";
      - new row 50 (49 is `tighten-spec-contract`'s): program execution and read substitution
        through the clone's local configuration (`gpg.program` with signing, `core.fsmonitor`,
        `pre-push`, `refs/replace/`), mitigated by the invocation policy, status
        `shipped (own-git-invocation-policy, ADR 0012)`.

      Update `docs/glossary.md` with the entry **git invocation policy**, and a *Not:* note
      that it is not the transfer set. Verify: both files updated, glossary term used verbatim
      in the ADR.
- [x] 7.3 Hand-offs:
      - add one sentence to `add-subprocess-access-log/proposal.md` noting that git argv records
        now carry the eight policy pairs (that change may render them as one token); it carries no
        `module-layering` delta any more, so nothing of this change's layering text needs keeping
        there;
      - renumber `fix-docker-exec-env-argv`'s planned `docs/adr/0011-secret-channels-to-child-processes.md`
        to 0017 via `/opsx:update` on that change (0011 is taken, 0012 is this change's);
      - record the UX2 doctor check for `add-doctor-command` (read with `--local`; print the
        `git config --local --unset core.hooksPath` line only when the value ends in
        `/gnomish-empty-hooks`) via `/opsx:update` on that change;
      - add a release-note line for UX2.

      Verify: all three edits present.
- [x] 7.4 Integration check: root `./gradlew check` green; the `openspec validate
      own-git-invocation-policy` result is clean; an FR/NFR/UX grep shows every proposal ID
      referenced by at least one spec or code unit (`traceability.md`).

## 8. Hooks defined in the configuration, and the git floor

- [x] 8.1 TDD (red first) for configured hooks (FR9, design D3; scenario "A hook defined in the
      configuration does not run"):
      - `GitInvocationIdentitySpec` gains a feature that defines one hook in the clone's local
        configuration by `hook.<name>.command` (a `StandIn.link` to the committed preset
        `hook-record-refuse`) with `hook.<name>.event` set to `reference-transaction`,
        `post-checkout` and `post-index-change`, then drives `createTask` (branch, `worktree
        add`, lifecycle commit), a round's `persist` and a register transaction; it asserts the
        hook's marker is absent, every operation succeeded, and `.git/config` is byte-identical
        apart from the spec's own plant;
      - `GitInvocationSpec` expects the switch pairs after the eight of D3, one per event of
        the owner's event list, in its order.

      Verify: both red against today's owner (the configured hook runs; the pairs are missing).
- [x] 8.2 Implement the event switches in `GitInvocation` (FR2, FR9, design D3): one constant
      list of the hook events of githooks(5) in git 2.56 — client and server side — rendered as
      `-c hook.<event>.enabled=false` after the eight pairs; the javadoc names the floor release
      the list was read from. Re-render the expectations that spell the pairs
      (`GitTransferInvocationPolicySpec`, `GitTransferSpec`, `GitPushSpec`,
      `DockerSeedCloneCommandSpec`, `GitNetworkCommandsSpec`, `GitProcessRunnerNetworkBranchSpec`,
      `GitVersionCheckSpec`, `GitVersionFloorSpec`, and any other spec a run of
      `grep -rln "core.useReplaceRefs=false" --include='*.groovy' .` finds). Verify: 8.1 green;
      `:gittransfer` PIT on `GitInvocation` all killed; the named specs green.
- [x] 8.3 Split the git floor (FR10, design D11, D10 row 3):
      - `GitVersion.FLOOR` becomes 2.56.0, and `FLOOR_REASON` names the event-level hook switch
        first, then the earlier reasons;
      - a second floor for the box keeps 2.51.0 and today's reason; `BoxGitFloor` reads it, and
        its javadoc stops calling it the factory's one floor;
      - specs: `GitVersionCheckSpec` refuses 2.55.x and passes 2.56.0; `GitVersionFloorSpec`
        (`:bootstrap`) and `BoxGitFloorSpec` (`:sandbox:docker`) keep the box at 2.51.0 (an
        image reporting 2.53.0 seeds).

      Old-way grep, recorded in the report: `grep -rn '2, 51\|2, 56\|"2\.51\|"2\.56' --include='*.java'`
      over production sources — hits only in `GitVersion`. Verify: the named specs green; PIT on
      `GitVersion`, `GitVersionCheck`, `BoxGitFloor` all killed.
- [x] 8.4 CI's git (UX3, design D11; `ubuntu-latest` ships 2.55.0, the `git-core` PPA 2.55.0):
      - add `scripts/install-git.sh`: one pinned version (2.56.0) and SHA-256
        (`26c56c296b38c0695b26fa95f475f1d01704d2d38e73465ca30b0b2f5dc789d3`, kernel.org's
        `sha256sums.asc`), downloads `git-2.56.0.tar.xz`, refuses a checksum mismatch, builds and
        installs it, and ends with the floor check (`git version` ≥ 2.56);
      - `ci.yml` `build` and `pitest-nightly.yml` replace the PPA step with the script, caching
        the install keyed on the script's hash (`actions/cache`); `release.yml` runs no specs and
        needs none;
      - `scripts/linux-runner/Dockerfile` drops the PPA and runs the script in one layer (the
        build tools it needs purged in the same layer); its header comment names the script;
      - update the `docs/glossary.md` entry that names the CI platform's git (≥ 2.51 → ≥ 2.56).

      Verify: the script builds and passes its floor check in the runner image;
      `scripts/linux-specs.sh` builds and runs one named spec; old-way grep
      `grep -rn "git-core\|ppa:" .github scripts docs` — hits only history.
- [x] 8.5 Docs (UX3, design D3, D11):
      - `docs/releases/v0.1.0.md`: the factory needs git 2.56.0 or newer, with the remedy
        (Homebrew's git; on Ubuntu a source build, since the 26.04 archive ships 2.53 and the
        `git-core` PPA 2.55);
      - `docs/adr/0008-git-transfer-policy.md`: amend the floor paragraph — 2.56.0 for the
        factory since `own-git-invocation-policy`, the box's own floor 2.51.0 until
        `add-sandbox-base-image`;
      - `docs/adr/0012-git-invocation-policy.md`: the event switches beside the eight pairs, and
        why `core.hooksPath` alone does not cover configured hooks;
      - `docs/sandbox-threat-registry.md` row 50: hooks defined in the configuration as a vector,
        mitigated by the event switches;
      - `docs/guides/operator-guide-sandbox.md` (lines 42–44) and `docs/examples/sandbox-image/`
        (`README.md`, `Dockerfile`): the image's git is judged against the box's floor, 2.51.0,
        no longer "the factory's own".

      Verify: `grep -rn "2\.51" docs` lists only statements about the box's floor or history.
- [x] 8.6 Hand-offs, made by `/opsx:update` on 2026-10-21 together with this group:
      `add-sandbox-base-image` records that it raises the box's floor to 2.56.0, joins the two
      floors, and moves the base image, the test sandbox images (`alpine:3`: 2.54.0;
      `eclipse-temurin:21-jdk-resolute`: 2.53.0) and the reference recipe to a git at or above
      it; `add-subprocess-access-log`'s note on the argv records names the new pair count.
- [x] 8.7 Integration check: root `./gradlew check` green (after `scripts/linux-specs.sh` over the
      modules group 8 touched, per `verification-scope.md`); `openspec validate
      own-git-invocation-policy` clean; the FR9, FR10 and UX3 grep shows each referenced by a spec
      or code unit.
