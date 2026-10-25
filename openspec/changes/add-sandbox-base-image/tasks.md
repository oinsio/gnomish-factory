# Tasks

Starts after `add-release-pipeline` and `own-git-invocation-policy` (both archived). Each task
runs its own scope (`verification-scope.md`: the specs it wrote or changed, PIT on its own
classes); the root `check` ran once for groups 1–6 in task 6.3 and runs once more for group 7
(folded in 2026-10-23) in task 7.11. Container suites run where Docker is available.

## 1. Contract source (design D2) — single-owner row 1

- [x] 1.1 Add `sandbox-image/image-contract.properties` (`contract-version=1`,
      `guard-alias=gnomish-guard`, `guard-port=8080`); `:sandbox:docker` copies
      it into its resources; add `ImageContract` loading it once (FR6). Spock
      spec for load and malformed file. Verify PIT 100% on `ImageContract`.
- [x] 1.2 `GuardCommands` takes alias and port from `ImageContract`; delete the
      two literals. Verify the guard specs pass and
      `git grep -n -e '"gnomish-guard"' -e 'PROXY_PORT = ' -- 'sandbox/docker/src/main/*'`
      is empty (M2). `ContainerRunSupportFactory`'s temporary-directory name
      `gnomish-guard` is a path, not the alias, and stays (design, row 1).
- [x] 1.3 `ImageContractSourceSpec` in `:bootstrap`: resource equals the source
      file; no file under `sandbox/docker/src/main` and no Dockerfile in the
      repository spells the alias, the port or a literal contract label value;
      asserts every scanned file was reached. Verify red with a planted literal.

## 2. The base image (design D1, D4, D5, D8)

- [x] 2.1 `sandbox-image/Dockerfile`, two stages on `ubuntu:24.04`: a build
      stage running `scripts/install-git.sh --prefix /opt/git` (the script is
      copied into the build context by the fixture and the workflows), and the
      published stage with `COPY --from` of `/opt/git`, git's runtime libraries,
      `curl ca-certificates`, user `gnome` uid 1000 (renaming an existing uid
      1000), `/gnomish/{work,scratch}`, root-owned `/etc/gnomish/{maven,ca}` and
      `/etc/claude-code/managed-settings.json`, proxy `ENV` from build args
      with no defaults (empty arg fails the build), Maven settings with the
      mirror build arg, OCI and contract labels, `USER gnome` (FR1, FR4, FR11,
      NFR-S1, NFR-P1). Verify through `BaseImageHelpersSpec` (task 2.2): the
      labels are present, `git --version` reports `GitVersion.FLOOR` or newer,
      the size is under 150 MB, no agent CLI, JDK or Node is present.
- [x] 2.2 `sandbox-image/bin/gnomish-trust-ca` (FR2) and
      `sandbox-image/bin/gnomish-verify-image` (FR3), POSIX `sh`, installed
      root-owned 0755 in `/usr/local/bin`. Verify `BaseImageHelpersSpec`
      (Docker-gated, in `:bootstrap`'s container E2E layer, over the fixture of
      task 4.2): trust-ca with an empty folder is a no-op, with a certificate
      and a grafted JDK reaches both stores; verify-image passes on the base
      and fails listing each violation when `/etc/gnomish` is made writable and
      when `curl` is removed. Markers for every scenario of the two base-image
      requirements it covers.

## 3. Contract check at box start (design D3) — single-owner row 2

- [x] 3.1 `DockerCommands`: replace the volumes inspect with one
      `image inspect -f '{{json .Config}}'`; add `ImageFacts` (declared volumes
      and labels); `DeclaredVolumeOverrides.resolve` takes `ImageFacts` in
      place of the `DockerCli`; `ContainerMaterializer` and `EgressGuard` read
      the facts once each and pass them on. Verify the declared-volume
      scenarios of `DeclaredVolumeOverridesSpec` stay green with the spec
      building `ImageFacts` from the JSON it fed the fake runner before, and a
      spec counts one inspect per image per environment.
- [x] 3.2 Add `ImageContractCheck` in `ContainerEnvironmentBuilder` for the
      sandbox image only: missing or different label → configuration error with
      the FR5 message and a `FROM …:<X.Y>` line (product version from
      `FactoryVersion`; `0.0.0-dev` names the local build instead) (FR5,
      NFR-O1, UX2). Verify the three execution-environment scenarios, markers
      included.

## 4. Recipe, test images and the one floor (design D7, D9)

- [x] 4.1 Rewrite `docs/examples/sandbox-image/Dockerfile` `FROM` the base
      (`GNOMISH_BASE_TAG` build arg), JDK grafted from
      `eclipse-temurin:25-jdk-noble`, Node from the official tarball, the agent
      CLI, `RUN gnomish-trust-ca && gnomish-verify-image`; README with the
      complete operator Dockerfile, the pinning advice (`X.Y.Z` or digest) and
      the git line (the base's git meets the floor; a custom image's must too)
      (FR9, UX1). Verify the recipe builds against the local base in a
      Docker-gated spec (M1; scenario "Recipe builds and verifies").
- [x] 4.2 One task, no image refused in between (FR11, FR10, G4, M3, M5):
      - the E2E base fixture in `:test-fixtures` builds `sandbox-image/Dockerfile`
        once per JVM with arguments from `image-contract.properties`;
      - `DockerImageBuilder` (`:adapters:git`) and `FakeAgentSandboxImage`
        (`:bootstrap`) build `FROM` it; `GitSandboxImage`, `EgressProbeImage`,
        `DeclaredVolumeSandboxImage`, `ContainerReadOnlySurfacesSpec` drop their
        own user, git and curl steps; delete `JvmProxyEgressImage` and run its
        JVM scenario on the base plus a grafted JDK;
      - delete `GitVersion.BOX_FLOOR` and `BOX_FLOOR_REASON`; `BoxGitFloor` reads
        `FLOOR` and `FLOOR_REASON`; `BoxGitFloorSpec` follows;
      - `GitFloorScriptSpec` in `:bootstrap`: `scripts/install-git.sh`'s
        `VERSION` equals `GitVersion.FLOOR` (design, row 3);
      - `docs/guides/operator-guide-sandbox.md` and the recipe's comments name
        one floor.
      Verify the container E2E suites pass against the local base,
      `git grep -n JvmProxyEgressImage` and `git grep -n BOX_FLOOR` are empty,
      and the four scenarios of the MODIFIED floor requirement keep their
      markers (`BoxGitFloorSpec`, the E2E floor spec).

- [x] 4.3 Maven mirror as an operator parameter (design D12): the base's
      root-owned `settings.xml` names `${env.GNOMISH_MAVEN_MIRROR}` (that
      `printf` line single-quoted, or written `\${env.GNOMISH_MAVEN_MIRROR}`:
      in double quotes the shell fails on `bad substitution`), the base sets
      `ENV GNOMISH_MAVEN_MIRROR` from `ARG MAVEN_MIRROR_URL` (default Maven
      Central); the recipe README shows the one-line override. Verify a
      Docker-gated spec over the local base fixture (task 4.2), with no network
      access: (a) the base's settings file names `${env.GNOMISH_MAVEN_MIRROR}`
      and the base's `ENV GNOMISH_MAVEN_MIRROR` is the Central URL (image
      inspection and the file's text — the base carries no Maven to run);
      (b) a child test image, built per D13, grafts Maven and its JDK from a
      stage of their own (an official Maven image, e.g.
      `maven:3-eclipse-temurin-25`), sets
      `ENV GNOMISH_MAVEN_MIRROR=http://mirror.invalid/maven2`, and a Maven run
      in it with `--offline` on a goal whose plugin is not in the local
      repository (e.g. `dependency:get`) fails with a message naming
      `gnomish-mirror` and `http://mirror.invalid/maven2` — Maven's own
      interpolation of the root-owned settings, no request made; (c) the
      settings file stays root-owned in the child. Marker for the
      sandbox-egress scenario "An operator selects a Maven mirror".
