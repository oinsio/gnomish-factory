package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR6 of add-doctor-command: the carrier of a doctor run with a {@code fail} result names how many
 * failed; its exit code and its silence are pinned by {@code RunExitCodeMapperSpec} and {@code
 * RunExceptionReportingSpec}.
 */
class DoctorFailedExceptionSpec extends Specification {

    def "FR6: the message counts the failing checks (#failed)"() {
        expect:
        new DoctorFailedException(failed).message == expected

        where:
        failed || expected
        1 || 'doctor found 1 failing check'
        2 || 'doctor found 2 failing checks'
    }
}
