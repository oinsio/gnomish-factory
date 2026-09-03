package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR9, FR10 of harden-task-branch-contract: the completion's own end of the intent→effect→receipt
 * protocol — what its probe answers when the tracker cannot be asked, and the two ways its delivery
 * declines to confirm, both of which must leave the destructive tail unrun.
 *
 * <p>The destructive tail is the assertion that matters: a cleanup commit that strips {@code
 * .gnomish-task/} from a tip whose finish never landed erases the evidence the next pickup needs to
 * recognize the {@code CompletedUncleaned} shape at all.
 */
class FinishEffectSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    Tracker tracker = Mock(Tracker)

    private static TrackerTask task(TrackerTaskState state, boolean finished = false) {
        new TrackerTask(REF, new TaskSnapshot('PROJ-1', 'title', 'body'), state, AbortFacts.none(), finished)
    }

    private FinishEffect effect(Runnable cleanup) {
        new FinishEffect(tracker, REF, INSTANCE, 'all stages passed', VirtualTimeRetries.terminalWrite(),
                new FinishTransition.Recovered(cleanup), LoggerFactory.getLogger(FinishEffectSpec))
    }

    // FR10: a tracker that cannot be asked reads as "not there" — the re-drive is safe because the
    // finish is a find-then-upsert (FR11), while skipping one that never landed loses the delivery.
    def "an unaskable tracker re-drives the finish rather than skipping it"() {
        given: 'the probe throws, and the claim check afterwards reports the claim still ours'
        def cleanupRuns = 0
        def probed = false
        tracker.fetchTask(REF) >> {
            if (!probed) {
                probed = true
                throw new RuntimeException('tracker unreachable')
            }
            task(new TrackerTaskState.Working(INSTANCE.value()))
        }

        and:
        def logs = LogCaptureSupport.attach(FinishEffectSpec)

        when:
        effect({ cleanupRuns++ }).drive()

        then: 'the finish is written, and only then does the destructive tail run'
        1 * tracker.finish(REF, 'all stages passed')
        cleanupRuns == 1

        and: 'FR15 of harden-logging-observability: the unverifiable probe is a coded WARN naming the task'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINISH_LANDING_UNVERIFIED.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // FR10: the probe found the finish already at the target — the write landed and only its
    // receipt was lost, so nothing is written again and the tail still runs.
    def "a finish the probe finds already landed is not written again"() {
        given:
        def cleanupRuns = 0
        tracker.fetchTask(REF) >> task(new TrackerTaskState.Finished(), true)

        when:
        effect({ cleanupRuns++ }).drive()

        then:
        0 * tracker.finish(*_)
        cleanupRuns == 1
    }

    // FR7 of add-claim-heartbeat, FR10 here: a claim that moved is not fought — and because the
    // delivery never confirmed, the cleanup commit that would strip the envelope stays unrun, so
    // the tip keeps the CompletedUncleaned shape the new holder's pickup can still read.
    def "a claim held by another instance skips both the finish and its destructive tail"() {
        given:
        def cleanupRuns = 0
        tracker.fetchTask(REF) >> task(new TrackerTaskState.Working('someone-else'))

        and:
        def logs = LogCaptureSupport.attach(FinishEffectSpec)

        when:
        effect({ cleanupRuns++ }).drive()

        then:
        0 * tracker.finish(*_)
        cleanupRuns == 0

        and: 'FR15: the skipped delivery is a coded WARN naming the task whose claim moved'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINISH_SKIPPED_CLAIM_LOST.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // FR15: the deferred-finish edge — the retry bound elapses with the tracker still out, so the
    // finish is left for a later resume to reconcile. Losing that line loses the only trace that a
    // delivered task is not yet finished on the tracker, so it is ERROR and it names the task.
    def "a finish the retry bound never confirms leaves a coded ERROR and no destructive tail"() {
        given:
        def cleanupRuns = 0
        tracker.fetchTask(REF) >> task(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.finish(REF, 'all stages passed') >> {
            throw new TrackerUnavailableException('tracker down')
        }
        def logs = LogCaptureSupport.attach(FinishEffectSpec)

        when:
        effect({ cleanupRuns++ }).drive()

        then:
        cleanupRuns == 0
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.FINISH_UNWRITTEN_AFTER_RETRIES.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }
}
