package com.github.oinsio.gnomish.adapter.environment

/**
 * Builds (once per JVM) the curl-capable sandbox image the egress integration
 * spec runs against: {@link GitSandboxImage}'s shape (alpine + git, non-root
 * {@code gnome} user owning {@code /gnomish/**}) plus {@code curl} — the probe
 * tool the self-check contract requires the sandbox image to provide (FR8; the
 * reference image recipe of task 9.1 bakes it for real).
 */
class EgressProbeImage {

    static final String IMAGE = 'gnomish-sandbox-egress-test:latest'

    private static volatile boolean built = false

    /** Builds the image if this JVM has not yet; returns the tag. Asserts the build succeeds. */
    static synchronized String ensureBuilt() {
        if (built) {
            return IMAGE
        }
        def dockerfile = '''
            FROM alpine:3
            RUN apk add --no-cache git curl busybox-extras \\
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
