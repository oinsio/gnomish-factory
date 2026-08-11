# Reference sandbox image for gnomish-factory container mode.
# Implements UX4, FR20 of add-sandbox-core (design D7).
#
# The factory's image contract (what the container adapter and self-check assume):
#   - git and curl on PATH (curl runs the egress self-check probes, FR8)
#   - a non-root user with uid 1000 named `gnome` owning /gnomish/** — every
#     exec, including the seed clone, runs as this user, never root
#   - injection-persistence surfaces (agent-CLI policy, proxy/CA/build configs)
#     root-owned, so the gnome cannot rewrite the rules of its own cage (FR20)
#
# Registry endpoints and the proxy address are build parameters, never
# hardcoded (D7): a future artifact depot or a private mirror is a --build-arg,
# not an image edit.
ARG BASE_IMAGE=eclipse-temurin:21-jdk-noble
FROM ${BASE_IMAGE}

ARG NPM_REGISTRY=https://registry.npmjs.org/
ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
ARG AGENT_CLI_PACKAGE=@anthropic-ai/claude-code
# The egress guard's stable network alias on the task network; matches the
# factory's GuardCommands alias. Override only if the factory's changes.
ARG PROXY_HOST=gnomish-guard
ARG PROXY_PORT=8080

# Toolchain: git + curl are the image contract; node runs the agent CLI.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates nodejs npm \
 && rm -rf /var/lib/apt/lists/*

# Agent CLI baked at build time — the box has no route to any registry at run
# time (the guard default-denies), so nothing may rely on in-box installs.
RUN npm install -g --registry "${NPM_REGISTRY}" "${AGENT_CLI_PACKAGE}"

# Factory CA seam (design D4): any *.pem / *.crt dropped into ca/ next to this
# Dockerfile is imported into the system and JVM trust stores at build time.
# Until change B the factory does not intercept TLS, so ca/ may stay empty —
# baking the seam now means change B is a rebuild with one file, not a redesign.
COPY ca/ /tmp/gnomish-ca/
RUN set -e; \
    for cert in /tmp/gnomish-ca/*.pem /tmp/gnomish-ca/*.crt; do \
      [ -f "$cert" ] || continue; \
      b="$(basename "$cert")"; \
      cp "$cert" "/usr/local/share/ca-certificates/${b%.*}.crt"; \
      keytool -importcert -noprompt -cacerts -storepass changeit \
        -alias "gnomish-${b%.*}" -file "$cert"; \
    done; \
    update-ca-certificates; \
    rm -rf /tmp/gnomish-ca

# The in-box task user: uid 1000, owns the adapter's two file-channel roots.
# The factory never overrides the image user, so everything — seed clone, agent
# rounds, checks — runs as gnome. Ubuntu-family bases ship a uid-1000 `ubuntu`
# user, so an existing uid 1000 is renamed rather than duplicated.
RUN if getent passwd 1000 >/dev/null; then \
      old="$(getent passwd 1000 | cut -d: -f1)"; \
      usermod -l gnome -d /home/gnome -m "$old"; \
      groupmod -n gnome "$(getent group "$(id -g gnome)" | cut -d: -f1)"; \
    else \
      useradd -m -u 1000 gnome; \
    fi \
 && mkdir -p /gnomish/work /gnomish/scratch \
 && chown -R gnome:gnome /gnomish

# Root-owned build configs (FR20). The JVM ignores proxy environment variables,
# so Gradle/Maven builds reach the guard only through baked system properties;
# Maven additionally gets a settings file with the proxy and a mirror whose URL
# is a build parameter (the artifact-depot seam, D7). /etc/gnomish is root-owned
# end to end — a root-owned file inside a gnome-writable directory could simply
# be unlinked, so the whole directory is the surface.
RUN mkdir -p /etc/gnomish/maven \
 && printf '%s\n' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
    '  <proxies>' \
    "    <proxy><id>gnomish</id><active>true</active><protocol>http</protocol><host>${PROXY_HOST}</host><port>${PROXY_PORT}</port></proxy>" \
    "    <proxy><id>gnomish-s</id><active>true</active><protocol>https</protocol><host>${PROXY_HOST}</host><port>${PROXY_PORT}</port></proxy>" \
    '  </proxies>' \
    '  <mirrors>' \
    "    <mirror><id>gnomish-mirror</id><mirrorOf>central</mirrorOf><url>${MAVEN_MIRROR_URL}</url></mirror>" \
    '  </mirrors>' \
    '</settings>' \
    > /etc/gnomish/maven/settings.xml \
 && chown -R root:root /etc/gnomish \
 && chmod -R a+rX,go-w /etc/gnomish

# Agent-CLI policy surface (FR20): managed settings override anything the gnome
# writes under its own home, and the directory is root-owned. Operators put
# their agent policy here (e.g. disallowed tools); an empty object is a valid
# baseline.
RUN mkdir -p /etc/claude-code \
 && printf '{}\n' > /etc/claude-code/managed-settings.json \
 && chown -R root:root /etc/claude-code \
 && chmod 0755 /etc/claude-code && chmod 0644 /etc/claude-code/managed-settings.json

# Runtime environment (D6: the image, not the factory, supplies the box env).
# ENV is fixed at container creation — an in-box process cannot persist a
# change into later execs. Proxy spellings for both tool families; JVM system
# properties because the JVM ignores the env-var spellings.
ENV HTTP_PROXY=http://${PROXY_HOST}:${PROXY_PORT} \
    HTTPS_PROXY=http://${PROXY_HOST}:${PROXY_PORT} \
    http_proxy=http://${PROXY_HOST}:${PROXY_PORT} \
    https_proxy=http://${PROXY_HOST}:${PROXY_PORT} \
    JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=${PROXY_HOST} -Dhttp.proxyPort=${PROXY_PORT} -Dhttps.proxyHost=${PROXY_HOST} -Dhttps.proxyPort=${PROXY_PORT}" \
    GRADLE_OPTS="-Dhttp.proxyHost=${PROXY_HOST} -Dhttp.proxyPort=${PROXY_PORT} -Dhttps.proxyHost=${PROXY_HOST} -Dhttps.proxyPort=${PROXY_PORT}" \
    MAVEN_ARGS="-s /etc/gnomish/maven/settings.xml"

USER gnome
WORKDIR /gnomish/work
