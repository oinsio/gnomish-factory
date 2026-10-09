package com.github.oinsio.gnomish.app.port

import spock.lang.Specification

/**
 * FR7, NFR-R2 of make-checkpoint-gate-durable: the resumed write's refusal names the task and why
 * nothing was written — the tip records no outcome to consume.
 */
class ResumedWriteRefusedExceptionSpec extends Specification {

    def "FR7: the refusal names the task and the null outcome it was decided on"() {
        when:
        def refused = new ResumedWriteRefusedException('PROJ-1')

        then:
        refused.message.contains('"PROJ-1"')
        refused.message.contains('no outcome to consume')
    }
}
