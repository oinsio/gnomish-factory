package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.executorcontract.Status
import spock.lang.Specification

/**
 * FR6 of migrate-verify-to-executor-contract (design D23): the {@code http} judgement's pass carries
 * its target URL across the contract as the SARIF run's {@code properties.runUrl}; a pass with no
 * URL writes none.
 */
class HttpResultsSpec extends Specification {

    def "a pass writes its URL as the SARIF run's properties.runUrl, and a pass without one writes none"() {
        when:
        def result = HttpResults.of(new PollStatus.Pass(url))
        def run = result.answer().path('sarif').path('runs')[0]

        then:
        result.status() == Status.COMPLETED
        run.path('results').isEmpty()
        run.has('properties') == (url != null)
        run.path('properties').path('runUrl').textValue() == url

        where:
        url << [
            'https://sonar.example/api/qualitygates/project_status?projectKey=widgets',
            null
        ]
    }
}
