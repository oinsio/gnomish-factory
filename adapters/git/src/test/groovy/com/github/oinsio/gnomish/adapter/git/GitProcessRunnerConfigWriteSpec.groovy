package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, NFR-O1 of own-git-invocation-policy (design D4): the runner's untyped entry refuses every
 * argv that writes the repository's configuration — {@code config} in any form but a read,
 * {@code remote} in a write form — before a process is launched, with an exception naming the
 * subcommand and the policy owner; every read form still runs. Each form is tried bare and behind
 * leading {@code -c} pairs, since the classification skips them.
 *
 * <p>Driven through the runner's git-binary seam: the committed {@code record-transfer-environment}
 * preset answers the clone-key {@code rev-parse} and records every other argv, so "never ran" and
 * "ran" are both observable. Linked and launched once per spec (see
 * {@link GitProcessRunnerTransferSpec} for why).
 */
class GitProcessRunnerConfigWriteSpec extends Specification {

    static final List<List<String>> REFUSED = [
        [
            'config',
            'core.hooksPath',
            '/dev/null'
        ],
        [
            'config',
            '--add',
            'remote.origin.fetch',
            '+refs/*:refs/*'
        ],
        [
            'config',
            '--unset',
            'core.hooksPath'
        ],
        [
            'config',
            '--local',
            'user.name',
            'gnome'
        ],
        [
            'config',
            'set',
            'core.hooksPath',
            '/dev/null'
        ],
        [
            'config',
            'unset',
            'core.hooksPath'
        ],
        ['config'],
        [
            'remote',
            'add',
            'upstream',
            '/a'
        ],
        [
            'remote',
            'rename',
            'origin',
            'old'
        ],
        ['remote', 'remove', 'origin'],
        ['remote', 'rm', 'origin'],
        [
            'remote',
            'set-head',
            'origin',
            'main'
        ],
        [
            'remote',
            'set-branches',
            'origin',
            'main'
        ],
        [
            'remote',
            'set-url',
            'origin',
            '/a'
        ],
    ]

    static final List<List<String>> ALLOWED = [
        [
            'config',
            '--get',
            'core.hooksPath'
        ],
        [
            'config',
            '--get-all',
            'remote.origin.fetch'
        ],
        [
            'config',
            '--get-regexp',
            '^remote\\.'
        ],
        [
            'config',
            '--get-urlmatch',
            'http',
            'https://example.com'
        ],
        ['config', '--list'],
        ['config', '-l'],
        [
            'config',
            '--local',
            '--get',
            'core.hooksPath'
        ],
        [
            'config',
            'get',
            'core.hooksPath'
        ],
        ['config', 'list'],
        ['remote', 'get-url', 'origin'],
        ['remote'],
        ['remote', '-v'],
        ['status'],
    ]

    static final List<String> PAIRS = ['-c', 'a=b', '-c', 'c=d']

    @Shared
    @TempDir
    Path tempDir

    @Shared
    Path recordingGit

    def setupSpec() {
        recordingGit = StandIn.recording(tempDir, 'record-transfer-environment')
        // The first launch of the stand-in, paid here rather than inside a feature.
        new GitProcessRunner(recordingGit.toString()).run(tempDir, 'version')
    }

    def setup() {
        Files.deleteIfExists(StandIn.log(recordingGit))
    }

    // Scenario: git/git-invocation-policy / A configuration write is refused before it runs
    def "FR5, NFR-O1: the untyped entry refuses #args before any process is launched"() {
        when:
        new GitProcessRunner(recordingGit.toString()).run(tempDir, args as String[])

        then: 'the refusal names the subcommand and the policy owner, and the stand-in never ran'
        def e = thrown(RepositoryConfigWriteException)
        e.message.startsWith("git ${args.find { it in ['config', 'remote'] }} in a write form")
        e.message.contains('GitInvocation')
        !Files.exists(StandIn.log(recordingGit))

        where:
        args << REFUSED + REFUSED.collect { PAIRS + it }
    }

    // Scenario: git/git-invocation-policy / A configuration read runs
    def "FR5: the untyped entry still runs #args"() {
        when:
        def result = new GitProcessRunner(recordingGit.toString()).run(tempDir, args as String[])

        then:
        result.exitCode() == 0
        Files.exists(StandIn.log(recordingGit))

        where:
        args << ALLOWED + ALLOWED.collect { PAIRS + it }
    }

    def "FR5: the classification never indexes past the end of #args"() {
        expect:
        RepositoryConfigWrites.refusedSubcommand(args as String[]) == Optional.ofNullable(expected)

        where:
        args | expected
        [] | null
        ['-c'] | null
        ['-c', 'a=b'] | null
        ['-c', 'a=b', 'config'] | 'config'
        ['remote'] | null
        ['-c', 'a=b', 'remote', 'add'] | 'remote'
    }
}
