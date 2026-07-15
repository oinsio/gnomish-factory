package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ConfigError: a single located validation problem (design D3). Contract:
 * {@code file}, {@code where} and {@code message} are non-blank so every error
 * names its location (NFR-O1); {@code render()} yields the one-line
 * {@code <file>: <where>: <message>} form reports present to the author (UX2).
 * Implements FR8 of load-pipeline-config.
 */
class ConfigErrorSpec extends Specification {

    // FR8: a validation problem is data carrying its location, not an exception
    def "a located error exposes file, where and message"() {
        when: 'an error is created with a file, a locator and a message'
        def error = new ConfigError('stages/build/stage.yaml', 'mechanism.executor', "unknown executor 'foo'")

        then: 'each component is exposed exactly as constructed'
        error.file() == 'stages/build/stage.yaml'
        error.where() == 'mechanism.executor'
        error.message() == "unknown executor 'foo'"
    }

    // NFR-O1/UX2: the rendering reads as <file>: <what and where>
    def "render produces the one-line located form"() {
        given: 'a located error'
        def error = new ConfigError('config.yaml', 'schemaVersion', 'missing required field')

        expect: 'the rendering names the file, the locator and the problem'
        error.render() == 'config.yaml: schemaVersion: missing required field'
    }

    // NFR-O1: an error that cannot name its location or problem is useless — rejected
    def "blank #component is rejected with the component name in the message"() {
        when: 'an error is created with one blank component'
        new ConfigError(file, where, message)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("ConfigError.$component")

        where:
        file          | where   | message   | component
        ''            | 'field' | 'problem' | 'file'
        '   '         | 'field' | 'problem' | 'file'
        'config.yaml' | ''      | 'problem' | 'where'
        'config.yaml' | '\t'    | 'problem' | 'where'
        'config.yaml' | 'field' | ''        | 'message'
        'config.yaml' | 'field' | ' \n'     | 'message'
    }

    // FR8: errors are values — aggregated lists compare by content (M2 data tables)
    def "errors with the same components are equal values"() {
        expect: 'two independently constructed errors with equal components are equal'
        new ConfigError('config.yaml', 'schemaVersion', 'missing') ==
                new ConfigError('config.yaml', 'schemaVersion', 'missing')
    }
}
