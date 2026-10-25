# The sandbox base image: the factory's image contract, built once and published, so an operator
# image builds FROM it and adds only a JDK, Node and an agent CLI.
# Implements FR1, FR2, FR3, FR4, FR11, FR12, NFR-S1, NFR-S2, NFR-P1 of add-sandbox-base-image (design D1, D2,
# D4, D5, D8, D14).
#
# Build context and inputs (the E2E base fixture and both publishing workflows follow this):
#   - the context is this directory, sandbox-image/;
#   - the named build context `scripts` is the repository's scripts/ directory, the one source of
#     the git install script (design D8: the base and the factory's CI build the same pinned
#     tarball); no copy of the script is kept here:
#       docker build --build-context scripts=scripts ... sandbox-image
#   - the contract values come from sandbox-image/image-contract.properties (design D2), one build
#     argument per key, with no default here; an empty one fails the build. The arguments are the
#     lines scripts/image-contract-build-args.sh prints (design D16), the one translation of the
#     file that the E2E base fixture and both publishing workflows run: each argument is named by
#     rule, the key upper-cased with every `-` replaced by `_`, never by a table; each line it
#     prints is one --build-arg:
#       sh scripts/image-contract-build-args.sh sandbox-image/image-contract.properties
#   - the OCI labels' values, also required: IMAGE_VERSION (the factory version, X.Y.Z) and
#     IMAGE_REVISION (the commit the image is built from);
#   - optional: MAVEN_MIRROR_URL, the default of GNOMISH_MAVEN_MIRROR, the variable the root-owned
#     Maven settings read the mirror from (the artifact-depot seam; Maven Central by default). An
#     operator image overrides the variable with one ENV line (design D12).
#
# What the image promises (the image contract):
#   - git at or above the factory's floor (GitVersion.FLOOR) and curl on PATH; git lives under
#     /opt/git, built from the pinned upstream release in a stage this image does not carry;
#   - a non-root user `gnome`, uid 1000, owning /gnomish/work and /gnomish/scratch, and the
#     final user of the image;
#   - /etc/gnomish (Maven settings, the CA drop folder, the list of gnome-writable home directories)
#     and /etc/claude-code (agent policy) root-owned and not writable by `gnome`, so the gnome cannot
#     rewrite its own cage;
#   - the gnome's home /home/gnome root-owned, its shell startup files root-owned stubs, and only the
#     directories declared with gnomish-home-dir (.claude and .cache here) writable by `gnome`
#     (design D14): the box outlives a round, so nothing the gnome writes there may run later;
#   - an agent policy that runs managed hooks only;
#   - the egress guard's proxy in the environment for curl-class tools, JVM tools and Maven;
#   - no agent CLI, no project toolchain (JDK, Node), no credential;
#   - the helpers gnomish-trust-ca, gnomish-home-dir and gnomish-verify-image in /usr/local/bin
#     (sandbox-image/bin).

# ---- Build stage: git from the pinned source release (design D8). Never published.
FROM ubuntu:24.04 AS git-build

COPY --from=scripts install-git.sh /tmp/install-git.sh
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && /tmp/install-git.sh --prefix /opt/git \
 && find /opt/git -type f -perm -u+x -exec sh -ec \
      'for f; do [ "$(head -c 4 "$f" | od -An -c | tr -d " ")" = "177ELF" ] && strip --strip-unneeded "$f"; done; :' \
      sh '{}' + \
 && rm -rf /opt/git/share/man /opt/git/share/doc /opt/git/share/gitweb

# ---- Published stage. Named so the weekly rebuild can build it without the layer cache
# (`no-cache-filters`), fetching fresh packages, while it reuses the git build stage above.
FROM ubuntu:24.04 AS published

ARG CONTRACT_VERSION
ARG GUARD_ALIAS
ARG GUARD_PORT
ARG IMAGE_VERSION
ARG IMAGE_REVISION
ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2

# Refuse a build that lacks a contract value or a label value: an image with an empty proxy or an
# empty contract label would pass the build and fail every box.
RUN : "${CONTRACT_VERSION:?build argument CONTRACT_VERSION (contract-version) is required}" \
      "${GUARD_ALIAS:?build argument GUARD_ALIAS (guard-alias) is required}" \
      "${GUARD_PORT:?build argument GUARD_PORT (guard-port) is required}" \
      "${IMAGE_VERSION:?build argument IMAGE_VERSION is required}" \
      "${IMAGE_REVISION:?build argument IMAGE_REVISION is required}" \
      "${MAVEN_MIRROR_URL:?build argument MAVEN_MIRROR_URL must not be empty}"

