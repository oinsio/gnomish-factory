package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR10, NFR-R1, M4 of bound-subprocess-commands: a docker management command is bounded by the
 * configured deadline and reports a named outcome when it ends early — never an exit code a caller
 * would read as "docker ran and failed". Both streams are drained concurrently with the running
 * process, so output past the OS pipe buffer cannot deadlock the command it belongs to.
 *
 * <p>Driven by a fake {@code docker} binary, so no daemon is required. The stall stands in for the
 * defect this bounds: {@code docker run} on an absent image reaching a registry that accepts the
 * connection and then never answers.
 */
class DockerCliBoundedSpec extends Specification {

    /** Long enough that a wall-clock assertion can only pass because the deadline fired. */
    static final String STALL_SECONDS = '600'

    @TempDir
    Path tempDir

    private DockerCli cliBackedBy(String script, Duration timeout) {
        Path bin = tempDir.resolve('fakedocker')
        Files.writeString(bin, "#!/bin/sh\n" + script)
        bin.toFile().setExecutable(true)
        new DockerCli(bin.toString(), timeout)
    }

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    /** The WARN lines the CLI wrote while {@code emit} ran — the operator's whole view of a bound that fired. */
    private static List<String> warnings(Closure emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(DockerCli)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.findAll {
            it.level == Level.WARN
        }*.formattedMessage
    }

    // FR10, NFR-R1, M4: the wedged registry case — the command ends on its deadline, not on docker
    def "FR10, M4: a management command that never answers ends on its deadline with a named timeout"() {
        given: 'a docker that accepts the command and then stalls far past any deadline'
        def cli = cliBackedBy("sleep ${STALL_SECONDS}", Duration.ofSeconds(2))

        when:
        def result = null
        def started = System.nanoTime()
        def warns = warnings {
            result = cli.run(['run', '--rm', 'absent-image'])
        }
        def elapsed = Duration.ofNanos(System.nanoTime() - started)

        then: 'the outcome names the timeout rather than dressing it up as a docker exit code'
        result.termination() == Termination.TIMED_OUT
        !result.ok()

        and: 'M4: the wall clock is the deadline plus a kill, not the stall'
        elapsed <Duration.ofSeconds(5)

        and: 'NFR-O1: one WARN names the command class, the timeout, and the deadline to raise'
        warns.size() == 1
        warns[0].contains('docker command timed out')
        warns[0].contains('subcommand=run')
        warns[0].contains('deadline=PT2S')

        and: 'NFR-O1: and the elapsed it reports is the command\'s own, measured from its start'
        def reported = Duration.parse((warns[0] =~ /elapsed=(PT[^,\s]+)/)[0][1])
        reported >= Duration.ofSeconds(2)
        reported <Duration.ofMinutes(1)
    }

    // NFR-O1: a command that answers is silent — an operator's WARN log means a bound actually fired
    def "NFR-O1: a command that runs to completion writes no WARN at all"() {
        given:
        def cli = cliBackedBy('exit 0', Duration.ofSeconds(30))

        expect:
        warnings { cli.run(['inspect', 'box']) }.isEmpty()
    }

    // NFR-O1: a bare `docker` invocation has no subcommand to name, and still reports usefully
    def "NFR-O1: a bound command with no subcommand is reported as docker itself"() {
        given:
        def cli = cliBackedBy("sleep ${STALL_SECONDS}", Duration.ofSeconds(1))

        when:
        def warns = warnings { cli.run([]) }

        then:
        warns.size() == 1
        warns[0].contains('subcommand=docker')
    }

    // FR10: a command that answers within its deadline is unchanged — EXITED, with its own code
    def "FR10, NFR-R3: a command that answers inside its deadline keeps its exit code and streams"() {
        given:
        def cli = cliBackedBy('echo "out:$1"; echo warn 1>&2; exit 3', Duration.ofSeconds(30))

        when:
        def result = cli.run(['inspect', 'box'])

        then:
        result.termination() == Termination.EXITED
        result.exitCode() == 3
        result.stdout().trim() == 'out:inspect'
        result.stderr().trim() == 'warn'
    }

    // design D11, M4: concurrent drains — a stream past the OS pipe buffer neither blocks nor truncates
    def "design D11, M4: output larger than the pipe buffer is captured in full on both streams"() {
        given: 'a docker printing well past 64 KiB to stdout and to stderr'
        def line = 'x' * 100
        def cli = cliBackedBy("""
i=0
while [ \$i -lt 2000 ]; do
  echo "${line}"
  echo "${line}" 1>&2
  i=\$((i + 1))
done
exit 0
""", Duration.ofSeconds(60))

        when:
        def result = cli.run(['logs', 'box'])

        then: 'nothing deadlocked and nothing was lost'
        result.termination() == Termination.EXITED
        result.ok()
        result.stdout().length() > 200_000
        result.stderr().length() > 200_000
    }

    // FR6: an interrupted wait is a named outcome, not the -1 sentinel the old catch returned
    def "FR6: an interrupted management command is a named outcome and preserves the interrupt flag"() {
        given:
        def cli = cliBackedBy("sleep ${STALL_SECONDS}", Duration.ofSeconds(60))

        when: 'the wait is interrupted before it begins, which drives the path deterministically'
        def result = null
        Thread.currentThread().interrupt()
        def warns = warnings { result = cli.run(['ps']) }

        then:
        result.termination() == Termination.INTERRUPTED

        and: 'NFR-O2: the WARN blames the interruption, and names no deadline that was never reached'
        warns.size() == 1
        warns[0].contains('docker command interrupted')
        warns[0].contains('subcommand=ps')
        !warns[0].contains('deadline=')

        and: 'the caller up the stack still sees the interrupt'
        Thread.interrupted()
    }
}
