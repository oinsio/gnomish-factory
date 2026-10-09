package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.Refspec
import com.github.oinsio.gnomish.gittransfer.TransferSource
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, FR6, NFR-S2, NFR-S3 of own-git-transfer-argv (design D5): the runner's typed entry applies
 * the owner's environment entries to the child and executes the owner's argv through the same
 * bounded, stall-detected path as before, and the untyped entry refuses every transfer subcommand
 * before a process is launched — so a transfer enters the runner only as the owner's value.
 *
 * <p>Driven through the runner's git-binary seam: a stand-in that answers the clone-key
 * {@code rev-parse} and records the argv and the environment it was handed to a file the spec
 * reads back. The environment half is only observable from inside the child, which is why the
 * stand-in reports it rather than the spec inspecting a builder.
 *
 * <p>The stand-in is written and launched once per spec, not per feature: macOS checks an
 * executable on its first launch, which costs some 300-500 ms per fresh file. Paid inside a feature,
 * that cost made every process-launching feature here slower than hundreds of other covering tests,
 * so the "FR6, NFR-S2" feature — the intended first killer of the transfer-environment mutant — ran
 * after 669 of them (FR1, M2 of kill-expensive-mutants, task 2.3; measured, scoped run of
 * 2026-10-09).
 */
class GitProcessRunnerTransferSpec extends Specification {

    static final String REFSPEC = 'refs/heads/main:refs/remotes/origin/main'

    @Shared
    @TempDir
    Path tempDir

    @Shared
    Path record

    @Shared
    Path recordingGit

    def setupSpec() {
        record = tempDir.resolve('record.txt')
        recordingGit = new RecordingGit(record)
                .answer('rev-parse', '.git')
                .record('argv', '$*')
                .record('allow', '[${GIT_ALLOW_PROTOCOL-unset}]')
                .record('count', '[${GIT_CONFIG_COUNT-unset}] key0=[${GIT_CONFIG_KEY_0-unset}]')
                .record('askpass', '[${GIT_ASKPASS-unset}] ssh=[${SSH_ASKPASS-unset}]')
                .record('global', '[${GIT_CONFIG_GLOBAL-unset}]')
                .write(tempDir)
        // The first launch of the fresh executable, paid here rather than inside a feature.
        new GitProcessRunner(recordingGit.toString()).run(tempDir, 'version')
    }

    def setup() {
        Files.deleteIfExists(record)
    }

    def "FR8: the typed entry runs the owner's fetch through the bounded, stall-detected path"() {
        given:
        def transfer = GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(REFSPEC))

        when:
        def result = recordingRunner().run(tempDir, transfer)

