package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, NFR-R1, NFR-R2 of kill-expensive-mutants (design D1): the runner's network branch observed
 * by what it hands the child — argv, environment, deadline — rather than by re-running a real
 * stall. A local command ({@code version}) and a non-mutating network one ({@code ls-remote},
 * which takes no clone lock and resolves no clone key) go through a {@link RecordingGit} that
 * prints its argv and {@code GIT_SSH_COMMAND} into a record file; the only waits are a 50 ms
 * and a zero deadline, and the only timing assertion has a margin of three orders of magnitude.
 *
 * <p>The behaviour itself is that of FR1, FR4, NFR-O1, NFR-S1 of bound-subprocess-commands; this
 * spec exists so that each mutant of the branch has a sub-second first killer.
 *
 * <p>The stand-ins are written and run once per spec, not per feature: macOS checks an executable
 * on its first launch, which costs some 300 ms per fresh file — more than the 50 ms deadline, and a
 * large share of the per-feature budget (FR1).
 *
 * <p>The {@code GIT_SSH_COMMAND} assertions are written against the parent environment, so they
 * hold whether or not the operator set one: unset, a network command gets the default limits; set,
 * it reaches the child unchanged (NFR-S1). When {@code add-subprocess-access-log} minimizes the
 * child environment, the variable belongs to that change's retained set and these features move
 * there rather than being deleted.
 */
class GitProcessRunnerNetworkBranchSpec extends Specification {

    static final String DEFAULT_SSH_COMMAND =
    'ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4'

    static final String STALL_DETECTION = '-c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60'

    static final Duration NETWORK_TIMEOUT = Duration.ofMillis(50)

    static final Duration SLOW_CHILD = Duration.ofMillis(150)

    /**
     * The deadline of the WARN feature, which is the first killer of the deadline and elapsed
     * mutants (FR1, M2). PIT tries the covering tests fastest first, by whole milliseconds of their
     * coverage-pass time, and every git command in the module covers these lines: under the 50 ms
     * deadline the feature ran after 136 faster covering tests, under 1 ms after 24 (measured, scoped
     * runs of 2026-10-09). A zero deadline cuts the child off as soon as it is launched — some 2 ms,
     * where a stand-in that runs to its exit takes some 4 — so the feature costs one launch and one
     * kill. The outcome cannot race: the child sleeps 150 ms, so it is still running when the zero
     * deadline is checked.
     */
    static final Duration CUT_OFF_TIMEOUT = Duration.ZERO

    /**
     * The deadline of the features that observe argv and environment: never reached, since their
     * stand-in exits at once, and nothing waits on it.
     */
    static final Duration UNREACHED_TIMEOUT = Duration.ofSeconds(30)

    @Shared
    @TempDir
    Path tempDir

    @Shared
    Path record

    @Shared
    Path instantGit

    @Shared
    Path slowGit

    def setupSpec() {
        record = tempDir.resolve('record.txt')
        instantGit = recordingGit(Duration.ZERO)
        slowGit = recordingGit(SLOW_CHILD)
        // The first launch of each fresh executable, paid here rather than inside a feature.
        new GitProcessRunner(instantGit.toString(), UNREACHED_TIMEOUT).run(tempDir, 'version')
        new GitProcessRunner(slowGit.toString(), UNREACHED_TIMEOUT).run(tempDir, 'version')
        // The first cut-off in the JVM, paid here too: the kill path, the log capture and the
        // elapsed parse each cost a one-time warm-up several times the cut-off itself. Nothing
        // here is asserted, so a mutant that loses the WARN fails the feature, not the spec.
        cutOffWarnings()
        elapsedIn(['elapsed=PT0S, deadline=PT0S'])
    }

    def setup() {
        Files.deleteIfExists(record)
    }

    def "FR1: a local command carries no stall-detection options and no ssh limits of the runner's"() {
        when:
        def result = recordingRunner().run(tempDir, 'version')

        then:
        result.exitCode() == 0
        recorded().argv == 'version'

        and: 'the child sees exactly the parent\'s GIT_SSH_COMMAND — none, unless the operator set one'
        recorded().ssh == "[${parentSshCommand() ?: 'unset'}]"
    }

