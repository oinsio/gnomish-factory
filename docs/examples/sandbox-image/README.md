# Reference sandbox image

The reference recipe for `factory.sandbox.image` — the container every gnome
round, judge vote, and command check runs in when a stage binds the container
adapter. Implements UX4 of add-sandbox-core (design D7); the operator guide's
sandbox chapter explains how the image fits the whole setup.

## Image contract

The container adapter and the egress self-check assume, in any image you build:

| Contract item                                         | Why                                                                          |
|-------------------------------------------------------|------------------------------------------------------------------------------|
| `git` on `PATH`                                       | the seed clone and snapshot commits run in-box                               |
| `curl` on `PATH`                                      | the fail-closed self-check probes run via `exec` (FR8)                       |
| non-root user `gnome`, uid 1000, owning `/gnomish/**` | every process — seed clone included — runs as the image user, never root     |
| control surfaces root-owned                           | agent-CLI policy, proxy/CA/build configs must survive a hostile round (FR20) |

Everything else — JDK version, extra toolchains, language runtimes — is your
project's choice: swap `BASE_IMAGE` or extend the Dockerfile.

One thing the image must **not** do is rely on a `VOLUME` declaration: the
factory mounts an empty, bounded, non-executable ephemeral filesystem over
every declared path it does not mount itself, so content baked under such a
path is invisible in the box. Keep toolchains and caches under paths the image
does not declare — see [Declared volumes in your
image](../../guides/operator-guide-sandbox.md#declared-volumes-in-your-image).

## Build

```bash
docker build -t my-project-sandbox:1 docs/examples/sandbox-image/
# then, in the project file ~/.gnomish/projects/<name>/project.yaml:
#   factory:
#     sandbox:
#       image: my-project-sandbox:1
```

Build arguments (all optional):

| Arg                         | Default                        | Purpose                                             |
|-----------------------------|--------------------------------|-----------------------------------------------------|
| `BASE_IMAGE`                | `eclipse-temurin:21-jdk-noble` | JDK base; pick your project's toolchain             |
| `NPM_REGISTRY`              | npmjs.org                      | registry the agent CLI installs from at build time  |
| `MAVEN_MIRROR_URL`          | Maven Central                  | the baked Maven mirror — the artifact-depot seam    |
| `AGENT_CLI_PACKAGE`         | `@anthropic-ai/claude-code`    | the agent CLI baked into the image                  |
| `PROXY_HOST` / `PROXY_PORT` | `gnomish-guard` / `8080`       | the egress guard's stable alias on the task network |

Registry endpoints are deliberately parameters (D7): pointing the image at a
private depot or mirror is a `--build-arg`, never an image edit.

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
building: it is imported into both the system trust store and the JVM
`cacerts`. Until change B (TLS interception) the directory may stay empty —
the guard forwards TLS unmodified today. Baking the seam now means change B is
a one-file rebuild.

## Proxy plumbing

The task network is internal-only; the guard is the single route out. The
image bakes that route everywhere tools look for it:

- `HTTP_PROXY`/`HTTPS_PROXY` (both spellings) for curl-class tools,
- `JAVA_TOOL_OPTIONS` and `GRADLE_OPTS` system properties — the JVM ignores
  proxy environment variables,
- a root-owned Maven `settings.xml` selected via `MAVEN_ARGS`.

These baked configs are convenience, not the security boundary: a process that
strips them all simply has no route (the network is `--internal`). The
self-check proves both halves before any gnome process runs.
