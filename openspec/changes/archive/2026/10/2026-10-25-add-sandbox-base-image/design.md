# Design: add-sandbox-base-image

## Context

Driven by FR1–FR12 of the proposal; D17–D21 by FR13–FR18, folded in on
2026-10-23 (serve readiness; the live incident in the proposal's Why). Current
state (verified 2026-10-22; the serve-readiness facts 2026-10-23):

- `docs/examples/sandbox-image/Dockerfile` builds `FROM eclipse-temurin:21-jdk-resolute`
  and carries the whole contract inline (user, `/gnomish/**`, `/etc/gnomish`,
  `/etc/claude-code/managed-settings.json`, proxy `ENV`, CA import with
  `keytool`, Maven settings), plus Node and the agent CLI; its git is the
  Ubuntu 26.04 archive's 2.53.0.
- `GuardCommands.PROXY_ALIAS = "gnomish-guard"` and `PROXY_PORT = 8080` are
  spelled again as the recipe's `PROXY_HOST`/`PROXY_PORT` build-argument
  defaults; a comment is the only link. `ContainerRunSupportFactory` in
  `:bootstrap` also spells `"gnomish-guard"`, as the name of the guard's
  temporary directory — a path, not the alias.
- `DeclaredVolumeOverrides.resolve(DockerCli, String image, Set<String>)` runs
  `docker image inspect -f '{{json .Config.Volumes}}'`
  (`DockerCommands.inspectImageVolumes`) once for the sandbox image
  (`ContainerMaterializer`) and once for the guard image (`EgressGuard`) before
  a box is created.
- `EnvironmentSelfCheck` runs inside a fresh box: the user id and the egress
  probes (the runtime half of the contract).
- `GitVersion` holds two floors: `FLOOR` 2.56.0 (the factory's own git) and
  `BOX_FLOOR` 2.51.0, read by `BoxGitFloor`, which runs
  `docker run --rm --network none <image> git --version` before every seed
  clone (design D11 of `own-git-invocation-policy`). No distribution archive or
  PPA ships 2.56.0 (Ubuntu 26.04: 2.53.0; the `git-core` PPA: 2.55.0, measured
  2026-10-21); CI and the Linux spec runner build it with
  `scripts/install-git.sh` (pinned kernel.org tarball, SHA-256 verified,
  `--prefix` selectable).
- Test images: five sandbox images on `alpine:3` (git 2.54.0) —
  `GitSandboxImage`, `EgressProbeImage`, `DeclaredVolumeSandboxImage`,
  `ContainerReadOnlySurfacesSpec` in `:adapters:git`, each built through
  `DockerImageBuilder`, and `FakeAgentSandboxImage` in `:bootstrap`, built by
  its own `docker build`; `JvmProxyEgressImage` re-types the recipe's JVM
  proxy plumbing on `eclipse-temurin:21-jdk-resolute`.
- The release workflow from `add-release-pipeline` is one job, `release`, on
  `v*` tags; its preflight (`scripts/release-preflight.sh`) is a step with the
  outputs `version` and `prerelease`.

## Decisions

**D1 — The base lives in `sandbox-image/` at the repository root.** Files:
`Dockerfile`, `bin/gnomish-trust-ca`, `bin/gnomish-verify-image`,
`image-contract.properties`. The recipe in `docs/examples/sandbox-image/` keeps
its place (operators copy from there; the release archive ships it) and becomes
`FROM ghcr.io/oinsio/gnomish-sandbox-base:${GNOMISH_BASE_TAG}` (FR1, FR9).
*Rationale:* the base is product source that CI builds; the recipe is an
example. Keeping them apart keeps the archive's `share/sandbox-image/` an
example only.
*Alternative rejected:* the base under `docs/examples/` — documentation paths
would become build inputs.

**D2 — One source for the cross-medium values: `image-contract.properties`.**
It holds `contract-version`, `guard-alias`, `guard-port`. The image build reads
it into build arguments (the Dockerfile declares them with no defaults and fails
on an empty one); Gradle copies it into `:sandbox:docker`'s resources, where
`ImageContract` loads it once; `GuardCommands` takes alias and port from
`ImageContract` (FR6).
*Rationale:* the alias and port pair has two ends in two media with no shared
classpath; adding the contract version would make it three values held by
comments. One file read by both sides removes the pair instead of declaring it.
*Alternative rejected:* a declared pair with an identity spec
(`manual-sync-pairs.md`, "no shared classpath" table, the `AdversarialGitConfig`
precedent) — acceptable by the rule, but a third value is arriving and the file
costs less than three synchronized spellings.

**D3 — One image inspection yields volumes and labels: `ImageFacts`.**
`DockerCommands` gains a single inspect of `{{json .Config}}`; `ImageFacts`
parses declared volumes and labels from it. `DeclaredVolumeOverrides.resolve`
takes `ImageFacts` instead of a `DockerCli` and issues no inspect of its own;
`ImageContractCheck` compares the sandbox image's label with
`ImageContract.version()` and throws a configuration error with the FR5 message
(FR5, NFR-R1, NFR-O1). The guard image is still inspected once for its volumes
— two images, two inspections per environment; the check reads only the
sandbox image's facts. `DeclaredVolumeOverridesSpec` builds `ImageFacts` from
the JSON it fed the fake runner before, so its scenarios stay green with their
input moved one step up.
*Alternatives rejected:* a second `docker image inspect` for labels (a runtime
call per box for data the first call already fetched); checking inside the box
(needs a container start, and a stale image could fail earlier in stranger
ways).

**D4 — Build-time and runtime checks split by what can change.**
`gnomish-verify-image` owns the filesystem contract (tools, ownership, write
permissions) — facts fixed at build time. `EnvironmentSelfCheck` keeps owning
the user a process runs as and the egress route — facts only a running box
shows. `BoxGitFloor` keeps owning the git version — a fact the factory's own
floor decides, checked where the factory is (D9). No item is checked by two
owners (FR3).
*Alternative rejected:* also checking `id -u` in the verify script — it runs as
whatever `USER` the build is at, so it would test the build step, not the image.

**D5 — `gnomish-trust-ca` replaces the inline CA loop.** It reads
`/etc/gnomish/ca/*.{pem,crt}`, installs them for `update-ca-certificates`, and
imports each into the JVM trust store with `keytool -cacerts` when `keytool` is
on `PATH` (FR2). The operator runs it after grafting a JDK; the base runs it at
its own build for the system store.
*Alternative rejected:* an `ONBUILD` trigger — runs at the wrong moment (before
the operator grafts the JDK) and surprises readers of the child Dockerfile.

**D6 — Publishing: the preflight becomes a job; the image job runs beside the
distribution.** The release workflow's preflight step moves into its own job,
`preflight` (same script, `contents: read` and `actions: read` only), exposing
`version`, `prerelease`, `base_tag` (D11) and `image_tags` (D15) as job outputs; the existing `release` job and the
new `image` job both `needs: preflight`, so a malformed tag stops both before
either builds. The `image` job holds `packages: write`, `id-token: write`,
`attestations: write` in that job only (NFR-S3): `docker/setup-qemu-action`,
`docker/setup-buildx-action`, `docker/login-action` to ghcr.io, a check that
`X.Y.Z` does not exist (`docker manifest inspect`, in `scripts/image-tag-refusal.sh`, D15),
`docker/build-push-action` for both platforms with the tags of the preflight's
`image_tags` output — `X.Y.Z` and `X.Y` for a final release, the full
`X.Y.Z-<pre>` tag alone for a pre-release, so `X.Y` never moves to a
pre-release; the script decides, the YAML holds no condition (D15) — and OCI labels,
`actions/attest-build-provenance` with `push-to-registry`, and
`osv-scanner scan image` uploading SARIF without failing the job (FR7, NG3).
`sandbox-image-rebuild.yml` runs weekly: finds the final release with the
highest version through `scripts/latest-final-release.sh` (D15: drafts,
pre-releases and tags not of the form `vX.Y.Z` left out, chosen by version,
never by creation date), checks out that tag, builds and pushes only `X.Y` the
same way (FR8); before any final release exists it skips the build.
`ReleasePreflightScriptSpec` drives the script, not the workflow; it gains the
`base_tag` (D11) and `image_tags` (D15) columns of its table, and the developer
guide's releasing section names the three jobs.
*Alternative rejected:* `image` with `needs: release` — serialises two
independent builds behind the distribution's time, and a distribution failure
unrelated to the image would leave the release's base unpublished.
*Alternative rejected:* re-pushing `X.Y.Z` weekly — a release tag must keep the
digest the release notes and attestations name.
*Alternative rejected:* a pre-release moving `X.Y` — operators of the final
`X.Y.0` building `FROM …:X.Y` would get the pre-release's base, and a contract
version raised in it would make their own factory refuse their fresh image.

**D7 — Every test sandbox image builds on the local base.** A test fixture in
`:test-fixtures` builds `sandbox-image/Dockerfile` once per JVM (build
arguments from `image-contract.properties` through the script of D16, a local tag) and both image
builders — `DockerImageBuilder` in `:adapters:git` and the `docker build` of
`FakeAgentSandboxImage` in `:bootstrap` — write `FROM` that tag;
`JvmProxyEgressImage` is deleted, its JVM scenario running on the base plus a
grafted JDK (FR10). The five `alpine:3` images lose their own user, git and
curl steps and keep only what their scenario adds.
*Rationale:* the base is the one image whose git meets the floor (D8, D9); the
test images must pass `BoxGitFloor` like an operator's, and building them on
the base is also the test of the base.
*Alternative rejected:* `alpine:edge` (git 2.56.0, measured 2026-10-20) — a
rolling base that moves under the suites, and a second spelling of the user and
surfaces the base exists to own.

**D8 — The base's git is built from the pinned upstream release, in a build
stage.** The Dockerfile has two stages: a build stage on `ubuntu:24.04` that
runs `scripts/install-git.sh --prefix /opt/git` (copied into the build context
from the repository, so the base and the factory's CI build the same tarball
with the same checksum), and the published stage, also `ubuntu:24.04`, that
`COPY --from`s `/opt/git` and installs only git's runtime libraries (`libcurl`,
`libpcre2`, `libexpat`, `zlib`, `ca-certificates`) with `apt`. The base stays
on Ubuntu 24.04 LTS as the design session agreed; only its git leaves the
archive (FR1, FR11, NFR-P1). The weekly rebuild (G3) rebuilds this stage too,
so the git stays at the pinned version while Ubuntu's packages move.
*Rationale:* no archive or PPA ships the floor (Context); the script already
exists, is pinned and verified, and the floor raise stays one edit
(`VERSION`/`SHA256` in the script, as D11 of `own-git-invocation-policy`
planned). A build stage keeps the compiler and headers out of the published
image.
*Alternative rejected:* `apt-get install git` from noble (2.43: refused by the
floor; both features of `JvmProxyEgressE2ESpec` failed this way on a noble
image, task 9.13f of `tighten-spec-contract`).
*Alternative rejected:* the `git-core` PPA — 2.55.0 on 2026-10-21, below the
floor, and a package source outside Ubuntu for G3's weekly rebuild to trust.
*Alternative rejected:* Ubuntu 26.04 as the base — its archive has 2.53.0, so
it does not remove the source build, and it leaves the LTS the session chose.

**D9 — One git floor: `GitVersion.BOX_FLOOR` is deleted.** `BoxGitFloor` reads
`GitVersion.FLOOR` and `FLOOR_REASON`; `BOX_FLOOR` and `BOX_FLOOR_REASON` are
removed, so the compiler lists every reader. The removal, the move of every
image the floor judges (D7, D8, the recipe) and the guide's text land in one
task, so no image is refused between the two steps (FR11, G4). The stable
requirement "The box's git meets the floor before the seed clone" is MODIFIED:
its scenarios stand, its Notes name one floor.
*Rationale:* the second constant existed only because the images could not
follow (NG5 of `own-git-invocation-policy`); once they do, two floors are two
places to raise.
*Alternative rejected:* keeping `BOX_FLOOR` as an alias of `FLOOR` — a second
name for one value, and the next reader cannot tell whether it may diverge.

**D10 — Scenarios a workflow verifies are exempted by name.** The scenarios of
"The base is published with the release and rebuilt weekly" are observable only
in a run of `release.yml` or `sandbox-image-rebuild.yml`; they are to be listed in
`config/spec-contract-exemptions/sandbox-sandbox-base-image.txt`, each naming
its workflow file (`testing.md`, "The exemption form"). Amended during apply
(task 5.1, 2026-10-23): the file is written **at archive**, in the step that
merges this delta into `openspec/specs/` (task 6.5 carries its full content),
not in tasks 5.1 and 5.2. Context: `checkSpecContract` accepts an exemption
file only for a capability a main spec already has
(`build-logic/src/main/groovy/com/github/oinsio/gnomish/buildlogic/ScenarioExemptions.groovy:50-53`,
"unknown spec id … no main spec has it"), while a marker resolves through an
ADDED delta, and a delta's scenarios need neither a marker nor an exemption
until they merge (`testing.md`, "Every scenario has a test");
`sandbox/sandbox-base-image` exists only in this change's delta, so a file
written before the archive fails the gate. Forgetting it at archive is a
visible failure: the archive's own gate run reports the three scenarios.
*Alternative rejected (2026-10-23, by the maintainer):* making
`ScenarioExemptions` accept a capability an unarchived ADDED delta declares —
it changes the spec-contract rule the `build-system/quality-gates` capability owns for one
change's timing, a scope this change does not own. Every other scenario of
the new capability has a Docker-gated spec: `BaseImageHelpersSpec` (helpers,
size target, no agent CLI or toolchain, the label), the recipe build in
`:bootstrap`'s container E2E layer. The manual runs in the publishing tasks are
acceptance, not the scenario's verification.
*Alternative rejected:* a spec that runs the workflow with `act` — a second
runner to keep in step with GitHub's, for scenarios whose subject is the
registry's state.

**D11 — One owner for the base tag of a release.** Added during apply (task
4.1, 2026-10-22); amended 2026-10-22 after the D11/D14 review (pre-releases).
The base tag a factory release names — in the contract refusal, the recipe's
default and the image job — is `X.Y` of a final release and the full version
of a pre-release (`0.4.1-rc.1`), since a pre-release never moves `X.Y` (D6);
a development build (`0.0.0-dev`) has none. Task 4.1 left the value derived
twice — `ImageContractCheck.releaseLine(FactoryVersion)` (private, a
`RELEASE_LINE` regex, empty for `FactoryVersion.DEVELOPMENT`) and
`def recipeBaseLine = productVersion == '0.0.0-dev' ? null : (productVersion =~ /^(\d+\.\d+)\./)…`
in `bootstrap/packaging.gradle`, both taking `X.Y` for a pre-release too — and
tasks 5.1 and 5.2 would add a third and a fourth in the workflows. Current
signatures: `public record FactoryVersion(String value)` with `current()` and
`readFrom(ClassLoader)`, reading `build.version` from
`META-INF/build-info.properties`; `product-version-conventions` sets
`version = providers.gradleProperty('releaseVersion').getOrElse('0.0.0-dev')`;
`scripts/release-preflight.sh <tag> <commit-sha>` runs the tag shape, the
reachability from `origin/main` and the `gh run list` CI query, then prints and
appends to `$GITHUB_OUTPUT` `version=` and `prerelease=` only (`prerelease` is
`true` for a tag with a `-` suffix). `ImageContractCheck(ImageContract, FactoryVersion)`
derives the line in its private `releaseLine(FactoryVersion)`, which tests
`equals(FactoryVersion.DEVELOPMENT)` first. `FactoryVersion` is constructed at
9 sites in 4 files (`FactoryVersion` itself, `FactoryVersionSpec`,
`ImageContractCheckSpec`, `BoxImageContractSpec`).
Decision:
- **The build computes the base tag once.** `product-version-conventions`
  derives it from the release version — `X.Y` from the leading `MAJOR.MINOR.`
  of a version with no `-` suffix, the whole version for one with a suffix —
  and exposes it to the project it is applied to as a provider that is absent
  for `0.0.0-dev`; a `releaseVersion` with no leading `MAJOR.MINOR.` fails the
  build naming the expected shape (the release workflow only ever hands it a
  version the preflight's tag shape accepted, so no real release meets it).
  `bootstrap/packaging.gradle` reads that provider for both of its uses:
  Boot's `buildInfo` additional properties get `baseTag` only when the
  provider is present — Boot's `MapProperty` refuses a `null` value, so a
  development build omits the key rather than writing an empty one — which
  Boot writes as `build.baseTag`; and the recipe stamp, which replaces the
  `ARG GNOMISH_BASE_TAG=` line in the shipped `README.md` as well as in the
  shipped `Dockerfile` (today only the Dockerfile is stamped, so the archive's
  README shows the repository default). The repository's own default stays a
  placeholder (single-owner row 4, exemption).
- **`FactoryVersion` carries the base tag it was built with.** It becomes
  `record FactoryVersion(String value, Optional<BaseTag> baseTag)` with
  `baseTag(): Optional<BaseTag>`; `BaseTag` is a new record in the same
  package holding either `X.Y` or a full pre-release version
  `X.Y.Z-<pre>` (its constructor refuses any other shape). The canonical
  constructor makes the inconsistent pair unconstructible: the base tag is
  absent exactly for `0.0.0-dev`; for a `value` with no `-` suffix it is the
  prefix of `value` up to its second dot, and for a suffixed `value` it equals
  `value`. `DEVELOPMENT` is the record with no base tag, so equality over both
  components changes nothing for it. `readFrom` reads `build.baseTag` beside
  `build.version`: a release version whose key is missing, malformed or
  inconsistent is a malformed resource, answered like every other one with
  `GF150` and `DEVELOPMENT` — never a release version without a base tag. That
  answer is loud where a release is made: the release workflow's archive check
  (`bin/gnomish --version` against the tag, FR8 of `add-release-pipeline`)
  then reads `0.0.0-dev` and fails the release.
- **`ImageContractCheck` takes the base tag, not the version.** Its
  constructor becomes `ImageContractCheck(ImageContract, Optional<BaseTag>)`;
  the remedy names `FROM …:<base tag>` when present and the local base
  otherwise (an absent base tag now means a development build by the record's
  invariant). With no `FactoryVersion` in reach, no consumer can parse
  `value()` for a tag.
- **The shell end has a pure mode.** `scripts/release-preflight.sh` computes
  the base tag right after the shape check from the tag pattern's groups —
  `MAJOR.MINOR` when the suffix group is empty, the tag without its `v`
  otherwise; the full run prints and outputs `base_tag=<base tag>` beside
  `version` and `prerelease`, and `scripts/release-preflight.sh --base-tag <tag>`
  runs the shape check only and prints `base_tag=<base tag>`, with no git and
  no `gh` call. The release `image` job reads the base tag from the
  `preflight` job's output; `sandbox-image-rebuild.yml` runs `--base-tag` on
  the highest-versioned final release's tag (D15), from the script of that tag's checkout, so
  it always prints `X.Y` there. Neither re-derives it.
- **The pair is declared and pinned by behaviour.** The script and the build
  share no classpath, so they are a declared pair (both ends carry "Kept in
  sync with"; registry row in `manual-sync-pairs.md`, "no shared classpath").
  The identity spec is `BaseTagIdentityFunctionalSpec` in
  `build-logic/src/functionalTest`: one data table of tags the script's
  `TAG_PATTERN` accepts (final and pre-release, multi-digit components, a
  dotted pre-release suffix such as `v0.4.1-rc.1`); for each, the script's
  `--base-tag` output equals the base tag a TestKit build of
  `product-version-conventions` with `-PreleaseVersion=<tag without v>`
  exposes. The script reaches the spec as a system property of the
  `functionalTest` task, declared as its input (the
  `gnomish.specContractLauncher` precedent). Versions the pattern refuses are
  out of the table: the script refuses them before any base tag, and the
  build refuses them by the rule above.
(FR5, FR7, FR8, FR9, UX2)
*Rationale:* four derivations of one value is past the rule of three; one
build owner plus one shell end keeps the workflow free of Gradle. The pure
mode keeps the weekly rebuild's freshness independent of checks that have
nothing to do with it (reachability from `main`, a CI run of a months-old
commit still listed by the API). A pre-release names its own full tag because
it is the only tag its base is pushed under (D6).
*Alternative rejected:* declaring the Java↔Gradle pair and deriving in each
workflow — four implementations. *Alternative rejected:* naming `X.Y.Z` in
the refusal and the recipe of a final release — contradicts UX2 and the
requirement's tag, pins operators to a tag the weekly rebuild never refreshes,
and the workflows still need `X.Y`. *Alternative rejected:* `X.Y` for a
pre-release too (the value task 4.1 derived) — it names a base the pre-release
never pushed, or one built from an older final release. *Alternative
rejected:* an identity spec comparing the source text of both ends (the
`TimeSourceOwnerBoundarySpec` precedent) — it checks two spellings, not the
values they produce. *Alternative rejected:* the weekly rebuild running the
full preflight on the old tag — it would need the whole history,
`actions: read` and a still-listed CI run of the tagged commit for a value the
tag alone decides.

**D12 — The Maven mirror stays an operator build parameter.** Added during
apply (task 4.1). The old recipe took `ARG MAVEN_MIRROR_URL`; the published
base bakes its build argument into the root-owned `settings.xml`, so an
operator using the published image could not choose a mirror. Decision: the
settings name `${env.GNOMISH_MAVEN_MIRROR}`; the base sets
`ENV GNOMISH_MAVEN_MIRROR` from `ARG MAVEN_MIRROR_URL` (default Maven Central);
an operator overrides it with one `ENV` line (sandbox-egress, "registry
endpoints as build parameters rather than hardcoded values"). The settings file
stays root-owned; the guard's allowlist, not the mirror, decides egress. The
settings line carrying `${env.GNOMISH_MAVEN_MIRROR}` is single-quoted in the
Dockerfile's `printf` (or its `$` escaped): inside double quotes the shell
reads it as a substitution and the build fails on `bad substitution`.
*Alternative rejected:* the baked value — an operator behind a private mirror
would rewrite a root-owned contract file. *Alternative rejected:* a helper
command that rewrites the settings — a third helper for one value an
environment variable already carries.

**D13 — Child builds download in their own stages.** Added during apply (task
4.1). The base's proxy `ENV` names the guard, which does not resolve at build
time, and a child cannot unset an inherited `ENV` with a build argument; a
network `RUN` in a stage `FROM` the base fails (measured with `curl`, exit 5).
Decision: the recipe downloads the JDK, Node and the agent CLI in stages of
their own and copies the results in; its final stage needs no network. A
download that cannot move to a stage of its own — operating-system packages,
which `apt` spreads across the filesystem and no single `COPY --from` carries —
is allowed in a stage `FROM` the base in one form only: the proxy variables are
unset for that one command, inside one `RUN`
(`RUN env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY apt-get …`).
That reaches neither the image's `ENV` nor `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`
or `MAVEN_ARGS`, and is written in the operator's own Dockerfile, where its
reader sees it. Setting or clearing a proxy variable with `ENV` stays
forbidden. No check catches an image that does — `gnomish-verify-image` reads
no proxy variable, and `EnvironmentSelfCheck`'s probes name the guard
explicitly (`curl -x`) — but it fails closed: the task network is
`--internal`, so a tool left without the proxy reaches nothing, and the egress
boundary does not depend on the variables. The README and the operator guide
state both forms (FR9, UX1).
*Alternative rejected:* forbidding operating-system packages in operator images
(a Non-Goal) — Ubuntu was chosen for the base partly for `apt-get`, and an
operator needing one package could not build at all (UX1).
*Alternative rejected:* a `SHELL` wrapper in the base that strips the proxy —
inherited by every child and used by shell-form `CMD`/`ENTRYPOINT` at run time,
hidden the way `ONBUILD` is (D5).

**D14 — The gnome's home is root-owned; only declared subdirectories are
gnome-writable.** Added during apply (task 4.2, 2026-10-22). Context, verified:
the user step of `sandbox-image/Dockerfile` hands the gnome its home and
Ubuntu's skeleton rc files in both of its branches — `usermod -l gnome -d
/home/gnome -m` when the image already has a uid-1000 user, `useradd -m -u
1000 gnome` otherwise; the box is long-lived, every
round is a `docker exec` (`DockerCommands.java:37,89,134`), the home sits in
the container's writable layer and survives rounds, parking and restart
(`ContainerMaterializer.java:77-88`), reset only by a fresh box
(`ContainerFreshSegmentSpec.groovy:101-120`). Claude Code sources `~/.bashrc`
or `~/.profile` into every Bash command and `claude -p` loads `~/.claude/*`;
the factory passes neither `--bare`, `--setting-sources` nor
`CLAUDE_CONFIG_DIR` (`AgentCommandLine.java:64-74`). The Dockerfile's comment
"The managed settings of the agent CLI override anything the gnome writes
under its home" is refuted: the file is written as `{}`
(`printf '{}\n' > /etc/claude-code/managed-settings.json`), and a managed
file overrides only the keys it sets. No factory process kills box
processes between rounds. So the scenario "Cage rules cannot be rewritten
from inside" (FR20, NFR-S2 of add-sandbox-core) does not hold for rc files,
and a root-owned file alone would not fix it: the gnome unlinks and recreates
it in a directory it owns. Decision (FR1, FR3, FR12, NFR-S2):
- `/home/gnome` is `root:root 0755` in both branches of the user step (neither
  `usermod -m` nor `useradd -m` creates or hands it over); `.bashrc`, `.profile` and
  `.bash_logout` are root-owned `0644` stubs (a comment line, no code). No
  other top-level file can be created (`.bash_profile`, `.bash_aliases`,
  `.gitconfig`, `.curlrc`, `.npmrc`, `.mavenrc`, `.claude.json`).
- **One declaration of the writable list.** `gnomish-home-dir <name>…`
  (root-run, root-owned helper) creates `~/<name>` owned by `gnome` and
  appends the name to the root-owned `/etc/gnomish/home-dirs`. The base calls
  it for `.claude` and `.cache`; an operator layer calls it for its
  toolchain's directories (the recipe: `.m2`, `.gradle`, `.npm`). Nothing
  else spells the list: `gnomish-verify-image` reads the file.
- `/etc/claude-code/managed-settings.json` is `{"allowManagedHooksOnly": true}`
  (hooks from `~/.claude/settings.json` and from the working copy's
  `.claude/settings.json` no longer run); the comment quoted above is
  rewritten to say what a managed file overrides. Not `allowManagedPermissionRulesOnly`: the factory
  grants tools with `--allowedTools`/`--disallowedTools`
  (`AgentInvocationOptions.java:43-45`), which that mode would likely void.
- `gnomish-verify-image` adds the home rule. It runs as root in the base and
  in the recipe and as either user in its spec, and its header promises the
  same answer whichever user runs it, so it never reads `$HOME` (`/root` under
  root, where the rule would always pass): the home is the `gnome` user's
  home from the user database (`getent passwd gnome | cut -d: -f6`), and a
  home other than `/home/gnome` is a violation of its own. That home and
  every top-level entry in it are owned by root and not writable by group,
  others or `gnome`; the gnome-writable directories directly under it are
  exactly the entries of `/etc/gnomish/home-dirs`, each a gnome-owned
  directory.
- `gnomish-verify-image` also fails when
  `/etc/claude-code/managed-settings.json` does not set `allowManagedHooksOnly`
  to `true` (a POSIX `grep -Eq '"allowManagedHooksOnly"[[:space:]]*:[[:space:]]*true'`),
  the violation naming the file and the key. An operator layer running as
  root can replace the file with its own policy and keep the owner and mode
  bits the existing rule checks; the key's value is a fact fixed at build
  time, so it belongs to the build-time owner (D4) and gains no second owner.
  An operator with a managed policy of its own keeps the key (the README and
  the operator guide say so). The `grep` does not parse JSON (a duplicated key
  defeats it); it catches the accidental overwrite, which is the failure.
- The self-check probes' `curl` (`EgressSelfCheckProbes.java:53,79,124`,
  run again on every reattach, `SelfCheckedEnvironment.java:77-80`) passes
  `-q` first, so no `~/.curlrc` an operator layer bakes can change a verdict.
- The stable "Read-only control surfaces inside the box" requirement is not
  modified: the code is brought to it for shell startup files and top-level
  home entries. The agent-CLI user configuration under `~/.claude` (its
  `settings.json` above all) still does not meet the scenario "Cage rules
  cannot be rewritten from inside": it stays gnome-writable, deferred by NG7.
  That remainder is pinned in the test tree, not only in the threat registry:
  a feature asserting that a write to `~/.claude/settings.json` fails stands
  under `@PendingFeature(reason = "NG7 of add-sandbox-base-image: …")` with
  the scenario's marker (`testing.md`, "Every scenario has a test"). The
  base-image capability adds the promise (no gnome-writable top-level home
  entry; the declared list; managed hooks only) because the helper and the
  verify rule are operator-facing contract.
- Left inside the declared directories (`~/.claude/*`, `~/.gradle/init.d`,
  `gradle.properties`) and leftover processes: NG7, NG8; threat rows #5 and
  #39 record it.
*Alternative rejected (alternative zero, "Z"):* narrow the requirement to
`/etc/*` with no code change — every instruction a round plants persists into
later rounds, resumes and command checks of the box, outside the diff; the
duty would be lost silently and nobody would carry it.
*Alternative rejected ("R"):* root-owned rc files in a gnome-owned home — the
gnome unlinks and recreates them; a write test is green, an unlink test red.
*Alternative rejected:* `HOME` on a tmpfs — it lives until the container
stops, not for one round (`DeclaredVolumeOverrides.java:60-65`), so rounds
are not reset.
*Alternative rejected:* the Dockerfile and `gnomish-verify-image` each
spelling the list — two ends, and an operator's directories would need a third.

**D15 — Workflow logic lives in scripts with specs; the YAML only calls
them.** Added during apply (2026-10-22, before task 5.1); amended 2026-10-22
after the D15 review (three outcomes of the tag check, the selection rule of
the rebuild, the push → attest window, the tag list). Context: the release
and rebuild workflows (D6, D11) carry four decisions besides the preflight —
which image tags a release pushes, refusing a push over an existing image tag,
choosing the highest-versioned final release for the weekly rebuild, and checking that
the unpacked archive's `share/sandbox-image/Dockerfile` and `README.md` carry
the release's base tag — and a step written in YAML is verified only by a run
of the workflow (D10's exemptions; the dry run on a test tag). Decision (FR7,
FR8, FR9; D11; single-owner row 4): each decision is a POSIX shell script
under `scripts/`, taking its inputs as arguments, so a Spock spec in
`:bootstrap`'s `distribution` package drives it over stand-in presets
(`testing.md`, "Stand-ins are prepared, not generated"; new presets in the
committed library, covered by `StandInLibrarySpec`):
- **The tag list: `scripts/release-preflight.sh`.** Beside `base_tag` it
  outputs `image_tags`, a JSON array derived from the `version` and `base_tag`
  it already computed (no second derivation): `["X.Y.Z","X.Y"]` for a final
  release, `["X.Y.Z-<pre>"]` for a pre-release, whose base tag is its full
  version (D11). The `image` job turns it into `tags:` with one fixed
  expression and no condition (`${{ env.IMAGE }}:${{ join(fromJSON(needs.preflight.outputs.image_tags), format(',{0}:', env.IMAGE)) }}`).
  `ReleasePreflightScriptSpec` gains the `image_tags` column of its table;
  `BaseTagIdentityFunctionalSpec` is untouched (the `--base-tag` mode prints
  `base_tag` only).
- **`scripts/image-tag-refusal.sh <image> <tag>`** reaches `docker` through
  `PATH` and has three outcomes. `docker manifest inspect` succeeds: the tag
  exists, exit 1 with an `::error` naming the tag and the recovery below. It
  fails with the registry's answer that no such manifest exists (the stderr
  texts recorded from ghcr in task 5.1's dry run, for a missing tag in an
  existing package and for a package not yet created, kept under the
  library's `data/`): exit 0. It fails any other way (login, network, rate
  limit, denied access): exit 2 with an `::error` naming the tag, the captured
  stderr and "re-run once the registry answers" — the job stops, nothing is
  pushed. A failure is never read as an absent tag, which would overwrite a
  release tag with a green build (the reason D6 refuses re-pushing `X.Y.Z`).
- **The push → attest window.** The `image` job's durable steps are the push
  of the tags (one `docker/build-push-action` push), the attestation pushed to
  the registry, and the scan report. A run killed or failed after the push
  leaves the tag without its attestation or report, and a re-run stops at the
  refusal, since the tag exists. The recovery owner is the releaser, by hand:
  the refusal message says that a tag left by an earlier run of the same
  release is not deleted but attested by its digest in a separate run, and
  the developer guide's releasing section (task 6.1) gives the steps (read the
  digest with `docker buildx imagetools inspect`, attest that subject digest
  with `push-to-registry`, then scan it). A missing `X.Y` after an
  interrupted final release is converged by the next weekly rebuild (FR8).
  The refusal logic itself is unchanged; a refusal that compares the existing
  image's revision label with the commit was considered and rejected by the
  maintainer (more workflow steps and a fourth outcome for a rare window whose
  failure is already visible).
- **`scripts/latest-final-release.sh`** reads the raw JSON of `gh release list
  --exclude-drafts --exclude-pre-releases --limit 1000 --json
  tagName,isDraft,isPrerelease` and selects itself, outside `gh` (`jq` over the
  JSON, then a numeric sort; never a `--jq` expression, which a stand-in
  preset answering by argv prefix could not evaluate): drafts, pre-releases
  and tags not of the form `vX.Y.Z` are left out, and the highest version
  wins — never the latest creation date, so a later patch of an older line
  (`v0.1.5` after `v0.2.0`) does not take the rebuild from the current one.
  Outcomes: a tag, printed and output as `tag=`, exit 0; no final release,
  exit 0 with a `::notice` and an empty `tag=`, and the workflow skips the
  build (so the schedule is not red every week before the first release);
  `gh` fails, exit 1 with an `::error` naming the failure and "re-run". The
  preset answers raw JSON from the library's `data/`.
- **`scripts/check-recipe-stamp.sh <unpacked archive> <base tag>`** reads
  files only and fails naming the file whose `ARG GNOMISH_BASE_TAG=` line
  differs (FR9, D11).

The workflows call the scripts and keep no logic of their own; `actionlint`
runs in `ci.yml` over every workflow file, at a pinned version and before
`./gradlew check`, so a malformed step fails before a tag is pushed. The dry
run of task 5.1 and the dispatch of 5.2 stay acceptance (D10), not the
verification. *Rationale:* a script has a spec that runs on every push; a YAML
step has one manual run per change, and its second edit has no test until the
next release. *Alternative rejected:* the logic in YAML, verified by the dry
run — the state after the next edit is unverified.
*Alternative rejected:* running the workflows under `act` — rejected in D10.

**D16 — One owner turns the contract file into build arguments:
`scripts/image-contract-build-args.sh`.** Added during apply (task 5.1,
2026-10-23). Context (FR6, FR7, FR8, FR10): three readers turn
`sandbox-image/image-contract.properties` into the base's build arguments, each
holding its own key → argument table. Task 5.1 wrote the second one, the release
`image` job's step "Read the image contract into build arguments":

```sh
sed -n -e 's/^contract-version=/CONTRACT_VERSION=/p' -e 's/^guard-alias=/GUARD_ALIAS=/p' \
  -e 's/^guard-port=/GUARD_PORT=/p' sandbox-image/image-contract.properties
```

written between `echo 'args<<CONTRACT'` and `echo 'CONTRACT'` into
`$GITHUB_OUTPUT` and consumed as `${{ steps.contract.outputs.args }}` in
`docker/build-push-action`'s `build-args:` list. The first is the E2E base fixture,
`SandboxBaseImage` in `:test-fixtures`:
`static final Map<String, String> CONTRACT_ARGUMENTS = [CONTRACT_VERSION: 'contract-version', GUARD_ALIAS: 'guard-alias', GUARD_PORT: 'guard-port'].asImmutable()`,
read by `static Map<String, String> contractValues(Path source)` (it keeps the
`key=value` lines, asserts each key is present and non-empty, and returns the
argument → value map `ensureBuilt()` turns into `--build-arg` pairs). Task 5.2's
`sandbox-image-rebuild.yml` would be the third — the rule-of-three trigger of
`manual-sync-pairs.md`, and a YAML step D15 says carries no logic. Decision: one
POSIX shell script, `scripts/image-contract-build-args.sh [<properties file>]`
(default `sandbox-image/image-contract.properties`, relative to the working
directory), prints one `NAME=value` line per `key=value` line of the file, in
file order — the newline-separated form `docker/build-push-action`'s `build-args`
input takes and the `args<<CONTRACT` output already carries. The argument name is
derived by **rule, not table**: the key upper-cased, each `-` replaced by `_`
(`guard-port` → `GUARD_PORT`). Comment and blank lines are skipped; any other line
not of the form `[a-z][a-z0-9-]*=…`, a key with an empty value, and a value that
begins or ends with a whitespace character (a space, a tab, or the `\r` a CRLF
line ending leaves) exit 1 with an `::error` naming the file and the line. The
whitespace refusal is visible on purpose: `ImageContract.text` strips the value
it reads (`value.strip()`), while `ImageContractCheck` compares the label with
the required version exactly, so a stripped hidden tail on one side and a kept
one on the other would build a base every box is refused on; refusing the line
keeps every reader of the file in agreement or the build red. All three readers call it: the release
`image` job and `sandbox-image-rebuild.yml` capture its output (a non-zero exit
fails the step, never an empty `args`), and the fixture runs it once per JVM in
`ensureBuilt()` and turns each line into a `--build-arg`. The fixture names both
paths absolutely from `repoRoot()` (`sh <root>/scripts/image-contract-build-args.sh
<root>/sandbox-image/image-contract.properties`), since a test JVM's working
directory is its module, not the repository root, and launches it the way
`TestDockerCommand` launches `docker`: environment through
`TestChildEnvironment.cleared`, standard input from `/dev/null`, a non-zero exit
an assertion carrying the script's output; `CONTRACT_ARGUMENTS` and `contractValues` are deleted. The fixture's
independence from `ImageContract` (the other half of the one-source claim) holds:
the script reads the source file, not the factory's reader.
`ImageContractBuildArgsScriptSpec` in `:bootstrap`'s `distribution` package drives
the script over the committed source file (its three lines, in order) and over
input files the spec writes (data, not executables): comments and blank lines
skipped, a new key yielding its derived name with no edit to the script, an empty
value, a malformed line, a value with a trailing space, a value with a leading
space and a line ending in CRLF each refused naming the line, the default path.
The base's comment table of key → argument (the `sandbox-image/Dockerfile`
header) and the release `contract` step's comment that points at it give way to
the script and its name rule, so no spelling of the table survives even in a
comment. The scan that keeps the workflows and the fixture free of the keys and
argument names lives in its own `architecture` spec,
`ImageContractBuildArgsSpellingSpec` in `:bootstrap`, built on `RepoSourceTree`
beside `ImageContractSourceSpec`, which is already near the file-size cap.
*Rationale:* alternative zero — with the name derived by rule there is no table
left to synchronize; a key added to the file reaches every build without an
edit, and the Dockerfile, which declares each argument with no default and fails
on an empty one, stays the check that the names match.
*Alternative rejected:* an identity spec pinning the fixture's table to the
script's output (the declared-pair form) — it keeps two tables when the fixture
can call the script for one `sh` process per JVM.
*Alternative rejected:* spelling the keys as argument names in the properties file
(`CONTRACT_VERSION=1`) so the workflows pass the lines through — the comment filter
and the empty-value refusal still need a step, so a script remains, and the
change renames the keys `ImageContract` already reads.
*Alternative rejected:* a key → argument table inside the script — a fourth
spelling of the key list beside the file, `ImageContract` and the Dockerfile's
`ARG`s, which the rule makes unnecessary.
*Alternative rejected:* a Gradle task printing the arguments — the `image` job and
the rebuild would need a JDK and the build for a three-line read.


**D17 — Static operator preconditions are assessed once, at `serve` startup,
by the checks the per-task path already runs.** (The principle that outlives this change —
assess before the first claim, a typed result after it, a daemon condition under a running daemon
— is `docs/adr/0019-operator-preconditions.md`; D17–D22 are its local decisions.) Today the factory's own git is
checked before any subcommand dispatches (`ManualRunRunner.run`:
`gitVersionCheck.verify()`), while every other operator precondition is met per
task, after the claim: the clone's ref format (`FactoryCloneRefFormat.require(Path cloneDir)`,
called from the touchpoint fetch, the branch classification and the locate), the
sandbox plan (`SandboxModeSelector.plan(PipelineDefinition, RegisteredClone)`
throwing `UsageException` from `TakeWorkRouter`), the image contract
(`ImageContractCheck`, D3) and the box's git floor (`BoxGitFloor`, D9). `run`
pre-checks the plan (`ManualRunners`), `serve` does not. The decision: one
startup owner, `ServePreconditions` in `:application`, runs after the tracker
section is required and before the label-provisioning smoke test
(`ServeCommand.run`: between `requireTrackerConfig` and `provisionTracker`),
each check in the order a slot would meet them — ref format, plan, image
contract, box git floor — through the ports the per-task path already reaches
them by, with the daemon's clone, definition and configured image: the ref
format through `TaskBranchGit.requireRefFormat(Path cloneDir)`
(`app/port/git/TaskBranchGit.java:33`; `FactoryCloneRefFormat` itself is
package-private in `:adapters:git`), the plan through `SandboxModeSelector.plan`,
and the image contract and the box git floor through a new port,
`SandboxImageReadiness.assess(String image)` in `app/port/run` beside
`ContainerRuntimeProbe`. Its adapter in `:sandbox:docker` runs one `ImageFacts`
inspection, then `ImageContractCheck.require(String key, ImageFacts facts)` and
`BoxGitFloor.require(String image)` — both package-private there, built today
per environment inside `ContainerMaterializer(DockerCli, String key, Path,
TaskContainerSettings, ObjectOwnership, ImageContractCheck)`, and in a module
`:application` does not depend on (`application/build.gradle`, the layering
gate). The adapter's floor helper carries a fixed environment key of its own
and the daemon's ownership labels, so the sandbox lifecycle sweep recognizes it
like any helper. The first refusal ends the process as a startup failure
(FR13, UX3, NFR-R3). The per-task checks stay: what can change while the daemon
runs is D19's, and only one answer cannot — whether an image is configured,
which the bound configuration fixes. The ref format can (an operator migrates
the clone, M7), the runtime can (Docker stops), and the contract and the floor
depend on an image the operator can rebuild; so each runs at startup *and* per
task, and each is a condition of D19. *Rationale:* a precondition the operator can fix once is reported once,
before the first claim, where the git version check already set the precedent;
a daemon that claims first and refuses second is the incident. The box git
floor's startup probe starts one container; that is one start per daemon
start, not per task, and it replaces a claim that would have been returned.
*Alternative zero:* no startup assessment — every refusal met in a slot (D18,
D19). What is left per run is a claim and a release per slot before the
condition opens, one task's worth of label churn per daemon start, and an
operator who learns the precondition from a slot's log instead of the
command's exit; rejected because the first claim is the thing G2 promises to
avoid. *Alternative rejected:* a second spelling of each check inside the
startup owner (a `git rev-parse` of its own, a `docker info`) — two owners of
one fact, the pair `manual-sync-pairs.md` forbids; the owner calls the check.
*Alternative rejected:* a non-refusing report of every precondition (the
`doctor` shape) — that is `add-doctor-command`'s (NG10), which builds its
assessment on this owner; a startup that reports and continues would claim
anyway.

**D18 — A precondition refusal after a claim is a typed result, not a rethrow.**
`ClaimedRunFailureDispatch.onFailure(TakeOrder order, RuntimeException failure)`
rethrows `CloneRefFormatRefusedException`, `BoxGitVersionRefusedException` and
`ImageContractRefusedException` after `releaseBestEffort`, on the stated reason
that the exit code is "the whole point of rethrowing". Verified false for these
three: `RunExitCodeMapper` maps them to the default 1 and `TakeExitCodeMapper`
never sees them, so the rethrow preserves nothing and costs every sink —
`TakeSlotRunner.run` reaches `outcomeLog.detail`, the `DrainReport`, the
`RunSummaryAccumulator` and `TaskOutcomeLedgerWriter.write` only on a returned
result, and a thrown one lands in `catch (Throwable)` as `GF076`. The decision:
a new variant `TakeResult.OperatorPreconditionUnmet(UntrustedText reason,
DaemonConditionKind kind)` beside `InfrastructureUnavailable(UntrustedText
reason)`; the three refusals and D19's `ContainerRuntimeUnavailableException`
map to it, and the claim is released by the one owner of an outage release,
`ClaimOutageRelease` (today `release(…)` returns `InfrastructureUnavailable`,
`ClaimOutageRelease.java:53`; it gains the form that returns the new variant),
so the switch to the fenced return that `add-claim-return` makes there (its
F59; NG11) covers this arm too; no abort marker, backoff or fuse is touched;
`take` exits 8, `run` exits 8 through `RunExitCodeMapper` for the same four
exceptions — for an unreachable runtime `run` exited 2 before this change
(Migration Plan); batch and serve receive the result like any other
(FR14, NFR-O3). `UsageException` and `DivergedBranchException` keep the rethrow
arm: their codes (2, 5) exist and are read. *Rationale:* a result is what
every sink reads; an exception is control flow by exception (Bloch, Item 69)
that only the one caller with a `catch` understands. The code 8: the family
rule of `manual-run` puts "the tool could not operate" below 10; `sysexits`
names this case `EX_CONFIG`; 8 is the lowest free number that is not the
retired 4, which the spec says is never returned and old scripts may still
treat as the interactive-EOF it was. *Alternative rejected:* reuse 16
(`InfrastructureUnavailable`) — a dead remote recovers by itself and an
operator precondition does not; a script that retries on 16 would retry a
`files` clone forever. *Alternative rejected:* code 4 — fills the gap the
`remove-interactive-console` requirement promises stays a gap; one requirement
less to modify is not worth a code whose old meaning scripts may remember.

**D19 — Daemon conditions: the remote outage gate becomes one of several, with
one owner.** `RemoteOutageGate` (`openOnFailure(String)`, `onSuccessfulRefresh()`,
`probeIfDue()`, `isOpen()`, `health()`; built by `RemoteOutageGates.forServe(BaseRefGit,
Path, ServeProperties, InstantSource, DirtyNotifier, RemoteOutageLedgerSink)`)
already is the shape FR15 asks for, for one precondition: a slot's signal
opens it, `FeedCycle.claimOrAbandon` reads `isOpen()` before `FeedTracker.claim`,
`FeedCycle.poll` drives `probeIfDue()` every cycle, the transition is logged
once with roll-ups between, a snapshot section and a ledger line record it. The
decision: the gate's state machine, schedule (`RemoteOutageProbeSchedule`),
log edges, suppressor and three-phase probe stay as they are and become one
`DaemonCondition` instance each, keyed by `DaemonConditionKind` (`remote`,
`containerRuntime`, `cloneRefFormat`, `imageContract`, `boxGitFloor`); the
owner `DaemonConditions` holds the set, answers the feed's one question
(`anyOpen()`), advances every due probe, and receives the typed refusal a slot
(`TakeResult.OperatorPreconditionUnmet`, D18) or the janitor (D21) reports. A
condition's probe is the check that opened it, run again against the daemon's
clone, definition or image (D17's checks, so startup, slot and probe are one
spelling); the remote keeps its `ls-remote`. Evidence rule (NFR-R2): a
condition opens only on the typed refusal its check throws; an unreachable
daemon is already told apart: `DockerCli.run` throws `DockerUnavailableException`
on a launch failure or a daemon-unreachable stderr (`DockerCli.java:124-128`),
so `BoxGitFloor` cites as a version refusal only a probe that ran and exited
non-zero (an image the runtime could not start, no `git`: 125/126/127), which is
the image's; the one materialize entry, `ContainerTaskExecutionEnvironment.materialize`
(its container-state inspect, `ContainerTaskExecutionEnvironment.java:107`, is the
first docker call of both `create` and `reattach`), maps the positive
`DockerUnavailableException` to `ContainerRuntimeUnavailableException` on every
materialize, nothing else does; a materialize after the first round still ends
as cannot-execute through `RoundExecution`'s catch-all, whatever the type
(corrected 2026-10-24, D22); everything untyped stays in `TakeCrashAbort`. The runtime is first met by the plan, not by the
box: `SandboxModeSelector.requireContainerPrerequisites` refuses an unreachable
runtime with a `UsageException` (`SandboxModeSelector.java:167`), and
`TakeWorkRouter` plans before any materialize (`TakeWorkRouter.java:77`, `:91`),
so that refusal becomes the same typed `ContainerRuntimeUnavailableException`
the box path throws; the plan's other refusals (no image configured, mixed
bindings, an unmet stage need) stay `UsageException`, configuration the
startup assessment refuses. One failure, one log (`logging.md`): the checks
throw without logging — `FactoryCloneRefFormat` (`GF162`), `BoxGitFloor`
(`GF163`) and `ImageContractCheck` log their own ERROR today — and the layer
that decides writes the line: the startup assessment once; the command a
refusal ends (`take`, `run`) once, beside the message it prints; under `serve`
the condition (its opening WARN with the fix, probes at DEBUG with the gate's
roll-up, its closing INFO), while the slot's outcome log keeps the result at
DEBUG, as it does for `InfrastructureUnavailable` (`SlotOutcomeLog.java:93`);
each moved line takes a catalog code of its own at its new call site, and the
codes left without a site retire. The release is the lease path's (NFR-R2 of
`release-vs-return` memory stands: the reaper returns after TTL); what stops
the re-claim loop is the open condition, not the release. *Rationale:* the
incident's loop exists because the feed reads nothing but the remote gate and
its own slot occupancy; the daemon already owns the right state for one
precondition, and a second copy of it per precondition is the twin
`manual-sync-pairs.md` forbids. *Alternative zero:* no conditions — startup
assessment (D17) plus the typed result (D18) alone. What is left per episode is
a claim and a release per free slot per TTL for as long as the precondition is
broken under a running daemon (the operator rebuilt the image, Docker stopped):
a bounded churn, visible in the ledger. Rejected because it is exactly the
incident's loop with a better log line, and because the probe that ends it is
the check the daemon already has. *Alternative rejected:* a second gate class
beside `RemoteOutageGate` for the new conditions — two state machines, two
schedules, two log vocabularies for one behaviour. *Alternative rejected:*
opening a condition on any `RuntimeException` from a slot, as
`FeedOutageRetry.run` does for the tracker — a bug would hold the feed off the
tracker with no abort recorded; AIP-97's rule (infrastructure only on a
positive signal, else unclassified) is the one kept.
*Amended 2026-10-25 (task 7.5b found, 7.5c fixes):* a held feed reads nothing from the
tracker and waits. Measured before the fix: with any condition open and a Ready task,
`FeedAutomaton.step()` polled the tracker (`listReady`, `listOpen`), `FeedCycle.claimOrAbandon`
abandoned the permit and `step()` returned `FILLING` with no sleep, so `run()` polled again at
once (10 steps, 10 polls, 0 sleeps), and `drain()` never stopped; true of the remote gate since
add-base-ref-resolution, and reachable for hours once a precondition can hold the feed. The
decision: `FeedCycle.held()` advances every due probe and answers `DaemonConditions.anyOpen()`
before the tracker is read; a held `step()` abandons its permit, reports `IDLE_BLOCKED` to the
view, writes no feed line (the condition's opening WARN is the operator's line) and sleeps the
idle interval; a held `drain()` stops claiming as on an empty poll. `claimOrAbandon` keeps its
check for a condition that opens between the poll and the claim, and only that abandon counts
toward `releasedClaims` (a permit given up with a candidate in hand). *Alternative rejected:*
keep the poll and add the sleep — one tracker read per interval for the whole episode, for
candidates nothing may claim. *Alternative rejected:* a new feed state `HELD` — the sync pair
`FeedState` ↔ `FeedPhase` would change for a reason the snapshot's `conditions` section already
carries.

**D20 — The slot boundary writes its own ledger line.** `TakeSlotRunner.run`'s
`catch (Throwable crash)` calls only `SlotOutcomeLog.crashed(TaskRef, Throwable,
Duration)`; the `TaskOutcomeLedgerWriter` and the dashboard's
`LedgerAggregator` (which counts aborted only from `taskOutcome` lines) never
learn the slot ended. The decision: the boundary writes a `taskOutcome` line
with `outcome: aborted`, `stage` null, zero tokens and the wall time, through
the same writer, before it logs `GF076` — only when the slot has written no
line yet (a sink that throws after `TaskOutcomeLedgerWriter.write(TaskRef,
TakeResult)` must not leave two), through an entry of the writer that takes no
result, and guarded: a failure of that write is suppressed on the crash, which
is still logged; the requirement "taskOutcome lines for
terminal results carrying a final state" is MODIFIED to say so (FR18, M8).
With D18 in place this arm is reached only by an unclassified throwable, which
is what `GF076` then means (NFR-O3). *Rationale:* a ledger that misses the
slots that ended worst is the wrong ledger to count aborts from. *Alternative
rejected:* a new `taskOutcome` value `crashed` — a reader change for a case the
abort protocol already names; `aborted` is what the task's accounting records
when the crash arm runs. *Alternative zero:* leave the line out and count
`GF076` lines — the log is not the ledger (serve-observability, "Ledger is
disposable, write-only history"), and the dashboard reads no log.

**D21 — The janitor's precondition refusals are conditions, not failed ticks.**
`ClaimlessDeliveredCleanup.converge` runs `branches.locate` and `classifyShape`
(the ref-format check inside) and `containers.modeSelector().plan(definition,
clone)` (a `UsageException`) per branch; every throwable reaches
`SupervisedLoop.tickGuarded` as the generic `DAEMON_LOOP_TICK_FAILED`. The
decision: the janitor's branch pass catches the typed refusals (D18's set, the
plan's runtime refusal included) per branch, reports each to `DaemonConditions`
(D19) and continues the pass; while a kind's condition is open the pass skips
the check that kind owns for its branches rather than meeting it again, so the
condition logs the edge once and the next tick names the condition; the plan's
other `UsageException`s are configuration the startup assessment refused, and
reach the loop's guard with everything else (FR17). *Rationale:* the janitor is the one
daemon duty that meets a precondition without a claim, so it is the earliest
signal the daemon gets after startup; a generic failed tick every minute is
the suppressed-roll-up version of the incident. *Alternative rejected:*
letting the loop guard classify — the guard is one owner for every loop and
knows no precondition.

**D22 — On every container path the first box is live before the engine runs
(amends D18, D19; added 2026-10-24 during task 7.3).** D18 and D19 rest on the
premise that a box refusal reaches `ClaimedRunFailureDispatch.onFailure(TakeOrder,
RuntimeException)` and `RunExitCodeMapper`. Verified true only for resume, which
makes the box live before the engine
(`ContainerResumePreparation.prepare(SandboxRunSupport, boolean, Position, String)`
→ `SandboxRunSupport.reattachFor(String stage)`, `ContainerResumePreparation.java:52`).
On a fresh claim (`TakeContainerFreshClaim.claim(TakeOrder, List<Segment>)`) and
a fresh run (`ContainerGitModeRunner.run(RunOrder, List<Segment>, TaskContext,
TaskState)` → `ContainerTerminalDrive.run(…)`, which only sweeps orphans first)
the first box is materialized lazily, by the first round's
`SandboxRoundEnvironmentSource.openRound` (`lease.environmentFor(stage)`, `:93`,
from `CliStageExecutor.execute`, `:205`). That call sits inside
`RoundExecution`'s catch-all (`RoundExecution.java:108-126`), which turns every
`RuntimeException` into `RoundOutcome.CannotExecute`. So a refused image or an
unreachable runtime parks the task as INFRA and exits 13, instead of ending in
`OperatorPreconditionUnmet`; under `serve` it parks one ready task after
another. The decision: both fresh paths, once the task branch exists (the seed
clone reads it) and before the engine runs, make the first stage's box live
through the same port step resume uses, `reattachFor(firstStage)`. The first
round's `environmentFor` then returns the recorded box (`LiveBox`, one key per
segment), so nothing is materialized twice. An operator precondition refusal,
decided by one classifier, `OperatorPreconditionRefusals.kindOf(RuntimeException)
→ Optional<DaemonConditionKind>` (the four types of D18;
`ClaimedRunFailureDispatch`, `RunExceptionReporting` and `RunExitCodeMapper`
read it in place of their own lists), leaves the fresh path to the dispatch or
the command. Any other failure is logged at DEBUG and left to the first round,
which meets it again through `LiveBox`'s rebuild (a failed build is never
recorded, `LiveBox.java:28-29`) and escalates it as cannot-execute, the same
class as today (FR14, NFR-R2). On `run` the step comes after the
runner-start orphan sweep, where the lazily made box came before
(`ContainerTerminalDrive.runFresh`): with no liveness verdict the sweep judges
this run's own `manual` objects by their age alone (`SandboxLifecycleDecision`),
so a box made before it would be judged too, and on a clock far from the
daemon's it is stopped (found by `FrozenTimeEquipmentRunSpec`, 2026-10-24). *Rationale:* the engine knows no operator precondition
and should not; the step resume already takes is the step the fresh paths
lacked, and the requirement says "before any round of the claimed task", which
is exactly where the step sits. *Alternative zero:* no early step. Every fresh
take parks as INFRA, which silently drains the queue to humans under `serve`,
and `run` exits 10/13 instead of 8; rejected. *Alternative rejected:* a marker
type in `:domain` that `RoundExecution` rethrows — it carries the precondition
vocabulary into the engine and the judge path and makes every round a place a
precondition can surface. *Alternative rejected:* letting every early failure
through to the dispatch — a `create network` failure would move from
cannot-execute escalation to `TakeCrashAbort`'s abort marker, a change of class
this change never argued. *Alternative rejected:* calling
`SandboxImageReadiness.assess` before branch creation — a second per-task call
of the check beside the materializer's own, and it still misses a runtime that
dies before the first box.

**D23 — `DaemonCondition` stays one class above the file-size cap (amends the
Risks row on `RemoteOutageGate`'s size; added 2026-10-25 after the check of
audit finding 4).** `DaemonCondition.java` is 331 lines against the 200-line
hard cap of `process-invariants.md`, after the subject, reporter, schedule and
counter splits (task 7.5a). The deviation was recorded only in the class
javadoc; this decision is its record. What is left is one responsibility: the
open/closed transition with its three-phase probe, under one monitor.
*Alternative rejected:* a `ConditionProbeCycle` holding the probe phases
(`beginProbe`, `recordUnanswered`, `applyProbeResult`, `close`) with the
schedule, counters and reporter — every phase reads and writes `open`,
`probeInFlight`, `openedAt` and `lastError` under the condition's monitor, so
the halves would reach into each other's state, the split
`process-invariants.md` names as the worse outcome. *Alternative rejected:* an
episode record (`openedAt`, `lastError`, `lastSuccessAt`, the counters,
`health()` and the close summary) — a genuine ownership transfer, but it
removes about 50 lines and leaves about 280; the cap is then reachable only by
cutting the class javadoc (40 lines) and the `probeIfDue` javadoc (30), which
carry the signal-source and lock-scope reasoning, so the cost is paid for
nothing. *Pickup condition:* the class gains a second condition-level
behaviour beyond the transition (a per-kind policy, a second probe shape), or
the episode record earns a second reader; then the episode split lands first.

**D24 — The box-git-floor scenario names the deciding layer's line, not an
ERROR of the check (amends the "An image whose git is below the floor is
refused before the seed clone" scenario of the `sandbox/execution-environment`
delta; added 2026-10-25 after the check of audit finding 7).** D19 moved the
line out of `BoxGitFloor`: the check throws and logs nothing
(`BoxGitFloorSpec` asserts the empty log), and the layer that decides writes
it — `take` `GF171` and `run` `GF172` at ERROR beside the message they print,
`serve` the condition's opening WARN `GF180`. The scenario's THEN kept D19's
predecessor wording, "one coded ERROR is logged", which is false under `serve`.
The THEN now states the decision; the title is unchanged, so the two markers
(`BoxGitFloorSpec`, `RunExceptionReportingSpec`) still resolve. *Alternative
rejected:* leaving the sentence and reading "ERROR" loosely — a scenario is a
promise (`spec-mismatch.md`), and the one it made was the pre-D19 behaviour.

### Sync surfaces

The guard alias and port are an undeclared pair today (recipe build-argument
defaults ↔ `GuardCommands` constants, linked only by a comment), and
`JvmProxyEgressImage` is an undeclared test copy of the recipe's JVM plumbing.
This change removes both rather than declaring them: D2 gives the three shared
values one source file, D7 deletes the test copy. The git version pinned by
`scripts/install-git.sh` reaches the base through the script itself (D8), not
a second spelling. Build-time and runtime checks split the contract without
overlap (D4). One declared pair is added (D11): the base tag computed by
`product-version-conventions` and printed by `scripts/release-preflight.sh` —
a shell script and a build plugin share no classpath, so the pair is declared
at both ends, registered in `manual-sync-pairs.md`, and pinned by an identity
spec (`BaseTagIdentityFunctionalSpec`); the abstraction is the build owner,
which every Java and packaging reader shares. The pair task 4.1 declared
between `ImageContractCheck` and `bootstrap/packaging.gradle` (each deriving
the line, each carrying "Kept in sync with" the other) is dissolved by D11:
both derivations and both markers are deleted. The gnome-writable home list
(D14) has two readers — the image build and `gnomish-verify-image` — and is
kept one by a file one helper writes, not declared as a pair. The translation
of the contract file into build arguments had grown three copies of one key →
argument table (the E2E fixture's `CONTRACT_ARGUMENTS`, the release `image` job's
`sed` step, and the rebuild about to copy it); D16 takes preference 1 at the
third, as `manual-sync-pairs.md` requires: one script derives the names by rule
and all three call it, so no pair is declared and no table remains.

D17–D21 touch two declared pairs of `manual-sync-pairs.md` and add no new one:
`LedgerJsonMapper` ↔ `LedgerAggregator` (the `remoteOutage` line generalizes to
`daemonCondition` with a `condition` field; the aggregator reads both spellings,
the old one as `remote`) and `SnapshotJsonMapper` ↔ `SnapshotJsonReader` (the
`remote` section is written beside a `conditions` section for one release, the
reader accepts a snapshot with either); both round-trip specs gain the new
tokens, every constant iterated (`testing.md`, "Every wire vocabulary has a
round-trip spec"). The pair `FeedState` ↔ `FeedPhase` is untouched: the feed
holding off on a condition is the existing `Idle` with the gate's reason, not a
new phase. D17 and D19 deliberately add no second spelling of any check: the
startup owner, the slot and the probe call the one class per precondition.

### Single-owner mechanisms

| Owner                                                                           | Value (type)                                                                                                                                                                         | Requires                                                                                                                                                                                                           | Consumers                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Old way removed                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Enforced by                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|---------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sandbox-image/image-contract.properties` → `ImageContract` (`:sandbox:docker`) | contract version, guard alias, guard port (`ImageContract`)                                                                                                                          | the classpath resource; nothing at runtime                                                                                                                                                                         | `GuardCommands`, `ImageContractCheck`, `sandbox-image/Dockerfile` (build args); through `scripts/image-contract-build-args.sh` (D16, the one translation of the file into build arguments): the release `image` job, `sandbox-image-rebuild.yml`, the E2E base fixture (`SandboxBaseImage`)                                                                                                                                                                                                                                                                                                                                                                                           | `GuardCommands.PROXY_ALIAS`/`PROXY_PORT` literals; the recipe's `PROXY_HOST`/`PROXY_PORT` defaults (the recipe inherits the base's `ENV`); the `sed` key table of the release `image` job's "Read the image contract into build arguments" step (task 5.1) and the fixture's `CONTRACT_ARGUMENTS` / `contractValues` (D16). Exemption: `ContainerRunSupportFactory`'s `"gnomish-guard"` temporary-directory name — a path, not the alias                                                                                                                                                                                         | `ImageContractSourceSpec` in `:bootstrap`: the resource equals the source file; no file under `sandbox/docker/src/main` and no Dockerfile spells the alias, the port or `io.gnomish.image-contract=<digit>`; the scan asserts it reached every file. `ImageContractBuildArgsSpellingSpec` in `:bootstrap`'s `architecture` package (D16): no file under `.github/workflows/` and none under `test-fixtures/src/main` spells a contract key followed by `=` (`contract-version=`, `guard-alias=`, `guard-port=`) or a derived argument name (`CONTRACT_VERSION`, `GUARD_ALIAS`, `GUARD_PORT`); its scan asserts it reached every file. `ImageContractBuildArgsScriptSpec` pins the script's rule                                                                                                                                                                                                                                 |
| `ImageFacts` (`:sandbox:docker`)                                                | the image's declared volumes and labels (`ImageFacts`, one inspect per image)                                                                                                        | a reachable Docker daemon with the image present locally                                                                                                                                                           | `DeclaredVolumeOverrides.resolve` (sandbox and guard image), `ImageContractCheck` (sandbox image, in a box and in the readiness port's adapter, D17)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | the `{{json .Config.Volumes}}` inspect in `DockerCommands` is replaced by the `{{json .Config}}` one; `resolve` no longer takes a `DockerCli`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | parameter type: `DeclaredVolumeOverrides.resolve(ImageFacts, …)`; a spec counts one inspect per image per environment, and one per readiness assessment                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `GitVersion.FLOOR` (`:gittransfer`)                                             | the one git floor (`GitVersion`)                                                                                                                                                     | nothing                                                                                                                                                                                                            | `GitVersionCheck` (the factory's git), `BoxGitFloor` (the box's git), `scripts/install-git.sh` (the version it builds, kept equal by `GitFloorScriptSpec`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `GitVersion.BOX_FLOOR` / `BOX_FLOOR_REASON` deleted                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | the compiler (no second constant); `GitFloorScriptSpec` in `:bootstrap`: the script's `VERSION` equals `FLOOR`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `product-version-conventions` → `FactoryVersion.baseTag()` (D11)                | the base tag of the release — `X.Y` for a final release, the full version for a pre-release (`Optional<BaseTag>`; empty exactly for a development build)                             | the product version; nothing at runtime                                                                                                                                                                            | `ImageContractCheck` (the refusal's `FROM` line), the recipe stamp in `bootstrap/packaging.gradle`, Boot's build info (`build.baseTag`); through `scripts/release-preflight.sh` (its `base_tag` output and the `image_tags` list derived from it in the same script, D15; or `--base-tag <tag>`): the release `image` job, `sandbox-image-rebuild.yml`, `scripts/check-recipe-stamp.sh` (D15; handed the tag it checks, never deriving it)                                                                                                                                                                                                                                            | `ImageContractCheck.releaseLine` and its `RELEASE_LINE` regex; the regex in `bootstrap/packaging.gradle`. Exemptions: the repository default `ARG GNOMISH_BASE_TAG=0.1` in `docs/examples/sandbox-image/Dockerfile` and the same line in its `README.md` — a placeholder the packaging stamp replaces in both shipped files, not a derivation; the regex of `RecipeBaseTagStampSpec.expectedTag` — the test's independent oracle, rewritten to the rule above (`X.Y` without a suffix, the whole version with one), and kept apart from the provider on purpose: a spec reading the provider would compare the owner with itself | the parameter type: `ImageContractCheck(ImageContract, Optional<BaseTag>)` sees no version to parse, and `FactoryVersion`'s constructor refuses a base tag inconsistent with its value; `BaseTagIdentityFunctionalSpec` (the script's `--base-tag` equals the build's base tag per tag, pre-releases included); `git grep -n RELEASE_LINE` empty. Development branch, in CI (`e2e.productVersion` is `0.0.0-dev` there): `BuildInfoSpec` (key absent) and `RecipeBaseTagStampSpec` (both shipped files keep the default). Release branch, which no CI test build reaches: the release workflow's "Unpacked archive reports the tag's version" step (`bin/gnomish --version`; a missing or inconsistent `build.baseTag` is `GF150`, so it prints `0.0.0-dev` and the release fails) and its archive stamp step (task 5.1: the unpacked `share/sandbox-image/Dockerfile` and `README.md` carry `ARG GNOMISH_BASE_TAG=<base tag>`) |
| `ServePreconditions` (`:application`, D17)                                      | the daemon's startup verdict on the static operator preconditions, in slot order (`StartupAssessment`: pass, or the first refusal's typed exception)                                 | the daemon's registered clone, its bound definition, the configured image; a reachable container runtime for the floor probe; no claim, no tracker                                                                 | `ServeCommand.run` (before `provisionTracker`), through `ServeStartupGates` — the facade over the three pre-claim steps that end in the startup exit (the law binding, this assessment, the smoke test), extracted during apply (task 7.4) because `ServeCommand` was at the seven-parameter limit; ADR 0010's answers: used together in one sequence, each step maps its own failure onto the one startup exit, "startup failure" is FR12's name. The owner lives in the `app` package beside `SandboxModeSelector` and `OperatorPreconditionRefusals`, whose plan and classifier are package-private; `add-doctor-command`'s assessment (NG10, reads the verdict, never the checks) | none deleted: the per-task calls of `FactoryCloneRefFormat.require`, `SandboxModeSelector.plan`, `ImageContractCheck` and `BoxGitFloor` stay, since each but the image being configured can change under a running daemon (D17); the owner adds no spelling of its own                                                                                                                                                                                                                                                                                                                                                           | the owner takes ports only — `TaskBranchGit` (ref format), `SandboxModeSelector` (plan), `SandboxImageReadiness` (image contract and box git floor, implemented in `:sandbox:docker`) — never a `GitRunner` or `DockerCli` (parameter types); `ServePreconditionSpellingSpec` in `:bootstrap`: no file under `app/serve` and no startup class spells `rev-parse --show-ref-format`, `docker info` or `git --version`; its scan asserts it reached every file                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `DaemonConditions` (`:application`, D19)                                        | the daemon's open or closed state per precondition (`DaemonCondition` keyed by `DaemonConditionKind`), the one `anyOpen()` the feed reads, the probe schedule and the transition log | the daemon's clock and the check ports of D17 (the probe is the check: `TaskBranchGit.requireRefFormat`, `SandboxImageReadiness.assess`, the runtime probe; the remote keeps its `ls-remote`); nothing of any task | `FeedCycle.claimOrAbandon` (one read before `FeedTracker.claim`) and `FeedCycle.poll` (advances every due probe); `TakeSlotRunner` (reports `OperatorPreconditionUnmet`, D18); the janitor's branch pass (D21); `RemoteOutageSignalingBaseRefGit`/`RemoteOutageSignalingTaskBranchGit` (the remote's signals, unchanged); the snapshot (`SnapshotSources`, `serveobservability/RemoteHealthAssembler`, the `conditions` section) and the ledger (`daemonCondition` line); the dashboard's alerts read the snapshot, not the owner                                                                                                                                                     | `RemoteOutageGate` as the one gate the feed reads: `FeedCycle` no longer holds a `RemoteOutageGate`, it holds `DaemonConditions`; `RemoteOutageGates.forServe` builds the set, not one gate; a slot reading the outage's health itself (none exists today — verified)                                                                                                                                                                                                                                                                                                                                                            | the parameter type: `FeedCycle` takes `DaemonConditions` and the compiler lists every former gate reader; `DaemonConditionKindRoundTripSpec` (every kind written and read back on ledger and snapshot); `ServeRuntimeWiringSpec` gains one feature per condition kind: a slot reporting the refusal and the next poll claiming nothing, red with the report removed                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `OperatorPreconditionRefusals` (`:application`, D22)                            | whether a failure is an operator precondition refusal, and which (`Optional<DaemonConditionKind>`; empty for everything else)                                                        | nothing                                                                                                                                                                                                            | `ClaimedRunFailureDispatch` (the typed result, D18), `RunExceptionReporting` (the coded line, and whether the refusal prints its own message) and `RunExitCodeMapper` (exit 8 of `run`), the first-box step of both fresh paths (`TakeContainerFreshClaim`, `ContainerGitModeRunner`: which failure leaves before the engine)                                                                                                                                                                                                                                                                                                                                                         | the per-class lists of the four refusal types in `ClaimedRunFailureDispatch`, `RunExceptionReporting` (`OPERATOR_PRECONDITIONS`, and the four entries of `PRINTS_OWN_MESSAGE`, which keeps its other types as a list) and `RunExitCodeMapper`, deleted                                                                                                                                                                                                                                                                                                                                                                           | `OperatorPreconditionOwnerSpec` in `:bootstrap`'s `architecture` package: under `application/src/main` the four refusal class names appear in a `case`, an `instanceof` or a `.class` literal only in `OperatorPreconditionRefusals`; the scan asserts it reached every file; a spec iterating `DaemonConditionKind` minus `REMOTE` that asserts each consumer's answer for that kind's refusal; a flow spec in `:bootstrap` over the scripted docker CLI, one feature per command: a fresh `take` and a fresh `run` refused at the image contract exit 8 with no `EXECUTOR_THREW` line, red with the early step removed                                                                                                                                                                                                                                                                                                        |
| `gnomish-home-dir` → `/etc/gnomish/home-dirs` (D14)                             | the gnome-writable directories under `/home/gnome` (a root-owned file, one name per line)                                                                                            | root at image build; nothing at runtime                                                                                                                                                                            | `sandbox-image/Dockerfile` (`.claude`, `.cache`), the recipe and operator layers (toolchain directories), `gnomish-verify-image` (reads the file)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `usermod -m` and `useradd -m` handing the whole home to `gnome`; a hand `chown` of a home directory                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `gnomish-verify-image`, judging the `gnome` user's home from the user database (never `$HOME`, so the answer does not depend on who runs it): a home other than `/home/gnome`, any gnome-writable entry under it not listed, or a listed entry that is not a gnome-owned directory, fails the build; `BaseImageHomeSpec` (split from `BaseImageHelpersSpec` for the file-size cap) pins both directions                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |

## Risks / Trade-offs

- [Operators without the label (custom non-Ubuntu images) are refused] → the
  guide says how to set the label after passing `gnomish-verify-image` (NG5);
  the refusal names it.
- [Multi-arch builds under QEMU are slow, and the git build stage adds a
  compile per architecture] → the stage is cached by buildx between the release
  and the weekly rebuild (its inputs are the script and the tarball); the
  image job runs beside the distribution job, not after it.
- [The git install grows the base] → git's installed tree without
  documentation is tens of megabytes; NFR-P1's 150 MB target holds and
  `BaseImageHelpersSpec` asserts it.
- [Test images on the base are heavier than `alpine:3`] → one base build per
  JVM, then layer caching; the suites already pay a `docker build` per image.
- [The weekly rebuild changes `X.Y` under running operators] → operators who
  want stability pin `X.Y.Z` or a digest; the guide says so.
- [Contract version bumps force every operator to rebuild] → that is the point;
  bumps are rare and named in release notes.
- [An operator's image on a distribution git is refused after the floor join]
  → the refusal's coded line names the floor and the base to rebuild from (U5);
  release notes name the raise.
- [A tool needs to write directly into the root-owned home (D14): the agent
  CLI's `~/.claude.json` or an atomic rename in `$HOME`, or a toolchain the
  operator did not declare] → a visible failure, not a silent one; tasks 4.5
  (the probe, without credentials), 4.6a (against a stand-in provider) and
  4.6b (the operator's authenticated session) measure the real CLI before any
  edit, and task 4.7 starts only if their table shows `$HOME` need not be
  writable — otherwise a `spec-mismatch.md` report and `/architect`. Measured
  so far (4.5, CLI 2.1.308): the CLI does try exactly that write — an atomic
  `~/.claude.json` in `$HOME` on every start — fails with `EACCES`, logs
  `continuing without persisting` and goes on; with
  `CLAUDE_CONFIG_DIR=/home/gnome/.claude` it writes nothing in `$HOME`.
  Whether it answers without the persisted file is 4.6b's question; if it
  does not, the report ranks a base-set `ENV CLAUDE_CONFIG_DIR=/home/gnome/.claude`
  among its options — the default that D4 of `harden-verification-integrity`
  (NG7) overrides per round, not a second owner of the variable.
- [A project setup script run in a container `FROM` the base (`.gnomish/setup.sh`,
  as `add-sandbox-hardening` plans) meets the root-owned home (D14)] → it
  declares its toolchain directories with `gnomish-home-dir` as root, like any
  operator layer; a write it attempts directly under the home fails visibly.
  That change resets nothing at the round boundary, so it is not NG8's pickup.
- [ghcr's answer for a missing tag, or for a package not yet created, differs
  from the stderr text the refusal script recognises (D15)] → the first
  release is refused with exit 2, visibly, never published over; task 5.1's
  dry run records the real texts before any release.
- [ghcr.io package visibility defaults to private] → a one-time repository
  setting, recorded in the developer guide's releasing section.
- [The box git floor's probe (D19) starts a one-shot container on the
  condition's cadence while `boxGitFloor` is open] → the cadence is the gate's
  growing, jittered, capped one, so an image left unfixed costs one container
  start per cap interval; the startup probe (D17) is one start per daemon
  start.
- [A failed `docker run` inside `BoxGitFloor` is today reported as a version
  refusal (`GF163`), so the wrong condition would open] → verified false (D19
  as corrected 2026-10-24): the runtime signal is `DockerUnavailableException`,
  thrown by `DockerCli` before any exit code; the spec of `BoxGitFloor` pins
  that it propagates rather than becoming a version refusal.
- [A fresh run refused at its first box (D22) leaves the task branch at its
  initial commit] → the next `run` of the task is `--resume`, as after any
  stopped run; the next `take` routes to resume, whose `reattachFor` meets the
  same refusal and ends in the same typed result.
- [A fresh path's early failure that is no precondition (D22) is met twice]
  → accepted: the first round rebuilds the box, so a failed `create` runs once
  more and a box the self-check rejected (`SelfCheckedEnvironment.java:77-84`)
  is started and checked again before the task parks as cannot-execute; one
  extra materialize per fresh task, only on a failure. On resume the same
  failure of `reattachFor` is caught by no one before the dispatch
  (`ContainerResumePreparation.java:52`, `TakeClaimAndWork.java:137`) and ends
  in `TakeCrashAbort`, as today; this change keeps both classes as they are.
  Pickup: a change that unifies how fresh and resume classify an early box
  failure.
- [A box materialized after the first round (a segment boundary, a judge box
  in `FreshJudgeEnvironments`) still escalates as cannot-execute on a
  precondition refusal] → by the requirement, since rounds have run. Pickup: a
  pipeline whose segments bind different images, where a later segment's image
  can be refused after a good first one.
- [`RemoteOutageGate` is 287 lines, over the file-size cap, before it becomes
  `DaemonCondition`] → the generalization splits a responsibility, not the
  line count (`process-invariants.md`): the state machine and its log edges
  stay one class, the kind and its probe another; it lands first with the
  remote as the only kind (task 7.5a, the existing specs green under the
  rename), and the new kinds after it (task 7.5b). The generalization landed
  and the file stayed over the cap; the deviation is decision D23.
- [A snapshot or ledger written before this change has `remote` and
  `remoteOutage` only] → both readers accept the old spellings (Sync surfaces);
  the ledger is disposable history and the snapshot is rewritten within one
  interval.
- [A `serve` that passed its startup assessment meets a precondition the
  operator broke a second later] → that is D19's case by design; the startup
  owner never promised more than the state at startup (NFR-R3).
- [D17–D21 were added during apply and have had no `/review-artifacts` pass]
  → `design-decisions.md`, "A decision added during implementation": each
  decision quotes the signatures it changes, and task 7.0 runs
  `/review-artifacts add-sandbox-base-image D17–D21` before 7.1 starts. Its
  pass of 2026-10-23 found the readiness port, the plan's runtime refusal, the
  log owner, the crash line's guard and the release owner missing; D17–D21 say
  them now.

## Migration Plan

Operators rebuild their image `FROM` the base using the new recipe. Until then
the factory refuses their old image with the rebuild line (FR5), or, for an
image whose git is below the joined floor, with the floor's refusal (FR11). The
operator stand's Dockerfile is rewritten as part of acceptance.

Serve readiness (D17–D21): an operator whose daemon is running on a `files`
clone stops it, migrates the clone (`git refs migrate --ref-format=reftable`,
operator guide), removes the stale `gnomish:working` label the incident left
(or waits for the reaper), and starts the daemon again; a daemon on a clone
that still refuses now exits at startup with the same line. A script reading
`take`'s exit code learns one new code, 8, in the below-10 family; nothing it
tested before changes meaning. A script reading `run`'s code sees one change:
`run` on a container-bound pipeline with the container runtime unreachable
exited 2 (a usage error) and now exits 8, the code of every operator
precondition. Old snapshots and ledgers stay readable (Sync
surfaces).
