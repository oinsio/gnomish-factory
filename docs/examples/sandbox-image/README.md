# Reference sandbox image

The reference recipe for `factory.sandbox.image` — the container every gnome
round, judge vote, and command check runs in when a stage binds the container
adapter. Implements FR9 and UX1 of add-sandbox-base-image (UX4 of
add-sandbox-core); the operator guide's sandbox chapter explains how the image
fits the whole setup.

## Built on the sandbox base image

The factory's image contract — the `gnome` user (uid 1000) owning
`/gnomish/**`, the root-owned control surfaces (`/etc/gnomish`, the agent
policy in `/etc/claude-code`), the egress guard's proxy for curl-class tools,
JVM tools and Maven, `git` and `curl`, and the label
`io.gnomish.image-contract` the factory checks before every box — comes from
`ghcr.io/oinsio/gnomish-sandbox-base`. Your Dockerfile adds only your
toolchain and the agent CLI, declares the toolchain's home directories with
`gnomish-home-dir` (below), then runs the base's two other helpers:
`gnomish-trust-ca` (imports the certificates in `/etc/gnomish/ca` into the
system and JVM trust stores) and `gnomish-verify-image` (fails the build,
naming every violation, if a layer broke the contract).

The complete operator Dockerfile — this directory's `Dockerfile` without its
comments:

```dockerfile
ARG GNOMISH_BASE_TAG=0.1
FROM eclipse-temurin:25-jdk-noble AS jdk
FROM ubuntu:24.04 AS node
ARG TARGETARCH
ARG NODE_VERSION=24.22.1
ARG NODE_DIST_URL=https://nodejs.org/dist
ARG NPM_REGISTRY=https://registry.npmjs.org/
ARG AGENT_CLI_PACKAGE=@anthropic-ai/claude-code
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl xz-utils \
 && case "${TARGETARCH}" in \
      amd64) arch=x64 sum=6c172a9ab6718f7b801fb5a7bd486af2ee5e32758086a04b84fcce2aa77095e6 ;; \
      arm64) arch=arm64 sum=dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e ;; \
      *) echo "no Node build for ${TARGETARCH}" >&2; exit 1 ;; esac \
 && curl -fsSL -o /tmp/node.tar.xz "${NODE_DIST_URL}/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${arch}.tar.xz" \
 && echo "${sum}  /tmp/node.tar.xz" | sha256sum -c - \
 && mkdir /opt/node && tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 --no-same-owner \
 && PATH="/opt/node/bin:${PATH}" npm install -g --registry "${NPM_REGISTRY}" "${AGENT_CLI_PACKAGE}"
FROM ghcr.io/oinsio/gnomish-sandbox-base:${GNOMISH_BASE_TAG}
USER root
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=node /opt/node /opt/node
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/opt/node/bin:${PATH}
COPY --chmod=0644 ca/ /etc/gnomish/ca/
RUN gnomish-home-dir .m2 .gradle .npm && gnomish-trust-ca && gnomish-verify-image
USER gnome
```

Swap the JDK image, add your own toolchain the same way, and keep the last
two steps: `RUN gnomish-trust-ca && gnomish-verify-image` as root after
everything that touches the trust stores or the filesystem (here preceded by
`gnomish-home-dir`, below), then `USER gnome`.

**Declare your toolchain's home directories.** The gnome's home,
`/home/gnome`, is root-owned: its shell startup files are root-owned stubs and
the gnome can create nothing directly under it, because the box outlives a
round and a file written there would run in a later one. The base declares
the agent CLI's `~/.claude` and `~/.cache`; declare every other directory your
toolchain writes under the home, as root, before `gnomish-verify-image`:

```sh
RUN gnomish-home-dir .m2 .gradle .npm && gnomish-trust-ca && gnomish-verify-image
```

The helper creates each directory owned by the gnome and records it in
`/etc/gnomish/home-dirs`; `gnomish-verify-image` fails the build on any
gnome-writable entry of the home that is not listed there. A tool that writes
an undeclared path under the home fails visibly in the box.

**Keep managed hooks only.** The base's agent policy,
`/etc/claude-code/managed-settings.json`, is `{"allowManagedHooksOnly": true}`:
no hook from the gnome's `~/.claude/settings.json` or the working copy's
`.claude/settings.json` runs. If you replace the file with a managed policy of
your own, keep `"allowManagedHooksOnly": true` in it; `gnomish-verify-image`
fails the build without it.

**Download in a stage of your own.** The base's proxy variables name the
egress guard, which exists only on a box's task network, so at build time a
`curl`, `apt-get` or `npm install` in a stage `FROM` the base has no proxy to
reach and fails. Two forms work:

- Fetch in a stage built on another image (as `node` above) and graft the
  result with `COPY --from`. This is the form for everything one `COPY` can
  carry: a JDK, Node, a toolchain, the agent CLI.
- Operating-system packages, which `apt` spreads across the filesystem, are
  installed in the final stage, as root, with the proxy variables unset for
  that one command inside one `RUN`:

  ```sh
  RUN env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
        sh -c 'apt-get update && apt-get install -y --no-install-recommends jq && rm -rf /var/lib/apt/lists/*'
  ```

  `env -u` changes only that command's environment; the image keeps every
  proxy setting of the base.

