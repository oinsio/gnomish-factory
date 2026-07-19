package com.github.oinsio.gnomish.adapter.git

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
        ServiceCommitMessages.round(stage, round) == expected

        where:
        stage    | round | expected
        'build'  | 3     | 'gnomish: round build#3'
        'verify' | 1     | 'gnomish: round verify#1'
        'plan'   | 12    | 'gnomish: round plan#12'
    }

    @Unroll
    def "FR2: taskEvent(#event) == #expected"() {
        expect:
        ServiceCommitMessages.taskEvent(event) == expected

        where:
        event                          | expected
        TaskLifecycleEvent.STARTED     | 'gnomish: task started'
        TaskLifecycleEvent.RESUMED     | 'gnomish: task resumed'
        TaskLifecycleEvent.COMPLETED   | 'gnomish: task completed'
        TaskLifecycleEvent.PAUSED      | 'gnomish: task paused'
        TaskLifecycleEvent.ESCALATED   | 'gnomish: task escalated'
        TaskLifecycleEvent.ABORTED     | 'gnomish: task aborted'
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
}
