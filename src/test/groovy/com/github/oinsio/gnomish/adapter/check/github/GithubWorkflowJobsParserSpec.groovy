package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * {@link GithubWorkflowJobsParser} (FR6 of add-external-check-github-actions): the job- and
 * step-level {@code conclusion} nullable-field handling (present, explicit JSON {@code null}, and
 * absent all read the same as "unknown") and the malformed-body failure path.
 *
 * <p>Implements FR6 of add-external-check-github-actions.
 */
class GithubWorkflowJobsParserSpec extends Specification {

    def "reads a job's null conclusion the same whether explicit JSON null or the field is absent"() {
        given:
        def body = """{"jobs":[
                {"id":10,"name":"build","status":"completed"${jobFragment},"steps":[]}
        ]}"""

        when:
        def jobs = GithubWorkflowJobsParser.parseJobs(body)

        then:
        jobs.size() == 1
        jobs[0].conclusion() == expectedConclusion

        where:
        jobFragment                | expectedConclusion
        ''                          | null
        ',"conclusion":null'       | null
        ',"conclusion":"failure"'  | 'failure'
    }

    def "reads a step's null conclusion the same whether explicit JSON null or the field is absent"() {
        given:
        def body = """{"jobs":[
                {"id":10,"name":"build","status":"completed","conclusion":"failure",
                 "steps":[{"name":"Compile","status":"completed"${stepFragment}}]}
        ]}"""

        when:
        def jobs = GithubWorkflowJobsParser.parseJobs(body)

        then:
        jobs[0].steps()[0].conclusion() == expectedConclusion

        where:
        stepFragment                | expectedConclusion
        ''                           | null
        ',"conclusion":null'        | null
        ',"conclusion":"success"'   | 'success'
    }

    def "a malformed response body raises GithubWorkflowRunQueryException with the parse failure as cause"() {
        when:
        GithubWorkflowJobsParser.parseJobs('not json')

        then:
        def ex = thrown(GithubWorkflowRunQueryException)
        ex.message.contains('List jobs')
        ex.cause != null
    }
}
