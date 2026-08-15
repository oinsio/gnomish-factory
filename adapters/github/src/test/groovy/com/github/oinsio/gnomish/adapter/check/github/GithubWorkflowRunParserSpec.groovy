package com.github.oinsio.gnomish.adapter.check.github

import spock.lang.Specification

/**
 * {@link GithubWorkflowRunParser} (FR1 of add-external-check-github-actions): the {@code
 * conclusion}/{@code html_url} nullable-field handling (present, explicit JSON {@code null}, and
 * absent all read the same as "unknown") and the malformed-body failure path.
 *
 * <p>Implements FR1 of add-external-check-github-actions.
 */
class GithubWorkflowRunParserSpec extends Specification {

    def "reads a null conclusion/html_url the same whether explicit JSON null or the field is absent"() {
        given:
        def body = """{"workflow_runs":[
                {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed"${runFragment}}
        ]}"""

        when:
        def runs = GithubWorkflowRunParser.parseRuns(body)

        then:
        runs.size() == 1
        runs[0].conclusion() == expectedConclusion
        runs[0].htmlUrl() == expectedHtmlUrl

        where:
        runFragment | expectedConclusion | expectedHtmlUrl
        '' | null | null
        ',"conclusion":null,"html_url":null' | null | null
        ',"conclusion":"success","html_url":"https://example/runs/1"' | 'success' | 'https://example/runs/1'
    }

    def "a malformed response body raises GithubWorkflowRunQueryException with the parse failure as cause"() {
        when:
        GithubWorkflowRunParser.parseRuns('not json')

        then:
        def ex = thrown(GithubWorkflowRunQueryException)
        ex.message.contains('List workflow runs')
        ex.cause != null
    }
}
