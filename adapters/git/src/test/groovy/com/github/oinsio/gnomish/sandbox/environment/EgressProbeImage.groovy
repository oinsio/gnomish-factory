package com.github.oinsio.gnomish.sandbox.environment

/**
 * Builds (once per JVM) the sandbox image the egress specs run against: the sandbox base image —
 * whose {@code curl} is the probe tool the self-check contract requires (FR8 of add-sandbox-core) —
 * plus two applets of a static busybox the base does not carry: {@code nslookup}, for the direct
 * DNS probe, and {@code httpd}, which the specs' allowlisted target container serves with. The
 * busybox is copied from its official image's own stage, because a stage {@code FROM} the base
 * cannot download (design D13 of add-sandbox-base-image).
 */
class EgressProbeImage {

    static final String IMAGE = 'gnomish-sandbox-egress-test:latest'

    private static volatile boolean built = false

    /** Builds the image if this JVM has not yet; returns the tag. Asserts the build succeeds. */
    static synchronized String ensureBuilt() {
        if (built) {
            return IMAGE
        }
        DockerImageBuilder.build(IMAGE, '''
            FROM busybox:1.37.0-musl AS busybox

            FROM ${BASE}
            USER root
            COPY --from=busybox /bin/busybox /usr/local/bin/busybox
            RUN ln -s busybox /usr/local/bin/nslookup \\
             && ln -s busybox /usr/local/bin/httpd
            USER gnome
        ''')
        built = true
        IMAGE
    }
}