        then: 'the child ran the owner\'s argv, with the network prefix the runner puts before it'
        result.exitCode() == 0
        recorded('argv') == '-c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 ' + transfer.argv().join(' ')
    }

    def "FR6, NFR-S2: the allowlist reaches the child and the inherited per-process configuration does not"() {
        given: 'the test JVM itself carries an inherited per-process configuration (the build sets it)'
        assert System.getenv('GIT_CONFIG_COUNT') == '1'

        when: 'the untyped entry runs a non-transfer command'
        recordingRunner().run(tempDir, 'ls-remote', 'origin')

        then: 'the child inherits it, as every git command did before'
        recorded('count') == '[1] key0=[' + System.getenv('GIT_CONFIG_KEY_0') + ']'

        when: 'the typed entry runs a transfer'
        Files.deleteIfExists(record)
        recordingRunner().run(tempDir, GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(REFSPEC)))

        then: 'the allowlist is set and the inherited configuration is gone'
        recorded('allow') == '[https:http:ssh:file]'
        // The owner strips the count, which is what git reads the keyed entries under; a key
        // left behind without its count is inert (git-config(1), GIT_CONFIG_COUNT).
        recorded('count') == '[unset] key0=[' + System.getenv('GIT_CONFIG_KEY_0') + ']'
    }

    def "FR6: #kind carries its own configuration isolation into the child"() {
        when:
        recordingRunner().run(tempDir, transfer)

        then:
        recorded('allow') == "[${allow}]"
        recorded('global') == "[${global}]"

        where:
        kind | transfer | allow | global
        'origin' | GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(REFSPEC)) | 'https:http:ssh:file' | System.getenv('GIT_CONFIG_GLOBAL')
        'container' | GitTransfer.fetch(new TransferSource.Container('ext::docker exec -i box %S /work'), new Refspec(REFSPEC)) | 'ext' | '/dev/null'
        'seed' | GitTransfer.clone(new TransferSource.SeedPath(Path.of('/seed/src'), Path.of('/seed/dst'))) | 'file' | TransferSource.SeedPath.SAFE_DIRECTORY_CONFIG
    }

    def "NFR-R2: the askpass hooks reach the child empty on the #entry entry"() {
        given:
        def runner = recordingRunner()

        when:
        if (typed) {
            runner.run(tempDir, GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(REFSPEC)))
        } else {
            runner.run(tempDir, 'ls-remote', 'origin')
        }

        then:
        recorded('askpass') == '[] ssh=[]'

        where:
        entry | typed
        'typed' | true
        'untyped' | false
    }

    def "FR6: a present entry is set and an empty one removed, whatever the child inherited"() {
        given: 'a child environment that inherited the variables a transfer strips, and one it sets'
        Map<String, String> environment = [GIT_CONFIG_COUNT: '2', GIT_ALLOW_PROTOCOL: 'git', UNRELATED: 'kept']
        def entries = new LinkedHashMap<String, Optional<String>>()
        entries.GIT_CONFIG_COUNT = Optional.empty()
        entries.GIT_ALLOW_PROTOCOL = Optional.of('ext')
        entries.GIT_CONFIG_GLOBAL = Optional.of('/dev/null')

        when:
        GitNetworkCommands.applyTransferEnvironment(environment, entries)

        then:
        environment == [GIT_ALLOW_PROTOCOL: 'ext', UNRELATED: 'kept', GIT_CONFIG_GLOBAL: '/dev/null']
    }

    def "FR8: the untyped entry refuses #args before any process is launched"() {
        when:
        recordingRunner().run(tempDir, args as String[])

        then: 'the refusal names the owner, and the stand-in never ran'
        def e = thrown(UnownedTransferException)
        e.message.contains('GitTransfer')
        !Files.exists(record)

        where:
        args << [
            ['fetch', 'origin', REFSPEC],
            ['clone', '/a', '/b'],
            ['pull'],
            [
                'submodule',
                'update',
                '--init'
            ],
            ['remote', 'update'],
            [
                '-c',
                'fetch.prune=false',
                'fetch',
                'origin',
                REFSPEC
            ],
            [
                '-c',
                'a=b',
                '-c',
                'c=d',
                'clone',
                '/a',
                '/b'
            ],
            ['-c', 'a=b', 'pull'],
        ]
    }

    def "FR8: the untyped entry still runs #args"() {
        when:
        def result = recordingRunner().run(tempDir, args as String[])

        then:
        result.exitCode() == 0
        recorded('argv').endsWith(args.join(' '))

        where:
        args << [
            ['push', 'origin', 'HEAD'],
            ['ls-remote', 'origin'],
            ['submodule', 'status'],
            [
                'remote',
                'add',
                'origin',
                '/a'
            ],
            ['-c', 'a=b', 'status'],
        ]
    }

    def "FR8: the transfer classification skips leading -c pairs and never indexes past the end"() {
        expect:
        GitNetworkCommands.isTransfer(args as String[]) == expected

        where:
        args | expected
        [] | false
        ['-c'] | false
        ['-c', 'a=b'] | false
        ['submodule'] | false
        ['remote'] | false
        ['remote', 'get-url'] | false
        [
            '-c',
            'a=b',
            'submodule',
            'update'
        ] | true
    }

    /**
     * A runner over the spec's git stand-in, which answers the runner's clone-key {@code rev-parse}
     * and records everything else — the argv and the environment it was handed — to the record
     * file, then exits 0.
     */
    private GitProcessRunner recordingRunner() {
        new GitProcessRunner(recordingGit.toString())
    }

    private String recorded(String key) {
        def line = record.toFile().readLines().find { it.startsWith(key + '=') }
        assert line != null: "the stand-in recorded no '${key}' line"
        line.substring(key.length() + 1)
    }
}
