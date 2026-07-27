package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * D16 of add-tracker-port: {@link TakeExitCodeException} carries the computed exit code
 * unchanged for {@link TakeExitCodeExceptionMapper} to unwrap.
 */
class TakeExitCodeExceptionSpec extends Specification {

    def "exitCode returns the constructed value"() {
        expect:
        new TakeExitCodeException(15).exitCode() == 15
    }

    def "message names the exit code for diagnostics"() {
        expect:
        new TakeExitCodeException(13).message.contains('13')
    }
}
