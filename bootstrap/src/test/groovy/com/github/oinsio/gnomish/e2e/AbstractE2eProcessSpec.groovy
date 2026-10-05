package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Shared scaffolding for specs that drive a real {@code gnomish run} process against the
 * {@code e2e} fixture: the process harness, the stateful command check's marker-file name,
 * the files the fake agent writes, and the cleanup that resets them so a later spec doesn't
 * see a stale already-passing check or a spent multi-attempt scenario left behind by an
 * earlier run ({@link ExitCodeMatrixSpec}, {@link ReferenceE2ESessionSpec},
 * {@link E2eProcessHarnessSmokeSpec} all extend this rather than re-declaring the trio).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
abstract class AbstractE2eProcessSpec extends Specification {

    protected static final String MARKER_FILE = 'attempt-marker.txt'

    /**
     * What the fake agent leaves in an in-place workspace: {@code plain-round}'s own output file
     * and the marker a multi-attempt scenario drops to remember its first invocation. The marker
     * would turn a later run's {@code decision-then-plain} into a plain round, so it is reset with
     * the command check's marker.
     */
    protected static final List<String> FAKE_AGENT_FILES = [
        'output.txt',
        '.gnomish-fake-attempt-marker'
    ]

    protected final E2eProcessHarness harness = new E2eProcessHarness()

    def cleanup() {
        Files.deleteIfExists(E2eFixture.projectRoot().resolve(MARKER_FILE))
        FAKE_AGENT_FILES.each {
            Files.deleteIfExists(E2eFixture.projectRoot().resolve(it))
        }
    }
}
