package com.github.oinsio.gnomish.testfixtures.standin

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR24, NFR-P2 of supervise-daemon-loops-and-embed-dashboard (design D23, ADR 0015): the committed
 * stand-in library is well formed scenario by scenario, and its one script does what its table says —
 * each action once, here, so no spec that selects a scenario re-tests the mechanics.
 *
 * <p>Lives in {@code :bootstrap} because {@code :test-fixtures} has no test source set. Every run
 * passes the leading {@code -c} pairs a runner would, so the strip is exercised throughout.
 */
class StandInLibrarySpec extends Specification {

    /** The actions a table row may name, with how many arguments each takes at least. */
    static final Map<String, Integer> ACTIONS = [
        record: 0, stdout: 1, stderr: 1, delay: 1, run: 1, write: 2, export: 2, 'export-name': 1, 'close-stdout': 0,
        exit: 1, answer: 2, refuse: 2, stall: 1, delegate: 1, 'exec-sh': 1,
    ]

    /** The action arguments that name a file of the library (by action, argument positions). */
    static final Map<String, List<Integer>> FILE_ARGUMENTS = [
        stdout: [0], stderr: [0], run: [0], write: [0], answer: [0], refuse: [1], 'exec-sh': [0],
    ]

    @TempDir
    Path tempDir

    private final List<Process> started = []

