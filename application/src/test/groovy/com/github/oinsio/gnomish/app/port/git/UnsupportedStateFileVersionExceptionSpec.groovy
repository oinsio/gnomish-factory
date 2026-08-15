package com.github.oinsio.gnomish.app.port.git

import spock.lang.Specification

/**
 * FR4 of add-git-workflow: the refusal a state-file reader raises when {@code .gnomish-task/}
 * carries a {@code "version"} this build does not support — the message names the offending file
 * and both versions, and the three facts stay readable off the exception for the CLI boundary
 * that reports it.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)): the type is a port-layer value carried
 * across the module boundary, so its own module's gate has to cover it.
 */
class UnsupportedStateFileVersionExceptionSpec extends Specification {

    // FR4: a version that is present but wrong reads as "unsupported version N", naming the file
    // and the one version this build accepts.
    def "renders a present-but-unsupported version, naming the file and the supported version"() {
        when:
        def ex = new UnsupportedStateFileVersionException('task.json', 2, 1)

        then:
        ex.message == 'task.json: unsupported version 2 (supported: 1)'
    }

    // FR4: a missing "version" field is reported as "missing", not as version -1 — the sentinel is
    // an internal encoding, never operator-facing.
    def "renders an absent version field as missing rather than as the -1 sentinel"() {
        when:
        def ex = new UnsupportedStateFileVersionException('state.json', -1, 1)

        then:
        ex.message == 'state.json: missing (supported: 1)'
        !ex.message.contains('-1')
    }

    // FR4: the boundary between the two renderings is exactly "negative means missing" — version 0
    // is a real, present version and must read as unsupported, not as missing.
    def "treats version 0 as present-and-unsupported, not as missing"() {
        when:
        def ex = new UnsupportedStateFileVersionException('task.json', 0, 1)

        then:
        ex.message == 'task.json: unsupported version 0 (supported: 1)'
    }

    // FR4: the CLI boundary reports the facts individually, so each accessor returns its own value.
    def "carries the file name and both versions as readable facts"() {
        when:
        def ex = new UnsupportedStateFileVersionException('state.json', 7, 3)

        then:
        ex.fileName() == 'state.json'
        ex.foundVersion() == 7
        ex.supportedVersion() == 3
    }
}
