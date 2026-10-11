# Tasks: own-git-invocation-policy

Sequenced before `add-subprocess-access-log` (same `GitProcessRunner.execute`; proposal,
Impact). Build order: groups 1 → 2 → 3 → 4 → 5 → 6 → 7, where 2 depends on 1 and 5 needs 2–4
green. Every group lands its own specs and docs.

Runs after group 14 of `supervise-daemon-loops-and-embed-dashboard`: every holder of the git runner
is typed as the package-private `GitRunner` role (`adapters/git`), and `GitProcessRunner` is its
one production implementation. Group 2 rewrites `GitProcessRunner.execute` only; no holder is
retyped to `GitProcessRunner`, or `GitRunnerBoundarySpec` in `:bootstrap` fails. Specs above the
adapter drive `ScriptedGit`, the in-process fake, not a stand-in binary.

## 1. The owner in `:gittransfer`

- [ ] 1.1 TDD (red first): `GitInvocationSpec` pins the owner (FR1, FR2, FR3, design D2, D3):
      - `commandLine(gitBinary, args)` returns the binary, then exactly the eight `-c` pairs of
        D3 in declared order, then the caller's arguments unchanged;
      - the environment-removal set is exactly `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`;
      - no pair names `credential.`, `core.sshCommand` or `url.` (NFR-S2);
      - a blank binary is refused.

      Verify: red against a stub, green after 1.2.
- [ ] 1.2 Implement `GitInvocation` (JDK-only; javadoc `Implements FR1, FR2, FR3 of
      own-git-invocation-policy`). Verify: 1.1 green; `:gittransfer:check` green (PIT 100%).
- [ ] 1.3 TDD then refactor `GitTransfer` to compose the owner (FR4, design D2, D3):
      - `argv()` begins with the owner's pairs;
      - the compact constructor refuses an argv that does not begin with them;
      - `CONFIG_KEYS` drops `maintenance.auto`/`gc.auto`;
      - `STRIPPED` drops the two configuration variables and applies the owner's set instead.

      A data-driven spec over every source kind asserts that every invocation pair is present
      before the subcommand and that no key has two values. Verify: `GitTransferSpec`, the new
      spec and `SeedTransferFixture`-based specs are green; `:gittransfer:check` and
      `:sandbox:docker:check` are green.

## 2. Both launchers take the owner's command line

- [ ] 2.1 TDD then wire `GitProcessRunner` (FR1, FR3, design D2):
      - `execute` builds its `ProcessBuilder` from `GitInvocation.commandLine` and removes the
        owner's environment set;
      - the private `commandLine` is deleted;
      - the typed transfer entry runs the value's argv as-is, without prefixing again;
      - classification keeps reading the caller's arguments.

      Spec: a commit with an inherited `GIT_CONFIG_COUNT` pair naming a hook directory runs no
      hook (FR3 scenario). Verify: `:adapters:git:check` green.
- [ ] 2.2 TDD then wire `GitExec` (FR1, FR3, design D1, D2):
      - `:gitobjects` gains `implementation project(':gittransfer')` and its layering allowlist
        entry with a comment naming the edge;
      - `run` builds its command line from the owner and removes the owner's environment set;
      - `GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM` isolation is kept;
      - the private `commandLine` is deleted.

      `LocalGitRepoFixture` plants a `reference-transaction` hook that writes a marker and
      exits 1; a `CommitBuilder` spec asserts the ref moved and no marker exists. Verify:
      `:gitobjects:check` green, and the layering gate is green.
- [ ] 2.3 Old-way sweep for row 1 of the D10 table. Run
      `grep -rn "commandLine(" --include=*.java */src/main */*/src/main` (expected: definitions
      only in `GitInvocation`) and
      `grep -rn '"gc.auto=0"\|"maintenance.auto=false"' --include=*.java . | grep /src/main/`
      (expected: only `GitInvocation`). Record both commands and their hits in the task report.

## 3. No repository-configuration writes

- [ ] 3.1 TDD (red first) then implement the runner refusal (FR5, NFR-O1, design D4). The new
      `RepositoryConfigWriteException` (javadoc traceability) is thrown before launch for:
      - `config` with no read form;
      - `remote add|rename|remove|rm|set-head|set-branches|set-url`.

      Allowed: `config --get|--get-all|--get-regexp|--get-urlmatch|--list|-l`, `config get|list`,
      `remote get-url`. The data-driven spec covers every refused and every allowed form, with
      and without leading `-c` pairs. Verify: `:adapters:git:check` green.
