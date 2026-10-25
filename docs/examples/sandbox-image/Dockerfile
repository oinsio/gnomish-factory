# Reference sandbox image for gnomish-factory container mode.
# Implements FR9, UX1 of add-sandbox-base-image (design D1, D5, D14); UX4 of add-sandbox-core.
#
# Every item of the factory's image contract comes from the sandbox base image: the `gnome` user
# (uid 1000) owning /gnomish/**, the root-owned control surfaces and home, the egress guard's proxy
# for curl-class tools, JVM tools and Maven, git at the factory's floor, and the helpers. This file
# adds only a toolchain and the agent CLI, declares the toolchain's home directories, then runs the
# helpers.
#
# The base's proxy names the egress guard, which exists only on a box's task network, so a
# download in a stage FROM the base fails at build time. Everything this recipe downloads is
# fetched in a stage of its own and grafted into the final stage with COPY --from.
#
# GNOMISH_BASE_TAG: the base of the factory release this recipe ships with (the release archive
# stamps its own base tag here: X.Y, or the full version of a pre-release). Pin X.Y.Z or a digest
# for a build that never moves; X.Y is rebuilt weekly with Ubuntu's security updates.
ARG GNOMISH_BASE_TAG=0.1

# The JDK, grafted from the official image.
FROM eclipse-temurin:25-jdk-noble AS jdk

# Node from the official tarball, checksum-verified (a new NODE_VERSION needs its two SHA-256 sums
# from SHASUMS256.txt), and the agent CLI installed into it — baked at build time, since the box
# has no route to any registry at run time. Download endpoints are build parameters, never
# hardcoded (design D7 of add-sandbox-core).
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
# The CA seam: any *.pem / *.crt dropped into ca/ next to this file is imported into the system and
# JVM trust stores by gnomish-trust-ca; ca/ may stay empty.
COPY --chmod=0644 ca/ /etc/gnomish/ca/
# The gnome's home is root-owned: gnomish-home-dir declares the toolchains' own directories under it
# gnome-writable (design D14), and a write anywhere else under the home fails. Then the trust stores
# and the verification of the whole contract, as root, after every other step.
RUN gnomish-home-dir .m2 .gradle .npm && gnomish-trust-ca && gnomish-verify-image
USER gnome
