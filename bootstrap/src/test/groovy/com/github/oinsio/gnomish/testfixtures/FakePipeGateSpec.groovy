package com.github.oinsio.gnomish.testfixtures

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR7 of make-pipe-reads-non-parking (design D6, the {@code FakePipe} row of the single-owner
 * table): the fixture gate beside {@link FakePipeSpec}. A {@link Process} double is
 * {@code FakeProcess} or one of its listed subclasses, and none of them replaces the streams — so
 * every double's pipe is the {@code FakePipe} that behaves as a Linux pipe, and a stream double
 * laxer than the platform cannot come back through a subclass.
 *
 * <p>The scan covers every test tree at any depth and {@code :test-fixtures}' own sources, comments
 * stripped; each listed file is asserted reached, so a moved file fails instead of widening the gate.
 */
class FakePipeGateSpec extends Specification {

    private static final String FIXTURES = 'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/sandbox/environment/'
    private static final String DOCKER_TESTS = 'sandbox/docker/src/test/groovy/com/github/oinsio/gnomish/sandbox/environment/'

    /** The one direct {@link Process} subclass. */
    private static final String OWNER = FIXTURES + 'FakeProcess.groovy'

    /** The files declaring a subclass of the owner, each with its double. */
    private static final Map<String, String> SUBCLASSES = [
        (DOCKER_TESTS + 'ScriptedDocker.groovy'): 'ScriptedProcess',
        (DOCKER_TESTS + 'ScriptedFileChannelDockerCli.groovy'): 'ScriptedFileChannelProcess',
        (FIXTURES + 'ScriptedSandboxDocker.groovy'): 'FakeExecProcess',
    ]

    private static final Pattern EXTENDS_PROCESS = ~/\bextends\s+Process\b/
    private static final Pattern EXTENDS_OWNER = ~/\bextends\s+FakeProcess\b/
    private static final Pattern STREAM_OVERRIDE = ~/\bInputStream\s+get(?:Input|Error)Stream\s*\(/

    // FR7: a Process double is the owner or a listed subclass of it
    def "FR7: FakeProcess is the one Process subclass, and only the listed files subclass it"() {
        given:
        def scanned = sources()
        def direct = matching(scanned, EXTENDS_PROCESS)
        def derived = matching(scanned, EXTENDS_OWNER)

        def reached = scanned.keySet()

        expect: 'the scan reached the trees and every listed file'
        reached.size() >= RepoSourceTree.KNOWN_TEST_SOURCES
        reached.containsAll([OWNER] + SUBCLASSES.keySet())

        and:
        direct == [OWNER]
        derived == SUBCLASSES.keySet().sort()
    }

    // FR7: no subclass replaces the FakePipe streams
    def "FR7: #fake in #file overrides neither stream"() {
        given:
        def code = sources()[file]

        expect:
        code != null
        !STREAM_OVERRIDE.matcher(code).find()

        where:
        file << [
            DOCKER_TESTS + 'ScriptedDocker.groovy',
            FIXTURES + 'ScriptedSandboxDocker.groovy'
        ]
        fake = SUBCLASSES[file]
    }

    // FR7: the file-channel double plays its script on the FakePipe streams, replacing neither
    def "FR7: the file-channel double overrides neither stream"() {
        given:
        def code = sources()[DOCKER_TESTS + 'ScriptedFileChannelDockerCli.groovy']

        expect:
        !STREAM_OVERRIDE.matcher(code).find()
    }

    private static Map<String, String> sources() {
        def files = RepoSourceTree.testSources() + RepoSourceTree.productionSources {
            it.startsWith('test-fixtures/src/main/')
        }
        files.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    private static List<String> matching(Map<String, String> scanned, Pattern pattern) {
        scanned.findAll { pattern.matcher(it.value).find() }.keySet().sort()
    }
}