- [ ] 3.2 Remove the hardening (FR6, design D4, D10 row 2):
      - delete `FactoryCloneHardening`, `FactoryCloneHardeningException`, their spec
        `FactoryCloneHardeningSpec`, `GitTaskBranches.harden` and the port method
        `TaskBranchGit.harden`;
      - the compiler then forces removal at `TakeFreshClaim.java:65`,
        `TakeContainerFreshClaim.java:58`, `TakeResumeBootstrap.java:66`,
        `TakeContainerResumeBootstrap.java:52`, `GitModeRunner.java:112`,
        `ContainerGitModeRunner.java:71` and `ContainerResumeRunner.java:94`;
      - update the fakes and specs that stub or assert `harden`: `RunChainFakes`,
        `TakeFreshClaimSpec`, `TakeContainerFreshClaimSpec`, `TakeClaimAndWorkSpec`,
        `GitModeRunnerFreshRunSpec`, `ContainerGitModeRunnerSpec`, `GitTaskBranchesSpec`,
        `TakeContainerResumeRoutingSpec`, `ContainerResumeRoutingSpec`, `AnchorLogSpec`;
      - retire or rewrite `RunnerStartHardeningSpec` so it asserts that no configuration is
        written.

      Verify: root `./gradlew check` green.
- [ ] 3.3 Reword the sync markers (design D9). Both ends of
      `TakeFreshClaim`↔`TakeContainerFreshClaim`,
      `TakeResumeBootstrap`↔`TakeContainerResumeBootstrap` and
      `GitModeRunner`↔`ContainerGitModeRunner` drop "harden" from the recipe they state, in the
      same wording at both ends. Confirm that `ContainerResumeRunner`↔`GitResumeRunner` needs no
      wording change. Verify: `grep -rn "Kept in sync with" --include=*.java */src/main | grep -i
      harden` is empty.
- [ ] 3.4 Old-way sweep for row 2 of the D10 table. Run
      `grep -rn 'harden(\|"config"' --include=*.java */src/main */*/src/main` (expected: only the
      refusal classifier) and `grep -rn "gnomish-empty-hooks\|FactoryCloneHardening" .
      --include=*.java --include=*.groovy` (expected: empty). Confirm the one exemption,
      `DockerSeedCloneCommand`'s in-box `git config gc.auto 0` (NG6), is unchanged. Record the
      commands and hits in the task report.

## 4. Adversarial local-configuration posture

- [ ] 4.1 Add `FixtureGitConfig` to `:test-fixtures`, a test-only raw writer for fixture
      configuration setup (design D6). Move `BareGitRepoFixture.addRemote` onto it, because the
      runner now refuses `remote add`. Verify: `:test-fixtures` builds, and the existing
      `addOrigin` specs are green.