Never set or clear a proxy variable with `ENV`: the box needs the base's
values. Nothing checks for it, but such an image fails closed — the task
network has no other route out.

## Which base tag

`GNOMISH_BASE_TAG` defaults to the base tag of the factory this recipe ships
with; the release archive stamps its own. A final release is published under
two tags:

- `X.Y.Z` — never re-pushed: the image a release's notes and attestation name.
- `X.Y` — rebuilt weekly with Ubuntu's security updates, so it moves.

A final release's base tag is `X.Y`. A pre-release (`X.Y.Z-rc.1`) is published
under its full version alone, never moves `X.Y`, and that full version is its
base tag.

For a build that never changes under you, pin `X.Y.Z` or a digest
(`ghcr.io/oinsio/gnomish-sandbox-base@sha256:…`). A factory refuses a box whose
image was built for another contract version and names the `FROM` line to
rebuild from.

## git

The base's `git` meets the factory's git floor; the factory refuses, before
the seed clone, an image whose `git` is older. Keep the base's `git`: an image
not built on the base (or one that installs another `git` ahead of it on
`PATH`) must meet the same floor.

## Build

```bash
docker build -t my-project-sandbox:1 docs/examples/sandbox-image/
# then, in the project file ~/.gnomish/projects/<name>/project.yaml:
#   factory:
#     sandbox:
#       image: my-project-sandbox:1
```

Build arguments (all optional):

| Arg                 | Default                     | Purpose                                                  |
|---------------------|-----------------------------|----------------------------------------------------------|
| `GNOMISH_BASE_TAG`  | the release's base tag      | the sandbox base image's tag (see above)                 |
| `NODE_VERSION`      | `24.22.1`                   | Node; a new version needs its two SHA-256 sums           |
| `NODE_DIST_URL`     | nodejs.org                  | where the Node tarball is downloaded from                |
| `NPM_REGISTRY`      | npmjs.org                   | registry the agent CLI installs from at build time       |
| `AGENT_CLI_PACKAGE` | `@anthropic-ai/claude-code` | the agent CLI baked into the image                       |

Download endpoints are deliberately parameters (design D7 of
add-sandbox-core): pointing the image at a private depot or mirror is a
`--build-arg`, never an image edit. The Maven mirror is an environment
variable, not a build argument: the base's root-owned `settings.xml` names
`${env.GNOMISH_MAVEN_MIRROR}`, which the base sets to Maven Central. Select your
own mirror with one line in your Dockerfile, after `FROM` the base (design D12
of add-sandbox-base-image); the settings file stays as the base wrote it:

```dockerfile
ENV GNOMISH_MAVEN_MIRROR=https://maven.example.com/maven2
```

The mirror's host must still be on the guard's allowlist: the mirror chooses
where Maven asks, the allowlist decides whether the box may reach it.

One thing the image must **not** do is rely on a `VOLUME` declaration: the
factory mounts an empty, bounded, non-executable ephemeral filesystem over
every declared path it does not mount itself, so content baked under such a
path is invisible in the box. Keep toolchains and caches under paths the image
does not declare — see [Declared volumes in your
image](../../guides/operator-guide-sandbox.md#declared-volumes-in-your-image).

## Agent authentication

<!-- implements FR9, UX1, UX3 of fix-operator-blockers -->

The image bakes the stock agent CLI and no credential. The agent logs in with
`CLAUDE_CODE_OAUTH_TOKEN` (a subscription token printed by `claude setup-token`)
or `ANTHROPIC_API_KEY`, set in the **factory's** environment: the factory hands
it to every agent round and judge vote in the box, and to nothing else, with no
`env-passthrough` entry. Add `api.anthropic.com` to
`factory.sandbox.egress-allowlist` in the project file. Nothing in the image wraps the `claude`
binary — the factory passes the permission mode and the MCP exclusion itself.
See [Agent authentication in the
box](../../guides/operator-guide-sandbox.md#agent-authentication-in-the-box).

## The CA seam

Drop the factory's CA certificate (`*.pem` or `*.crt`) into `ca/` before
building: the recipe copies it into the base's CA folder, `/etc/gnomish/ca`,
and `gnomish-trust-ca` imports it into both the system trust store and the
JVM `cacerts`. Two files whose names differ only in letter case are refused:
`keytool` folds aliases to lower case, so one would silently replace the
other's import. Until change B (TLS interception) the directory may stay empty —
the guard forwards TLS unmodified today.

## Proxy plumbing

The task network is internal-only; the guard is the single route out. The
base bakes that route everywhere tools look for it — `HTTP_PROXY`/`HTTPS_PROXY`
in both spellings for curl-class tools, `JAVA_TOOL_OPTIONS` and `GRADLE_OPTS`
system properties (the JVM ignores proxy environment variables), and a
root-owned Maven `settings.xml` selected via `MAVEN_ARGS` — and the recipe
inherits it unchanged. These baked settings are convenience, not the security
boundary: a process that strips them all simply has no route (the network is
`--internal`). The self-check proves both halves before any gnome process runs.
