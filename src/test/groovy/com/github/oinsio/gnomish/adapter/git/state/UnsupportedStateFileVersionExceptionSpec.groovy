package com.github.oinsio.gnomish.adapter.git.state

import spock.lang.Specification

/**
 * FR4 of add-git-workflow: the refusal message names the offending file and distinguishes a
 * missing {@code "version"} field ({@code foundVersion == -1}) from an explicitly unsupported one.
 */
class UnsupportedStateFileVersionExceptionSpec extends Specification {

    // PIT ConditionalsBoundaryMutator on `foundVersion < 0`: the boundary value 0 itself is a
    // real, explicitly unsupported version (not "missing"), and must render as such.
    def "renders 'missing' only for -1, and an explicit unsupported version for 0 and above"() {
        given:
        def ex = new UnsupportedStateFileVersionException('task.json', foundVersion, 1)

        expect:
        ex.message == expectedMessage
        ex.fileName() == 'task.json'
        ex.foundVersion() == foundVersion
        ex.supportedVersion() == 1

        where:
        foundVersion || expectedMessage
        -1            | 'task.json: missing (supported: 1)'
        0             | 'task.json: unsupported version 0 (supported: 1)'
        2             | 'task.json: unsupported version 2 (supported: 1)'
    }
}
