package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, UX2 of kill-expensive-mutants (design D4); FR24 of
 * supervise-daemon-loops-and-embed-dashboard (design D23): {@link StallingGit} hands out only
 * presets whose table stalls, a stalling preset stalls on the subcommands it names and answers the
 * rest at once, and a marked stall can be awaited before an interrupt is sent.
 *
 * <p>Lives in {@code :bootstrap} because {@code :test-fixtures} has no test source set. The
 * table's own mechanics are {@code StandInLibrarySpec}'s; this spec is about the selector. The
 * stand-in is run with the leading {@code -c} pairs the runner would pass.
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

    def "FR2: a stalling preset answers the subcommands it does not stall at once"() {
        given:
        Path git = StallingGit.git('stall-fetch')

        when:
        Process process = start(git, 'version')

        then:
        process.waitFor() == 0
        process.inputStream.text == ''
    }

    def "FR2: a stalled subcommand stays in flight"() {
        when:
        Process process = start(StallingGit.git('stall-fetch'), 'fetch', 'origin')

        then:
        !process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    def "FR24: a preset whose table does not stall is not a stalling stand-in"() {
        when:
        StallingGit.git('delegate-git')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("'delegate-git' does not stall")
    }

    def "FR2: a marked stall is awaited, and the stand-in is still stalled once it has begun"() {
        given:
        Path git = StallingGit.marked(tempDir.resolve('stalling'), 'stall-everything')

        when:
        Process process = start(git, 'rev-parse', '--git-common-dir')
        StallingGit.awaitStall(git)

        then:
        StandInLog.blocks(git)*.argv == [
            '-c core.askPass= -c a.b=c rev-parse --git-common-dir'
        ]
        process.alive
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
