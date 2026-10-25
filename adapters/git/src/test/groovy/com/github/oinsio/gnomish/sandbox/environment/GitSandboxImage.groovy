package com.github.oinsio.gnomish.sandbox.environment

/**
 * Builds (once per JVM) the git-capable sandbox image the Docker-gated environment specs run
 * against: the sandbox base image and nothing of its own. The base carries everything the seed
 * clone and the file channel need — a git at the factory's floor, the non-root {@code gnome} user
 * owning {@code /gnomish/**} — and the image-contract label the box start checks, so this image is
 * the smallest operator image there is: one built {@code FROM} the base with no git of its own
 * (design D7 of add-sandbox-base-image).
 */
class GitSandboxImage {

    static final String IMAGE = 'gnomish-sandbox-git-test:latest'

    private static volatile boolean built = false

    /** Builds the image if this JVM has not yet; returns the tag. Asserts the build succeeds. */
    static synchronized String ensureBuilt() {
        if (built) {
            return IMAGE
        }
        DockerImageBuilder.build(IMAGE, '''
            FROM ${BASE}
        ''')
        built = true
        IMAGE
    }
}
