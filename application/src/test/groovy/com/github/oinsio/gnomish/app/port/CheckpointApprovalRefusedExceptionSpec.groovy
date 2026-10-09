package com.github.oinsio.gnomish.app.port

import com.github.oinsio.gnomish.domain.engine.Position
import spock.lang.Specification

/**
 * FR3, NFR-O1 of make-checkpoint-gate-durable: the approval's refusal names the task, the gate the
 * caller asked to open and the position the branch tip actually records.
 */
class CheckpointApprovalRefusedExceptionSpec extends Specification {

    def "FR3: the refusal carries the tip's actual position and names it in its message"() {
        when:
        def refused = new CheckpointApprovalRefusedException('PROJ-1', new Position.AwaitingApproval('build'),
                new Position.AtStage('test'))

        then:
        refused.actualPosition() == new Position.AtStage('test')
        refused.message.contains('PROJ-1')
        refused.message.contains('"build"')
        refused.message.contains(new Position.AtStage('test').toString())
    }
}
