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

## 1. The leaf and its values

- [ ] 1.1 Create the `:gittransfer` module: `settings.gradle` include with the
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
- [ ] 1.2 TDD (red first) `TransferSourceSpec`: sealed `TransferSource` with
      `Origin` (singleton), `Container(String extUrl)`, `SeedPath(Path source,
      Path destination)` — no branch: the seed script binds it to `"$1"`
      (design D6); each carries its protocol allowlist
      string and its configuration-isolation shape as values (design D2
      table). `Refspec` value type refusing a leading `-` and an empty string.
      Verify: data-driven spec over the three kinds asserts the allowlist
      strings `https:ssh:file` / `ext` / `file` (FR4).
- [ ] 1.3 TDD (red first) `GitTransferSpec`: `GitTransfer.fetch(source,
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
- [ ] 1.4 `GitVersion` value in the leaf: lenient parse of `git version X.Y.Z`
      (vendor suffixes such as `(Apple Git-…)` ignored), comparison, and the
      `FLOOR = 2.45.1` constant with its reason string. TDD: parse table
      including a blank and a garbage line → `Optional.empty()`. Verify:
      `GitVersionSpec` green (FR10, NFR-R2).

## 2. The runner: typed entry and refusal

- [ ] 2.1 TDD (red first) in a new `GitProcessRunnerTransferSpec`: `run(Path,
      GitTransfer)` applies the value's set and unset entries to the
      `ProcessBuilder` environment and executes the argv through the existing
      `execute` path — assert with the recording-git fixture that the argv
      carries the stall-detection prefix, that `GIT_ALLOW_PROTOCOL` reaches
      the child (the fake git echoes `env`), and that an inherited
      `GIT_CONFIG_COUNT` does not, and that `GIT_ASKPASS`/`SSH_ASKPASS` reach
      the child empty on the typed entry as on the untyped one. Verify: green
      (FR8, FR6, NFR-S2, NFR-S3).
- [ ] 2.2 TDD (red first) in the same spec: `run(Path, String...)` throws
      `UnownedTransferException` (message names `GitTransfer`) for `fetch`,
      `clone`, `pull`, `submodule update`, `remote update`, with and without
      leading `-c` pairs, before any process is launched (the fake git's
      record file stays absent); `rev-parse`, `push`, `ls-remote` still run.
      Implement via `GitNetworkCommands.subcommandIndex`. Verify: green;
      the mutation lock and bounding features of `GitProcessRunnerSpec`
      unchanged (FR8).
- [ ] 2.3 TDD (red first) `FetchRefusal` in `adapters:git` (design D4):
      a package-private record `(UntrustedText messageId, UntrustedText
      object)` with `static Optional<FetchRefusal> parse(UntrustedText
      stderr)`, annotated `@UntrustedParser`, matching the two shapes
      `error: object <id>: <msg-id>: ...` and `fatal: fsck error in packed
      object` and returning empty for every other stderr (non-fast-forward,
      daemon, auth). Record real git 2.55 output as the spec's fixture. Add
      the class to `UntrustedTextGateSpec.PARSER_CONVERSIONS` with its
      conversion. Verify: `FetchRefusalSpec` green; `:bootstrap`
      `UntrustedTextGateSpec` green (FR5, NFR-O2).

## 3. Origin consumers

- [ ] 3.1 Replace the five `NarrowFetch.of` calls — `BaseRefresh.fetchBranch`,
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
- [ ] 3.2 `RefreshedTip.undelivered`, `CommitBaseFetch.unresolved` and
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
- [ ] 3.3 Identity spec on real git, `GitTransferIdentitySpec` feature
      "origin": adversarial fixture per FR12, built on the shared
      `BareGitRepoFixture` (`:test-fixtures`) rather than a parallel fixture —
      `addOrigin` already leaves the clone one commit behind origin with a
      same-name tag on the stale commit (`divergeFromOrigin`). Add to the
      fixture, as named opt-in methods beside `addConvergedOrigin` (not as a
      widening of the default posture: `divergeFromOrigin` promises origin's
      tree stays byte-for-byte what the spec seeded, and a submodule changes
      the tree), a tag inside the fetched history at origin, an initialized
      submodule whose own remote has advanced, and a temporary global config
      file setting `submodule.recurse=true` and `fetch.prune=true`. The
      global file must reach the child git: `GitProcessRunner` inherits the
      JVM environment, so the spec sets `GIT_CONFIG_GLOBAL` at the Gradle
      `test` task (and under `pitest { jvmArgs }` per `testing.md`) or
      records why a `HOME` override is the right medium — decide in the task,
      not by assuming. Assert the `for-each-ref` diff of the clone is exactly
      the named tracking ref, the submodule's refs are unchanged, and
      `FETCH_HEAD` is absent. Verify: the feature is red against the
      pre-change argv (run once with the D2 flags removed to prove it bites)
      and green after (FR12, UX1, M2).
- [ ] 3.4 Real-git feature "origin cannot use ext": a global config
      `url."ext::…".insteadOf` rewriting the origin URL; assert git refuses
      with its protocol-not-allowed message and no process named in the
      rewrite runs (a marker file the rewritten command would create is
      absent). Verify: green (FR4, NFR-S2).

## 4. Harvest

- [ ] 4.1 `ContainerHarvestFetch.fetch` builds its argv from
      `GitTransfer.fetch(new Container(url(containerName)), refspec)` and
      runs it through the typed entry; the class's `-c protocol.ext.allow=user`
      is deleted, not moved — the owner's `GIT_ALLOW_PROTOCOL=ext` is what
      enables `ext` (design D2); drop the `protocol.ext.allow` mention from
      `GitProcessRunner`'s javadoc with it. Update `ContainerHarvestFetchSpec`'s
      argv feature to the owner's exact list. Verify: spec green, no literal
      `"fetch"` and no `protocol.ext.allow` left in the class (FR6, FR4).
- [ ] 4.2 `ContainerHarvestFetch.classify` asks `FetchRefusal.parse` first
      and maps a present value to the boundary-violation exception the
      rewrite refusal uses, with the same report shape naming message id and
      object, before the daemon and non-fast-forward reads it keeps. TDD with
      the fsck stderr fixture. Verify: `ContainerHarvestFetchSpec` feature
      green; the existing daemon and rewrite features unchanged (FR5, NFR-R1,
      UX3).
- [ ] 4.3 Identity spec feature "container": a `Container` source whose ext
      URL is `ext::git upload-pack <path>` standing in for `docker exec`
      (the argv is otherwise identical), a box repo with a tag inside the
      task branch's history and a tag elsewhere, the operator clone holding
      a same-name tag; assert the ref diff is exactly the task branch,
      `FETCH_HEAD` absent, and — with a `.gitmodules` symlink planted in the
      box branch — the fetch is refused with a parsed `FetchRefusal` and the branch
      ref is unchanged. Two more assertions on the same run: a global config
      file naming `credential.helper` as a marker-writing script is in place
      and the marker is absent after the fetch (the owner points the global
      config at nowhere for this source; askpass is already emptied by
      `GitProcessRunner` for every invocation and is asserted in 2.1); and
      the `ext::` script counts its invocations — exactly one upload-pack
      session ran, and the clone has no `.git/shallow`. Verify: red with the
      pre-change argv, green after (FR12, NFR-S1, NFR-S3, NFR-P1, UX1, M2).
- [ ] 4.4 Docker-gated: `ContainerGitMechanicsSpec` gains one feature
      asserting a tag created in a real box does not appear in the factory
      clone after harvest. Verify: green under the Docker-gated run (FR6).

## 5. Seed clone

- [ ] 5.1 `DockerSeedCloneCommand.seedClone` renders `GitTransfer.clone(new
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
- [ ] 5.2 Identity spec feature "seed": a factory clone with a lightweight and
      an annotated tag on the task branch; run the owner's clone argv with
      real git into a temp destination; assert the destination holds exactly
      `refs/heads/<branch>` and `refs/remotes/origin/<branch>`, no tag, and
      that a planted bad object on the branch is refused (proving the
      transport path, not local mode); and, with a global config file naming
      `credential.helper` as a marker-writing script, that the marker is
      absent after the clone (the owner's `GIT_CONFIG_GLOBAL` names the
      throwaway `safe.directory` file, so the operator's file is never read).
      Verify: red before (tags copied), green after (FR7, FR12, NFR-S3, M2).
- [ ] 5.3 Docker-gated: the seed feature of
      `ContainerTaskExecutionEnvironmentContractSpec` asserts no tag in the
      box after materialize and that the helper image's `git --version` is at
      or above the floor. Verify: green under the Docker-gated run (FR7,
      FR10).

## 6. The gate and the floor

- [ ] 6.1 `GitTransferBoundarySpec` in `:bootstrap` `architecture` (design D7):
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
- [ ] 6.2 `GitVersionCheck`, public in `adapters:git` (design D8:
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
- [ ] 6.3 `GitVersionFloorSpec` in `bootstrap` on `AppAssemblyFixture` (the
      shared app-layer assembly fixture): drive `ManualRunRunner.run` for
      each of `run`, `take`, `serve` with the floor failing and assert no
      transfer, claim, or tracker write is attempted (the recording git sees
      only `--version`; the in-memory tracker sees nothing). Verify: green
      (FR10).

## 7. Documents

- [ ] 7.1 Write `docs/adr/0008-git-transfer-policy.md` (status accepted,
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
- [ ] 7.2 Amend `docs/adr/0006-base-refresh-fetch.md`: "Flags on every
      refresh" becomes a pointer to ADR 0008; remove the sentence claiming
      `NarrowFetch` serves every factory fetch; add 0008 to "See also".
      Verify: `grep -n NarrowFetch docs/` returns nothing (FR11).
- [ ] 7.3 Three rows in `docs/sandbox-threat-registry.md` (object poisoning
      of the operator clone through harvest; ref planting into the operator
      clone from the box; operator ref and tag disclosure into the box), each
      naming its mitigation and this change; glossary entries "transfer" and
      "transfer source" under Sandbox. Verify: rows and entries present;
      `grep -rn NarrowFetch openspec/changes --include='*.md' | grep -v
      archive` returns only this change's own artifacts (the archived
      `signal-outage-gate-on-origin-contact` design keeps its wording; task
      7.1 records the replacement in ADR 0008) (FR11).

## 8. Verification and sweep

- [ ] 8.1 Full `./gradlew check` including PIT on `:gittransfer`,
      `:adapters:git`, `:sandbox:docker`, `:bootstrap`; the Docker-gated
      suites of 4.4 and 5.3 run once locally. Verify: green, mutation score
      100% (or documented exceptions per `testing.md`) (M1–M3).
- [ ] 8.2 Old-way sweep report: `grep -rn -E '"fetch"|"clone"|git (fetch|clone|pull)|NarrowFetch'
      --include=*.java --include=*.groovy */src/main */src/test
      adapters/*/src/main adapters/*/src/test sandbox/*/src/main
      sandbox/*/src/test` — every hit is the owner, the runner's classifier,
      or listed with its reason, and no hit names `NarrowFetch` in either
      tree (javadoc links and spec comments included — the deleted class must
      leave no dangling reference); confirm the single-owner table of design.md
      row by row (consumers, old way removed, enforcement). Verify: the
      report is appended to this file's completion note; M4 checked by
      describing the fourth-source procedure in ADR 0008.
