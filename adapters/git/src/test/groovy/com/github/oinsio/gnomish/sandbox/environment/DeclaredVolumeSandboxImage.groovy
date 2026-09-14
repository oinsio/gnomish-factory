package com.github.oinsio.gnomish.sandbox.environment

/**
 * Builds (once per JVM) the sandbox image the declared-volume identity spec runs
 * against: {@link GitSandboxImage}'s shape plus two {@code VOLUME} declarations —
 * one path outside the factory's own mounts ({@code /cache}) and one equal to the
 * working copy ({@code /gnomish/work}), so a single materialize exercises both
 * halves of the rule (FR5 of fix-image-declared-volumes): the outside path is made
 * ephemeral, the explicitly mounted one keeps its named factory volume.
 *
 * <p>The declared path is owned by the image's own {@code gnome} user, as an image that
 * means its user to write there would arrange it — which is what makes the mode half of
 * design D5 observable: the runtime mounts the {@code tmpfs} {@code root:root} and copies
 * no ownership (runc's tmpfs path has no {@code chown}), where the anonymous volume the
 * change prevents copied owner and mode alike. The write the spec then performs as
 * {@code gnome} succeeds only because of {@code tmpfs-mode=1777}, so this image is what
 * turns that assertion into the runc-floor gate.
 *
 * <p>The marker file baked at {@code /cache/baked.txt} is what makes the deliberate
 * behaviour change of design D1 observable: an anonymous volume would receive a copy
 * of it, a {@code tmpfs} mounts empty, so its absence in the box is the assertion
 * that the path really is the override and not the image's own content.
 */
class DeclaredVolumeSandboxImage {

    static final String IMAGE = 'gnomish-sandbox-volume-test:latest'

    /** The declared path outside every factory mount — the one the override occupies. */
    static final String DECLARED_PATH = '/cache'

    /** The file the image bakes under {@link #DECLARED_PATH}, invisible once the path is a tmpfs. */
    static final String BAKED_FILE = DECLARED_PATH + '/baked.txt'

    private static volatile boolean built = false

    /** Builds the image if this JVM has not yet; returns the tag. Asserts the build succeeds. */
    static synchronized String ensureBuilt() {
        if (built) {
            return IMAGE
        }
        def dockerfile = """
            FROM alpine:3
            RUN apk add --no-cache git \\
             && adduser -D -u 1000 gnome \\
             && mkdir -p /gnomish/work /gnomish/scratch ${DECLARED_PATH} \\
             && chown -R gnome:gnome /gnomish \\
             && printf 'baked\\n' > ${BAKED_FILE} \\
             && chown -R gnome:gnome ${DECLARED_PATH}
            VOLUME ["${DECLARED_PATH}", "${ContainerTaskExecutionEnvironment.WORKING_COPY}"]
            USER gnome
        """.stripIndent()
        DockerImageBuilder.build(IMAGE, dockerfile)
        built = true
        IMAGE
    }
}
