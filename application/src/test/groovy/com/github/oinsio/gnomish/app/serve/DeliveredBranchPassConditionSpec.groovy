package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * FR17 of add-sandbox-base-image (design D21): an operator precondition refusal the janitor's
 * branch pass meets — on a branch's cleanup or on the listing — opens its daemon condition and is
 * no branch failure, the pass visits the remaining branches, and while a precondition condition is
 * open the next pass waits with one DEBUG line naming it instead of refusing again at WARN.
 */
class DeliveredBranchPassConditionSpec extends DeliveredBranchPassWorld {

    private static CloneRefFormatRefusedException refusal() {
        new CloneRefFormatRefusedException(CLONE_DIR.toString(), 'reftable', UntrustedText.subprocess('files'))
    }

    // Scenario: serve/factory-serve / The janitor's refusal opens the condition and the pass continues
    def "FR17: a branch's ref-format refusal opens the condition, the pass continues, and the next tick names the condition"() {
        given:
        def conditionLogs = LogCaptureSupport.attach(DaemonCondition)
        def passLogs = LogCaptureSupport.attach(DeliveredBranchPass, Level.DEBUG)

        when:
        pass.run(Set.of())

        then: 'the first branch meets the refusal, the second is still visited'
        1 * branches.list(CLONE_DIR) >> [
            row('PROJ-1', new BranchShape.CompletedUncleaned()),
            row('PROJ-2', new BranchShape.CompletedUncleaned())
        ]
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> { throw refusal() }
        1 * branches.locate(CLONE_DIR, 'PROJ-2') >> local('PROJ-2')
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-2', _) >> new BranchShape.Delivered()

        and: 'the condition opened with its one WARN; the refusal is no branch failure'
        conditionLogs.list.findAll {
            it.level == Level.WARN
        }*.formattedMessage.every {
            it.startsWith(OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED.head())
        }
        conditionLogs.list.count { it.level == Level.WARN } == 1
        !passLogs.list.any { it.level.isGreaterOrEqual(Level.WARN) }

        when: 'the next tick'
        pass.run(Set.of())

        then: 'the pass waits, naming the open condition at DEBUG, and meets nothing'
        0 * branches._
        passLogs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('waits') && it.formattedMessage.contains('CLONE_REF_FORMAT')
        }
        conditionLogs.list.count { it.level == Level.WARN } == 1

        cleanup:
        conditionLogs.detach()
        passLogs.detach()
    }

    def "FR17: a refusal of the listing itself opens the condition and does not fail the tick"() {
        given:
        def conditionLogs = LogCaptureSupport.attach(DaemonCondition)

        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> { throw refusal() }
        noExceptionThrown()
        conditionLogs.list.count {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED.head())
        } == 1

        cleanup:
        conditionLogs.detach()
    }

    def "NFR-R2: an unclassified failure of the listing still fails the tick and opens no condition"() {
        given:
        def failure = new IllegalStateException('git for-each-ref crashed')
        branches.list(CLONE_DIR) >> { throw failure }

        when:
        pass.run(Set.of())

        then:
        def thrownFailure = thrown(IllegalStateException)
        thrownFailure.is(failure)
        conditions.openPreconditions().isEmpty()
    }

    def "FR17: an open remote gate is no precondition, so the pass still runs"() {
        given:
        def conditionLogs = LogCaptureSupport.attach(DaemonCondition)
        conditions.remote().openOnFailure('origin unreachable')

        when:
        pass.run(Set.of())

        then:
        1 * branches.list(CLONE_DIR) >> []
        conditionLogs.list.count {
            it.formattedMessage.startsWith(OperatorEvent.REMOTE_OUTAGE_GATE_OPENED.head())
        } == 1

        cleanup:
        conditionLogs.detach()
    }
}
