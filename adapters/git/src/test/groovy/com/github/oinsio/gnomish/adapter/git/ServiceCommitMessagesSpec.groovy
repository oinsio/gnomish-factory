package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR2 of add-git-workflow (design D14, proposal Q1): the machine-recognizable
 * commit-message scheme for service commits made by the git adapter — round,
 * task lifecycle event, salvage, cleanup. A human/audit aid, not a parser's
 * input (D14); this spec only pins down the exact text.
 */
class ServiceCommitMessagesSpec extends Specification {

    @Unroll
    def "FR2: round(#stage, #round) == #expected"() {
        expect:
        ServiceCommitMessages.round(RoundStates.roundOf(new AttemptKey('T-1', stage, round))) == expected

        where:
        stage | round | expected
        'build' | 3 | 'gnomish: round build#3'
        'verify' | 1 | 'gnomish: round verify#1'
        'plan' | 12 | 'gnomish: round plan#12'
    }

    @Unroll
    def "FR2: taskEvent(#event) == #expected"() {
        expect:
        ServiceCommitMessages.taskEvent(event) == expected

        where:
        event | expected
        TaskLifecycleEvent.STARTED | 'gnomish: task started'
        TaskLifecycleEvent.RESUMED | 'gnomish: task resumed'
        TaskLifecycleEvent.APPROVED | 'gnomish: task approved'
        TaskLifecycleEvent.COMPLETED | 'gnomish: task completed'
        TaskLifecycleEvent.PAUSED | 'gnomish: task paused'
        TaskLifecycleEvent.ESCALATED | 'gnomish: task escalated'
        TaskLifecycleEvent.ABORTED | 'gnomish: task aborted'
    }

    def "FR2: taskEvent is total over every TaskLifecycleEvent constant, with no gaps"() {
        expect:
        TaskLifecycleEvent.values().every { event ->
            def message = ServiceCommitMessages.taskEvent(event)
            message.startsWith('gnomish: task ') && message.length() > 'gnomish: task '.length()
        }
    }

    def "FR2: salvage() is the fixed constant string"() {
        expect:
        ServiceCommitMessages.salvage() == 'gnomish: salvage'
    }

    def "FR2: cleanup() is the fixed constant string"() {
        expect:
        ServiceCommitMessages.cleanup() == 'gnomish: cleanup'
    }

    // FR10 of add-claim-heartbeat: the callers' specs compare the tip's subject with this method,
    // so only a literal here pins the message itself
    def "FR10: trackerWriteConfirmed() is the fixed constant string"() {
        expect:
        ServiceCommitMessages.trackerWriteConfirmed() == 'gnomish: task write-confirmed'
    }
}
