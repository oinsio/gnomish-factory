package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.logtext.OperatorEvent
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, NFR-O2, UX3 of bound-subprocess-commands: the three best-effort push points keep their
 * discipline — one WARN, no retry, no throw — but say what actually happened. "The remote rejected
 * the push", "the remote never answered" and "we were shut down" have to be readable from that one
 * line, and a push that was killed or interrupted must never be logged as {@code push failed}: an
 * operator triaging a stopped factory would go looking for a remote problem that never existed.
 *
 * <p>Driven through the runner's git-binary seam ({@link StallingGitFixture}). The rejected-push
 * wording of each point is covered by its own spec ({@link BestEffortPushSpec},
 * {@link PushBestEffortTaskRepositorySpec}, {@link BranchPushSpec}).
 */
class PushTerminationLoggingSpec extends Specification implements StallingGitFixture {

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    def "FR8, UX3: a timed-out push names the timeout instead of borrowing a rejection's words"() {
        when:
        def runner = timedOutRunner()
        def events = capture(BestEffortPush) {
            new BestEffortPush(runner).pushBestEffort(
            TASK_ID, 'implement', 1, tempDir, BRANCH, readyBoundary(runner), 'HEAD~1')
        }

        then:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.PUSH_FAILED.head() + "push timed out: taskId=${TASK_ID}")
        !warnings[0].formattedMessage.contains('push failed')
    }

    def "FR8, NFR-O2: an interrupted lifecycle push names the interruption, never a failure"() {
        given:
        def push = new LifecyclePush(interruptibleRunner())

        when:
        def events = capture(LifecyclePush) {
            def runner = new Thread({
                push.pushAfter(TASK_ID, 'park', tempDir, BRANCH)
            })
            runner.start()
            awaitPushStarted(tempDir)
            runner.interrupt()
            runner.join(Duration.ofSeconds(30).toMillis())
        }

        then:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.LIFECYCLE_PUSH_FAILED.head()
                + "lifecycle push was interrupted: taskId=${TASK_ID}")
        !warnings[0].formattedMessage.contains('failed')
    }

    def "FR8, UX3: the revocation push distinguishes the same three outcomes"() {
        when:
        def events = capture(BranchPush) {
            new BranchPush(timedOutRunner()).pushBestEffort(tempDir, BRANCH)
        }

        then: 'never throws — the salvage commit already made the work durable locally'
        noExceptionThrown()

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.BRANCH_PUSH_FAILED.head() + "revocation push timed out: branch=${BRANCH}")
    }

    // NFR-O1: only the runner knows both numbers, so the WARN that carries them is the runner's.
    def "NFR-O1: the runner names the command class, the elapsed time and the configured deadline"() {
        when:
        def events = capture(GitProcessRunner) {
            timedOutRunner().run(tempDir, 'push', 'origin', 'HEAD')
        }

        then:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.GIT_NETWORK_COMMAND_TIMED_OUT.head() + 'git network command timed out')
        warnings[0].formattedMessage.contains('subcommand=push')
        warnings[0].formattedMessage.contains('deadline=PT2S')

        and: 'elapsed is the time this command actually ran — the deadline, plus its kill'
        def elapsed = Duration.parse(
                (warnings[0].formattedMessage =~ /elapsed=(PT[^,]+)/)[0][1] as String)
        elapsed >= Duration.ofSeconds(2)
        elapsed <Duration.ofSeconds(30)
    }

    private GitProcessRunner timedOutRunner() {
        new GitProcessRunner(stallingGit(tempDir).toString(), Duration.ofSeconds(2))
    }

    private GitProcessRunner interruptibleRunner() {
        new GitProcessRunner(stallingGit(tempDir).toString(), Duration.ofSeconds(30))
    }

    /**
     * The real boundary check over the stand-in, told that {@code HEAD} is on the task branch and
     * that the previous tip is an ancestor — so the push itself is what this spec exercises.
     */
    private RoundBoundaryCheck readyBoundary(GitProcessRunner runner) {
        headBranch(tempDir).toFile().text = BRANCH
        new RoundBoundaryCheck(runner, tempDir, BRANCH)
    }

    private static List<ILoggingEvent> capture(Class<?> subject, Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(subject)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        appender.list
    }
}
