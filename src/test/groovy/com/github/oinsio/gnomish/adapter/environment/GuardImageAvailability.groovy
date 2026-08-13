package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability

/**
 * Docker-plus-guard-image prerequisite detection for the egress integration
 * layer (task 6.5, M2): beyond a reachable daemon (the {@link GiteaAvailability}
 * convention), the real mitmproxy guard image must be locally present or
 * pullable — an offline dev machine without the image skips the spec cleanly
 * instead of failing on the pull. The answer is memoized so the (possibly slow)
 * pull happens at most once per JVM.
 */
final class GuardImageAvailability {

    /** The image the integration spec runs the real guard from; matches the SandboxProperties default. */
    static final String IMAGE = 'mitmproxy/mitmproxy:12'

    private static volatile Boolean available

    private GuardImageAvailability() {}

    /**
     * @return {@code true} when the daemon is reachable and the guard image is
     *     locally present or was pulled; never throws
     */
    static synchronized boolean available() {
        if (available == null) {
            available = GiteaAvailability.dockerAvailable() && (imagePresent() || pulled())
        }
        available
    }

    private static boolean imagePresent() {
        docker('image', 'inspect', IMAGE) == 0
    }

    private static boolean pulled() {
        docker('pull', IMAGE) == 0
    }

    private static int docker(String... args) {
        try {
            def process = new ProcessBuilder(['docker'] + args.toList()).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            return process.waitFor()
        } catch (IOException ignored) {
            return -1
        }
    }
}
