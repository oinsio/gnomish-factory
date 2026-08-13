package com.github.oinsio.gnomish.adapter.environment

/**
 * Shared docker-image-build mechanics for the JVM-memoized sandbox test
 * fixtures ({@link GitSandboxImage}, {@link EgressProbeImage}): build a tag
 * from an inline Dockerfile passed on stdin, asserting the build succeeds.
 */
final class DockerImageBuilder {

    private DockerImageBuilder() {}

    /** Builds {@code tag} from {@code dockerfile} via `docker build`; asserts success. */
    static void build(String tag, String dockerfile) {
        def process = new ProcessBuilder('docker', 'build', '-t', tag, '-').redirectErrorStream(true).start()
        process.outputStream.withWriter('UTF-8') { it << dockerfile }
        def output = new String(process.inputStream.readAllBytes(), 'UTF-8')
        assert process.waitFor() == 0: "docker build of ${tag} failed:\n${output}"
    }
}
