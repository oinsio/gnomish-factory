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
}
