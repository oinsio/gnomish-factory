package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR12, D7 of add-factory-serve: {@link ServeExitCodeExceptionMapper} unwraps
 * {@link ServeExitCodeException#exitCode()}, falling back to 1 for anything else, mirroring
 * {@link TakeExitCodeExceptionMapper}.
 */
class ServeExitCodeExceptionMapperSpec extends Specification {

    private ServeExitCodeExceptionMapper mapper = new ServeExitCodeExceptionMapper()

    def "getExitCode unwraps the exit code carried by ServeExitCodeException"() {
        expect:
        mapper.getExitCode(new ServeExitCodeException(2)) == 2
        mapper.getExitCode(new ServeExitCodeException(0)) == 0
    }

    def "getExitCode falls back to 1 for an unrecognized Throwable"() {
        expect:
        mapper.getExitCode(new RuntimeException('unexpected')) == 1
        mapper.getExitCode(new IllegalStateException('surprise')) == 1
    }
}
