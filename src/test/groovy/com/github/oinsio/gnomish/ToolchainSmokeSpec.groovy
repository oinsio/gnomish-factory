package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * Toolchain-matrix proof: Groovy 4 compiles on the Java 25 toolchain and
 * Spock 2 runs on the JUnit Platform. Implements FR1, FR5 of add-project-skeleton.
 */
class ToolchainSmokeSpec extends Specification {

    def "spock specification compiles with the configured toolchain and runs on the JUnit Platform"() {
        given: 'the Java runtime executing this spec'
        def featureVersion = Runtime.version().feature()

        when: 'the runtime feature version is inspected'
        def isExpectedToolchain = featureVersion == 25

        then: 'the spec runs on the Java 25 toolchain'
        isExpectedToolchain
    }
}