- [ ] 4.2 Plant the posture in `BareGitRepoFixture.initWorkingRepo` (FR8, design D6):
      - a hook directory with marker-writing `pre-commit`, `post-checkout` and
        `reference-transaction` hooks that exit 1;
      - `commit.gpgSign=true` with a marker-writing `gpg.program` that exits 1;
      - a marker-writing `core.fsmonitor`;
      - `branch.autoSetupMerge=always`.

      Also add `initPlainWorkingRepo` for specs that need a plain clone by name, and an
      `assertNothingPlantedRan(repo)` helper. Update `.claude/rules/testing.md` ("Git fixtures
      are adversarial by default") with the local-config posture. Verify: a fixture spec asserts
      the posture is in force and that the plain variant carries none of it.
- [ ] 4.3 Sweep the real-git test suites that now fail on the posture: route them through the
      fixture helpers, or switch them to `initPlainWorkingRepo` with a comment giving the
      reason. Starting set: the files found by `grep -rln "ProcessBuilder\|\.execute()"` over
      the test sources that also run git. Verify: root `./gradlew check` green. List every
      `initPlainWorkingRepo` use and its reason in the task report.

## 5. Identity, concurrency and container-resume specs

- [ ] 5.1 `GitInvocationIdentitySpec` (`:adapters:git`, real git, adversarial posture; FR8,
      M4, NFR-S1, design D8). It drives:
      - the worktree attempt commit;
      - the lifecycle commit;
      - `update-ref` through the reconciler;
      - a `gitobjects` commit;
      - `worktree add`;
      - branch creation from a tracking ref;
      - a state-file read with a planted `refs/replace/` substitution.

      It asserts that no marker exists, that the read returns the original content, and that
      `.git/config` is byte-identical. Verify: green, and red with the owner's pairs emptied
      (record the red run in the task report).
- [ ] 5.2 Container-resume spec (NFR-R2, design D5). With `branch.autoSetupMerge=always` planted,
      `ContainerResumeBranch.ensureLocalBranch` on a remote-only branch returns true, the local
      ref exists, and the config is byte-identical. Verify: green.
- [ ] 5.3 `ConcurrentCloneConfigSpec` (`:adapters:git`; NFR-R1, lock-scope rule "Specs"). Eight
      virtual threads released by one latch each run the fresh-claim git sequence (branch create,
      worktree add, commit) on one clone. It asserts that no result carries `could not lock
      config file` and that `.git/config` is byte-identical. Verify: green over 20 local runs.
- [ ] 5.4 Remove the temporary `@Retry(count = 2, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)`,
      its `TEMPORARY` comment and the `spock.lang.Retry` import from
      `TakeSlotRunnerContainerConcurrencySpec` "two slots run containers concurrently" (added on
      2026-09-30 to mask the `.git/config` lock race). Keep the per-attempt setup only if it
      still earns its place; otherwise restore the field initializers. Verify: the spec passes
      20 consecutive runs (`--rerun-tasks`) with Docker available (M3). Record the run count
      in the task report.

## 6. Enforcement gate

- [ ] 6.1 `GitLaunchBoundarySpec` in `:bootstrap` (FR7, design D7), an ArchUnit spec over all
      production classes:
      - the set of classes depending on `java.lang.ProcessBuilder` equals the allowlist
        `GitProcessRunner`, `GitExec`, `DockerCli`, `HostTaskExecutionEnvironment` and
        `CaptureRunner`, each entry carrying its reason;
      - `GitProcessRunner` and `GitExec` both call `GitInvocation.commandLine`;
      - seeded scenarios show the detector fails on a synthetic extra spawn site and on a
        launcher that skips the owner.

      Verify: `:bootstrap:test` green; each seeded scenario is red with its seed.

## 7. Docs and hand-offs

- [ ] 7.1 Write `docs/adr/0011-git-invocation-policy.md`. Content:
      - the clone configuration is untrusted input, not storage;
      - one command-scope policy for every factory git process;
      - the no-write rule;
      - what stays ambient (ADR 0008);
      - the boundary, NG1: attribute-selected filters and alternates in a shared `.git`, with
        git's SECURITY guidance.

      Reference it from `design.md` and ADR 0008. Verify: file present; links resolve.
- [ ] 7.2 Update `docs/sandbox-threat-registry.md`:
      - row 38: "core.hooksPath points at an empty location in all factory copies" → "every
        factory git invocation neutralizes hooks by command-scope configuration";
      - new row 49: program execution and read substitution through the clone's local
        configuration (`gpg.program` with signing, `core.fsmonitor`, `refs/replace/`), mitigated
        by the invocation policy, status `shipped (own-git-invocation-policy, ADR 0011)`.

      Update `docs/glossary.md` with the entry **git invocation policy**, and a *Not:* note
      that it is not the transfer set. Verify: both files updated, glossary term used verbatim
      in the ADR.
- [ ] 7.3 Hand-offs:
      - add one sentence to `add-subprocess-access-log/proposal.md` (Capabilities →
        `module-layering`) naming this change's `gittransfer`/`gitobjects` sentences among those
        its re-layering must keep, and noting that argv records now carry the policy pairs;
      - record the UX2 doctor check for `add-doctor-command` (read with `--local`; print the
        `git config --local --unset core.hooksPath` line only when the value ends in
        `/gnomish-empty-hooks`) via `/opsx:update` on that change;
      - add a release-note line for UX2.

      Verify: all three edits present.
- [ ] 7.4 Integration check: root `./gradlew check` green; the `openspec validate
      own-git-invocation-policy` result is clean; an FR/NFR/UX grep shows every proposal ID
      referenced by at least one spec or code unit (`traceability.md`).
