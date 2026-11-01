package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.testfixtures.standin.StandIn

/**
 * Builds (once per JVM) the sandbox image of the packaged-jar end-to-end suite's {@code program}
 * stage (task 8.3 of define-executor-contract, FR10): the sandbox base image with the committed
 * stand-in library copied in at {@link #LIBRARY}, and the reference executor's link beside its
 * interpreter, so the program a law declares as {@link #REFERENCE} is the reference preset of
 * {@code tables/executor.params} (design D18) running inside the box.
 *
 * <p>The library is copied into the image, not linked: a host link does not resolve inside a box
 * (testing.md, the container exemption). Nothing is generated: the copy is the library as committed,
 * its modes included, and the one link is the name the interpreter reads its preset from, made in
 * the image the way {@code StandIn} makes it in a test JVM.
 */
final class ReferenceExecutorSandboxImage {

    /** Where the library lives inside the box. */
    static final String LIBRARY = '/opt/gnomish-stand-in'

    /** The in-box path of the reference executor: the program argv of the fixture's declaration. */
    static final String REFERENCE = LIBRARY + '/reference'

    /** The image tag the spawned factory is configured with. */
    static final String TAG = 'gnomish-sandbox-e2e-reference-executor:latest'

    private static boolean built

    private ReferenceExecutorSandboxImage() {}

    /** Builds the image if this JVM has not yet; returns its tag. */
    static synchronized String ensureBuilt() {
        if (!built) {
            SandboxBaseImage.buildFromText(TAG, StandIn.library(), """
                FROM \${BASE}
                USER root
                COPY . ${LIBRARY}
                RUN ln -s stand-in.sh ${REFERENCE}
                USER gnome
            """)
            built = true
        }
        TAG
    }
}
