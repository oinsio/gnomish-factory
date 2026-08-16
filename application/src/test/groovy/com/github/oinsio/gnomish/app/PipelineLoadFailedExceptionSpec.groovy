package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR12, D10 of add-manual-run: carries the rendered loader-error lines unchanged.
 */
class PipelineLoadFailedExceptionSpec extends Specification {

    def "carries the rendered errors and joins them into the message"() {
        given:
        def errors = [
            'stage "build" has no verify checks',
            'unknown executor type "foo"'
        ]

        when:
        def exception = new PipelineLoadFailedException(errors)

        then:
        exception.renderedErrors() == errors
        exception.message == errors.join('\n')
    }

    def "defensively copies the errors list"() {
        given:
        def errors = new ArrayList<>(['one error'])
        def exception = new PipelineLoadFailedException(errors)

        when:
        errors.add('mutated after construction')

        then:
        exception.renderedErrors() == ['one error']
    }
}