    def "FR1, FR4: a network command carries the stall-detection options and the ssh limits"() {
        when:
        def result = recordingRunner().run(tempDir, 'ls-remote', 'origin')

        then:
        result.exitCode() == 0
        recorded().argv == "${STALL_DETECTION} ls-remote origin"
        recorded().ssh == "[${parentSshCommand() ?: DEFAULT_SSH_COMMAND}]"
    }

    // Declared right after a feature that launches a process, not after the 50 ms one: a launch that
    // follows some 200 ms of waiting measured twice as slow (5 ms against 3 ms in the coverage pass),
    // enough to put this feature behind the instant-exit features it must precede (M2).
    def "NFR-O1: the timeout WARN reports the time the command actually ran"() {
        when:
        def warnings = cutOffWarnings()

        then:
        warnings.size() == 1
        warnings[0].startsWith(OperatorEvent.GIT_NETWORK_COMMAND_TIMED_OUT.head())
        warnings[0].contains('subcommand=ls-remote')
        warnings[0].contains('deadline=PT0S')

        and: 'at least the deadline, and nowhere near a minute — a sum of clock readings reports decades'
        def elapsed = elapsedIn(warnings)
        elapsed >= CUT_OFF_TIMEOUT
        elapsed <Duration.ofMinutes(1)
    }

    def "FR1, NG3: under a 50 ms network deadline a slow local command exits and a slow network one is cut off"() {
        given:
        def runner = slowRunner()

        when: 'the local command outlives the network deadline'
        def local = runner.run(tempDir, 'version')

        then: 'it is not bounded'
        local.termination() == Termination.EXITED
        local.exitCode() == 0

        when: 'the network command outlives it'
        GitCommandResult network = null
        def events = LogCaptureSupport.capture(GitProcessRunner, Level.INFO) {
            network = runner.run(tempDir, 'ls-remote', 'origin')
        }

        then: 'it is killed on the deadline, and the runner says so'
        network.termination() == Termination.TIMED_OUT
        events.count { it.level == Level.WARN } == 1
    }

    // Runs only where the parent environment carries an operator GIT_SSH_COMMAND: the runner has no
    // seam for the child's base environment, and the spec does not rewrite the JVM's own. Where it
    // is unset the two branch features above assert the same pass-through rule's other half.
    @Requires({ System.getenv('GIT_SSH_COMMAND') })
    def "NFR-S1: an operator-set GIT_SSH_COMMAND in the parent environment reaches the child unchanged"() {
        when:
        recordingRunner().run(tempDir, 'ls-remote', 'origin')

        then:
        recorded().ssh == "[${parentSshCommand()}]"
    }

    /** The stand-in that exits at once, under a deadline it never reaches. */
    private GitProcessRunner recordingRunner() {
        new GitProcessRunner(instantGit.toString(), UNREACHED_TIMEOUT)
    }

    /** The stand-in whose every invocation takes 150 ms, under the 50 ms network deadline. */
    private GitProcessRunner slowRunner() {
        new GitProcessRunner(slowGit.toString(), NETWORK_TIMEOUT)
    }

    /** The WARN lines of one slow network command cut off on {@link #CUT_OFF_TIMEOUT}. */
    private List<String> cutOffWarnings() {
        def events = LogCaptureSupport.capture(GitProcessRunner, Level.INFO) {
            new GitProcessRunner(slowGit.toString(), CUT_OFF_TIMEOUT).run(tempDir, 'ls-remote', 'origin')
        }
        events.findAll { it.level == Level.WARN }*.formattedMessage
    }

    /** The {@code elapsed=} the first of {@code warnings} reports. */
    private static Duration elapsedIn(List<String> warnings) {
        Duration.parse((warnings[0] =~ /elapsed=(PT[^,]+)/)[0][1] as String)
    }

    private Path recordingGit(Duration delay) {
        new RecordingGit(record)
                .record('argv', '$*')
                .record('ssh', '[${GIT_SSH_COMMAND-unset}]')
                .delay(delay)
                .write(tempDir)
    }

    /** The one block the stand-in recorded. */
    private Map<String, String> recorded() {
        def blocks = RecordingGit.blocks(record)
        assert blocks.size() == 1: "expected one recorded invocation, got ${blocks.size()}"
        blocks[0]
    }

    private static String parentSshCommand() {
        System.getenv('GIT_SSH_COMMAND')
    }
}
