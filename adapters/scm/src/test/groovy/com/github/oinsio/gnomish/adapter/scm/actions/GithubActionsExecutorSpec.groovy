package com.github.oinsio.gnomish.adapter.scm.actions

import static com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsRig.LOG
import static com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsRig.SHA
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR6, FR13, FR17, NFR-C1 of migrate-verify-to-executor-contract, design D4, D13, D16: the {@code
 * github-actions} built-in answers the executor contract over the same runs judgement the external-
 * check rendering polls — a run in progress is {@code pending} on the attempt commit and a poll of
 * that key polls the same commit, a finished run is {@code completed} with SARIF, and a request it
 * cannot answer is {@code failed/infrastructure}.
 */
class GithubActionsExecutorSpec extends Specification {

    WireMockServer platform = new WireMockServer(0)

    def setup() {
        platform.start()
    }

    def cleanup() {
        platform.stop()
    }

    def "FR16: the describe declares a polling verdict built-in with its token, GitHub's hosts, a known-zero usage and its check id (the workflow file) as a pinned path"() {
        when:
        def describe = GithubActionsRig.executor(platform).describe()

        then:
        describe.roles() == [Role.VERDICT]
        describe.capabilities() == [Capability.POLL]
        describe.credentials() == [
            'GNOMISH_GITHUB_ACTIONS_TOKEN'
        ]
        describe.egress() == [
            'api.github.com',
            'results-receiver.actions.githubusercontent.com',
            '*.blob.core.windows.net'
        ]
        describe.settingsSchema().toString() == '{"type":"object","additionalProperties":false}'
        describe.reportsUsage()
        describe.checkIdIsPinnedPath()
    }

    // Scenario: verification/github-external-check / The adapter consumes the engine workspace type
    def "a run in progress is pending on the attempt commit, and a poll of the key polls that commit again"() {
        given:
        GithubActionsRig.run(platform, 'in_progress', null)
        def executor = GithubActionsRig.executor(platform)

        when:
        def started = executor.start(GithubActionsRig.verdict())

        then:
        started.status() == Status.PENDING
        started.pendingKey() == new PendingKey(SHA)
        started.usage().provenance() == UsageProvenance.REPORTED

        when: 'the run finished green by the next poll'
        GithubActionsRig.run(platform, 'completed', 'success')
        def polled = executor.poll(GithubActionsRig.verdict(), new PendingKey(SHA))

        then:
        polled.status() == Status.COMPLETED
        GithubActionsRig.results(polled) == []
    }

    // FR6: the failed jobs the provider reported become error results, the run link and log folded in
    def "a red run completes with one error result per failed job, named by the job"() {
        given:
        GithubActionsRig.run(platform, 'completed', 'failure')
        GithubActionsRig.failedBuildJob(platform)
        platform.stubFor(get(urlEqualTo(LOG)).willReturn(aResponse().withStatus(200).withBody('boom: assertion failed')))

        when:
        def result = GithubActionsRig.executor(platform).start(GithubActionsRig.verdict())

        then:
        result.status() == Status.COMPLETED
        def results = GithubActionsRig.results(result)
        results.size() == 1
        results[0].level() == SarifLevel.ERROR
        results[0].uri().forLog() == 'build'
        results[0].message().forLog() == "Job 'build' failed at step(s): Compile"
        results[0].details().contains('https://github.example/acme/widgets/actions/runs/1')
        results[0].details().contains('boom: assertion failed')
    }

    def "a platform that cannot answer is failed/infrastructure with the judgement's reason"() {
        given:
        platform.stubFor(get(urlEqualTo(GithubActionsRig.RUNS)).willReturn(aResponse().withStatus(404)))

        when:
        def result = GithubActionsRig.executor(platform).start(GithubActionsRig.verdict())

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains("external check 'ci.yml' cannot be verified (HTTP 404)")
    }

    def "a request it cannot answer is refused before any call: #why"() {
        when:
        def result = GithubActionsRig.executor(platform).start(request)
        def polled = GithubActionsRig.executor(platform).poll(request, new PendingKey(SHA))

        then:
        [result, polled].every {
            it.status() == Status.FAILED && it.failureClass() == FailureClass.INFRASTRUCTURE && it.reason().contains(reason)
        }
        platform.allServeEvents.isEmpty()

        where:
        why | request || reason
        'another role' | GithubActionsRig.request(Role.TRANSFORM, RoleInputs.transform('do it')) || 'answers verdict runs only, and was asked for a transform run'
        'settings' | GithubActionsRig.verdict([workflow: 'ci.yml']) || 'takes no settings, and was given 1'
    }

    def "a working copy that names no attempt commit is refused"() {
        given:
        def executor = new GithubActionsExecutor(GithubActionsRig.runs(platform), GithubActionsRig.check(), new Workspace() {})

        when:
        def result = executor.start(GithubActionsRig.verdict())

        then:
        result.status() == Status.FAILED
        result.reason().contains('reads the attempt commit from its working copy')
        platform.allServeEvents.isEmpty()
    }
}
