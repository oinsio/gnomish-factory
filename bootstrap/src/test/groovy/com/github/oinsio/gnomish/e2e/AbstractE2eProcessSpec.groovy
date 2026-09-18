package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Shared scaffolding for specs that drive a real {@code gnomish run} process against the
 * {@code e2e} fixture: the process harness, the stateful command check's marker-file name,
 * and the cleanup that resets it so a later spec doesn't see a stale already-passing check
 * left behind by an earlier run ({@link ExitCodeMatrixSpec}, {@link ReferenceE2ESessionSpec},
 * {@link E2eProcessHarnessSmokeSpec} all extend this rather than re-declaring the trio).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
abstract class AbstractE2eProcessSpec extends Specification {

    protected static final String MARKER_FILE = 'attempt-marker.txt'

    protected final E2eProcessHarness harness = new E2eProcessHarness()

    def cleanup() {
        Files.deleteIfExists(E2eFixture.projectRoot().resolve(MARKER_FILE))
    }
}