# curl for the egress self-check probes, CA certificates, and the shared libraries the git built
# above links against (libcurl with OpenSSL, expat, zlib); nothing else.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl libcurl4t64 libexpat1 zlib1g \
 && rm -rf /var/lib/apt/lists/*

COPY --from=git-build /opt/git /opt/git
ENV PATH=/opt/git/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# The in-box task user: uid 1000, owns the adapter's two file-channel roots. Ubuntu ships a
# uid-1000 `ubuntu` user, so an existing uid 1000 is renamed rather than duplicated; the renamed
# user leaves the supplementary groups `ubuntu` had (sudo, adm, ...), so `gnome` holds only its own.
# Neither branch creates or hands over a home (design D14): the old user's home is removed, and
# /home/gnome is created by root, root-owned 0755, with the three startup files bash reads as
# root-owned one-comment stubs. The gnome can therefore neither rewrite nor unlink-and-recreate an
# rc file or add one (.bash_profile, .gitconfig, .curlrc, ...); the directories it may write are
# declared below with gnomish-home-dir.
RUN if getent passwd 1000 >/dev/null; then \
      old="$(getent passwd 1000 | cut -d: -f1)"; \
      old_home="$(getent passwd 1000 | cut -d: -f6)"; \
      case "$old_home" in /home/?*) rm -rf "$old_home" ;; esac; \
      usermod -l gnome -d /home/gnome "$old"; \
      groupmod -n gnome "$(getent group "$(id -g gnome)" | cut -d: -f1)"; \
      usermod -G '' gnome; \
    else \
      useradd -M -d /home/gnome -u 1000 gnome; \
    fi \
 && rm -rf /home/gnome \
 && mkdir -m 0755 /home/gnome \
 && for rc in .bashrc .profile .bash_logout; do \
      printf '# Root-owned by the sandbox base image: the gnome cannot change its shell startup.\n' \
        > "/home/gnome/$rc"; \
      chmod 0644 "/home/gnome/$rc"; \
    done \
 && mkdir -p /gnomish/work /gnomish/scratch \
 && chown -R gnome:gnome /gnomish

# Root-owned control surfaces (NFR-S1). The whole directory is the surface: a root-owned file
# inside a gnome-writable directory could simply be unlinked. Maven gets the proxy and a mirror
# whose URL Maven reads from GNOMISH_MAVEN_MIRROR when it runs (design D12): an operator selects its
# own mirror with one ENV line and never rewrites this file. The mirror line is single-quoted, so
# the shell leaves ${env.…} for Maven to interpolate. The CA drop folder starts empty (gnomish-trust-ca imports what an operator layer drops there).
# The agent CLI's managed settings override only the keys they set (design D14): this file sets
# allowManagedHooksOnly, so no hook from the gnome's ~/.claude/settings.json or the working copy's
# .claude/settings.json runs; the rest of ~/.claude stays the gnome's (NG7). An operator replacing
# the file with its own managed policy keeps the key, which gnomish-verify-image checks.
RUN mkdir -p /etc/gnomish/maven /etc/gnomish/ca /etc/claude-code \
 && printf '%s\n' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
    '  <proxies>' \
    "    <proxy><id>gnomish</id><active>true</active><protocol>http</protocol><host>${GUARD_ALIAS}</host><port>${GUARD_PORT}</port></proxy>" \
    "    <proxy><id>gnomish-s</id><active>true</active><protocol>https</protocol><host>${GUARD_ALIAS}</host><port>${GUARD_PORT}</port></proxy>" \
    '  </proxies>' \
    '  <mirrors>' \
    '    <mirror><id>gnomish-mirror</id><mirrorOf>central</mirrorOf><url>${env.GNOMISH_MAVEN_MIRROR}</url></mirror>' \
    '  </mirrors>' \
    '</settings>' \
    > /etc/gnomish/maven/settings.xml \
 && printf '{"allowManagedHooksOnly": true}\n' > /etc/claude-code/managed-settings.json \
 && chown -R root:root /etc/gnomish /etc/claude-code \
 && chmod -R u=rwX,go=rX /etc/gnomish /etc/claude-code

# Runtime environment: the image, not the factory, supplies the box's environment, fixed at
# container creation. Both proxy spellings for curl-class tools; JVM system properties, because
# the JVM ignores the environment spellings; Maven reads the root-owned settings, and the mirror
# they name from GNOMISH_MAVEN_MIRROR (MAVEN_MIRROR_URL, Maven Central unless the build overrides it).
ENV HTTP_PROXY=http://${GUARD_ALIAS}:${GUARD_PORT} \
    HTTPS_PROXY=http://${GUARD_ALIAS}:${GUARD_PORT} \
    http_proxy=http://${GUARD_ALIAS}:${GUARD_PORT} \
    https_proxy=http://${GUARD_ALIAS}:${GUARD_PORT} \
    JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=${GUARD_ALIAS} -Dhttp.proxyPort=${GUARD_PORT} -Dhttps.proxyHost=${GUARD_ALIAS} -Dhttps.proxyPort=${GUARD_PORT}" \
    GRADLE_OPTS="-Dhttp.proxyHost=${GUARD_ALIAS} -Dhttp.proxyPort=${GUARD_PORT} -Dhttps.proxyHost=${GUARD_ALIAS} -Dhttps.proxyPort=${GUARD_PORT}" \
    MAVEN_ARGS="-s /etc/gnomish/maven/settings.xml" \
    GNOMISH_MAVEN_MIRROR=${MAVEN_MIRROR_URL}

# The trust, home-directory and verification helpers (FR2, FR3; design D4, D5, D14): root-owned,
# run by the operator's own layers. The base runs all three at its own build: home-dir declares the
# agent CLI's configuration and cache directories, the only gnome-writable entries of the home;
# trust-ca for the system store (a no-op while the CA folder is empty); verify-image as the last
# check of what this file promises.
COPY --chmod=0755 bin/gnomish-trust-ca bin/gnomish-home-dir bin/gnomish-verify-image /usr/local/bin/
RUN gnomish-home-dir .claude .cache && gnomish-trust-ca && gnomish-verify-image

LABEL io.gnomish.image-contract="${CONTRACT_VERSION}" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.source="https://github.com/oinsio/gnomish-factory" \
      org.opencontainers.image.title="gnomish-sandbox-base" \
      org.opencontainers.image.description="Sandbox base image carrying the gnomish factory's image contract"

USER gnome
WORKDIR /gnomish/work
