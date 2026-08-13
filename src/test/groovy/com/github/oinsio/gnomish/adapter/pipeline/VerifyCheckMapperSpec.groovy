package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * {@link VerifyCheckMapper} (FR9 of add-external-check-github-actions): an unrecognized
 * {@code timeout-class} both appends a located error AND still maps the check's {@code
 * timeoutClass} to the safe {@code QUALITY} fallback (mirroring {@code DurationConfig#parse}'s
 * convention) — a fact {@link PipelineMapperSpec}'s error-path scenario cannot observe directly,
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
    // PipelineMapperSpec cannot observe, since the definition is discarded once
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
                    new VerifyCheckDto.External('ci', '30s', '5m', null, ['a.yml', 'b.yml'])
                ], errors)
        def without = VerifyCheckMapper.mapAll('m', [
            new VerifyCheckDto.External('ci', '30s', '5m', null)
        ], errors)

        then:
        errors.isEmpty()
        (withPins[0] as VerifyCheck.External).pinPaths() == ['a.yml', 'b.yml']
        (without[0] as VerifyCheck.External).pinPaths() == []
    }
}
