package com.github.oinsio.gnomish.sandbox.environment

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared docker-image-build mechanics for the JVM-memoized sandbox test fixtures of this module
 * ({@link GitSandboxImage}, {@link EgressProbeImage}, {@link DeclaredVolumeSandboxImage}, and the
 * images of {@link ContainerReadOnlySurfacesSpec} and {@link EgressSelfCheckCurlrcSpec}): every one
 * builds {@code FROM} the sandbox base image the {@code :test-fixtures} fixture builds once per JVM
 * ({@link SandboxBaseImage}), so the user, the root-owned home, the git at the factory's floor, curl
 * and the image-contract label are the base's, and a test image adds only what its scenario needs
 * (design D7 of add-sandbox-base-image).
 *
 * <p>Implements FR10, FR11 of add-sandbox-base-image.
 */
final class DockerImageBuilder {

    private DockerImageBuilder() {}

    /**
     * Builds {@code tag} from {@code dockerfile}, whose box stage starts {@code FROM ${BASE}}, with an
     * empty build context; asserts success.
     */
    static void build(String tag, String dockerfile) {
        Path context = Files.createTempDirectory('gnomish-image-context-')
        try {
            SandboxBaseImage.buildFromText(tag, context, dockerfile)
        } finally {
            Files.deleteIfExists(context)
        }
    }
}
