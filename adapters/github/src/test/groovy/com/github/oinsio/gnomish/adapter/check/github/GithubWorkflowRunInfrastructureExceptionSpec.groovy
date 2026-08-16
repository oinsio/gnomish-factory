package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * {@link GithubWorkflowRunInfrastructureException} (NFR-R1 of add-external-check-github-actions):
 * carries the offending status code and a message naming it.
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions.
 */
class GithubWorkflowRunInfrastructureExceptionSpec extends Specification {

    def "carries the status code and names it in the message"() {
        when:
        def ex = new GithubWorkflowRunInfrastructureException(503)

        then:
        ex.statusCode() == 503
        ex.message.contains('503')
    }
}