    def cleanup() {
        started.each { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    def "FR24: scenario #scenario is a link onto the one script and one section whose rows parse"() {
        given:
        Path link = StandIn.preset(scenario)

        expect: 'a relative link onto the one script'
        Files.readSymbolicLink(link).toString() == '../stand-in.sh'
        Files.isSameFile(link, StandIn.library().resolve('stand-in.sh'))

        and: 'one section, every row a known action with its arguments, every file it names in the library'
        def rows = StandIn.tables().rows(scenario)
        !rows.isEmpty()
        rows.every { List<String> row ->
            ACTIONS.containsKey(row[1]) && row.size() - 2 >= ACTIONS[row[1]]
        }
        rows.collectMany { List<String> row ->
            FILE_ARGUMENTS.getOrDefault(row[1], []).collect { row[2 + it] }
        }.findAll {
            it != '-' && !it.startsWith('@')
        }.every {
            it.contains('#') ? StandIn.data(it - 'data/') != null : Files.isRegularFile(StandIn.library().resolve(it))
        }

        where:
        scenario << scenarios()
    }

    def "FR24: every section has its link, and no scenario has two sections"() {
        given:
        List<String> sections = StandIn.tables().sections().values().flatten() as List<String>

        expect:
        sections.toSorted() == scenarios()
        sections.toSet().size() == sections.size()
    }

    def "FR24: #file stays within the file-size budget and ends with a line break"() {
        expect:
        file.toFile().readLines().size() <= 120
        file.toFile().text.endsWith('\n')

        where:
        file << StandIn.tables().tables() + Files.list(StandIn.library().resolve('data')).withCloseable {
            it.sorted().toList()
        }
    }

    def "FR24: every answer under data/ is one some scenario's row names"() {
        given:
        Set<String> named = StandInLibrarySpec.scenarios().collectMany { String scenario ->
            StandIn.tables().rows(scenario).collectMany { List<String> row ->
                FILE_ARGUMENTS.getOrDefault(row[1], []).collect { row[2 + it] }
            }
        }.findAll { it.startsWith('data/') }.collect { it - 'data/' } as Set

        expect:
        Files.list(StandIn.library().resolve('data')).withCloseable {
            it.toList()
        }.collectMany { Path file ->
            file.toFile().readLines().findAll {
                it ==~ /\[[^\]]+\]/
            }.collect {
                "${file.fileName}#${it[1..-2]}".toString()
            }
        }.findAll { !(it in named) } == []
    }

    def "FR24: no file of #dir holds a machine path"() {
        expect:
        Files.list(StandIn.library().resolve(dir)).withCloseable {
            it.toList()
        }.every { Path file ->
            [
                '/Users',
                '/home',
                '/var/folders'
            ].every {
                !file.toFile().text.contains(it)
            }
        }

        where:
        dir << [
            'tables',
            'data',
            'steps',
            'process'
        ]
    }

    def "FR24: process fake #name is a committed executable script under the interpreter line of the library's own"() {
        given:
        Path script = StandIn.process(name)

        expect:
        Files.isExecutable(script)
        script.toFile().readLines().first() == StandIn.library().resolve('stand-in.sh').toFile().readLines().first()
        [
            '/Users',
            '/home',
            '/var/folders'
        ].every {
            !script.toFile().text.contains(it)
        }

        where:
        name << Files.list(StandIn.library().resolve('process')).withCloseable { stream ->
            stream.map { it.fileName.toString() - '.sh' }.sorted().toList()
        }
    }

    def "FR24: the library holds no log — nothing ran a recording scenario through its committed link"() {
        expect:
        Files.walk(StandIn.library()).withCloseable { walk ->
            walk.filter { it.fileName.toString().endsWith('.log') }.toList()
        } == []
    }

    def "FR24: answer rows match by argv prefix after the -c pairs, the first match wins"() {
        given:
        Path git = StandIn.git('action-answer')

        expect:
        answer(git, 'rev-parse', '--git-common-dir') == [exit: 0, stdout: '.git\n']
        answer(git, 'rev-parse', 'HEAD') == [exit: 1, stdout: '']
        answer(git, 'remote', 'get-url', 'origin') == [exit: 0, stdout: StandIn.data('common#url')]
        answer(git, 'version') == [exit: 0, stdout: '']
    }

    def "FR24: a refuse row exits with its code and its scenario's stderr; the rest reaches the real git"() {
        given:
        Path git = StandIn.git('refuse-fetch')
        Path repo = Files.createDirectories(tempDir.resolve('repo'))
        assert realGit(repo, 'init', '-q') == 0

        when:
        def refused = run(git, repo, 'fetch', 'origin')
        def delegated = run(git, repo, 'rev-parse', '--is-inside-work-tree')

        then:
        refused.exit == 128
        refused.stderr == StandIn.data('stderr#unable-to-access')
        delegated == [exit: 0, stdout: 'true\n', stderr: '']
    }

    def "FR24: a stall row leaves the process alive until it is killed, and the kill reaches the sleep itself"() {
        given:
        Process process = start(StandIn.git('stall-fetch'), tempDir, 'fetch', 'origin')

        expect:
        !process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        process.descendants().count() == 0
    }

    def "FR24: a record row appends one block per invocation beside the per-run link, and an answered one none"() {
        given:
        Path git = StandIn.recording(tempDir, 'action-record')

        when:
        run(git, ['GIT_SSH_COMMAND': 'ssh -o BatchMode=yes'], 'push', 'origin')
        run(git, 'rev-parse', '--git-common-dir')
        run(git, 'status', '--short')

        then: 'the argv keeps its -c pairs; a variable reads back set or unset'
        StandInLog.blocks(git)*.subMap([
            'argv',
            'GIT_SSH_COMMAND',
            'STAND_IN_UNSET'
        ]) == [
            [argv: '-c core.askPass= -c a.b=c push origin', GIT_SSH_COMMAND: '[ssh -o BatchMode=yes]', STAND_IN_UNSET: 'unset'],
            [argv: '-c core.askPass= -c a.b=c status --short', GIT_SSH_COMMAND: 'unset', STAND_IN_UNSET: 'unset'],
        ]

        and: 'each argument is kept as it was passed'
        StandInLog.argv(StandInLog.blocks(git)[0]) == [
            '-c',
            'core.askPass=',
            '-c',
            'a.b=c',
            'push',
            'origin'
        ]

        and: 'the log is the one file beside the link'
        Files.list(tempDir).withCloseable {
            it.map {
                it.fileName.toString()
            }.sorted().toList()
        } == [
            'action-record-1',
            'action-record-1.log'
        ]
    }

    def "FR24: a recorded value holding a line break or a backslash reads back whole"() {
        given:
        Path git = StandIn.recording(tempDir, 'action-record')
        String message = 'line one\n\na\\path\n--'

        when:
        run(git, ['GIT_SSH_COMMAND': 'a\\b\nc'], 'commit', '-m', message)

        then: 'one block, every value as it was passed'
        def blocks = StandInLog.blocks(git)
        blocks.size() == 1
        StandInLog.argv(blocks[0]) == [
            '-c',
            'core.askPass=',
            '-c',
            'a.b=c',
            'commit',
            '-m',
            message
        ]
        blocks[0].GIT_SSH_COMMAND == '[a\\b\nc]'
    }

    def "FR24: a recorded delegation logs a block before the real git and its exit after"() {
        given:
        Path git = StandIn.recording(tempDir, 'record-delegate')

        when:
        def result = run(git, tempDir, 'rev-parse', '--is-inside-work-tree')

        then:
        result.exit == 128
        def blocks = StandInLog.blocks(git)
        blocks.size() == 2
        blocks[0].argv == '-c core.askPass= -c a.b=c rev-parse --is-inside-work-tree'
        blocks[1] == [pid: blocks[0].pid, exit: '128']
    }

    def "FR24: @name rows read what the spec wrote beside the per-run link"() {
        given:
        Path git = StandIn.recording(tempDir, 'action-per-run')
        StandIn.beside(git, 'ls-remote.out').toFile().text = 'abc\trefs/heads/main\n'
        StandIn.beside(git, 'ls-remote.exit').toFile().text = '2\n'

        expect:
        answer(git, 'ls-remote', 'origin') == [exit: 2, stdout: 'abc\trefs/heads/main\n']
    }

    def "FR24: steps run in order before the terminal row — stderr, a delay, the spec's hook, a write"() {
        given:
        Path git = StandIn.recording(tempDir, 'action-steps')
        Path ran = tempDir.resolve('hook-ran')
        StandIn.beside(git, 'hook.sh').toFile().text = "echo \"\$@\" > '${ran}'\n"

        when:
        long begin = System.nanoTime()
        def result = run(git, 'push', 'origin')
        Duration elapsed = Duration.ofNanos(System.nanoTime() - begin)

        then:
        result.exit == 3
        result.stderr == StandIn.data('stderr#password-prompt-leak')
        elapsed >= Duration.ofMillis(300)
        ran.toFile().text == '-c core.askPass= -c a.b=c push origin\n'

        and: 'what the push wrote is what a later invocation reads'
        answer(git, 'status') == [exit: 0, stdout: StandIn.data('stdout-git#landed')]
    }

    def "FR24: an exported variable reaches the script exec-sh becomes"() {
        expect:
        answer(StandIn.git('action-exec'), 'version') == [exit: 0, stdout: 'probe=probe-value argv=-c core.askPass= -c a.b=c version\n']
    }

    def "FR24: export-name hands the script the name of the per-run link, so one scenario serves every value"() {
        given:
        Path link = StandIn.link(tempDir.resolve('probe-name'), 'action-export-name')

        expect:
        answer(link, 'version') == [exit: 0, stdout: 'probe=probe-name argv=-c core.askPass= -c a.b=c version\n']
    }

    def "FR24: export-name through the committed link is refused — its name is not a value"() {
        when:
        def result = run(StandIn.preset('action-export-name'), 'version')

        then:
        result.exit == 97
        result.stderr.contains('export-name needs a per-run link')
    }

    def "FR24: close-stdout ends the output while the process stays"() {
        given:
        Process process = start(StandIn.git('closed-stdout-stall'), tempDir, 'status')

        expect:
        process.inputStream.text == ''
        process.alive
    }

    def "FR24: an invocation no row matches is a broken scenario, exit 97 with the argv on stderr"() {
        when:
        def result = run(StandIn.git('action-broken'), 'status')

        then:
        result.exit == 97
        result.stderr.contains('no row matches: -c core.askPass= -c a.b=c status')
    }

    def "FR24: a recording scenario run through its committed link is refused and writes nothing into the library"() {
        given: 'the committed path, which StandIn.git refuses to hand out'
        Path committed = StandIn.preset('action-record')

        when:
        def result = run(committed, 'status')

        then:
        result.exit == 97
        result.stderr.contains('@log needs a per-run link')
        !Files.exists(StandIn.log(committed))
    }

    def "NFR-P2: after the script's first run, a scenario starts in milliseconds through a fresh link"() {
        given: 'one untimed run: the script\'s own first-run assessment (macOS), paid once per checkout'
        run(StandIn.git('action-answer'), 'version')

        when:
        List<Duration> runs = (1..5).collect {
            Path link = StandIn.recording(tempDir, 'action-answer')
            long begin = System.nanoTime()
            run(link, 'version')
            Duration.ofNanos(System.nanoTime() - begin)
        }

        then:
        runs.every { it <Duration.ofMillis(500) }
    }

    /** Every scenario of the library: the names of its committed links. */
    static List<String> scenarios() {
        Files.list(StandIn.library().resolve('links')).withCloseable { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        }
    }


    /** Exit code and stdout of one run, for the features that assert only those two. */
    private Map answer(Path binary, String... argv) {
        run(binary, argv).subMap(['exit', 'stdout'])
    }

    private Map run(Path binary, String... argv) {
        run(binary, tempDir, [:], argv)
    }

    private Map run(Path binary, Map<String, String> environment, String... argv) {
        run(binary, tempDir, environment, argv)
    }

    private Map run(Path binary, Path dir, String... argv) {
        run(binary, dir, [:], argv)
    }

    /** Runs {@code binary} with the leading {@code -c} pairs a runner passes; exit, stdout and stderr. */
    private Map run(Path binary, Path dir, Map<String, String> environment, String... argv) {
        Process process = start(binary, dir, environment, argv)
        String stdout = process.inputStream.text
        String stderr = process.errorStream.text
        [exit: process.waitFor(), stdout: stdout, stderr: stderr]
    }

    private Process start(Path binary, Path dir, String... argv) {
        start(binary, dir, [:], argv)
    }

    private Process start(Path binary, Path dir, Map<String, String> environment, String... argv) {
        def builder = new ProcessBuilder([
            binary.toString(),
            '-c',
            'core.askPass=',
            '-c',
            'a.b=c'
        ] + argv.toList())
        .directory(dir.toFile())
        TestChildEnvironment.cleared(builder).putAll(environment)
        Process process = builder.start()
        started << process
        process
    }

    private static int realGit(Path dir, String... argv) {
        def builder = new ProcessBuilder(['git'] + argv.toList()).directory(dir.toFile()).inheritIO()
        TestChildEnvironment.cleared(builder)
        builder.start().waitFor()
    }
}
