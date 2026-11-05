package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * {@link OpaqueSettings}, the loader's one copy of an opaque settings or params map (FR11 of
 * load-pipeline-config; UX2 of add-command-executor, design D10): a key with no value is a located
 * error left out of the copy, reported in key-name order, never an exception.
 */
class OpaqueSettingsSpec extends Specification {

    def "UX2: an absent map copies to an empty one and reports nothing"() {
        given:
        List<ConfigError> errors = []

        expect:
        OpaqueSettings.copy('stages/s/stage.yaml', 'executor.settings', null, errors) == [:]
        errors.isEmpty()
    }

    def "UX2: every key with no value is a located error in key-name order, and the copy keeps the rest"() {
        given: 'a parsed map whose order is not the key-name order'
        def parsed = new LinkedHashMap<String, Object>()
        parsed.put('zeta', null)
        parsed.put('mode', 'fast')
        parsed.put('alpha', null)
        List<ConfigError> errors = []

        when:
        def copy = OpaqueSettings.copy('stages/s/stage.yaml', 'verify[1].params', parsed, errors)

        then:
        errors == [
            new ConfigError('stages/s/stage.yaml', 'verify[1].params.alpha', "missing value for 'alpha'"),
            new ConfigError('stages/s/stage.yaml', 'verify[1].params.zeta', "missing value for 'zeta'")
        ]
        copy == [mode: 'fast']
    }

    def "UX2: the copy is immutable"() {
        when:
        OpaqueSettings.copy('f', 'w', [a: 1], []).put('b', 2)

        then:
        thrown(UnsupportedOperationException)
    }
}
