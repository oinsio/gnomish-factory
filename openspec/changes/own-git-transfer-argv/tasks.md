# Tasks: own-git-transfer-argv

Sequenced after `add-base-ref-resolution`, `signal-outage-gate-on-origin-contact`
and `split-logtext-leaves` — all three archived and synced, so every delta of
this change is written over the stable text. Groups 2–5 leave the build red
between them on purpose — the runner's refusal (2.2) lands before the last
caller moves (5.x); groups 2–5 are one review unit and are verified together
by 8.1, not group by group (the agent never commits; the human decides the
commit boundary). Every group's report ends with the
old-way sweep of `implementation.md`: the grep run, the hits, and what
happened to each.

## 0. Test medium

- [x] 0.1 Add `test-fixtures/src/main/resources/adversarial-gitconfig` holding only
      the `gnomish-probe` alias. In `test-conventions` set `environment
      'GIT_CONFIG_GLOBAL'` on `tasks.withType(Test)`, in `pitest-conventions`
      the same on the `pitest` task (`PitestTask` extends `JavaExec`; minions
      inherit it), both reading one constant path. Verify: full `./gradlew
      check --configuration-cache` green; `git config --global --list` run
      through `GitProcessRunner` from any spec shows the alias alone; the
      report states whether Gradle treated the variable as a task input and
      whether the configuration cache was reused (design D11).
- [x] 0.2 `AdversarialGitConfig` in `:test-fixtures` (path constant, one
      documented line per key, `assertInEffect(runner)` running the probe),
      and `AdversarialGitConfigSpec` in `:bootstrap`: the probe answers, the
      keys listed by `git config --global --list` through the runner equal
      the file and show nothing of the developer's `~/.gitconfig`, and a
      seeded runner with the variable pointed elsewhere fails
      `assertInEffect`. Add the paragraph to `.claude/rules/testing.md` under
      "Git fixtures are adversarial by default": the whole test build runs
      under the committed hostile global configuration, and a spec that
      proves isolation calls `assertInEffect` first. Verify: spec green;
      `checkTestTimeInjection` unaffected (D11).
