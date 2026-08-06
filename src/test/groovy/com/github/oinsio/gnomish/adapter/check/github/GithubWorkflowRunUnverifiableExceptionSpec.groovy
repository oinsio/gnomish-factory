package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * {@link GithubWorkflowRunUnverifiableException} (NFR-R3 of add-external-check-github-actions):
 * carries the offending client-rejection status code and names it in the message.
 *
 * <p>Implements NFR-R3 of add-external-check-github-actions.
 */
class GithubWorkflowRunUnverifiableExceptionSpec extends Specification {

    def "carries the status code and names it in the message"() {
        when:
        def ex = new GithubWorkflowRunUnverifiableException(404)

        then:
        ex.statusCode() == 404
        ex.message.contains('404')
    }
}
