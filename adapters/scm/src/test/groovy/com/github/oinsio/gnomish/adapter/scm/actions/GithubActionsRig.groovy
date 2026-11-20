package com.github.oinsio.gnomish.adapter.scm.actions

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SarifResult
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.githubapi.GithubHttpClient
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.github.tomakehurst.wiremock.WireMockServer
import java.time.Duration
import java.time.Instant

/**
 * The {@code github-actions} executor over a WireMock platform, assembled the way production does:
 * the operator subsection read and admitted by {@link ScmConnectionReader} with {@code loopback}
 * granted under {@code factory.egress.in-process} (the fixtures' operator file), the GitHub core over
 * the admitted endpoint, and the requests and answers of the executor contract.
 */
final class GithubActionsRig {

    static final String SHA = 'abc123'
    static final String RUNS = "/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=${SHA}&per_page=100"
    static final String JOBS = '/repos/acme/widgets/actions/runs/1/jobs?per_page=100'
    static final String LOG = '/repos/acme/widgets/actions/jobs/10/logs'

    private GithubActionsRig() {}

    static VerifyCheck.Executor check() {
        VerifyChecks.external('ci.yml', GithubActionsExecutor.NAME, Duration.ofSeconds(1), Duration.ofMinutes(1),
                VerifyCheck.TimeoutClass.QUALITY)
    }

    /** The executor of {@link #check()} at {@link #SHA}, against the platform at {@code server}. */
    static GithubActionsExecutor executor(WireMockServer server) {
        new GithubActionsExecutor(runs(server), check(), AttemptCommitWorkspaces.at(SHA))
    }

    /** The runs judgement over the admitted connection to the platform at {@code server}. */
    static GithubWorkflowRuns runs(WireMockServer server) {
        def connection = new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback']))
        .read(GithubActionsExecutor.NAME, [('api-url'): server.baseUrl(), repo: 'acme/widgets'],
        GithubActionsExecutor.ENDPOINT, GithubActionsExecutor.EGRESS)
        new GithubWorkflowRuns(new GithubHttpClient(connection.endpoint(), 'tok'), 'acme', 'widgets',
                new VirtualClock(Instant.EPOCH))
    }

    static ExecutorRequest verdict(Map<String, ?> settings = [:]) {
        request(Role.VERDICT, RoleInputs.verdict('the workflow is green'), settings)
    }

    static ExecutorRequest request(Role role, RoleInputs inputs, Map<String, ?> settings = [:]) {
        new ExecutorRequest(role, new Workspace('.'), new Policy.ReadOnly(), inputs,
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
    }

    /** A run of {@code ci.yml} at {@link #SHA} in the given state. */
    static void run(WireMockServer server, String status, String conclusion) {
        server.stubFor(get(urlEqualTo(RUNS)).willReturn(aResponse().withStatus(200).withBody("""
                {"workflow_runs":[{"id":1,"head_sha":"${SHA}","path":"ci.yml","run_attempt":1,
                 "status":"${status}","conclusion":${conclusion == null ? 'null' : '"' + conclusion + '"'},
                 "html_url":"https://github.example/acme/widgets/actions/runs/1"}]}
                """)))
    }

    /** One failed job, {@code build}, failing at {@code Compile}. */
    static void failedBuildJob(WireMockServer server) {
        server.stubFor(get(urlEqualTo(JOBS)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[{"id":10,"name":"build","status":"completed","conclusion":"failure",
                  "steps":[{"name":"Compile","status":"completed","conclusion":"failure"}]}]}
                ''')))
    }

    /** The SARIF results of a completed result's answer, read through the contract's own reading. */
    static List<SarifResult> results(ExecutorResult result) {
        def reading = Answer.read(result.answer(), { String text ->
            UntrustedText.factory(text)
        })
        assert reading instanceof AnswerReading.Parsed
        ((reading as AnswerReading.Parsed).answer() as Answer.Verdict).runs().collectMany {
            it.results()
        }
    }
}
