package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * D16 of add-tracker-port: {@link TakeExitCodeExceptionMapper} unwraps
 * {@link TakeExitCodeException#exitCode()}, falling back to 1 for anything else, mirroring
 * {@link RunExitCodeMapper}'s own discipline of never relying on Spring Boot's implicit default.
 */
class TakeExitCodeExceptionMapperSpec extends Specification {

    private TakeExitCodeExceptionMapper mapper = new TakeExitCodeExceptionMapper()

    def "getExitCode unwraps the exit code carried by TakeExitCodeException"() {
        expect:
        mapper.getExitCode(new TakeExitCodeException(10)) == 10
        mapper.getExitCode(new TakeExitCodeException(0)) == 0
    }

    def "getExitCode falls back to 1 for an unrecognized Throwable"() {
        expect:
        mapper.getExitCode(new RuntimeException('unexpected')) == 1
        mapper.getExitCode(new IllegalStateException('surprise')) == 1
    }
}
