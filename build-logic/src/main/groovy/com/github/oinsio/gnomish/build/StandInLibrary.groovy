package com.github.oinsio.gnomish.build

import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Hands a Test JVM the committed stand-in library (ADR 0015, design D23 of
 * supervise-daemon-loops-and-embed-dashboard) as the {@code standInDir} system property, which
 * {@code StandIn} in {@code :test-fixtures} reads.
 *
 * <p>The scripts run from the source tree, not from a copy: a resource copy would be a new
 * executable file per build (and a jar none at all), which is exactly the first-run assessment the
 * library exists to pay once per checkout. The library's content is an input of the task — a
 * changed preset re-runs the specs — while its location is not, so a second checkout still hits
 * the build cache ({@link LogExpectationEvidence} measured why that matters).
 */
class StandInLibrary implements CommandLineArgumentProvider {

    /** The system property {@code StandIn} reads. */
    static final String PROPERTY = 'standInDir'

    /** The library, relative to the root project. */
    static final String RELATIVE_PATH = 'test-fixtures/src/main/resources/stand-in'

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    File directory

    @Override
    Iterable<String> asArguments() {
        ["-D${PROPERTY}=${directory.absolutePath}".toString()]
    }
}
