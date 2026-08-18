package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * {@link VerifyCheckMapper} (FR9 of add-external-check-github-actions): an unrecognized
 * {@code timeout-class} both appends a located error AND still maps the check's {@code
 * timeoutClass} to the safe {@code QUALITY} fallback (mirroring {@code DurationConfig#parse}'s
 * convention) — a fact {@link PipelineMapperDurationSpec}'s error-path scenario cannot observe directly,
 * since {@link PipelineMapper} discards the whole definition once any error is present.
 *
 * <p>Implements FR9 of add-external-check-github-actions.
 */
class VerifyCheckMapperSpec extends Specification {

    def "an unrecognized timeout-class still maps the check to the QUALITY fallback alongside the located error"() {
        given:
        def errors = []
        def dto = new VerifyCheckDto.External('ci', '30s', '15m', 'urgent')

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml', [dto], errors)

        then:
        errors.size() == 1
        (checks[0] as VerifyCheck.External).timeoutClass() == VerifyCheck.TimeoutClass.QUALITY
    }

    // FR13 (add-sandbox-core): an unrecognized verify-in still maps the command
    // to the SAME_BOX fallback alongside the located error — a fact
    // PipelineMapperSandboxSpec cannot observe, since the definition is discarded once
    // any error is present
    def "an unrecognized verify-in still maps the command to the SAME_BOX fallback alongside the located error"() {
        given:
        def errors = []
        def dto = new VerifyCheckDto.Command('./gradlew test', 'elsewhere')

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml', [dto], errors)

        then:
        errors.size() == 1
        (checks[0] as VerifyCheck.Command).verifyIn() == VerifyCheck.VerifyIn.SAME_BOX
    }

    // FR16 (add-sandbox-core): external pin paths are copied verbatim; a null
    // (absent) pin-path list maps to empty, and both cases parse with no error
    def "external pin paths are copied verbatim, null mapping to empty"() {
        given:
        def errors = []

        when:
        def withPins = VerifyCheckMapper.mapAll('m',
                [
                    new VerifyCheckDto.External('ci', null, null, '30s', '5m', null, ['a.yml', 'b.yml'])
                ], errors)
        def without = VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.External('ci', '30s', '5m', null)
        ], errors)

        then:
        errors.isEmpty()
        (withPins[0] as VerifyCheck.External).pinPaths() == ['a.yml', 'b.yml']
        (without[0] as VerifyCheck.External).pinPaths() == []
    }

    // FR13 (add-plugin-architecture), M4: a manifest written before providers
    // existed declares no `provider`, and still resolves to github — the default
    // is recorded in the model, so a defaulted selection is visible rather than
    // guessed later at dispatch
    def "an absent provider is recorded as the defaulted github"() {
        given:
        def errors = []

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml',
                [
                    new VerifyCheckDto.External('ci/build', '30s', '5m', null)
                ], errors)

        then:
        errors.isEmpty()
        (checks[0] as VerifyCheck.External).provider() == 'github'
        (checks[0] as VerifyCheck.External).params() == [:]
    }

    // FR7 (add-plugin-architecture): a declared provider is carried verbatim —
    // including one no jar serves, which the load seam reports — together with
    // its opaque provider-owned params
    def "a declared provider and its params are carried verbatim"() {
        given:
        def errors = []

        when:
        def checks = VerifyCheckMapper.mapAll('stages/build/stage.yaml',
                [
                    new VerifyCheckDto.External('gate', 'unserved-provider', [url: 'https://sonar/api'],
                    '30s', '5m', null, null)
                ], errors)

        then: 'the mapper never grades the selection; it only records it'
        errors.isEmpty()
        (checks[0] as VerifyCheck.External).provider() == 'unserved-provider'
        (checks[0] as VerifyCheck.External).params() == [url: 'https://sonar/api']
    }
}
