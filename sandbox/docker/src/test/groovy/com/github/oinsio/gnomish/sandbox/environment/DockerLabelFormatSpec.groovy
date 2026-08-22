package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

class DockerLabelFormatSpec extends Specification {

    def "parses a comma-separated k=v label string"() {
        expect:
        DockerLabelFormat.parse('a=1,b=2') == [a: '1', b: '2']
    }

    def "a blank string parses to an empty map"() {
        expect:
        DockerLabelFormat.parse('') == [:]
        DockerLabelFormat.parse('   ') == [:]
    }

    def "an entry with no equals sign is skipped"() {
        expect:
        DockerLabelFormat.parse('a=1,noequals,b=2') == [a: '1', b: '2']
    }

    def "an entry whose equals sign is the first character (an empty key) is skipped"() {
        expect:
        DockerLabelFormat.parse('=x,b=2') == [b: '2']
    }

    def "a value may itself contain an equals sign"() {
        expect:
        DockerLabelFormat.parse('a=x=y') == [a: 'x=y']
    }
}