- [x] 4.4 One base tag per release (design D11, single-owner row 4): `X.Y` for
      a final release, the full version for a pre-release, none for `0.0.0-dev`.
      - `product-version-conventions` computes the base tag once and exposes it
        as a provider absent for `0.0.0-dev`; a `releaseVersion` with no leading
        `MAJOR.MINOR.` fails the build;
      - `bootstrap/packaging.gradle` reads that provider: Boot's build-info
        additional properties get `baseTag` only when present (the key is
        omitted for `0.0.0-dev`), and the recipe stamp replaces the
        `ARG GNOMISH_BASE_TAG=` line in the shipped `Dockerfile` and `README.md`;
        its regex is deleted;
      - `FactoryVersion` becomes `record FactoryVersion(String value, Optional<BaseTag> baseTag)`
        with `baseTag(): Optional<BaseTag>` and a canonical constructor
        refusing a base tag inconsistent with `value` (absent exactly for
        `0.0.0-dev`; `X.Y` for an unsuffixed value; equal to a suffixed value);
        new record `BaseTag` (`X.Y` or `X.Y.Z-<pre>`, other shapes refused);
        `readFrom` reads `build.baseTag`, and a release version without a
        valid, consistent base tag is a malformed resource (`GF150`,
        `DEVELOPMENT`); the construction sites in `FactoryVersion`,
        `FactoryVersionSpec`, `ImageContractCheckSpec` and
        `BoxImageContractSpec` follow;
      - `ImageContractCheck(ImageContract, Optional<BaseTag>)` replaces the
        `FactoryVersion` parameter; `releaseLine(FactoryVersion)`, its
        `RELEASE_LINE` regex and the `equals(FactoryVersion.DEVELOPMENT)` test
        are deleted;
      - delete the "Kept in sync with" markers of the dissolved pair
        `ImageContractCheck` ↔ `bootstrap/packaging.gradle` (the class javadoc
        and the packaging comment);
      - `scripts/release-preflight.sh` computes the base tag from the tag
        pattern's groups after the shape check (`MAJOR.MINOR` without a suffix,
        the tag without its `v` with one), prints and outputs
        `base_tag=<base tag>` in a full run, and gains `--base-tag <tag>`: the
        shape check only, then `base_tag=<base tag>`, no git and no `gh` call;
      - the script and `product-version-conventions` carry "Kept in sync with"
        each other, and `manual-sync-pairs.md` gets a row in the "no shared
        classpath" table;
      - `RecipeBaseTagStampSpec.expectedTag` keeps its own derivation as the
        test's oracle (single-owner row 4, exemption), rewritten to the same
        rule (`X.Y` without a suffix, the whole version with one);
      - `BaseTagIdentityFunctionalSpec` in `build-logic/src/functionalTest`
        (the script handed to the `functionalTest` task as a system property
        and declared as its input): for one table of tags `TAG_PATTERN`
        accepts — final and pre-release (`v0.4.1-rc.1`, `v1.0.0-beta.2`),
        multi-digit components — the script's `--base-tag` output equals the
        base tag a TestKit build with `-PreleaseVersion=<tag without v>`
        exposes.
      Verify `FactoryVersionSpec` (base tag read for a final and a pre-release
      version; missing, malformed and inconsistent base tag on a release
      version → `GF150` and `DEVELOPMENT`), `ImageContractCheckSpec` (the
      remedy names `X.Y`, a full pre-release version, or the local base),
      `BoxImageContractSpec`, `RecipeBaseTagStampSpec` (both shipped files),
      `BuildInfoSpec` (`build.baseTag` present and equal to the base tag for a
      release version, absent for `0.0.0-dev`), `ReleasePreflightScriptSpec`
      (the `base_tag` output of a full run, final and pre-release;
      `--base-tag` prints the base tag, refuses a malformed tag, and makes no
      `gh` call), `ReleaseVersionSpec` (the base tag of a final and a
      pre-release version, its absence for a development build, the refused
      shape), `BaseTagIdentityFunctionalSpec`; `git grep -n RELEASE_LINE`
      empty and `git grep -n "Kept in sync with"` lists no
      `ImageContractCheck` ↔ `packaging.gradle` pair; PIT on `FactoryVersion`,
      `BaseTag` and `ImageContractCheck`.
