package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * {@link CheckParamsValidator}: the provider-owned hook the loader grades an {@code external}
 * check's {@code params} with (FR5, FR6 of add-plugin-architecture).
 *
 * <p>The claim under test is {@link CheckParamsValidator#none}: a real validator that accepts
 * everything, never {@code null}. The load seam keys its registry by every discovered provider, so
 * a provider grading no params must still contribute an entry — otherwise a missing key would mean
 * both "no such provider" and "nothing to grade", and a served provider would be reported unknown.
 */
class CheckParamsValidatorSpec extends Specification {

    def "none is a real validator that accepts any params"() {
        given:
        def validator = CheckParamsValidator.none()

        expect:
        validator != null
        validator.validate('stages/build/stage.yaml', 'verify[0].params', [:]).isEmpty()
        validator.validate('stages/build/stage.yaml', 'verify[0].params', [anything: 'at all']).isEmpty()
    }
}
