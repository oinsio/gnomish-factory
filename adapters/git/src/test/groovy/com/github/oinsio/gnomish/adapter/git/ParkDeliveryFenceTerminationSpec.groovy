package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, UX2, M3 of bound-subprocess-commands: the fence in front of a park's tracker write must
 * never turn a push it could not finish into a claim about what origin holds. An interrupted push
 * spends no re-attempt and produces no {@code origin is behind} note; a timed-out one spends no
 * re-attempt either, and earns that note only when a bounded re-check of the remote tip confirms
 * the park is genuinely missing — the kill may well have landed after the transfer did.
 *
 * <p>Driven through the runner's git-binary seam rather than a real remote: a stand-in git that
 * answers every read the fence makes from files this spec controls, and stalls on {@code push} far
 * longer than the injected deadline. That is the only way to script "the push was killed and the
 * commit arrived anyway" deterministically. The real-remote behaviour of the same fence lives in
 * {@link ParkDeliveryFenceSpec}.
 */
class ParkDeliveryFenceTerminationSpec extends Specification implements StallingGitFixture {

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    def "FR7, UX2: an interrupted push spends no re-attempt and claims nothing about origin"() {
        given: 'a push that never finishes, and a run that is shut down while it is in flight'
        def fence = new ParkDeliveryFence(new GitProcessRunner(stallingGit(tempDir).toString(), Duration.ofSeconds(30)))
        ParkDeliveryVerdict verdict = null

        when:
        def events = LogCaptureSupport.capture(ParkDeliveryFence, Level.INFO) {
            def runner = new Thread({
                verdict = fence.ensureDelivered(tempDir, TASK_ID)
            })
            runner.start()
            awaitPushStarted(tempDir)
            runner.interrupt()
            runner.join(Duration.ofSeconds(30).toMillis())
        }

        then: 'the push was attempted once, not twice — a shutdown is not a transient rejection'
        pushAttempts(tempDir).toFile().readLines().size() == 1

        and: 'the operator is told delivery is unknown, never that origin is behind'
        verdict instanceof ParkDeliveryVerdict.Undelivered
        def note = (verdict as ParkDeliveryVerdict.Undelivered).note()
        note.contains('could not be verified')
        note.contains('was interrupted before it finished')
        !note.contains('origin is behind')

        and: 'NFR-O2: the WARN names the interruption and does not call the push a failure'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.PARK_FENCE_INTERRUPTED.head()
                + 'park delivery push interrupted, delivery unverified')
        !warnings.any { it.formattedMessage.contains('push failed') }
    }

    def "FR7, M3: a timed-out push whose commit did arrive is confirmed delivered by the re-check"() {
        given: 'a push that stalls only after the remote has taken the tip — the kill lands too late'
        pushHook(tempDir).toFile().text =
                "printf '%s\\trefs/heads/${BRANCH}\\n' ${STALLED_TIP} > ${lsRemoteOut(tempDir)}\n"
        ParkDeliveryVerdict verdict = null

        when:
        def events = LogCaptureSupport.capture(ParkDeliveryFence, Level.INFO) {
            verdict = fence().ensureDelivered(tempDir, TASK_ID)
        }

        then: 'origin itself answers that it carries the park, so the park report says nothing'
        verdict instanceof ParkDeliveryVerdict.Delivered
        pushAttempts(tempDir).toFile().readLines().size() == 1

        and: 'FR12 of harden-logging-observability: a recovered transient is INFO, not a WARN —'
        // the delivery happened; there is nothing here for an operator to act on.
        events.findAll { it.level == Level.WARN }.isEmpty()
        events.findAll { it.level == Level.INFO }*.formattedMessage.any {
            it.startsWith('park delivery push timed out, but origin carries the park')
        }
    }

    def "FR7, UX2: a timed-out push claims origin is behind only once origin confirms it"() {
        given: 'origin answers the re-check and does not have the branch'
        ParkDeliveryVerdict verdict = null

        when:
        def events = LogCaptureSupport.capture(ParkDeliveryFence, Level.INFO) {
            verdict = fence().ensureDelivered(tempDir, TASK_ID)
        }

        then:
        verdict instanceof ParkDeliveryVerdict.Undelivered
        (verdict as ParkDeliveryVerdict.Undelivered).note().contains('origin is behind this park')

        and: 'still one push: the deadline proved the remote unresponsive, a second wait proves nothing'
        pushAttempts(tempDir).toFile().readLines().size() == 1
        events.findAll { it.level == Level.WARN }*.formattedMessage.any {
            it.startsWith(OperatorEvent.PARK_FENCE_TIMED_OUT_ORIGIN_BEHIND.head()
            + 'park delivery push timed out, origin confirmed behind')
        }
    }

    def "FR7, UX2: an unanswerable re-check reports that delivery could not be verified"() {
        given: 'the remote stops answering ls-remote too, so nothing about origin can be established'
        pushHook(tempDir).toFile().text = "echo 128 > ${lsRemoteExit(tempDir)}\n"
        ParkDeliveryVerdict verdict = null

        when:
        def events = LogCaptureSupport.capture(ParkDeliveryFence, Level.INFO) {
            verdict = fence().ensureDelivered(tempDir, TASK_ID)
        }

        then:
        verdict instanceof ParkDeliveryVerdict.Undelivered
        def note = (verdict as ParkDeliveryVerdict.Undelivered).note()
        note.contains('could not be verified')
        note.contains('origin did not answer the re-check')
        !note.contains('origin is behind')

        and:
        pushAttempts(tempDir).toFile().readLines().size() == 1
        events.findAll { it.level == Level.WARN }*.formattedMessage.any {
            it.startsWith(OperatorEvent.PARK_FENCE_TIMED_OUT_ORIGIN_SILENT.head()
            + 'park delivery push timed out and origin did not answer the re-check')
        }
    }

    private ParkDeliveryFence fence() {
        new ParkDeliveryFence(new GitProcessRunner(stallingGit(tempDir).toString(), Duration.ofSeconds(2)))
    }
}