- [x] 4.5 Prepare and run the home probe without credentials (design D14,
      Risks; FR12, NFR-S2). Runs after 4.4; 4.6 and 4.7 follow, all before
      group 5. Edit nothing in the repository's tracked files. Write the probe
      as an uncommitted scratch procedure whose location the task report
      names: a `Dockerfile` building the recipe image on the local base
      (task 4.2 fixture tag), a throwaway layer making `/home/gnome`
      `root:root 0755` with `~/.claude` and `~/.cache` gnome-owned, `strace`
      installed in a stage `FROM` the base in the one form D13 allows
      (`RUN env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY apt-get …`),
      and `{"allowManagedHooksOnly": true}` as the managed settings with a
      recording hook in `~/.claude/settings.json` and in
      `/gnomish/work/.claude/settings.json`; and a `run.sh` that starts the
      probe container with `--cap-add SYS_PTRACE` and the proxy variables
      cleared for it (`-e HTTP_PROXY= -e HTTPS_PROXY= -e http_proxy= -e https_proxy=`;
      outside a box the guard's alias does not resolve), and passes a
      credential, when the caller has one, as `-e CLAUDE_CODE_OAUTH_TOKEN` by
      name only (the value comes from the caller's environment, never argv).
      Run now the part that needs no credential — a dummy key and an
      unreachable `ANTHROPIC_BASE_URL` (e.g. `http://127.0.0.1:9`) — and record
      in the probe's results table: the CLI version and the
      image digest; the file operations under `$HOME` at start
      (`strace -f -e trace=file`); whether it creates `~/.claude.json` or
      renames a file directly in `$HOME`, and the error text if a write there
      fails; whether `CLAUDE_CONFIG_DIR` moves `.claude.json` into that
      directory. Verify: the probe files and the first rows of the table
      exist; no build.
      Measured (2026-10-22, CLI 2.1.308, `--network none`, a dummy key, an
      unreachable `ANTHROPIC_BASE_URL`): the CLI starts and reaches prompt
      submission with `$HOME` root-owned. On every start it tries an atomic
      write of `~/.claude.json` directly in `$HOME`
      (`openat("…/.claude.json.tmp.<pid>.<rand>", O_CREAT|O_EXCL)` → `EACCES`,
      eight or nine attempts), logs `Failed to write file atomically` and
      `Config fallback write also failed; continuing without persisting`, and
      goes on; nothing else at the top level of `$HOME` is touched (no rc file
      is read at start; no `.bash_history`, `.npm`, `.config`). With
      `CLAUDE_CONFIG_DIR=/home/gnome/.claude` the file, its lock and its
      backups land under `~/.claude` and no write in `$HOME` is attempted.
      `.credentials.json` is looked for under `~/.claude`. With
      `allowManagedHooksOnly: true` neither hook fires (debug: `Skipping plugin
      hooks - allowManagedHooksOnly is enabled`); under `{}` both fire. The
      only policy file read is `/etc/claude-code/managed-settings.json`
      (`managed-settings.d/`, `CLAUDE.md`, `.claude/rules`, `.claude/skills`
      under `/etc/claude-code` are probed, all `ENOENT`). Not observable
      without a model: whether it answers, the tool gates, the shell snapshot,
      the OAuth state.
- [x] 4.6a Home probe against a stand-in provider (no credential; after 4.5,
      before 4.6b). Add to the probe of 4.5 a Messages API stand-in on the
      probe's Docker network — a canned streaming reply to every request: one
      `tool_use` of `Bash` (a command writing a marker file), then
      `end_turn` — and point the sessions at it (`ANTHROPIC_BASE_URL`, a dummy
      key). The stand-in answers `/v1/messages` only; it does not imitate
      `/api/oauth/*`, so no row whose subject is the credential is filled from
      this run. Fill rows 1 (the CLI completes a turn with `$HOME`
      root-owned), 7 (`--allowedTools` / `--disallowedTools`: the marker of the
      allowed session exists, that of the disallowed one does not), 9 (a second
      session reuses an existing `~/.claude/shell-snapshots/` file or creates a
      new one), 10 (the snapshot shell's argv: a login shell or not) and 11
      (every write under `$HOME` during a turn, `strace -f -e trace=file`).
      Verify: the five rows filled with the trace lines that show them; no
      build.
- [x] 4.6b Authenticated home probe (human step, like 6.4; one session, after
      4.6a). What is already known: the factory's own container runs hand
      `CLAUDE_CODE_OAUTH_TOKEN` to the box by name through `AgentAiSeam`, and
      multi-round tasks reached `delivered` that way on an image whose home
      the gnome owns — the token as a variable works without a keychain and
      across sessions; only the root-owned home is in question. The operator
      exports the token in their own shell (the factory's secrets folder of a
      registered project holds it,
      `~/.gnomish/projects/<project>/secrets/CLAUDE_CODE_OAUTH_TOKEN`, so
      `CLAUDE_CODE_OAUTH_TOKEN=$(cat …) ./run.sh auth` reads it without
      typing it), passed as `-e CLAUDE_CODE_OAUTH_TOKEN` by name only, never
      on a command line, never committed, and runs one `claude -p` session on
      a trivial prompt, then a second with `CLAUDE_CONFIG_DIR=/home/gnome/.claude`.
      The questions left after 4.5 and 4.6a: whether the CLI answers while its
      `~/.claude.json` write fails (`EACCES` logged, `continuing without
      persisting`) or refuses; which files it writes directly under `$HOME`
      with a real token besides `~/.claude.json`; where the OAuth state lands
      (`~/.claude/.credentials.json`, `~/.claude.json`, elsewhere) and whether
      the second session re-reads it; whether, with `CLAUDE_CONFIG_DIR`, every
      write lands under `~/.claude` and the first session's state serves the
      second. The CLI version and the image digest. Verify: the table exists
      with every row filled. An unchecked 4.6b stops a sequential apply before
      4.7.
- [x] 4.7 The gnome's home is root-owned; only declared subdirectories are
      gnome-writable (design D14, single-owner row 5; FR1, FR3, FR12, NFR-S2;
      FR20 and NFR-S2 of add-sandbox-core).
      - (a) **Precondition.** Start only if the table of 4.6a/4.6b exists and
        shows that the CLI does not need `$HOME` itself writable: it answers
        with `$HOME` root-owned, its direct `$HOME` write failing with the
        logged fallback (4.5) or absent, and every other write landing under
        the declared directories. Then D14 stands as written. If instead the
        CLI refuses to answer, or loses state between sessions, without the
        persisted `~/.claude.json`: make no edit to the Dockerfile, a scenario
        or D14; write the five-answer report of `spec-mismatch.md` (premises
        with `file:line`, class map, alternative zero, options ranked by
        failure class, what each leaves) and run `/architect` on it. One option
        the report ranks: the base sets `ENV CLAUDE_CONFIG_DIR=/home/gnome/.claude`,
        under which 4.5 measured no `$HOME` write at all; its cost is named
        against NG7 — D4 of `harden-verification-integrity` owns a factory-set
        `CLAUDE_CONFIG_DIR` per round, and a base-set value is the default that
        D4 overrides, not a second owner. Copy the table's rows, the CLI
        version and the image digest into the task report. Verify: the
        precondition answered in the task report.
      - (b) `sandbox-image/bin/gnomish-home-dir` (POSIX `sh`, root-owned 0755,
        refuses a name with `/` or `..` and a run as non-root); the Dockerfile
        creates `/home/gnome` `root:root 0755` in both branches of its user
        step — the `usermod -l gnome -d /home/gnome -m` branch (an existing
        uid-1000 user) and the `useradd -m -u 1000 gnome` branch — instead of
        handing it over, writes `.bashrc`, `.profile`, `.bash_logout` as
        root-owned 0644 one-comment stubs, runs `gnomish-home-dir .claude
        .cache`, writes `{"allowManagedHooksOnly": true}` to the managed
        settings, and rewrites the comment "The managed settings of the agent
        CLI override anything the gnome writes under its home" (a managed file
        overrides only the keys it sets); the recipe runs
        `gnomish-home-dir .m2 .gradle .npm` in its root stage; the recipe
        README shows the call and says that an operator's own managed policy
        keeps `"allowManagedHooksOnly": true`. Verify:
        `./gradlew :bootstrap:test --tests 'BaseImageHelpersSpec'` and the
        recipe build spec of task 4.1 (base and recipe still build and pass
        `gnomish-verify-image`).
      - (c) `gnomish-verify-image` gains the home rule and the hooks rule. The
        home is the `gnome` user's home from the user database
        (`getent passwd gnome | cut -d: -f6`), never `$HOME`, and a home other
        than `/home/gnome` is a violation of its own; that home and each
        top-level entry root-owned and not group/other/gnome-writable; the
        gnome-writable directories directly under it exactly the entries of
        `/etc/gnomish/home-dirs`, each a gnome-owned directory;
        `/etc/claude-code/managed-settings.json` sets `allowManagedHooksOnly`
        to `true` (`grep -Eq '"allowManagedHooksOnly"[[:space:]]*:[[:space:]]*true'`),
        else a violation naming the file and the key. `BaseImageHomeSpec` (as built; split from `BaseImageHelpersSpec` for the file-size cap):
        the base passes, with the check run as root and as `gnome`; a
        violation line each for a gnome-owned home, a gnome-writable
        `~/.bashrc`, an undeclared gnome-owned `~/.config`, a listed name whose
        directory is missing, and `printf '{}\n' > /etc/claude-code/managed-settings.json`;
        a gnome-owned home is reported when the check runs as root too;
        `gnomish-home-dir .m2` then verify passes. Markers for the sandbox/
        sandbox-base-image scenarios "An operator declares a toolchain
        directory", "An undeclared writable home entry fails the build" and
        "An agent policy without managed-hooks-only fails the build". Verify:
        `./gradlew :bootstrap:test --tests 'BaseImageHelpersSpec'`.
      - (d) `ContainerReadOnlySurfacesSpec`: as `gnome` in a box on the base,
        overwriting `~/.bashrc`, `rm` then recreating it, and creating
        `~/.gitconfig` and `~/.mavenrc` each fail, and a later `exec` reads the
        stub unchanged and finds neither file. Its existing marker for
        sandbox/execution-environment "Cage rules cannot be rewritten from
        inside" now covers the rc-file part; add the marker for
        sandbox/sandbox-base-image "The home holds no gnome-writable startup or
        configuration file". Add a feature "a write to `~/.claude/settings.json`
        fails" under `@PendingFeature(reason = "NG7 of add-sandbox-base-image:
        the agent-CLI configuration under ~/.claude stays gnome-writable until
        D4 of harden-verification-integrity")`, carrying the marker of "Cage
        rules cannot be rewritten from inside" (D14: the remainder is pinned in
        the test tree). `ContainerFreshSegmentSpec` moves its poison from
        `$HOME/bin` and `$HOME/.profile` (no longer writable) to `~/.cache` and
        `/tmp`, keeping its subject (a fresh box resets everything outside the
        working copy). Verify: `./gradlew :adapters:git:test --tests
        'ContainerReadOnlySurfacesSpec' --tests 'ContainerFreshSegmentSpec'`.
      - (e) `docs/sandbox-threat-registry.md`: row #5 reads "…so code runs later
        outside the box or in a later round of the same box", its mitigation
        names the root-owned home (D14) and the remainder in `~/.claude/*` with
        its pickup (NG7: the implementation of D4 of
        `harden-verification-integrity`); row #39 records the in-segment
        remainder (declared toolchain directories, leftover processes) with its
        pickup (NG8: the change that adds a reset of the box at the round
        boundary — the first change that takes #39 inside a segment). Verify:
        the two rows read against NG7 and NG8; no build.
      - (f) `EgressSelfCheckProbes`: `-q` as the first argument of all three
        `curl` calls (`EgressSelfCheckProbes.java:53,79,124`; curl honours `-q`
        only first). `EnvironmentSelfCheckSpec` asserts `-q` leads each probe's
        argv; a Docker-gated feature beside the self-check suites
        (`EgressGuardIntegrationSpec`; as built in `EgressSelfCheckCurlrcSpec` beside it) runs the self-check in a test image whose
        root-owned `~/.curlrc` (baked at build, since the gnome can no longer
        write one) would flip a probe's verdict, and asserts the verdict is
        unchanged. Verify: `./gradlew :sandbox:docker:spotlessApply
        :sandbox:docker:compileTestGroovy`, `./gradlew :sandbox:docker:test
        --tests 'EnvironmentSelfCheckSpec'`, `./gradlew :adapters:git:test
        --tests 'EgressGuardIntegrationSpec'`, `./gradlew
        :sandbox:docker:pitestVerifyAllKilled
        -PpitScope=com.github.oinsio.gnomish.sandbox.environment.EgressSelfCheckProbes*`.

## 5. Publishing (design D6, D10, D11, D15, D16)

Before 5.1 starts: `/review-artifacts add-sandbox-base-image D15`
(`design-decisions.md`, "A decision added during implementation is reviewed
before its task runs").

- [x] 5.1 Release workflow: move the preflight step into a `preflight` job with
      outputs `version`, `prerelease`, `base_tag` (D11) and `image_tags` (D15);
      `scripts/release-preflight.sh` gains the `image_tags` output — a JSON
      array, `["X.Y.Z","X.Y"]` for a final release and `["X.Y.Z-<pre>"]` for a
      pre-release, derived from the `version` and `base_tag` it already
      computes — and `ReleasePreflightScriptSpec`'s pass table gains the
      `image_tags` column (its three rows; `BaseTagIdentityFunctionalSpec`
      untouched). `release` gains `needs: preflight`; new `image` job (`needs:
      preflight`): QEMU, buildx, ghcr login, refuse an existing `X.Y.Z` (for a
      pre-release, its full `X.Y.Z-<pre>` tag) through
      `scripts/image-tag-refusal.sh <image> <tag>` (D15), build-push
      `linux/amd64,linux/arm64` with `tags:` formed from
      `needs.preflight.outputs.image_tags` by the one fixed expression of D15,
      with no condition in the YAML (D6), attest with `push-to-registry`,
      `osv-scanner scan image` SARIF upload non-blocking; `packages: write`,
      `id-token: write`, `attestations: write` in this job only (FR7, NFR-S3).
      `image-tag-refusal.sh` has the three outcomes of D15: the tag exists →
      exit 1, the `::error` naming the tag and the recovery of the push →
      attest window (a tag left by an earlier run of this release is attested
      by its digest in a separate run, steps in the developer guide); the
      registry answers that no such manifest exists → exit 0; `docker` fails
      any other way → exit 2, the `::error` naming the tag, the captured stderr
      and "re-run", nothing pushed. In the `release` job, beside the step
      "Unpacked archive reports the tag's version", a step running
      `scripts/check-recipe-stamp.sh <unpacked archive> <base_tag>` (D15),
      which checks that the unpacked archive's `share/sandbox-image/Dockerfile`
      and `README.md` each carry `ARG GNOMISH_BASE_TAG=<base_tag>` and fails
      naming the file otherwise (single-owner row 4: the release branch of the
      stamp, which no CI test build reaches). `image-tag-refusal.sh` reaches
      `docker` through `PATH`; `check-recipe-stamp.sh` reads files only. Their
      specs, `ImageTagRefusalScriptSpec` and `RecipeStampArchiveScriptSpec` in
      `:bootstrap`'s `distribution` package beside `ReleasePreflightScriptSpec`,
      drive them — the first over new `docker` stand-in presets in the
      committed library (covered by `StandInLibrarySpec`), whose stderr
      answers are the ghcr texts under `data/`: the tag present, absent, a
      pre-release tag, `docker` failing otherwise (refused with exit 2, not
      pushed), and the refusal message naming the recovery; the second over
      unpacked trees: the stamp right in both files, wrong in one, missing.
      Markers: "An existing release tag is not overwritten" and "An
      unanswered registry check publishes nothing" in
      `ImageTagRefusalScriptSpec`. `ci.yml` gains, before `./gradlew check`, an
      `actionlint` step over `.github/workflows/*.yml` at a pinned version —
      the `rhysd/actionlint:1.7.12` image, which bundles `shellcheck`, run by
      tag (not tracked by Dependabot: raised by hand). Verify the three specs,
      `actionlint` 1.7.12 locally and in CI, and one local run of
      `check-recipe-stamp.sh` over an archive built with
      `-PreleaseVersion=<X.Y.Z>` (the release branch of the stamp), recorded
      in the task report. The two workflow-verified scenarios of the release
      ("Release pushes both tags", "A pre-release pushes only its full tag")
      get no exemption file here: the gate refuses one for a capability no
      main spec has yet, so it lands at archive (D10 as amended 2026-10-23,
      task 6.5). Acceptance: a dry run on a
      test tag, run link in the task report; before it, record ghcr's real
      stderr of `docker manifest inspect` for a missing tag in the existing
      package and for a package not yet created — those texts are the
      presets' answers (D15), and a difference found later is a visible
      refusal, never a push.
      *Local part done 2026-10-25; open: the dry run and the logged-in ghcr answer.*
      `actionlint` 1.7.12 (`rhysd/actionlint:1.7.12`, the `ci.yml` step's command) over
      `.github/workflows/*.yml`: no findings, exit 0. `./gradlew :bootstrap:distTar
      -PreleaseVersion=0.3.0`, unpacked: `release-preflight.sh --base-tag v0.3.0` →
      `base_tag=0.3`; `check-recipe-stamp.sh <unpacked> 0.3` exit 0 (`ARG
      GNOMISH_BASE_TAG=0.3` at `share/sandbox-image/Dockerfile:17` and `README.md:27`);
      `bin/gnomish --version` → `0.3.0`; the same archive against `0.2` exit 1 with one
      `::error` per file. `-PreleaseVersion=0.3.0-rc.1`: `base_tag=0.3.0-rc.1`, the stamp check
      exit 0. Specs green: `ImageTagRefusalScriptSpec` 10, `RecipeStampArchiveScriptSpec` 7,
      `ReleasePreflightScriptSpec` 17. ghcr, docker 29.4.0, no login: a missing tag in an
      existing public package (`ghcr.io/gitleaks/gitleaks:9.9.9-nonexistent`) answers
      `manifest unknown`, as `data/stderr-registry` records; `ghcr.io/oinsio/gnomish-sandbox-base`
      does not exist yet (its package page answers 404), and every tag of it answers
      `Get "https://ghcr.io/v2/oinsio/gnomish-sandbox-base/manifests/<tag>": denied`, which
      `image-tag-refusal.sh` reads as exit 2. **Finding, unverified:** if the release job's
      logged-in client gets the same `denied` for the package not yet created, the first image
      release is refused on every re-run — a permanent stall, not the visible one-off refusal
      the risk row of D15 (`design.md`, "ghcr's answer … differs") assumes. The logged-in answer
      is not recorded (no `gh` login on the machine). It must be read before the dry run, since
      the dry run's push creates the package and that answer can no longer be observed; if it is
      `denied`, the question goes through `/architect` (`spec-mismatch.md`) before the release.
Before 5.2 starts: `/review-artifacts add-sandbox-base-image D16` (added
during the apply of 5.1, same rule as above).

- [x] 5.2 `sandbox-image-rebuild.yml`: weekly schedule plus manual dispatch;
      the final release with the highest version, from
      `scripts/latest-final-release.sh` (D15: raw JSON of `gh release list
      --exclude-drafts --exclude-pre-releases --limit 1000 --json
      tagName,isDraft,isPrerelease`, selected in the script, never by `--jq`;
      drafts, pre-releases and tags not of the form `vX.Y.Z` left out; highest
      version, not latest creation date; no final release → exit 0 with a
      `::notice` and an empty `tag=`, and the workflow skips the build; `gh`
      failing → exit 1 naming it) → checkout tag → `X.Y` from the checked-out
      `scripts/release-preflight.sh --base-tag "$TAG"` (D11: the shape check
      only; no history, no `actions: read`, no CI-run lookup; a final tag
      always yields `X.Y`) → build-push `X.Y` only → attest → scan (FR8,
      NFR-C1). Spec `LatestFinalReleaseScriptSpec` in `:bootstrap` over new
      `gh` stand-in presets answering raw JSON from the library's `data/`:
      finals and pre-releases mixed, a draft, a later-created patch of an
      older line (`v0.1.5` after `v0.2.0` → `v0.2.0`), a malformed tag name
      ignored, no final release (notice, empty output, exit 0), `gh` failing
      (exit 1); marker "The weekly rebuild takes the highest final release".
      The rebuild's build arguments come from
      `scripts/image-contract-build-args.sh` (D16, below), never from a step
      of its own.
      - One owner for the contract build arguments (design D16, single-owner
        row 1; FR6, FR7, FR8, FR10). Today's two copies, quoted as they stand
        before this task: the fixture `SandboxBaseImage` in `:test-fixtures`
        holds `static final Map<String, String> CONTRACT_ARGUMENTS =
        [CONTRACT_VERSION: 'contract-version', GUARD_ALIAS: 'guard-alias',
        GUARD_PORT: 'guard-port'].asImmutable()` read by `static Map<String,
        String> contractValues(Path source)`; the release `image` job's step
        "Read the image contract into build arguments" (`id: contract`) runs
        `sed -n -e 's/^contract-version=/CONTRACT_VERSION=/p' -e
        's/^guard-alias=/GUARD_ALIAS=/p' -e 's/^guard-port=/GUARD_PORT=/p'
        sandbox-image/image-contract.properties` between `echo
        'args<<CONTRACT'` and `echo 'CONTRACT'` into `$GITHUB_OUTPUT`. Add
        `scripts/image-contract-build-args.sh [<properties file>]` (POSIX
        `sh`; default `sandbox-image/image-contract.properties`): one
        `NAME=value` line per `key=value` line, in file order, the name derived
        by rule (upper-case, `-` → `_`), comments and blank lines skipped, a
        malformed line, an empty value, or a value beginning or ending with a
        whitespace character (space, tab, the `\r` of a CRLF line) → exit 1
        with an `::error` naming the file and the line (D16: a visible refusal,
        never a silent strip, since `ImageContract` strips and
        `ImageContractCheck` compares the label exactly). Spec `ImageContractBuildArgsScriptSpec` in
        `:bootstrap`'s `distribution` package (beside
        `ImageTagRefusalScriptSpec`, spawned through
        `TestChildEnvironment.cleared`): the committed source file yields
        exactly `CONTRACT_VERSION=…`, `GUARD_ALIAS=…`, `GUARD_PORT=…` in that
        order with its values; comments and blank lines skipped; a new key in a
        spec-written input yields its derived name; an empty value and a
        malformed line refused naming the line; a value with a trailing space,
        a value with a leading space and a line ending in CRLF each refused
        naming the line; the default path used when no argument is given.
      - Retrofit the 5.1 `image` job: the `contract` step captures the
        script's output (`args=$(sh scripts/image-contract-build-args.sh)`, so
        a non-zero exit fails the step) and writes it between the same
        delimiters; `build-args:` is unchanged.
      - `sandbox-image-rebuild.yml` uses the same step.
      - The fixture runs the script once in `ensureBuilt()` as `sh
        <root>/scripts/image-contract-build-args.sh
        <root>/sandbox-image/image-contract.properties`, both paths absolute
        from `repoRoot()` (a test JVM's working directory is its module), with
        the environment through `TestChildEnvironment.cleared` and standard
        input from `/dev/null`, the shape `TestDockerCommand` uses; a non-zero
        exit is an assertion carrying the script's output. It turns each
        line into a `--build-arg`; delete `CONTRACT_ARGUMENTS` and
        `contractValues`, and update the class javadoc (the values come from
        the script, still never from `ImageContract`).
      - The `sandbox-image/Dockerfile` header names
        `scripts/image-contract-build-args.sh` and the name rule (the key
        upper-cased, `-` → `_`) in place of its `CONTRACT_VERSION <-
        contract-version` table; the release `contract` step's comment points
        at the script, not at the Dockerfile's header (D16).
      - New spec `ImageContractBuildArgsSpellingSpec` in `:bootstrap`'s
        `architecture` package, built on `RepoSourceTree` beside
        `ImageContractSourceSpec` (which stays under the file-size cap;
        single-owner row 1): no file under `.github/workflows/` and none under
        `test-fixtures/src/main` spells `contract-version=`, `guard-alias=`,
        `guard-port=`, `CONTRACT_VERSION`, `GUARD_ALIAS` or `GUARD_PORT`; the
        scan asserts it reached every file.
      Verify `actionlint`, `LatestFinalReleaseScriptSpec`,
      `ImageContractBuildArgsScriptSpec`, `ImageContractSourceSpec`,
      `ImageContractBuildArgsSpellingSpec`, one
      container E2E spec that builds the base through the fixture
      (`BaseImageContractSpec`), `git grep -n CONTRACT_ARGUMENTS` empty, and
      the `sed` step gone from `release.yml`. The scenario "Weekly rebuild
      leaves the release tag alone" gets no exemption file here (D10 as
      amended; task 6.5). Acceptance: one manual dispatch run; `:X.Y.Z`
      digest unchanged afterwards.
      *Verified 2026-10-25; open: the acceptance run, which needs a published final image (after 5.1).*
      `actionlint` 1.7.12 (`rhysd/actionlint:1.7.12`) over `.github/workflows/*.yml`: no
      findings, exit 0. Specs green (`--rerun`): `LatestFinalReleaseScriptSpec` 8,
      `ImageContractBuildArgsScriptSpec` 13, `ImageContractSourceSpec` 11,
      `ImageContractBuildArgsSpellingSpec` 5, `BaseImageContractSpec` 7 (the base built through
      the fixture's run of the script; docker layer cache warm). `git grep -n CONTRACT_ARGUMENTS`:
      no hit in code, only the quotations in this change's `design.md` and `tasks.md`. The `sed`
      key table is gone from `release.yml`; its `contract` step and `sandbox-image-rebuild.yml`
      both run `args="$(sh scripts/image-contract-build-args.sh)"` (`release.yml:264`,
      `sandbox-image-rebuild.yml:111`).

## 6. Docs and wrap-up

- [x] 6.1 `operator-guide-sandbox.md`: build your image `FROM` the base,
      the contract refusal and its fix, pinning, custom images and the label,
      downloading in a stage of your own and the one-`RUN` proxy unset for
      operating-system packages (D13), the Maven mirror `ENV` (D12), the
      root-owned home and `gnomish-home-dir` for toolchain directories (D14),
      an operator's own managed policy keeping `"allowManagedHooksOnly": true`,
      the base tag a pre-release names (its full version; `X.Y` moves only
      with a final release, D6, D11), and the remainders NG7 and NG8; the recipe
      README's "Download in a stage of your own" paragraph, which today forbids
      clearing a proxy variable outright, rewritten to the two forms D13 allows;
      `docs/glossary.md`: image contract, base image, base tag; developer guide: the
      three release jobs, the one-time ghcr.io visibility setting, and the
      recovery of the push → attest window (D15: a tag an interrupted run left
      unattested is attested by its digest in a separate run, never deleted). Verify:
      `RecipeGuideExampleSpec` in `:bootstrap` — the fenced Dockerfile block of
      `operator-guide-sandbox.md` equals `docs/examples/sandbox-image/Dockerfile`
      (instruction lines compared, comment lines aside), red when either
      drifts; the guide is not checked by reading.
- [x] 6.2 Traceability: every FR/NFR/UX named by a spec, javadoc or script
      comment; every scenario of the three deltas has a marker, except the
      three workflow-verified scenarios of `sandbox/sandbox-base-image`, whose
      exemption lines land at archive (task 6.5, D10). Verify by one command, not by reading: for every `FR`, `NFR-*` and
      `UX` id of `proposal.md`, `git grep -qP '\b<id>\b[^;]*\bof
      add-sandbox-base-image' -- ':!openspec'` finds a reference, the loop
      printing each id it does not; and `./gradlew checkSpecContract --change
      add-sandbox-base-image`. (Corrected 2026-10-25, audit finding 9: the
      literal `"<id> of add-sandbox-base-image"` misses every id cited in a list
      — `FR1, FR2, FR3, FR4, FR11, FR12, … of add-sandbox-base-image` in
      `sandbox-image/Dockerfile`, the helpers and the image suites — so it
      reported FR1–FR4 and FR12 as unreferenced while the references exist; the
      PCRE form finds every id, verified over the whole proposal id set.)
- [x] 6.3 Root `./gradlew check` green, once. Verify the exit code.
- [x] 6.4 Operator-stand acceptance (human step): rewrite the stand's
      Dockerfile `FROM` the base and run one container-mode task. The stand's
      image today differs from the base in exactly what D14 closes — a
      distribution image in its `FROM`, a home the gnome owns with the
      distribution's rc files, `/etc/claude-code/managed-settings.json` as
      `{}` — so this run is the acceptance of D14 with the real agent CLI, not
      only of the contract. Verify: `gnomish-verify-image` passes on the
      rebuilt image, the box starts and the task reaches a terminal outcome.
      Then the incident's replay (M6, M7, after group 7): on the stand's
      factory clone still on the `files` backend, start `serve` with one
      `Ready` task — it exits at startup with the `[GF173]` line and the
      tracker shows no claim; migrate the clone (`git refs migrate
      --ref-format=reftable`), start `serve`, and under the running daemon
      migrate a copy back to `files` and point one slot at it — one WARN,
      one INFO, no `GF076`, at most one claim and release per slot. Remove the
      stale `gnomish:working` label the 2026-10-23 incident left on the
      stand's issue #30 (or let the reaper return it) before the replay.
      *Verified 2026-10-25 on the `time-zones` stand; six findings below go to `/architect`.*
      Stand: jar built from `389bee90` (`:bootstrap:bootJar`), base `gnomish-sandbox-base:local`
      built from the same tree with `scripts/image-contract-build-args.sh`, the stand's
      Dockerfile `FROM ${GNOMISH_BASE_IMAGE}` (time-zones `5ae7c8b`), image
      `time-zones-gnomish-sandbox:5`. `gnomish-verify-image` passes on it: the home root-owned
      with `.cache`, `.claude`, `.local`, `.npm` owned by the gnome, `managed-settings.json` =
      `{"allowManagedHooksOnly": true}`, git 2.56.0, label `io.gnomish.image-contract=1`.
      Container-mode runs with the real agent CLI, every stage of the pipeline to a terminal
      outcome: #30 `delivered` (PR time-zones#37, 47 min 16 s; resumed after the finding-2
      park below) and #38 `delivered` (PR time-zones#39, 59 min 15 s; `specify` passed on its
      second attempt after a judge `Fail`, every other stage on its first).
      M6 (18:15): clone on `files`, #40 `Ready` → `serve` exits at startup with the
      `CLONE_REF_FORMAT` refusal (`[GF173]`); the issue's timeline shows only `labeled
      gnomish:ready`: no claim, no `gnomish:working`.
      M7 (18:31–18:51): clone on `reftable`, `serve` started, then the clone migrated to
      `files` under the running daemon and #40 labelled `Ready`. One claim (18:33:09), one
      WARN `[GF176] daemon condition CLONE_REF_FORMAT opened … claim released` (18:33:10), no
      further claim while open; clone migrated back, one INFO `daemon condition
      CLONE_REF_FORMAT closed: open PT17M46S over 4 failed probe(s)` at the next probe
      (18:50:56); `grep -c GF076` over the daemon's log 0; WARN/ERROR lines over the episode: 1.
      #40 then ran on: parked INFRA by finding 2, returned to `Ready` by hand, resumed at
      `specify` with the interrupted attempt not counted, and was `delivered` (PR
      time-zones#41, 1 h 59 min; attempts after judge `Fail`s: `specify` 4, `fix-specs` 2,
      `implement` 3, `review-code` 2, each inside its stage's `attemptLimit`; its summary
      again says `attempts=0`, finding 6).
      **Deviation from the task text:** the clone itself was migrated, not a copy one slot
      points at: the project registers one clone, and the slot's precondition path is the
      same either way.
      Findings, each to `/architect` (Mode A) per `spec-mismatch.md`, none fixed here:
      1. **Image lookup answered "No such image" → `GF054` stack trace at startup, twice
         (12:56, 18:24).** Docker Desktop's Resource Saver stopped its VM after 5 min idle
         (`com.docker.backend.log`: `VM stopped`); while stopped, its API cache answers 404 to
         `GET /images/<tag>/json` for every tag, while `docker image ls` lists the image and
         `inspect` by id finds it; one `docker run` woke the VM. `ImageFacts.read` throws
         `DockerCommandFailedException`, which `DockerImageReadiness.assess` leaves
         unclassified, so neither a missing image nor this false answer reaches the operator
         as a coded precondition line with its remedy. Its effect under a running `serve`
         (a claim-time check) is inferred from code, not reproduced.
      2. **The sandbox sweep stops a live box the same daemon reclaimed after a stale-claim
         removal, twice.** #30: reaper removed the stale claim 13:26:13, reclaim 13:26:41,
         sweep 13:31:11; #40: removal 18:51:35, reclaim 18:52:09, sweep 18:56:25 (box age
         4 min 7 s), each ~2–10 s before the reaper's next tick. `StalenessMemory.staleRefs()`
         keeps the task until the reaper's next `observe()`, and `LivenessOracle` subtracts it
         from the live set, so the sweep disposes the guard ("unowned guard") and stops the
         main box ("unowned running main-box") mid-round; the task parks INFRA with
         `gnomish:needs-human`. One fault yields three lines (`GF121`, `GF006`, `GF112`), and
         the dashboard renders the stop as "an instance died or hung". Predates this change
         (add-serve-sandbox-lifecycle, add-claim-heartbeat).
      3. **`GF122` is a false WARN.** On #30, #38 and #40 the 1000-line tail window of the guard's
         log filled with allowed connections (registry.npmjs.org); the only denials in both
         guard logs were the self-check's probe of `selfcheck-denied.gnomish.invalid`.
      4. **A trailing newline is escaped, not trimmed**: `printed: files\n` in the
         `CLONE_REF_FORMAT` refusal and condition lines, and docker's stderr in finding 1.
      5. **The `COMPLETED` lifecycle commit is logged twice** (#30: 15:17:24 and 15:17:31;
         #38: 17:42:43 and 17:42:51; #40: 20:58:04 and 20:58:11), and the main box's "disposed" line twice; whether two
         commits land or one write is logged twice is not yet checked.
      6. **Attempt counts read zero**: `attempt finished: attemptsUsed=0` after a passing
         attempt, and #38's `task summary` says `attempts=0` although `specify` ran two.
- [x] 6.5 At archive (D10 as amended 2026-10-23), in the same step that merges
      this change's deltas into `openspec/specs/`: write
      `config/spec-contract-exemptions/sandbox-sandbox-base-image.txt` with
      exactly this content (the titles are the delta's scenario headings,
      verbatim):

      ```text
      spec: sandbox/sandbox-base-image
      Release pushes both tags => .github/workflows/release.yml (image job)
      A pre-release pushes only its full tag => .github/workflows/release.yml (image job)
      Weekly rebuild leaves the release tag alone => .github/workflows/sandbox-image-rebuild.yml
      ```

      Not earlier: `checkSpecContract` refuses an exemption file whose
      capability no main spec has
      (`build-logic/src/main/groovy/com/github/oinsio/gnomish/buildlogic/ScenarioExemptions.groovy:50-53`).
      Verify: `./gradlew checkSpecContract` green after the merge; forgetting
      the file is a visible failure, since the gate then reports these three
      scenarios as unlinked.

## 7. Serve readiness and daemon conditions (design D17–D21; FR13–FR18) — single-owner rows 6 and 7

Folded in 2026-10-23 after the live incident (proposal, Why). Every task names the specs it
runs and the classes it mutates; nothing here runs a module's whole `test` or the root `check`
before 7.11.

- [x] 7.0 `/review-artifacts add-sandbox-base-image D17–D21` (`design-decisions.md`, "A
      decision added during implementation"): freshness of the quoted signatures
      (`ClaimedRunFailureDispatch.onFailure(TakeOrder, RuntimeException)`,
      `RemoteOutageGates.forServe(BaseRefGit, Path, ServeProperties, InstantSource,
      DirtyNotifier, RemoteOutageLedgerSink)`, `ServeCommand.run` between
      `requireTrackerConfig` and `provisionTracker`, `SandboxModeSelector.plan(PipelineDefinition,
      RegisteredClone)`, `FactoryCloneRefFormat.require(Path)`), the two single-owner rows against
      the rule, and tasks 7.1–7.11. Findings are applied with `/update-from-review` before 7.1.
- [x] 7.1 The typed result and its exit code (D18, FR14): add
      `TakeResult.OperatorPreconditionUnmet(UntrustedText reason, DaemonConditionKind kind)` and
      the `DaemonConditionKind` enum in `:application`, and the typed
      `ContainerRuntimeUnavailableException` (new, `app/port/run`, thrown by task 7.3);
      `ClaimedRunFailureDispatch` maps `CloneRefFormatRefusedException`,
      `ContainerRuntimeUnavailableException`, `BoxGitVersionRefusedException` and
      `ImageContractRefusedException` to it after releasing the claim through the outage
      release's one owner, `ClaimOutageRelease` (a form that returns the new variant; D18), and
      the class javadoc's "exit code is the whole point"
      paragraph is rewritten to say which two exceptions still rethrow and why (`UsageException`
      2, `DivergedBranchException` 5); `TakeExitCodeMapper` maps the variant to 8;
      `RunExitCodeMapper` maps the four exceptions to 8 and `RunExceptionReporting` keeps them
      in `PRINTS_OWN_MESSAGE` (the runtime one added); the command a refusal ends writes its
      one coded line (D19, "One failure, one log"), the dispatch logs at DEBUG. Verify: `ClaimedRunFailureDispatchSpec` (one feature per
      exception: the result, the release, no abort write; markers for "A slot's ref-format
      refusal is a result, not a crash" and "Operator precondition refused after the claim
      exits 8"), `TakeExitCodeMapperSpec` and `RunExitCodeMapperSpec` (every variant and the
      two retired/absent codes, 4 never produced; markers for "Retired code is never returned",
      "Each tool failure has its exit code below 10", "A precondition refusal in run exits 8,
      never 1"), `TakeExitCodeE2eSpec` in `:bootstrap`'s `e2e` package, which holds "Shared
      codes mean the same in run" today; PIT scope
      `app.ClaimedRunFailureDispatch*,app.ClaimOutageRelease,app.take.TakeExitCodeMapper,app.RunExitCodeMapper`.
- [x] 7.2 Every sink reads the variant (D18, FR14): `SlotOutcomeLog.detail` and `summarize`
      (a typed line like `InfrastructureUnavailable`'s, naming the kind and the fix),
      `DrainReport.record`, `RunSummaryAccumulator.record`, `TaskOutcomeLedgerWriter.write`
      (no line — the episode is the condition's), `take/TakeResultDescription`,
      `take/TaskSummaryAssembler` (no summary, as for `InfrastructureUnavailable`), the batch
      `take` summary and its aggregate code. Old-way sweep: `grep -rn "InfrastructureUnavailable"
      application/src/main` lists every switch the new variant must join (today it also hits
      `ClaimOutageRelease`, `ResumeLawBinding`, `ServeRuntimeAssembly`, `FeedAssembly` and
      `RemoteOutageGate`, which build or read the outage variant and need no arm); the compiler
      enforces exhaustiveness on the sealed interface only where no `default` arm exists, so
      each hit is read. Verify: `SlotOutcomeLogSpec`, `DrainReportSpec`,
      `RunSummaryAccumulatorSpec`, `TaskOutcomeLedgerWriterSpec`, `TakeBatchSpec`, the specs of
      `TakeResultDescription` and `TaskSummaryAssembler` by name; PIT scope the seven classes.
- [x] 7.2a `/review-artifacts add-sandbox-base-image D22` (`design-decisions.md`, "A decision
      added during implementation"): freshness of D22's signatures and line references, its
      single-owner row, the corrected evidence rule of D19, and tasks 7.2b and 7.3 as revised;
      resolve the report through `/update-from-review` before 7.2b starts.
- [x] 7.2b The first box is live before the engine on both fresh container paths (D22, FR14,
      NFR-R2; the scenarios "A refused image releases the claimed task", "Runtime unreachable
      at materialize returns the task", "Operator precondition refused after the claim exits
      8"). One classifier, `OperatorPreconditionRefusals.kindOf(RuntimeException)` in
      `:application`, owns the four refusal types; `ClaimedRunFailureDispatch`,
      `RunExceptionReporting` and `RunExitCodeMapper` read it and their own type lists are
      deleted (in `RunExceptionReporting` both: `OPERATOR_PRECONDITIONS`, and the four entries
      of `PRINTS_OWN_MESSAGE`, which asks the classifier for them and keeps its other types). `TakeContainerFreshClaim.claimAt` and `ContainerGitModeRunner.run`, after
      `GitFreshTaskSupport.createTask` and before the engine (on `run` after the runner-start
      sweep too, `ContainerTerminalDrive.runFresh`), call
      `SandboxRunSupport.reattachFor` for the definition's first stage: a failure the
      classifier names propagates; any other is logged at DEBUG and left to the first round.
      The `ContainerResumePreparation` javadoc no longer claims to be the only caller of
      `reattachFor`. Old-way sweep: `grep -rn "CloneRefFormatRefusedException\|BoxGitVersionRefusedException\|ImageContractRefusedException\|ContainerRuntimeUnavailableException" application/src/main`
      — every hit is the classifier, a declaration or a site that throws. Verify:
      `TakeContainerFreshClaimSpec` and `ContainerGitModeRunnerSpec` (`:application`; a support
      whose `reattachFor` throws each refusal: it propagates and the engine never runs; any
      other exception does not propagate and the engine runs), the classifier's spec (every
      `DaemonConditionKind` but `REMOTE`), `ClaimedRunFailureDispatchSpec`,
      `RunExceptionReportingSpec`, `RunExitCodeMapperSpec`; the flow spec in `:bootstrap` over
      the scripted docker CLI (D22's single-owner row: a fresh `take` and a fresh `run`
      refused at the image contract exit 8 with no `EXECUTOR_THREW` line), the new
      `OperatorPreconditionOwnerSpec` in `:bootstrap`'s `architecture` package (the four names in
      a `case`, `instanceof` or `.class` only in the classifier; the scan reached every file);
      PIT scope `app.OperatorPreconditionRefusals,app.TakeContainerFreshClaim,app.ContainerGitModeRunner,app.ClaimedRunFailureDispatch,app.RunExceptionReporting,app.RunExitCodeMapper`.
- [x] 7.3 The runtime is told apart from the image (D19 as corrected by D22, NFR-R2; the
      MODIFIED "The box's git meets the floor before the seed clone" and "Runtime outage is an
      infrastructure failure"): `BoxGitFloor` classifies no runtime itself — `DockerCli.run`
      already throws `DockerUnavailableException` for an unreachable daemon, and the floor
      probe lets it propagate; a probe that ran and exited non-zero stays
      `BoxGitVersionRefusedException`. `ContainerTaskExecutionEnvironment.materialize`, the one
      materialize entry (its container-state inspect is the first docker call of both
      `create` and `reattach`), maps `DockerUnavailableException` to
      `ContainerRuntimeUnavailableException` (task 7.1) on every materialize; mid-task deaths
      keep their infrastructure classification through `RoundExecution`'s catch-all; `SandboxModeSelector.requireContainerPrerequisites`
      throws it in place of its `UsageException` when `runtimeProbe.available()` is false (the
      plan meets the runtime first, D19; its other refusals stay `UsageException`), the
      message unchanged; `ClaimedRunFailureDispatch` maps the type to
      `OperatorPreconditionUnmet(…, CONTAINER_RUNTIME)` through the classifier of task 7.2b.
      `FactoryCloneRefFormat`,
      `BoxGitFloor` and `ImageContractCheck` stop logging their refusals (D19): the throw
      carries the message, and the deciding layers of tasks 7.1, 7.4 and 7.5b write the line.
      Verify: `BoxGitFloorSpec` (markers "A runtime that cannot start the helper is not a
      version refusal": a `DockerUnavailableException` from the probe propagates and no version
      refusal is thrown), `ContainerTaskExecutionEnvironmentUnitSpec` in `:sandbox:docker` by
      name ("Runtime unreachable at materialize returns the task": the inspect throws, the
      typed refusal leaves, `create` is never reached; the same on the `reattach` path), `SandboxRoundOutageSpec` in `:bootstrap`
      ("Docker daemon dies mid-task" still green), the `SandboxModeSelector` spec by name (the
      runtime refusal typed, the other three still `UsageException`),
      `ClaimedRunFailureDispatchSpec`, the specs pinning `GF162`/`GF163` moved with their lines;
      PIT scope `sandbox.environment.BoxGitFloor,sandbox.environment.ContainerTaskExecutionEnvironment,sandbox.environment.ContainerMaterializer*,sandbox.environment.ImageContractCheck,app.SandboxModeSelector,adapter.git.FactoryCloneRefFormat`.
- [x] 7.4 The startup owner (D17, FR13, NFR-R3, UX3; single-owner row 6): the port
      `SandboxImageReadiness.assess(String image)` in `app/port/run`, implemented in
      `:sandbox:docker` (one `ImageFacts` inspection, `ImageContractCheck`, `BoxGitFloor` with a
      fixed helper key and the daemon's ownership labels) and bound in `:bootstrap` beside
      `ContainerRuntimeProbe`; `ServePreconditions` in `:application` takes ports only
      (`TaskBranchGit.requireRefFormat`, `SandboxModeSelector`, `SandboxImageReadiness`) and runs
      them in slot order against the daemon's registered clone, bound definition and configured
      image, returning the first typed refusal; `ServeCommand.run`
      calls it after `requireTrackerConfig` and before `provisionTracker` and turns a refusal
      into the startup-failure exit through the existing `ServeExitCodeException` path, the
      check's own message printed, with the startup's one coded line (D19). Verify:
      `ServePreconditionsSpec` (order, first refusal wins, nothing written; markers for the four
      startup scenarios of `serve/factory-serve`), the adapter's spec in `:sandbox:docker` by
      name (one inspect per assessment, the helper's labels), a new
      `ServeCommandPreconditionsSpec` (startup exit before any tracker call), the new
      `ServePreconditionSpellingSpec` in `:bootstrap`'s `architecture` package (no file under
      `app/serve` or the startup classes spells `show-ref-format`, `docker info` or
      `git --version`; the scan asserts it reached every file); PIT scope
      `app.serve.ServePreconditions*` and the readiness adapter.
- [x] 7.5a Daemon conditions, the remote only (D19, FR15; single-owner row 7):
      `RemoteOutageGate` (287 lines, over the cap) becomes `DaemonCondition` keyed by
      `DaemonConditionKind`, split by responsibility, not by line count: the state machine with
      its log edges and suppressor is one class, the kind with its `ConditionProbe` another;
      `RemoteOutageProbeSchedule` and the three-phase probe unchanged (`lock-scope.md`: still a
      state-guarding lock with the probe outside it; `RemoteOutageGateProbeConcurrencySpec`
      stays and is renamed with the class). `DaemonConditions` holds the set (the remote its
      only member here), answers `anyOpen()`, advances every due probe;
      `RemoteOutageGates.forServe` builds the set; `FeedCycle` takes `DaemonConditions` (the
      `RemoteOutageGate` field and parameter go, so the compiler lists every former reader) and
      reads `anyOpen()` where it read `isOpen()`; `SnapshotSources` and
      `serveobservability/RemoteHealthAssembler` read the owner. Old-way sweep: `grep -rn
      "RemoteOutageGate\b" application bootstrap --include='*.java'` — every code hit routed to
      the owner or listed in the design row (javadoc mentions in `dashboard/AlertCondition*` and
      `ServeProperties` re-pointed; the dashboard reads the snapshot, not the owner). Verify: the
      existing `RemoteOutageGate*Spec`s green under the rename (behaviour pinned once, not
      re-tested per kind), `FeedCycleSpec`, `ServeRuntimeWiringSpec`; PIT scope
      `app.serve.DaemonCondition*,app.serve.FeedCycle`.
- [x] 7.5b The precondition kinds (D19, FR15, FR16, NFR-O2): `containerRuntime`,
      `cloneRefFormat`, `imageContract`, `boxGitFloor`, each with the probe of D17's port
      (`TaskBranchGit.requireRefFormat`, the runtime probe, `SandboxImageReadiness.assess`), so
      startup, slot and probe are one spelling; `DaemonConditions` receives
      `report(OperatorPreconditionUnmet)`; `TakeSlotRunner` reports the variant after the
      sinks; the condition writes the one opening WARN with the fix, probes DEBUG, the closing
      INFO (D19, "One failure, one log"). Verify: `DaemonConditionsSpec` (one open holds the
      feed; a probe closes only its kind; a failing probe logs at DEBUG, never the check's
      ERROR; markers "One gate read covers every condition", "A condition's probe is its own
      check", "A container start is the box git floor's probe cadence" on virtual time with a
      counting fake probe), `ServeRuntimeWiringSpec` (one feature per kind: a slot reporting the
      refusal and the next poll claiming nothing, each red with the report removed; "One claim
      per free slot for the whole episode", "The container runtime stops under a running
      daemon", "An unclassified failure opens no condition"); PIT scope
      `app.serve.DaemonCondition*,app.serve.TakeSlotRunner`.
      *Done 2026-10-24, as built:* `ServePreconditions.rechecks(clone, definition)` is the probe of
      every precondition kind (one `check(kind, …)` shared with `assess`), handed to the runtime by
      `ServeStartupGates.assessPreconditions` and `ServeRuntimeAssembly.assemble`; a probe that meets
      another kind's refusal first answers `RefusedElsewhere` (counted as a failed probe, the other
      kind opened through `DaemonConditions.report`). The per-kind codes GF174–GF181 (open,
      sustained-open) were taken here, not in 7.7, since the opening WARN needs them
      (`PreconditionConditionLog`); the precondition conditions write no ledger line until 7.7. The
      per-kind slot features live in `SlotConditionReportSpec` (`:bootstrap`, the real slot runner
      over the real container support and a scripted docker; four of five red with the report
      removed), not in `ServeRuntimeWiringSpec`: that spec drives `serve --drain`, which with an
      open condition and a Ready task never stops polling (see the finding reported with 7.5b).
- [x] 7.5c A held feed reads nothing and waits (D19 as amended 2026-10-25): `FeedCycle.held()`
      runs `DaemonConditions.probeDue()` (moved out of `FeedCycle.poll(Instant)`) and answers
      `anyOpen()`; `FeedAutomaton.step()` asks it after `SlotLedger.acquire()` and, when held,
      abandons the permit, reports `FeedState.IDLE_BLOCKED` to its view tracker with no
      `FeedStateLogger` line, and sleeps `idleTiming.jittered()`; `FeedAutomaton.drain()` breaks
      on a held cycle as on an empty poll; `claimOrAbandon` keeps its own `anyOpen()` check
      (counted toward `releasedClaims`). Verify: `FeedAutomatonSpec` or a new
      `FeedAutomatonHeldSpec` (markers "A held feed asks the tracker nothing", "A drain stops
      while the feed is held": a counting tracker, a counting sleeper, ten held steps make no
      tracker read and ten sleeps; a held drain returns), `FeedCycleSpec`,
      `RemoteOutageServeEndToEndSpec`, `DaemonConditionsSpec`; PIT scope
      `app.serve.FeedAutomaton,app.serve.FeedCycle`.
- [x] 7.6 The janitor reports instead of failing the tick (D21, FR17): the branch pass of the
      worktree janitor catches the typed refusals of 7.1 and 7.3 (the plan's runtime refusal
      among them) per branch, reports each to `DaemonConditions` and continues the pass; while
      a kind's condition is open the pass skips that kind's check for its branches (D21);
      the plan's other `UsageException`s and everything else still reach `SupervisedLoop`'s
      guard. Verify: `WorktreeJanitorSpec`
      or the branch-pass spec by name (markers "The janitor's refusal opens the condition and
      the pass continues"), `WorktreeJanitorLifecycleSpec` still green; PIT scope the janitor
      pass class.
      *Done 2026-10-25, as built:* D21's premise was stale — since fix-terminal-receipt-convergence
      `DeliveredBranchPass` already stops every branch failure at its branch with one `GF164` WARN
      per pass; only a failed listing reached the loop's guard. So a typed refusal, from the listing
      or a branch, is classified by `OperatorPreconditionRefusals.unmet` (now public; the rechecks of
      `ServePreconditions` use it too) and reported through `DaemonConditions.report`, never counted
      in `GF164`; while `DaemonConditions.openPreconditions()` is non-empty the pass waits with one
      DEBUG line naming the kinds (an open remote gate does not hold it); every other failure keeps
      its existing path (`GF164`, or the guard for the listing). Verify ran
      `DeliveredBranchPassConditionSpec` (new), the `DeliveredBranchPass*` specs,
      `WorktreeJanitor*` in `:bootstrap`; PIT 44/44.
- [x] 7.7 Observability of conditions (NFR-O2, UX4): `LedgerJsonMapper` writes the
      `daemonCondition` line (`condition` field; `target` for the remote) where it wrote
      `remoteOutage`, and `LedgerAggregator` reads both spellings, the old as `remote`;
      `SnapshotJsonMapper` writes the `conditions` section (the remote's entry first) and
      `SnapshotJsonReader` accepts `conditions` or a bare `remote`; the dashboard renders the
      section; `OperatorEvent` gains one open and one sustained-open code per new kind
      (the next free codes after `GF173`, which tasks 7.1 and 7.4 took for the release failure, the take and command lines and the startup line; the close reusing the recovery line's shape), listed in `logging.md`'s
      catalog; `manual-sync-pairs.md`'s two rows name the new tokens. Verify:
      `DaemonConditionKindRoundTripSpec` (every kind and state on ledger and snapshot, unknown
      token kept; markers "Every condition kind round-trips the snapshot", "A snapshot with
      only the remote section is read as one condition", "Old remoteOutage lines still
      aggregate", "A precondition episode is one line naming its condition", "A conditions
      entry names the precondition, not a task", "A precondition condition logs the fix once",
      "An open precondition is a dashboard field"), `LedgerAggregatorSpec`, the dashboard
      rendering spec by name; PIT scope the two mappers, the reader and the aggregator.
      *Done 2026-10-25, as built:* the codes were taken in 7.5b (GF174–GF181); the tokens have one
      owner, `ConditionTokens` (`serveobservability`), which the ledger mapper, the snapshot mapper
      and reader, the aggregator and the dashboard call — so the two declared pairs gain no token
      table of their own; the section entry `ConditionHealth` keeps the token as a string, so a
      newer factory's token survives a read; `Snapshot` keeps a constructor without the section
      that derives it from `remote` (the reader's path for an old document); the dashboard flags
      an open precondition as `AlertCondition.PreconditionOpen`, the remote keeping its
      `RemoteGateOpen`; `LedgerAggregator` counts only remote episodes toward the day's outages.
      `logging.md` holds no catalog list to extend. Specs: `DaemonConditionKindRoundTripSpec`,
      `ConditionHealthAssemblerSpec`, `RemoteOutageLedgerWriterSpec`, `LedgerAggregatorSpec`,
      `AlertConditionEvaluatorSpec`, the two reference anchors; PIT 195/195.
- [x] 7.8 The slot boundary writes its line (D20, FR18, M8): `TakeSlotRunner.run`'s
      `catch (Throwable)` writes a `taskOutcome` line with `outcome: aborted`, null `stage`,
      zero tokens and the wall time through a result-less entry of `TaskOutcomeLedgerWriter`
      before `SlotOutcomeLog.crashed`, only when the slot wrote no line yet, and guarded (a
      failed write is suppressed on the crash, which is still logged; D20); `LedgerAggregator`
      needs no change (it counts `taskOutcome` lines). Verify: `TakeSlotRunnerSpec` (an
      injected `Error` and an injected `RuntimeException` each leave one line; a `summarize`
      that throws after the write leaves one line, not two; a throwing writer still logs the
      crash; marker "A crashed slot leaves one line"),
      `ServeRuntimeWiringSpec` feature on the real ledger; PIT scope `app.serve.TakeSlotRunner`.
      *Done 2026-10-25, as built:* `TaskOutcomeLedgerWriter.writeCrashed(TaskRef)` shares the one
      append path with `write` (the slot-missing WARN and the append ERROR stay one site each);
      the slot's specs live in `SlotCrashLedgerLineSpec` (`:bootstrap`, the real slot runner and
      ledger): an `Error` and a `RuntimeException` each leave one line, a refusal whose report
      throws after the slot's own write leaves none (red with the flag lost), and a crash line the
      writer refuses is suppressed on the crash, still logged at ERROR.
- [x] 7.9 Docs: `operator-guide-serve.md` (startup assessment in the lifecycle section, the
      conditions in place of the remote gate, the dashboard section, exit code table unchanged
      — a refused startup is the existing 1 with the check's message); `operator-guide.md`
      (`take` exit code 8 in the reference table; the reftable remedy paragraph names the
      startup refusal); `docs/glossary.md`: **Daemon condition** (new, Base ref resolution or
      a new Serve readiness group), **Remote outage gate** re-pointed as the first condition;
      the principle that outlives the change (an operator precondition is assessed before
      the first claim; met after it, it is a daemon condition, never a task failure) is one
      paragraph in `docs/adr/0013-supervised-daemon-loop.md`'s neighbour, a new short
      `docs/adr/0019-operator-preconditions.md`, which the glossary entry and `design.md` D17
      and D19 reference instead of restating. Verify: the guide-table spec by name if one
      exists for `operator-guide.md`'s exit codes, else reading.
- [x] 7.10 Traceability for FR13–FR18, NFR-R2, NFR-R3, NFR-O2, NFR-O3, UX3, UX4: the loop of
      task 6.2 over the new ids; every scenario of the four new deltas and the revised
      execution-environment delta has a marker; `./gradlew checkSpecContract --change
      add-sandbox-base-image` green.
- [x] 7.11 Root `./gradlew check` green, once, after 7.1–7.10 (7.5a and 7.5b included) (`verification-scope.md`;
      `scripts/linux-specs.sh` first for any platform spec a task touched — none is expected).
      Verify the exit code. Then 6.4's replay (M6, M7).
