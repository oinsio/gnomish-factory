package com.github.oinsio.gnomish.adapter.scm.actions

import static com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsRig.LOG
import static com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsRig.SHA
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR6 of migrate-verify-to-executor-contract (design D4): the {@code github-actions} built-in keeps
 * the former provider's observable behaviour. A workflow run on the attempt commit with two red
 * jobs is judged twice over the same platform — once by the provider's runs judgement ({@link
 * GithubWorkflowRuns#runsOf}), once by the built-in's {@code start} — and the built-in's
 * error results carry exactly the provider's job-log findings, from which the engine's one
 * derivation fails the check.
 */
class GithubActionsFindingsUnchangedSpec extends Specification {

    WireMockServer platform = new WireMockServer(0)

    def setup() {
        platform.start()
    }

    def cleanup() {
        platform.stop()
    }

    // FR6: two red jobs fail the check with the very findings the former provider's poll produced
    // Scenario: verification/verdict-executors / GitHub Actions findings unchanged
    def "FR6: a run with two red jobs fails the check with the provider's job-log findings, one result per job"() {
        given: 'a failed run on the attempt commit: two red jobs with their logs, one green job'
        GithubActionsRig.run(platform, 'completed', 'failure')
        platform.stubFor(get(urlEqualTo(GithubActionsRig.JOBS)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                  {"id":10,"name":"build","status":"completed","conclusion":"failure",
                   "steps":[{"name":"Compile","status":"completed","conclusion":"failure"}]},
                  {"id":11,"name":"lint","status":"completed","conclusion":"failure",
                   "steps":[{"name":"Run lint","status":"completed","conclusion":"failure"}]},
                  {"id":12,"name":"docs","status":"completed","conclusion":"success","steps":[]}
                ]}
                ''')))
        platform.stubFor(get(urlEqualTo(LOG)).willReturn(aResponse().withStatus(200).withBody('boom: compile error')))
        platform.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/11/logs'))
                .willReturn(aResponse().withStatus(200).withBody('lint: 3 violations')))

        when: 'the former provider judges the run, and the built-in answers a verdict run on it'
        def provider = GithubActionsRig.runs(platform).runsOf('ci.yml', SHA)
        def result = GithubActionsRig.executor(platform).start(GithubActionsRig.verdict())

        then: 'the provider failed the run with one finding per red job, each with its log'
        provider instanceof PollStatus.Fail
        def findings = (provider as PollStatus.Fail).findings()
        findings*.location() == ['build', 'lint']
        findings[0].details().contains('boom: compile error')
        findings[1].details().contains('lint: 3 violations')

        and: 'the built-in completes with one error result per red job, carrying the same texts'
        result.status() == Status.COMPLETED
        def results = GithubActionsRig.results(result)
        results*.level() == [
            SarifLevel.ERROR,
            SarifLevel.ERROR
        ]
        def carried = results.collect {
            new Finding(it.message().forParsing(), it.uri()?.forParsing(), it.details()?.forParsing())
        }
        carried == findings

        and: 'the engine derives a failing check carrying the provider findings'
        VerdictDerivation.derive(new VerdictAnswer.Sarif(carried.collect {
            new VerdictAnswer.Result(ResultLevel.ERROR, it)
        }, null), Threshold.DEFAULT) == new Verdict.Fail(findings)
    }
}
