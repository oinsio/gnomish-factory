package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

/**
 * FR2, UX2 of kill-expensive-mutants (design D4): the {@link StallingGit} builder writes a
 * stand-in that stalls on the chosen subcommands only, answers the rest from its table in
 * declaration order, delays local commands on request, and runs its pre-stall lines before the
 * stall begins.
 *
 * <p>Lives in {@code :bootstrap} because {@code :test-fixtures} has no test source set. The
 * script is run directly, with every leading {@code -c} pair the runner would pass, so the strip
 * is exercised on every feature.
 */
class StallingGitSpec extends Specification {

    @TempDir
    Path tempDir

    private final List<Process> started = []

    def cleanup() {
        started.each { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    def "FR2: an unlisted subcommand answers at once, exit 0 and no output"() {
        given:
        def git = new StallingGit().stallOn('ls-remote').write(tempDir)

        and: 'one untimed run: the first exec of a freshly written file pays the OS malware scan (macOS)'
        timed(git, 'version')

        when:
        def run = timed(git, 'version')

        then:
        run.exit == 0
        run.stdout == ''
        run.elapsed <Duration.ofMillis(100)
    }

    def "FR2: a stalled subcommand sleeps out the stall"() {
        given:
        def git = new StallingGit().stallOn('fetch', 'ls-remote').stall(Duration.ofMillis(200)).write(tempDir)

        expect:
        timed(git, 'ls-remote', 'origin').elapsed >= Duration.ofMillis(200)
    }

    def "FR2: a local delay holds every unstalled command but the clone-key resolution"() {
        given:
        def git = new StallingGit().localDelay(Duration.ofMillis(300)).write(tempDir)

        expect:
        timed(git, 'version').elapsed >= Duration.ofMillis(300)

        and: 'the runner resolves the clone key in front of a network command; that one answers at once'
        timed(git, 'rev-parse', '--git-common-dir').elapsed <Duration.ofMillis(300)
    }

    def "FR2: an answer row prints its stdout and exits with its code"() {
        given:
        def git = new StallingGit().answer('remote', "it's 'quoted'", 128).write(tempDir)

        when:
        def run = timed(git, 'remote', 'get-url', 'origin')

        then:
        run.exit == 128
        run.stdout == "it's 'quoted'\n"
    }

    def "FR2: answers match by argv prefix in declaration order, the first match wins"() {
        given:
        def git = new StallingGit()
                .answer([
                    'rev-parse',
                    '--git-common-dir'
                ], '.git', 0)
                .answer('rev-parse', '', 1)
                .write(tempDir)

        expect:
        timed(git, 'rev-parse', '--git-common-dir').with {
            it.stdout == '.git\n' && it.exit == 0
        }
        timed(git, 'rev-parse', 'HEAD').with { it.stdout == '' && it.exit == 1 }
    }

    def "FR2: an answerWith fragment is evaluated at run time, so a file rewritten after write is answered"() {
        given:
        def answerFile = tempDir.resolve('ls-remote-out')
        Files.writeString(answerFile, 'before\n')
        def git = new StallingGit().answerWith('ls-remote', "cat '${answerFile}'").write(tempDir)

        when:
        Files.writeString(answerFile, 'after\n')

        then:
        timed(git, 'ls-remote', 'origin').with {
            it.stdout == 'after\n' && it.exit == 0
        }
    }

    def "FR2: beforeStall lines run in declaration order before the stall"() {
        given:
        def log = tempDir.resolve('before-stall.log')
        def git = new StallingGit().stallOn('push')
                .beforeStall("echo first >> '${log}'")
                .beforeStall("echo second >> '${log}'")
                .write(tempDir)

        when:
        def process = start(git, 'push', 'origin')

        then: 'both lines landed, in order, while the stand-in is still stalled'
        new PollingConditions(timeout: 20).eventually {
            assert Files.exists(log) && Files.readAllLines(log) == ['first', 'second']
        }
        process.isAlive()
    }

    def "FR2: markOnStall makes the marker appear once the stall began"() {
        given:
        def marker = tempDir.resolve('stall-started')
        def git = new StallingGit().stallOnEverything().markOnStall(marker).write(tempDir)

        when:
        def process = start(git, 'rev-parse', '--git-common-dir')

        then:
        new PollingConditions(timeout: 20).eventually {
            assert Files.exists(marker)
        }
        process.isAlive()
    }

    private Map timed(Path git, String... argv) {
        long begin = System.nanoTime()
        Process process = start(git, argv)
        String stdout = process.inputStream.text
        int exit = process.waitFor()
        [exit: exit, stdout: stdout, elapsed: Duration.ofNanos(System.nanoTime() - begin)]
    }

    private Process start(Path git, String... argv) {
        def builder = new ProcessBuilder([
            git.toString(),
            '-c',
            'core.askPass=',
            '-c',
            'a.b=c'
        ] + argv.toList())
        TestChildEnvironment.cleared(builder)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        Process process = builder.start()
        started << process
        process
    }
}