- [x] 0.3 Add the hostile keys of D11 to the file (`submodule.recurse`,
      `fetch.prune`, the sentinel `insteadOf` writing `.gnomish-ext-ran`, the
      `credential.helper` writing `.gnomish-credential-get` on `get` only).
      Verify: full `./gradlew check` green again, Docker-gated suites
      included once locally; every spec that reds is fixed as a spec finding
      and listed in the report (D11, NFR-S2).
      **Completion note (2026-09-22).** Wiring lives in its own plugin,
      `adversarial-gitconfig-conventions`, applied from `test-conventions`
      (both existing scripts sat at the 200-line cap); the file is declared as
      an explicit task input by content, since Gradle does not treat a forked
      JVM's environment as one; the configuration cache is reused across runs.
      Findings under the hostile keys: (1) 15 fixture fetches spelled a
      short-name source (`gnomish/X:refs/remotes/origin/gnomish/X`), which
      `fetch.prune=true` turns into a deletion of the destination — all now
      spell `refs/heads/…` (rule recorded in `testing.md`); (2) the same effect
      in production `NarrowFetch` (reconcile and locate fetch a bare branch
      name) — bridged with `-c fetch.prune=false` on its argv, the flag of
      design D2's common set, until group 3 replaces the class. Old-way sweep
      `GIT_CONFIG_GLOBAL|XDG_CONFIG_HOME|'HOME'` over `*/src/test` and
      `test-fixtures/src/main`: hits only in the two credential-scrub specs,
      `ShellCommandCheckRunnerSpec`/`HostTaskExecutionEnvironmentSpec` (HOME as
      a stand-in name), `ContainerTaskExecutionEnvironmentUnitSpec` (asserts the
      seed script's own export) and `ContainerModeIsolationE2ESpec` — none
      plants a global git configuration.

## 1. The leaf and its values

- [x] 1.1 Create the `:gittransfer` module: `settings.gradle` include with the
      rationale comment in the style of `:baseref`, `build.gradle` with
      `library-conventions` + `layering-conventions` and `allowedProjects = []`,
      no external dependency. Register the leaf in the build gate that reads
      those declarations as data: a `gittransfer: [] as Set` row in
      `ModuleBuildFileSpec.LEAF_PRODUCTION_DEPENDENCIES` (`:bootstrap`
      `architecture`), which is the `where:` source of "the shared leaf
      #module declares only its permitted production dependencies";
      `LEAF_ALLOWED_PROJECTS` needs no row (its default is the empty set), and
      `DomainLeafPuritySpec` is not involved — it gates only the leafs
      `:domain` reaches, and `:domain` does not reach this one. Verify:
      `./gradlew projects` lists `:gittransfer`; `:gittransfer:check` green on
      an empty module; `ModuleBuildFileSpec` green with the new row and red
      without it (FR2).
- [x] 1.2 TDD (red first) `TransferSourceSpec`: sealed `TransferSource` with
      `Origin` (singleton), `Container(String extUrl)`, `SeedPath(Path source,
      Path destination)` — no branch: the seed script binds it to `"$1"`
      (design D6); each carries its protocol allowlist
      string and its configuration-isolation shape as values (design D2
      table). `Refspec` value type refusing a leading `-` and an empty string.
      Verify: data-driven spec over the three kinds asserts the allowlist
      strings `https:http:ssh:file` / `ext` / `file` (FR4; `http` added by Q3 on
      2026-09-22, after group 4).
- [x] 1.3 TDD (red first) `GitTransferSpec`: `GitTransfer.fetch(source,
      refspec)` and `GitTransfer.clone(SeedPath)` return `argv()` and
      `environment()` (`Map<String, Optional<String>>` — empty means unset).
      Assert per source: the everywhere half of design D2's common set
      (`--no-tags --no-recurse-submodules`, every `-c` pair, the three fsck
      ignores) on all three kinds; the fetch-only half on the fetch sources
      alone — `--no-write-fetch-head` on `Origin` and `Container`, absent
      from the `SeedPath` argv (clone rejects it), `--refmap=` on `Origin`
      only; no `protocol.*.allow` key and no `GIT_PROTOCOL_FROM_USER` entry
      on any kind (design D2: the allowlist is the whole policy); `clone
      --no-local --no-hardlinks --single-branch --no-tags
      --no-recurse-submodules --branch <BRANCH_PARAMETER>` on `SeedPath`,
      where `GitTransfer.BRANCH_PARAMETER` is the leaf's placeholder element
      the caller substitutes (a real branch factory-side, `"$1"` in the seed
      script), `--end-of-options` before source and refspec, environment
      set/unset entries per row. Verify: one
      feature per source kind, green; PIT 100% on the leaf (FR1, FR3, FR4,
      FR5, FR6, FR7).
- [x] 1.4 `GitVersion` value in the leaf: lenient parse of `git version X.Y.Z`
      (vendor suffixes such as `(Apple Git-…)` ignored), comparison, and the
      `FLOOR = 2.45.1` constant with its reason string. TDD: parse table
      including a blank and a garbage line → `Optional.empty()`. Verify:
      `GitVersionSpec` green (FR10, NFR-R2).
      **Completion note (2026-09-22, group 1).** `:gittransfer` is included after
      `:gitobjects`; `ModuleBuildFileSpec` carries the `gittransfer: [] as Set`
      row (proved to read rows as data: a row naming a module with no build
      file reds the gate). PIT refuses an empty module outright ("no mutations
      found"), so `:gittransfer:check` first went green at 1.2, with every
      other task of `check` green on the empty module. The fetch kinds are
      typed: `TransferSource permits FetchSource, SeedPath` and `FetchSource
      permits Origin, Container`, so `GitTransfer.fetch` takes a `FetchSource`
      and a clone from a fetch source, or a fetch from a seed path, does not
      compile — no runtime guard, no mutant. The seed helper's global file path
      is the leaf's constant `SeedPath.SAFE_DIRECTORY_CONFIG`, for 5.1 to read
      back so the script's export and the owner's value agree by construction
      (D6). The intended argv was probed on git 2.55.0 before the spec was
      written: the clone lands one branch and no tag, the fetch writes no
      `FETCH_HEAD`, and `clone --no-write-fetch-head` is refused as unknown.
      PIT on the leaf: 18/18 killed. Old-way sweep (`"fetch"|"clone"|git
      (fetch|clone|pull)` over every `src/main`, comments excluded): the owner
      (`GitTransfer`, `TransferSource`), the runner's classifier
      (`GitNetworkCommands`, `GitProcessRunner`), and the four sites groups 3–5
      remove — `NarrowFetch`, `ContainerHarvestFetch`, `TaskBranchLocator`'s
      `"fetch"` label (6.1 renames it), `DockerSeedCloneCommand`'s script line.
      No consumer moved in this group by design.

## 2. The runner: typed entry and refusal

- [x] 2.1 TDD (red first) in a new `GitProcessRunnerTransferSpec`: `run(Path,
      GitTransfer)` applies the value's set and unset entries to the
      `ProcessBuilder` environment and executes the argv through the existing
      `execute` path — assert with the recording-git fixture that the argv
      carries the stall-detection prefix, that `GIT_ALLOW_PROTOCOL` reaches
      the child (the fake git echoes `env`), and that an inherited
      `GIT_CONFIG_COUNT` does not, and that `GIT_ASKPASS`/`SSH_ASKPASS` reach
      the child empty on the typed entry as on the untyped one. Verify: green
      (FR8, FR6, NFR-S2, NFR-S3).
- [x] 2.2 TDD (red first) in the same spec: `run(Path, String...)` throws
      `UnownedTransferException` (message names `GitTransfer`) for `fetch`,
      `clone`, `pull`, `submodule update`, `remote update`, with and without
      leading `-c` pairs, before any process is launched (the fake git's
      record file stays absent); `rev-parse`, `push`, `ls-remote` still run.
      Implement via `GitNetworkCommands.subcommandIndex`. Verify: green;
      the mutation lock and bounding features of `GitProcessRunnerSpec`
      unchanged (FR8).
- [x] 2.3 TDD (red first) `FetchRefusal` in `adapters:git` (design D4):
      a package-private record `(UntrustedText messageId, UntrustedText
      object)` with `static Optional<FetchRefusal> parse(UntrustedText
      stderr)`, annotated `@UntrustedParser`, matching the two shapes
      `error: object <id>: <msg-id>: ...` and `fatal: fsck error in packed
      object` and returning empty for every other stderr (non-fast-forward,
      daemon, auth). Record real git 2.55 output as the spec's fixture. Add
      the class to `UntrustedTextGateSpec.PARSER_CONVERSIONS` with its
      conversion. Verify: `FetchRefusalSpec` green; `:bootstrap`
      `UntrustedTextGateSpec` green (FR5, NFR-O2).
      **Completion note (2026-09-22, group 2).** `GitProcessRunnerTransferSpec`
      (28 features) and `FetchRefusalSpec` (11) green; the architecture gates of
      `:bootstrap`, `buildHealth`, Spotless and `:test-fixtures:check` green. The
      classification (`isTransfer`) and the environment application live in
      `GitNetworkCommands`, the runner's classifier, since `GitProcessRunner` is
      already past the file-size cap; the typed entry `run(Path, GitTransfer)`
      and the refusal `UnownedTransferException` are in the runner. Two additions
      beyond the task text, both to make an assertion real rather than vacuous:
      (1) the test build now also inherits a per-process configuration
      (`GIT_CONFIG_COUNT=1` naming the inert key `gnomish.inheritedProbe`, set
      beside `GIT_CONFIG_GLOBAL` in build-logic `AdversarialGitConfig`), so the
      child-side assertion "an inherited `GIT_CONFIG_COUNT` does not reach the
      transfer" observes a difference between the untyped and the typed entry —
      recorded in `testing.md` as the third medium of D11; (2) the refusal
      reached every test-side transfer too — 47 spec and fixture sites spelled
      `clone`/`fetch` through the runner (`run`, `gitOutput`, `gitExitCode`) as
      seeding, which groups 3–5 do not own. They now go through the new
      `SeedTransferFixture` trait in `:test-fixtures` (mixed into
      `BareGitRepoFixture`): `fetchFromOrigin` takes the owner's value through
      the typed entry (the factory path, wherever a spec can take it), while
      `seedClone`/`seedFetch` run git directly for the shapes the owner does not
      produce (a whole-repository clone, a fetch from a sibling path); the trait
      is listed in `RawCaptureOwners` and is the one test-side seeding site 8.2
      will list. Two dependency facts for the record: `:adapters:git` keeps the
      leaf as `implementation`; `:test-fixtures` carries it as `api`, because
      groovyc canonicalizes `GitProcessRunner` whole and every test tree that
      names the runner needs the leaf on its classpath — the same shape as the
      `:sandbox:docker`/`:subprocess` precedent, exempted the same narrow way in
      the root `dependencyAnalysis` block. Real git 2.55.0 fsck stderr was
      recorded for `FetchRefusalSpec` (a commit with no committer email, fetched
      and cloned under `fsckObjects`); the summary line alone parses to a refusal
      naming no object. Expected red until groups 3–5 (the production sites
      still spell their fetch by hand and now meet the refusal): in
      `:adapters:git` 87 features across `BaseRefresh*`, `BaseStartPointRegression`,
      `BranchStateReader`, `ContainerHarvestFetch`, `ContainerResumeBranch`,
      `DeliveredBranchReader`, `GitTaskBranches/Store/Worktrees`,
      `ReplicaPairReconciler(*Termination)`, `ResumeBaseResolution`,
      `TaskBranchLister/Locator`, `UsageHistoryWalker(*EdgeCases)`,
      `ContainerGitMechanics`, `ContainerTaskExecutionEnvironmentContract`; in
      `:bootstrap` `GitResumeBootstrapSpec` (9) and `TakeDispositionSpec` (13) —
      every one fails on `UnownedTransferException` raised from `NarrowFetch`,
      `ContainerHarvestFetch` or `TaskBranchLocator`, not on a fixture. PIT on
      `:adapters:git` is deferred to 8.1 for the same reason. One finding for the
      design: `GitTransfer` is a public record, so its canonical constructor is
      public by the language rule, and `new GitTransfer(anyArgv, anyEnv)` handed
      to the typed entry bypasses the owner's policy — the 6.1 source gate closes
      it for production, but a private-constructor shape (a sealed interface
      over a package-private record) would close it by type; worth a line in D1
      or D5. Old-way sweep: `fsck|error: object` over `adapters/git/src/main` —
      `FetchRefusal` only; `GIT_CONFIG_COUNT|GIT_ALLOW_PROTOCOL|GIT_CONFIG_PARAMETERS`
      over every `src/main` outside the leaf — one javadoc mention in
      `GitNetworkCommands`; `run\(.*'(fetch|clone|pull)'|gitOutput|gitExitCode`
      with a transfer literal over every `src/test` and `test-fixtures/src/main`
      — none left (the hit `worktreesRoot.resolve('clone')` is a path name).

## 3. Origin consumers

- [x] 3.1 Replace the five `NarrowFetch.of` calls — `BaseRefresh.fetchBranch`,
      `TagBaseFetch.fetch`, `CommitBaseFetch.fetch`, `TaskBranchLocator`,
      `ReplicaPairReconciler` — with `runner.run(cloneDir,
      GitTransfer.fetch(TransferSource.ORIGIN, refspec))`; delete
      `NarrowFetch.java`; move its per-flag rationale into ADR 0008 (task
      7.1); rewrite the `ReplicaPairReconciler` comment that names it, the
      `{@link NarrowFetch}` javadoc references in `LsRemote` (class javadoc,
      "the sibling NarrowFetch shows the flag") and `TaskBranchLocator` (class
      javadoc, "the one construction site of a factory fetch's argv") to name
      the owner instead, and the two spec comments that cite it
      (`ReplicaPairReconcilerSpec` "built by NarrowFetch",
      `TaskBranchLocatorSpec` "is built by NarrowFetch and carries the same
      protective flags"). Verify: `:adapters:git:compileJava` green;
      `grep -rn NarrowFetch --include='*.java' --include='*.groovy' .` returns
      nothing (a `{@link}` to a deleted class is a dangling reference javadoc
      rejects and the reader cannot follow); `BaseRefreshSpec` "every
      refresh fetch carries the flags that keep it narrow" extended to assert
      `--no-recurse-submodules` and the fsck `-c` pairs (FR3, FR5).
- [x] 3.2 `RefreshedTip.undelivered`, `CommitBaseFetch.unresolved` and
      `TaskBranchLocator.classifyFailedFetch` ask `FetchRefusal.parse` before
      their probe / carriage decision and map a present value to a task-level
      refusal with the report naming message id and object; never to
      `Unavailable`. For the two base sites the arm is
      `BaseRefreshOutcome.Refused`; `BranchLocation` has no refusal arm today,
      so this task adds one (`BranchLocation.Refused(UntrustedText)`, port in
      `:application`) and wires its consumer in the locate path, or records in
      design D4 why `Unavailable` is acceptable there — it is not, per FR5. TDD with the recording git returning the fsck
      stderr. Verify: `BaseRefreshSpec` and `TaskBranchLocatorSpec` features
      green; the outage gate's `RemoteOutageSignalingBaseRefGitSpec` sees no
      `Unavailable` from it (FR5, NFR-R1).
- [x] 3.3 Identity spec on real git, `GitTransferIdentitySpec` feature
      "origin": `AdversarialGitConfig.assertInEffect(runner)` first — the
      recursion and prune keys come from the build-wide file (design D11,
      task 0.3), the spec plants no configuration of its own. Adversarial
      fixture per FR12, built on the shared `BareGitRepoFixture`
      (`:test-fixtures`) rather than a parallel fixture — `addOrigin` already
      leaves the clone one commit behind origin with a same-name tag on the
      stale commit (`divergeFromOrigin`). Add to the fixture, as named opt-in
      methods beside `addConvergedOrigin` (not as a widening of the default
      posture: `divergeFromOrigin` promises origin's tree stays byte-for-byte
      what the spec seeded, and a submodule changes the tree), a tag inside
      the fetched history at origin and an initialized submodule whose own
      remote has advanced. Assert the `for-each-ref` diff of the clone is
      exactly the named tracking ref, the submodule's refs are unchanged, and
      `FETCH_HEAD` is absent. Verify: the feature is red against the
      pre-change argv (run once with the D2 flags removed to prove it bites)
      and green after (FR12, UX1, M2).
- [x] 3.4 Real-git feature "origin cannot use ext": `assertInEffect` first;
      the clone's origin URL is the sentinel `https://gnomish-rewrite.invalid/`
      that the build-wide file rewrites to an `ext::` command (D11, task
      0.3); assert git refuses with its protocol-not-allowed message and
      `.gnomish-ext-ran` is absent from the clone's working directory, so the
      rewritten command never ran. Verify: green (FR4, NFR-S2).
      **Completion note (2026-09-22, group 3).** The five `NarrowFetch.of` callers now
      hand `GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(…))` to the runner's typed
      entry; `NarrowFetch.java` is deleted and no Java, Groovy or non-archive Markdown
      names it except ADR 0006 (task 7.2) and this change's own artifacts (7.3). The
      `BaseRefreshSpec` flags feature asserts `--no-recurse-submodules`, both recursion
      keys and the five fsck pairs. 3.2: `FetchRefusal.parse` is asked first in
      `RefreshedTip.undelivered`, `CommitBaseFetch.unresolved` and
      `TaskBranchLocator.classifyFailedFetch`, each composing its report around
      `FetchRefusal.refusedObjectClause()` (one wording for the four sites; first named
      `phrase()`, renamed because `UntrustedTextSinkGateSpec` pins the set of ambiguous
      accessor names and `phrase` is a `String` accessor elsewhere). The port gained
      `BranchLocation.Refused(UntrustedText report)`; its consumers are recorded in
      design D4: `GitTaskBranches.classifyShape` maps it to `BranchShape.Corrupt`, so the
      claimed take parks through the existing quarantine arm with no new park path, and
      the five other readers throw the new `BranchLocationRefusedException` (a row in
      `TypedExceptionMessageSpec`). `FetchRefusalOutcomeSpec` (9 features, stand-in git
      replaying the recorded git 2.55 fsck stderr) pins all of it: no `ls-remote` probe
      runs after a refused fetch, and no site answers `Unavailable`. 3.3/3.4:
      `GitTransferIdentitySpec` (2 features) on real git under the build-wide hostile
      file; the two adversarial opt-ins (`tagInsideOriginHistory`,
      `addAdvancedSubmodule`) live in a new trait `TransferAdversaryFixture` extending
      `BareGitRepoFixture` rather than inside it, which already stood at twice the
      file-size cap; `SeedTransferFixture` gained `seedSubmodules` (a direct
      `submodule update --init`, the third seeding shape the owner does not produce).
      The origin feature uses the locate's short-name source shape (`main:refs/remotes/
      origin/main`), the one `fetch.prune=true` bites on. Red run recorded: with the
      `-c` pairs, `--no-tags` and `--no-recurse-submodules` stripped from the owner the
      feature fails — git's own output shows `[deleted] (none) -> origin/master` (the
      prune) and `[new tag] inside -> inside` (the auto-follow); restored, both features
      green. Run for the group: `spotlessCheck`, `:test-fixtures:check`, `:adapters:git`,
      `:application`, `:bootstrap` — every remaining failure is the expected group-4 red
      (`ContainerHarvestFetch` still builds its argv by hand and meets the runner's
      refusal): `ContainerHarvestFetchSpec` (6), `ContainerGitMechanicsSpec` (6),
      `ContainerTaskExecutionEnvironmentContractSpec` (1), the six Docker-gated E2E
      specs in `:bootstrap`, and `TakeSlotRunnerContainerConcurrencySpec`, whose
      mid-round harvest logs the same exception and which fails identically on the tree
      without this group's edits. Old-way sweep: `NarrowFetch` over `*.java`/`*.groovy`
      — none; `"fetch"|"clone"|"pull"|git (fetch|clone|pull)` over
      `adapters/git/src/main` — `GitNetworkCommands` (2) and `GitProcessRunner` (1) are
      the classifiers, `ContainerHarvestFetch:55` is group 4's site,
      `TaskBranchLocator` `failureDetail("fetch")` is the label 6.1 renames;
      `fsck|error: object` over `adapters/git` and `application` `src/main` —
      `FetchRefusal` alone reads the grammar, the three other hits are the operator
      sentence "inspect it on origin (git fsck)". Consumer list of the design's first
      row, origin half: `BaseRefresh.fetchBranch`, `TagBaseFetch.fetch`,
      `CommitBaseFetch.fetch`, `TaskBranchLocator.attempt`, `ReplicaPairReconciler.reconcile`
      — all five take the value from the owner; second row, origin half: the three sites
      above ask the parser before their probe or carriage decision.

## 4. Harvest

- [x] 4.1 `ContainerHarvestFetch.fetch` builds its argv from
      `GitTransfer.fetch(new Container(url(containerName)), refspec)` and
      runs it through the typed entry; the class's `-c protocol.ext.allow=user`
      is deleted, not moved — the owner's `GIT_ALLOW_PROTOCOL=ext` is what
      enables `ext` (design D2); drop the `protocol.ext.allow` mention from
      `GitProcessRunner`'s javadoc with it. Update `ContainerHarvestFetchSpec`'s
      argv feature to the owner's exact list. Verify: spec green, no literal
      `"fetch"` and no `protocol.ext.allow` left in the class (FR6, FR4).
- [x] 4.2 `ContainerHarvestFetch.classify` asks `FetchRefusal.parse` first
      and maps a present value to the boundary-violation exception the
      rewrite refusal uses, with the same report shape naming message id and
      object, before the daemon and non-fast-forward reads it keeps. TDD with
      the fsck stderr fixture. Verify: `ContainerHarvestFetchSpec` feature
      green; the existing daemon and rewrite features unchanged (FR5, NFR-R1,
      UX3).
- [x] 4.3 Identity spec feature "container": a `Container` source whose ext
      URL is `ext::git upload-pack <path>` standing in for `docker exec`
      (the argv is otherwise identical), a box repo with a tag inside the
      task branch's history and a tag elsewhere, the operator clone holding
      a same-name tag; assert the ref diff is exactly the task branch,
      `FETCH_HEAD` absent, and — with a `.gitmodules` symlink planted in the
      box branch — the fetch is refused with a parsed `FetchRefusal` and the branch
      ref is unchanged. `assertInEffect` first, then two more assertions on
      the same run: `.gnomish-credential-get` is absent from the clone's
      working directory after the fetch — the build-wide file names a
      marker-writing `credential.helper` (D11) and the owner points the
      global config at nowhere for this source; askpass is already emptied by
      `GitProcessRunner` for every invocation and is asserted in 2.1; and the
      `ext::` script counts its invocations — exactly one upload-pack session
      ran, and the clone has no `.git/shallow`. Verify: red with the
      pre-change argv, green after (FR12, NFR-S1, NFR-S3, NFR-P1, UX1, M2).
- [x] 4.4 Docker-gated: `ContainerGitMechanicsSpec` gains one feature
      asserting a tag created in a real box does not appear in the factory
      clone after harvest. Verify: green under the Docker-gated run (FR6).
      **Completion note (2026-09-22, group 4).** `ContainerHarvestFetch.fetch` hands
      `GitTransfer.fetch(new Container(url(containerName)), new Refspec(refspec(branch)))` to the
      runner's typed entry; `-c protocol.ext.allow=user` and the hand-written
      `--no-recurse-submodules` are gone (`grep protocol.ext` over every `src/main`: none), the
      class and `GitProcessRunner` javadocs name the owner's allowlist instead. `classify` asks
      `FetchRefusal.parse` first and maps a present value to
      `HarvestRefusedException.objectValidation(branch, refusal)` — a package-private factory on
      the rewrite's own exception, message `harvest refused for branch "…": an object from the
      environment failed validation: <refusedObjectClause>` — before the daemon and
      non-fast-forward reads it keeps. `ContainerHarvestFetchSpec` (7 features) pins the owner's
      exact argv as a literal list and the validation arm on the recorded git 2.55 stderr. 4.3:
      `GitTransferIdentitySpec` "container" (3 features in the spec now) — the box is a clone of
      the operator's repo served by an `ext::<script> %S <path>` command that logs each session
      and execs `git upload-pack`, the harvest's own URL shape; the box moves the same-name tag
      `inside` onto its gnome commit and adds `only-in-box` and `elsewhere`; assertions: ref diff
      exactly `refs/heads/gnomish/task-1`, both operator tags untouched, `FETCH_HEAD` and
      `.git/shallow` absent, `.gnomish-credential-get` absent, exactly one upload-pack session;
      then a `.gitmodules` symbolic link planted on the box branch by plumbing (`git add` and
      `update-index --cacheinfo` both refuse to stage one, so `mktree` with stdin and stdout
      through files — `RawCaptureGateSpec` forbids an in-JVM process capture in `:test-fixtures`)
      is refused with `FetchRefusal.parse` → `gitmodulesSymlink` and the branch ref unchanged.
      Red run recorded: with the owner temporarily emitting the pre-change harvest argv
      (`-c protocol.ext.allow=user fetch --no-recurse-submodules <url> <refspec>`, no
      environment) the feature fails on `extra: [refs/tags/only-in-box]` — the auto-follow of
      2026-09-13 reproduced; restored, green. The box-side forms (`boxUploadPackUrl`,
      `plantGitmodulesSymlink`) and the ref-diff readers (`refs`, `changedRefs`) moved into
      `TransferAdversaryFixture` so the spec stays under the file-size cap with the seed feature
      of 5.2 still to come. 4.4: `ContainerGitMechanicsSpec` "a tag created in the box does not
      appear in the factory clone after harvest" ran against the real daemon (8/8 green). Run for
      the group: `:adapters:git:test` whole (908/908, Docker-gated included), `:test-fixtures:check`,
      `spotlessCheck`, and `:bootstrap:test` whole (1907): the gates `RawCaptureGateSpec`,
      `UntrustedTextGateSpec`, `TypedExceptionMessageSpec` green; three Docker E2E features
      (`ContainerLifecycleCoverageGapsE2ESpec`, `ContainerModeResumeE2ESpec`,
      `TakeContainerLifecycleE2ESpec`) fail on `fatal: transport 'http' not allowed` — the Gitea
      lane's origin is plain `http`, which design D2 / FR4's `Origin` allowlist `https:ssh:file`
      did not list; in the take E2E the refusal reached production (`[GF134]`, the base
      refresh of `main`). Decided as proposal Q3 the same day: `http` joins the `Origin`
      allowlist (FR4, design D2, the `git-transfer` spec, `TransferSource.Origin` and its three
      specs updated), and the three E2E features are re-run under it. Old-way sweep: `"fetch"|"clone"|git (fetch|clone|pull)|protocol\.ext`
      over `adapters/git` and `sandbox/docker` `src/main` — `GitNetworkCommands` (2) and
      `GitProcessRunner` (1) classify, `TaskBranchLocator:175` is the label 6.1 renames,
      `DockerSeedCloneCommand:37` is group 5's site; `fsck|error: object` over
      `adapters/git/src/main` — `FetchRefusal` alone reads the grammar, three operator sentences
      "inspect it on origin (git fsck)". Consumer list, first row, container half:
      `ContainerHarvestFetch.fetch` takes the value from the owner; second row:
      `ContainerHarvestFetch.classify` asks the parser first.

## 5. Seed clone

- [x] 5.1 `DockerSeedCloneCommand.seedClone` renders `GitTransfer.clone(new
      SeedPath(SEED_SOURCE, WORKING_COPY))` into the constant script's clone
      line as `env K=V … env -u K … git <argv>`, emitting the argv's
      `BRANCH_PARAMETER` element as the shell word `"$1"` and every other
      element verbatim, keeping the `safe.directory` global file the script
      already writes and asserting in the rendering that the owner's
      `GIT_CONFIG_GLOBAL` entry names that same file. `SEED_SCRIPT` stays a
      `static final` constant built once from constants; branch and pin stay
      the helper's positional parameters. `DockerSeedCloneCommandSpec` pins
      the rendered line against the owner's value (the identity of design
      D6) and keeps its existing "never interpolated" assertions — the branch
      must not appear in the `sh -c` literal, and `"$1"` must. Verify: spec
      green;
      `DockerCommandsSpec`'s `run --rm` assertion (fix-image-declared-volumes)
      unchanged (FR7, FR6).
- [x] 5.2 Identity spec feature "seed": a factory clone with a lightweight and
      an annotated tag on the task branch; run the owner's clone argv with
      real git into a temp destination; assert the destination holds exactly
      `refs/heads/<branch>` and `refs/remotes/origin/<branch>`, no tag, and
      that a planted bad object on the branch is refused (proving the
      transport path, not local mode); and, after `assertInEffect`, that
      `.gnomish-credential-get` is absent from the destination (the
      build-wide file names a marker-writing `credential.helper`, D11, and
      the owner's `GIT_CONFIG_GLOBAL` names the throwaway `safe.directory`
      file, so the operator's file is never read). Verify: red before (tags
      copied), green after (FR7, FR12, NFR-S3, M2).
- [x] 5.3 Docker-gated: the seed feature of
      `ContainerTaskExecutionEnvironmentContractSpec` asserts no tag in the
      box after materialize and that the helper image's `git --version` is at
      or above the floor. Verify: green under the Docker-gated run (FR7,
      FR10).
      **Completion note (2026-09-22, group 5).** 5.1: `:sandbox:docker` declares
      `implementation project(':gittransfer')` and lists it in `layering.allowedProjects`;
      `DockerSeedCloneCommand` holds `SEED_CLONE = GitTransfer.clone(new SeedPath(SEED_SOURCE,
      WORKING_COPY))` and `SEED_SCRIPT = seedScript(SEED_CLONE)`, both `static final`. The
      package-private `seedScript(GitTransfer)` renders the clone line as `env -u K … K=V … git
      <argv>` — unsets first, then assignments, because BusyBox `env` (the reference image) stops
      option parsing at the first `NAME=VALUE` word — with `BRANCH_PARAMETER` emitted as `"$1"`
      and every other word verbatim after a `[A-Za-z0-9_./:=-]+` check that refuses (never
      quotes) anything wider. The script's `export GIT_CONFIG_GLOBAL=…` is read from the owner's
      own entry (`orElseThrow` when absent or unset), so the file the script writes
      `safe.directory` into and the file the owner's `env` names are one value by construction,
      not two constants compared. `DockerSeedCloneCommandSpec` (10 features) pins the production
      script equal to `seedScript(owner)`, the rendered `env … git …` line as a literal, the
      export against `SeedPath.SAFE_DIRECTORY_CONFIG`, keeps "never interpolated" (`"$1"` present,
      `gnomish/task-x` and the placeholder absent), and drives both refusals (global file
      absent/unset; `$`, space, quote, `;` in an argv word, a value, an unset name).
      `ContainerTaskExecutionEnvironmentUnitSpec` asserts the new clone prefix; `DockerCommandsSpec`
      untouched (22/22). 5.2: `TransferAdversaryFixture.seedTransfer(source, destination, branch)`
      substitutes the branch into the owner's value; `GitTransferIdentitySpec` "seed" (4 features
      in the spec now, 217 lines — over the 200 cap, see below) clones a factory repo carrying a
      lightweight and an annotated tag on the branch tip into a temp destination through the
      runner's typed entry and asserts refs exactly `{refs/heads/<b>, refs/remotes/origin/<b>}`,
      no `.git/shallow`, no `FETCH_HEAD`, no `.gnomish-credential-get` (after `assertInEffect`),
      then plants a `.gitmodules` symlink and asserts the second clone is refused with
      `FetchRefusal.parse` → `gitmodulesSymlink` (the transport path validates; local mode would
      have copied). Red run recorded: with `seedTransfer` temporarily returning the pre-change
      argv (`clone --no-hardlinks --single-branch --branch <b> <src> <dest>`, no environment) the
      feature fails on `refs/tags/heavy` and `refs/tags/light` present in the box; restored, green.
      5.3: `ContainerTaskExecutionEnvironmentContractSpec.arrange` tags the branch tip
      (`factory-tag`); the new feature execs `git -C /gnomish/work tag` (empty) and
      `git --version` (parses, not below `GitVersion.FLOOR`) in the real box — 18/18 under the
      daemon, which also proves the rendered `env -u … git …` line runs under BusyBox. Runs for
      the group: `:sandbox:docker:check` (PIT 596/596 killed, 100%), `:test-fixtures:check`,
      `:adapters:git:test` whole (910/910, Docker-gated included), `spotlessCheck`, and
      `:bootstrap:test` whole: 1906 tests, one `initializationError` in `ContainerLifecycleCoverageGapsE2ESpec` — the Gitea container answered 404 until the Testcontainers wait timed out, before any factory code ran; rerun alone, 2/2 green (the three Docker E2E features group 4 left to re-run under the `http` allowlist passed in the whole run). File-size deviation: `GitTransferIdentitySpec` is
      217 lines with the third source kind; the design names one spec with one feature per kind,
      and splitting by kind would be three files of one feature each, so the cap is exceeded
      here by choice and recorded for review rather than hidden by a split that moves no
      responsibility. Old-way sweep `"fetch"|"clone"|git (fetch|clone|pull)|NarrowFetch` over
      `sandbox/docker/src/main` and `adapters/git/src/main`: `GitNetworkCommands` (2) and
      `GitProcessRunner` (1) classify, `TaskBranchLocator:175` is the label 6.1 renames, no
      script literal survives in `sandbox/docker` (`clone --no-hardlinks --single-branch` appears
      in no source tree); `GIT_CONFIG_GLOBAL|safe\.directory` over `*.java` in `sandbox`,
      `adapters`, `gittransfer`: only the owner (`TransferSource`), the runner's environment
      application, and the seed command. Consumer list, first row, third consumer:
      `DockerSeedCloneCommand.seedClone` takes the value from the owner and builds no flag.

## 6. The gate and the floor

- [x] 6.1 `GitTransferBoundarySpec` in `:bootstrap` `architecture` (design D7):
      enumerate every `src/main` root from `settings.gradle` includes; read
      each file through `RepoSourceTree.code` (comments stripped — the raw
      text of fourteen surviving files names `git fetch` / `git clone` in
      javadoc); scan for the argument-literal shapes `"fetch"`, `"clone"`,
      `"pull"`, `"submodule", "update"`, `"remote", "update"` and the
      script-literal shapes `git fetch`, `git clone`, `git pull`, `submodule
      update`, `remote update`; allowlist exactly the `:gittransfer` owner
      files, `GitNetworkCommands`, `GitProcessRunner`; assert the
      scanned-root set equals the include set. Rename the one code literal
      the scan would otherwise flag, `TaskBranchLocator.why`'s
      `failureDetail("fetch")`, to `"narrow fetch"`, and update the
      `TaskBranchLocatorSpec` assertion "the fetch exited 128" to the new
      wording. Add to `RemotePrimitiveSingleSiteSpec` the feature "the fetch
      argv is constructed in no adapter file" (`filesContaining('"fetch",')
      == []`). Seed a violating temp file in the spec to prove the scan
      bites, and a commented-only occurrence to prove it does not. Verify:
      `:bootstrap:check` green on the tree, red on the seeded file;
      `:adapters:git:test` green (FR9, M1, M3).
- [x] 6.2 `GitVersionCheck`, public in `adapters:git` (design D8:
      `GitProcessRunner.run` and `GitCommandResult` are package-private, so
      the check lives beside them): runs `git --version` through the runner
      once per process, parses the captured stdout with `GitVersion` under an
      `@UntrustedParser` marker (the converted value is the leaf's typed
      `GitVersion`, never a `String`), logs one INFO line (version, floor) on
      pass, and on fail or unparsable output throws a startup exception whose
      message names floor, installed, and the reason; a new `[GFnnn]` code in
      the operator-event catalog for the refusal. Expose it as a bean of the
      adapter's auto-configuration and call it from `ManualRunRunner.run`
      before `SubcommandDispatch.dispatchNonRun`, so `run`, `take`, `serve`
      all pass through it; `FactoryApplication` is untouched. TDD with the
      recording git returning `git version 2.44.0`, `2.45.1`,
      `2.55.0 (Apple Git-200)`, and garbage. Verify: `GitVersionCheckSpec`
      green in `adapters:git`; `LogContractGateSpec` accepts the new code;
      `UntrustedTextGateSpec` lists the parser with its warrant (FR10,
      NFR-R2, NFR-O1, UX2).
- [x] 6.3 `GitVersionFloorSpec` in `bootstrap` on `AppAssemblyFixture` (the
      shared app-layer assembly fixture): drive `ManualRunRunner.run` for
      each of `run`, `take`, `serve` with the floor failing and assert no
      transfer, claim, or tracker write is attempted (the recording git sees
      only `--version`; the in-memory tracker sees nothing). Verify: green
      (FR10).
      **Completion note (2026-09-22, group 6).** 6.1: `GitTransferBoundarySpec`
      (`:bootstrap` `architecture`, 16 features) scans every production source
      through `RepoSourceTree.code`, allowlists the `gittransfer/src/main/` root
      and the two classifiers, asserts each allowlist entry is live, and pins the
      reach clause by parsing `settings.gradle` (`include` and `includeBuild`)
      and asserting the scanned root set equals it; the seeded line table (13
      shapes) and a seeded temp file prove the detector bites on code and not on
      prose. `TaskBranchLocator.why` now labels `"narrow fetch"`
      (`TaskBranchLocatorSpec` updated), and the `failureDetail` javadoc of
      `GitCommandResult` names the same example — the raw text scan of
      `RemotePrimitiveSingleSiteSpec` saw its `("fetch",` otherwise. Deviation
      from the task text: that spec's new feature asserts
      `filesContaining('"fetch",') == ['GitNetworkCommands.java',
      'GitProcessRunner.java']`, not `[]` — both spell the token in a `case`
      arm as classifiers (`isTransfer`/`isNetwork`, `isRepoLevelMutating`),
      exactly as the existing `ls-remote` feature of the same spec lists
      `GitNetworkCommands`; design D7 allowlists the same two files. 6.2:
      `GitVersionCheck` (public, `adapters:git`, `@UntrustedParser`) runs
      `--version` through the runner once per process (volatile record; the
      second call runs no subprocess), parses with `GitVersion.parse` into the
      leaf's typed value, logs one INFO on pass and one `[GF149]`
      (`STARTUP_GIT_VERSION_REFUSED`) ERROR on refusal, throwing
      `GitVersionRefusedException` — declared in `app.port.git` beside
      `UnsupportedStateFileVersionException`, so `RunExceptionReporting` prints
      its message as a precondition (new arm, spec row added); the
      unreported-version arm takes the captured output as a carrier
      (`TypedExceptionMessageSpec` row). `verify()` returns void: `:gittransfer`
      is an implementation edge of `adapters:git`, so `:bootstrap` cannot name
      `GitVersion` in a call. Bean in `ManualRunConfiguration` beside the
      runner; `ManualRunRunner.run` calls it first inside the reporting action,
      before `dispatchNonRun`. `GitVersionCheckSpec` (6 features: 2.45.1,
      2.55.0 (Apple Git-200), 2.44.0, garbage, non-zero exit quoting stderr,
      once-per-process). `UntrustedTextGateSpec` lists the parser with its
      warrant; `LogContractGateSpec` green with the code named by the spec.
      6.3: `GitVersionFloorSpec` drives `ManualRunRunner.run` for `run`,
      `take`, `serve` over a fake git reporting 2.44.0 handed to the check and
      to `TaskGitFixture.real(runner)` (new overload in the fixture owner), with
      a `Mock(Tracker)` behind the `github` registry key: the record holds
      `--version` alone and the tracker sees no interaction.
      `AppAssemblyFixture.newManualRunRunner` gained two trailing defaulted
      parameters (tracker registry, version check over real git). Old-way
      sweep: `"--version"|git version|GitVersion\.parse|GitVersion\.FLOOR` over
      every `src/main` — `GitVersionCheck` is the only reader and the only
      parser call; `implements ApplicationRunner` — `ManualRunRunner` is the one
      entry point, and every subcommand passes `dispatchNonRun` after the check.

## 7. Documents

- [x] 7.1 Write `docs/adr/0008-git-transfer-policy.md` (status accepted,
      introduced by this change): sources table, common set with one line per
      flag (moved from `NarrowFetch`'s javadoc), what each source may and may
      not change, validation and its classification, configuration isolation
      per source, the floor and its reason, the rule for adding a source, the
      definition of origin contact for ADR 0005's outage accounting — "the
      owner's `Origin` fetch ran", superseding the "`NarrowFetch` ran" wording
      of the archived `signal-outage-gate-on-origin-contact` design, which is
      not edited (design D10) — the alternatives (D1, D3, D4, D9 rejected
      forms). Mermaid flowchart: caller
      → owner → runner / seed script. Verify: file exists; every FR of the
      proposal is cited at least once (FR11).
- [x] 7.2 Amend `docs/adr/0006-base-refresh-fetch.md`: "Flags on every
      refresh" becomes a pointer to ADR 0008; remove the sentence claiming
      `NarrowFetch` serves every factory fetch; add 0008 to "See also".
      Verify: `grep -n NarrowFetch docs/` returns nothing (FR11).
- [x] 7.3 Three rows in `docs/sandbox-threat-registry.md` (object poisoning
      of the operator clone through harvest; ref planting into the operator
      clone from the box; operator ref and tag disclosure into the box), each
      naming its mitigation and this change; glossary entries "transfer" and
      "transfer source" under Sandbox. Verify: rows and entries present;
      `grep -rn NarrowFetch openspec/changes --include='*.md' | grep -v
      archive` returns only this change's own artifacts (the archived
      `signal-outage-gate-on-origin-contact` design keeps its wording; task
      7.1 records the replacement in ADR 0008) (FR11).

## 8. Verification and sweep

- [x] 8.1 Full `./gradlew check` including PIT on `:gittransfer`,
      `:adapters:git`, `:sandbox:docker`, `:bootstrap`; the Docker-gated
      suites of 4.4 and 5.3 run once locally. Verify: green, mutation score
      100% (or documented exceptions per `testing.md`) (M1–M3).
      **Completion note (2026-09-23, group 8 — check).** First full `check` failed at
      `:adapters:verifyModuleLayering`: `:sandbox:docker`'s new `:gittransfer` edge (5.1) reaches
      `:adapters`, `:adapters:agent` and `:bootstrap` on their runtime classpaths, and the gate
      walks the transitive graph. Each of the three now lists `:gittransfer` in
      `layering.allowedProjects` with the path it arrives by, the same shape `:subprocess` and
      `:untrustedtext` use there. Second full `check` (`--continue`, 57 min, Docker up): every
      task green except two PIT gates — `:application` one SURVIVED
      (`GitVersionRefusedException.requirement` returning `""`: no spec in the module asserted the
      message; `GitVersionRefusedExceptionSpec` now pins both arms' full text) and `:adapters:git`
      one NO_COVERAGE (`FetchRefusal.refusedObjectClause`'s summary-only arm; `FetchRefusalSpec`
      gained a data-driven feature over both shapes). Re-run: `:adapters:git` 867/867 killed,
      `:application` 2270/2270 killed, both `pitestVerifyAllKilled` green. One PIT re-run tripped
      on `SnapshotWriterLifecycleSpec` "the timer beat alone…" failing in the coverage pass while
      the two module PIT runs shared the host — a real-time 30 ms timer under load, predating
      this change and untouched by it; green alone. `:gittransfer`, `:sandbox:docker`,
      `:bootstrap` PIT: 100%, no exceptions added. Docker-gated suites ran inside `check`:
      `ContainerGitMechanicsSpec` 8/8 (the FR6 tag feature of 4.4 included) and
      `ContainerTaskExecutionEnvironmentContractSpec` 18/18 (the seed feature of 5.3 included),
      none skipped.
- [x] 8.2 Old-way sweep report: `grep -rn -E '"fetch"|"clone"|git (fetch|clone|pull)|NarrowFetch'
      --include=*.java --include=*.groovy */src/main */src/test
      adapters/*/src/main adapters/*/src/test sandbox/*/src/main
      sandbox/*/src/test` — every hit is the owner, the runner's classifier,
      or listed with its reason, and no hit names `NarrowFetch` in either
      tree (javadoc links and spec comments included — the deleted class must
      leave no dangling reference); confirm the single-owner table of design.md
      row by row (consumers, old way removed, enforcement). Verify: the
      report is appended to this file's completion note; M4 checked by
      describing the fourth-source procedure in ADR 0008.
      **Completion note (2026-09-22, group 8 — sweep).** Pattern
      `"fetch"|"clone"|git (fetch|clone|pull)|NarrowFetch` over every `src/main` and `src/test`
      (`*.java`, `*.groovy`), plus the same over `*.md`/`*.gradle` for the deleted name.
      *Production hits:* the owner (`GitTransfer.fetch`/`clone`, `TransferSource`, `Refspec`,
      `package-info` — argv literals and javadoc in `:gittransfer`); the runner's classifiers
      (`GitProcessRunner` line 209 `case "fetch", "push"`, `GitNetworkCommands` lines 67/87 —
      `case` arms that name the subcommand to refuse, bound and lock it, constructing nothing);
      every other hit is javadoc prose (`git clone` describing a `cloneDir` parameter in
      `TaskWorktreeManager`, `TaskBranchLocator`, `DeliveredBranchReader`, `GitTaskRepository`,
      `UsageHistoryWalker`, `BranchStateReader`, `TaskBranchLister`, `TaskBranchCreator`,
      `ContainerTaskExecutionEnvironment`; `git fetch` in `ContainerHarvestFetch`'s class comment).
      No production source builds a transfer argv outside the owner — the same answer
      `GitTransferBoundarySpec` gives mechanically. *Test hits:* `GitTransferBoundarySpec`'s own
      token table and fixtures; `NoNetworkCommandGuardSpec`, `GitNetworkCommandsSpec`,
      `GitProcessRunnerSpec`/`GitProcessRunnerTransferSpec` (classifier and refusal tables);
      `GitTransferSpec`/`TransferSourceSpec` (the owner's own argv); `FetchRefusalSpec` (recorded
      stderr); `recordedSubcommands(log).count('fetch')` assertions in `BaseRefreshSpec`,
      `ResumeBaseResolutionSpec`, `FetchRefusalOutcomeSpec`, `BaseRefreshCloneSafetySpec`;
      `tempDir.resolve('clone')` directory names everywhere else. The one test-side transfer
      builder is `SeedTransferFixture` in `:test-fixtures` (`seedClone`, `seedFetch`,
      `seedSubmodules`): declared scaffolding for the shapes the owner does not produce (a whole
      second repository, a fetch from a sibling path), on no production classpath, and its
      `fetchFromOrigin` takes the owner's value through the runner's typed entry;
      `LocalBoxEnvironment` and `ReplicaPairReconcilerSpec` reach it through the trait.
      *`NarrowFetch`:* two surviving comment mentions found by this sweep — `GitTransferBoundarySpec`
      line 65 and `RemotePrimitiveSingleSiteSpec` line 58 — reworded in this task; the name now
      appears in no `.java`/`.groovy` file. Outside the trees it remains in ADR 0008 (three
      mentions: the predecessor named in context, the superseded "`NarrowFetch` ran" wording of
      ADR 0005's outage accounting, the rejected facade alternative) — provenance an ADR is meant
      to carry, so task 7.2's "returns nothing over `docs/`" reads as ADR 0006 alone, which is
      clean — and in this change's own artifacts, which task 7.3 already allows.
      *Row 2 (`FetchRefusal.parse`):* `fsck|error: object` over `adapters/git/src/main` hits the
      parser itself and the three consumer reports' operator hint ("inspect it on origin (git
      fsck)") — prose, no second reader of `forParsing()`; `UntrustedTextGateSpec` pins the
      warrant. *Row 3 (`GitVersionCheck`):* unchanged since group 6's sweep. *Row 4
      (`AdversarialGitConfig`):* `GIT_CONFIG_GLOBAL|XDG_CONFIG_HOME|'HOME'` over every `src/test`
      and `test-fixtures/src/main`: the owner (`AdversarialGitConfig.VARIABLE`), the owner-value
      assertions in `GitTransferSpec`, `TransferSourceSpec`, `GitProcessRunnerTransferSpec`,
      `DockerSeedCloneCommandSpec` (they read the entry the owner sets, plant no file), the
      three listed exemptions (`TakeCommandCredentialScrubSpec` and
      `CliStageExecutorCredentialScrubSpec` using `HOME` as a stand-in credential name;
      `ContainerTaskExecutionEnvironmentUnitSpec` asserting the seed script's own export), plus
      two `ChildEnvAllowlist.of([], ['HOME'])` allowlist entries (`ShellCommandCheckRunnerSpec`,
      `HostTaskExecutionEnvironmentSpec`) and `ContainerModeIsolationE2ESpec`'s inherited-name
      list — none writes a global configuration or overrides `HOME`. *Consumers, row by row:*
      row 1 — seven consumers visited in groups 3–5, each hands a `GitTransfer` value to
      `GitProcessRunner.run(Path, GitTransfer)`; enforcement `GitTransferBoundarySpec` plus the
      runner's refusal on `run(Path, String...)`. Row 2 — four consumers ask `parse` before their
      own arm (groups 3–4). Row 3 — `ManualRunRunner.run` ahead of `dispatchNonRun` (group 6).
      Row 4 — `test-conventions` and `pitest-conventions` read the one path;
      `GitTransferIdentitySpec` and the "origin cannot use ext" feature call `assertInEffect`.
      M4: ADR 0008's sources section states the fourth-source procedure — one permitted
      record in the leaf and one row in the sources table, never a flag list at a call site.
