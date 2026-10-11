package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, NFR-O2, UX3 of bound-subprocess-commands: the three best-effort push points keep their
 * discipline — one WARN, no retry, no throw — but say what actually happened. "The remote rejected
 * the push", "the remote never answered" and "we were shut down" have to be readable from that one
 * line, and a push that was killed or interrupted must never be logged as {@code push failed}: an
 * operator triaging a stopped factory would go looking for a remote problem that never existed.
 *
 * <p>Driven through the runner's port ({@link StalledPush}, design D27 of
 * supervise-daemon-loops-and-embed-dashboard); the runner's own WARN for a bound that fired is
 * {@link GitProcessRunnerBoundedNetworkSpec}'s. The rejected-push
 * wording of each point is covered by its own spec ({@link BestEffortPushSpec},
 * {@link PushBestEffortTaskRepositorySpec}, {@link BranchPushSpec}).
 */
class PushTerminationLoggingSpec extends Specification {

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    def "FR8, UX3: a timed-out push names the timeout instead of borrowing a rejection's words"() {
        when:
        def runner = StalledPush.timingOut()
        def events = LogCaptureSupport.capture(BestEffortPush, Level.INFO) {
            new BestEffortPush(runner.git).pushBestEffort(
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
        def stalled = StalledPush.blocking()
        def push = new LifecyclePush(stalled.git)

        when:
        def events = LogCaptureSupport.capture(LifecyclePush, Level.INFO) {
            def runner = new Thread({
                push.pushAfter(TASK_ID, 'park', tempDir, BRANCH)
            })
            runner.start()
            stalled.awaitPushStarted(runner)
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
        def events = LogCaptureSupport.capture(BranchPush, Level.INFO) {
            new BranchPush(StalledPush.timingOut().git).pushBestEffort(tempDir, BRANCH)
        }

        then: 'never throws — the salvage commit already made the work durable locally'
        noExceptionThrown()

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.BRANCH_PUSH_FAILED.head() + "revocation push timed out: branch=${BRANCH}")
    }

    /**
     * The real boundary check over the scripted git, told that {@code HEAD} is on the task branch
     * and that the previous tip is an ancestor — so the push itself is what this spec exercises.
     */
    private RoundBoundaryCheck readyBoundary(StalledPush push) {
        push.headBranch = BRANCH
        new RoundBoundaryCheck(push.git, tempDir, BRANCH)
    }
}
