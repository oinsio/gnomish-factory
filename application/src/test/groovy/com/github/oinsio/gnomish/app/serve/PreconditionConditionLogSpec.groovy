package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.logtext.RepeatOccurrence
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * NFR-O2, UX4 of add-sandbox-base-image (design D19, "One failure, one log"): an operator
 * precondition's condition names itself by its own code — one opening WARN and one sustained-open
 * ERROR per kind, each carrying the check's message with its fix — keeps every failed probe at
 * DEBUG, closes with one INFO, and is never built for the remote, whose lines are the remote outage
 * gate's.
 */
class PreconditionConditionLogSpec extends Specification {

    def "NFR-O2: the opening WARN carries its kind's own code and the cause: #kind"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition)

        when:
        new PreconditionConditionLog(kind).opened('refused; the fix')

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.WARN
        logs.list[0].formattedMessage.startsWith(code.head())
        logs.list[0].formattedMessage.contains(kind.name())
        logs.list[0].formattedMessage.contains('refused; the fix')

        cleanup:
        logs.detach()

        where:
        kind | code
        DaemonConditionKind.CONTAINER_RUNTIME | OperatorEvent.CONDITION_CONTAINER_RUNTIME_OPENED
        DaemonConditionKind.CLONE_REF_FORMAT | OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED
        DaemonConditionKind.IMAGE_CONTRACT | OperatorEvent.CONDITION_IMAGE_CONTRACT_OPENED
        DaemonConditionKind.BOX_GIT_FLOOR | OperatorEvent.CONDITION_BOX_GIT_FLOOR_OPENED
    }

    def "NFR-O2: the sustained-open ERROR carries its kind's own code, the threshold and the last cause: #kind"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition)

        when:
        new PreconditionConditionLog(kind).sustainedOpen(Duration.ofHours(1), 'still refused')

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.ERROR
        logs.list[0].formattedMessage.startsWith(code.head())
        logs.list[0].formattedMessage.contains(kind.name())
        logs.list[0].formattedMessage.contains('PT1H')
        logs.list[0].formattedMessage.contains('still refused')

        cleanup:
        logs.detach()

        where:
        kind | code
        DaemonConditionKind.CONTAINER_RUNTIME | OperatorEvent.CONDITION_CONTAINER_RUNTIME_SUSTAINED_OPEN
        DaemonConditionKind.CLONE_REF_FORMAT | OperatorEvent.CONDITION_CLONE_REF_FORMAT_SUSTAINED_OPEN
        DaemonConditionKind.IMAGE_CONTRACT | OperatorEvent.CONDITION_IMAGE_CONTRACT_SUSTAINED_OPEN
        DaemonConditionKind.BOX_GIT_FLOOR | OperatorEvent.CONDITION_BOX_GIT_FLOOR_SUSTAINED_OPEN
    }

    def "NFR-O2: every failed probe is DEBUG, whatever repeat form it took"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition, Level.DEBUG)

        when:
        new PreconditionConditionLog(DaemonConditionKind.IMAGE_CONTRACT).probeFailed(occurrence)

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('IMAGE_CONTRACT')
        logs.list[0].formattedMessage.contains('stale label')
        logs.list[0].formattedMessage.contains(count)

        cleanup:
        logs.detach()

        where:
        occurrence | count
        new RepeatOccurrence.First('stale label') | 'still unmet'
        new RepeatOccurrence.Repeat('stale label', 3) | '(3x)'
        new RepeatOccurrence.RollUp('stale label', 7, Duration.ofMinutes(6)) | '7x over PT6M'
    }

    def "a probe that threw is the same DEBUG streak line, with its trace on the first occurrence only"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition, Level.DEBUG)
        def cause = new IllegalStateException('No such image')

        when:
        lines.probeUnanswered(occurrence, cause)

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('probe did not answer')
        (logs.list[0].throwableProxy != null) == traced
        !traced || logs.list[0].throwableProxy.className == IllegalStateException.name

        cleanup:
        logs.detach()

        where:
        lines | occurrence | traced
        new PreconditionConditionLog(DaemonConditionKind.IMAGE_CONTRACT) | new RepeatOccurrence.First('probe did not answer') | true
        new PreconditionConditionLog(DaemonConditionKind.IMAGE_CONTRACT) | new RepeatOccurrence.Repeat('probe did not answer', 3) | false
        new PreconditionConditionLog(DaemonConditionKind.IMAGE_CONTRACT) | new RepeatOccurrence.RollUp('probe did not answer', 7, Duration.ofMinutes(6)) | false
        new ConditionLog.RemoteLines('origin') | new RepeatOccurrence.First('probe did not answer') | true
        new ConditionLog.RemoteLines('origin') | new RepeatOccurrence.Repeat('probe did not answer', 3) | false
        new ConditionLog.RemoteLines('origin') | new RepeatOccurrence.RollUp('probe did not answer', 7, Duration.ofMinutes(6)) | false
    }

    def "NFR-O2: closing is one INFO with the episode's duration and failed probe count"() {
        given:
        def logs = LogCaptureSupport.attach(DaemonCondition, Level.DEBUG)

        when:
        new PreconditionConditionLog(DaemonConditionKind.BOX_GIT_FLOOR).closed(Duration.ofMinutes(42), 5)

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.INFO
        logs.list[0].formattedMessage.contains('BOX_GIT_FLOOR closed')
        logs.list[0].formattedMessage.contains('PT42M')
        logs.list[0].formattedMessage.contains('5 failed probe(s)')

        cleanup:
        logs.detach()
    }

    def "FR15: the remote is no operator precondition and keeps the remote outage gate's lines"() {
        when:
        new PreconditionConditionLog(DaemonConditionKind.REMOTE)

        then:
        thrown(IllegalArgumentException)
        ConditionLog.forKind(DaemonConditionKind.REMOTE, 'origin') == new ConditionLog.RemoteLines('origin')
        ConditionLog.forKind(DaemonConditionKind.BOX_GIT_FLOOR, 'BOX_GIT_FLOOR') ==
                new PreconditionConditionLog(DaemonConditionKind.BOX_GIT_FLOOR)
    }
}
