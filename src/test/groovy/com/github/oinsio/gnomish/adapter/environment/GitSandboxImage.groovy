package com.github.oinsio.gnomish.adapter.environment

/**
 * Builds (once per JVM) the minimal git-capable sandbox image the Docker-gated
 * environment specs run against: alpine + git, a non-root {@code gnome} user
 * owning {@code /gnomish/**} — the same shape the reference image recipe (task
 * 9.1) bakes for real. The seed clone of FR3 runs in-box as the image's user,
 * so a bare {@code alpine:3} (no git, root user) can no longer materialize an
 * environment; every spec that materializes uses this image instead.
 */
class GitSandboxImage {

    static final String IMAGE = 'gnomish-sandbox-git-test:latest'

    private static volatile boolean built = false

    /** Builds the image if this JVM has not yet; returns the tag. Asserts the build succeeds. */
    static synchronized String ensureBuilt() {
        if (built) {
            return IMAGE
        }
        def dockerfile = '''
            FROM alpine:3
            RUN apk add --no-cache git \\
             && adduser -D -u 1000 gnome \\
             && mkdir -p /gnomish/work /gnomish/scratch \\
             && chown -R gnome:gnome /gnomish
            USER gnome
        '''.stripIndent()
        DockerImageBuilder.build(IMAGE, dockerfile)
        built = true
        IMAGE
    }
}
