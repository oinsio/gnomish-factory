package com.github.oinsio.gnomish.app.doctor

import spock.lang.Specification

/**
 * UX1 of add-doctor-command: every {@code warn} and {@code fail} result carries a fix; the value
 * refuses one without, so no check can leave it out.
 */
class DoctorVerdictSpec extends Specification {

    def "UX1: a #status result with fix '#fix' is refused"() {
        when:
        new DoctorVerdict(DoctorCheckId.SECRETS, status, 'd', fix)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "a ${status.token()} result of secrets needs a fix".toString()

        where:
        [status, fix] << [
            [
                DoctorStatus.WARN,
                DoctorStatus.FAIL
            ],
            [null, '', ' ']
        ].combinations()
    }

    def "UX1: a #status result may go without a fix, and any result may carry one"() {
        expect:
        new DoctorVerdict(DoctorCheckId.SECRETS, status, 'd', fix).fix() == fix

        where:
        status | fix
        DoctorStatus.OK | null
        DoctorStatus.SKIPPED | null
        DoctorStatus.SKIPPED | 'f'
        DoctorStatus.WARN | 'f'
        DoctorStatus.FAIL | 'f'
    }
}
