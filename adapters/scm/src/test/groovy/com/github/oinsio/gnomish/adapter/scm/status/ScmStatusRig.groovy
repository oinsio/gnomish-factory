package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.adapter.scm.ScmRepository
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
import com.github.oinsio.gnomish.githubapi.GithubFastRetryConfig
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.github.tomakehurst.wiremock.WireMockServer
import java.time.Duration

/**
 * The {@code scm-status} executor over a WireMock SCM, assembled the way production does: the
 * operator subsection read and admitted by {@link ScmConnectionReader} with {@code loopback} granted
 * under {@code factory.egress.in-process} (the fixtures' operator file), the token resolved through
 * the secrets port, and the requests and answers of the executor contract.
 */
final class ScmStatusRig {

    /** The attempt commit. */
    static final String SHA = 'a' * 40

    /** The previous attempt's commit. */
    static final String PREVIOUS = 'b' * 40

    static final String TOKEN = 'scm-token'

    private ScmStatusRig() {}

    static String statusPath(String sha = SHA) {
        "/repos/acme/widgets/commits/${sha}/status?per_page=100"
    }

    static String checkRunsPath(String sha = SHA) {
        "/repos/acme/widgets/commits/${sha}/check-runs?per_page=100"
    }

    static VerifyCheck.Executor check(List<String> expected) {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, ScmStatusExecutor.NAME, 'ci', [:],
        Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [],
        VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, expected)
    }

    static SecretsProvider secrets(Map<String, String> values = [GNOMISH_SCM_STATUS_TOKEN: TOKEN]) {
        { name -> Optional.ofNullable(values[name]) } as SecretsProvider
    }

    /** The reader production connects, from the operator's subsection over the platform at {@code server}. */
    static ScmStatusReader connected(WireMockServer server) {
        connect(new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])),
        [('api-url'): server.baseUrl(), repo: 'acme/widgets'], secrets())
    }

    /** Reads and admits {@code subsection} as run assembly does, then connects the reader over it. */
    static ScmStatusReader connect(ScmConnectionReader reader, Map<String, Object> subsection, SecretsProvider secrets) {
        ScmStatusReader.connect(reader.read(ScmStatusExecutor.NAME, subsection, ScmStatusExecutor.ENDPOINT, []), secrets)
    }

    /** A reader on the core's fast-retry seam, for the answers the core retries. */
    static ScmStatusReader fastRetrying(WireMockServer server) {
        def connection = new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback']))
        .read(ScmStatusExecutor.NAME, [('api-url'): server.baseUrl(), repo: 'acme/widgets'],
        ScmStatusExecutor.ENDPOINT, [])
        new ScmStatusReader(GithubFastRetryConfig.fastClient(connection.endpoint()),
                ScmRepository.of('factory.check.scm-status', connection.settings()))
    }

    static ScmStatusExecutor executor(ScmStatusReader reader, List<String> expected, String sha = SHA) {
        new ScmStatusExecutor(reader, check(expected), AttemptCommitWorkspaces.at(sha))
    }

    static ExecutorRequest verdict(Map<String, ?> settings = [:]) {
        request(Role.VERDICT, RoleInputs.verdict('the contexts are green'), settings)
    }

    static ExecutorRequest request(Role role, RoleInputs inputs, Map<String, ?> settings = [:]) {
        new ExecutorRequest(role, new Workspace('.'), new Policy.ReadOnly(), inputs,
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
    }

    /** A combined status on {@code reported}, served for a read of {@code sha}. */
    static void statuses(WireMockServer server, List<Map<String, ?>> entries, String sha = SHA, String reported = sha) {
        def body = [state: 'pending', sha: reported, total_count: entries.size(), statuses: entries]
        server.stubFor(get(urlEqualTo(statusPath(sha))).willReturn(json(body)))
    }

    /** The check runs served for a read of {@code sha}. */
    static void checkRuns(WireMockServer server, List<Map<String, ?>> runs, String sha = SHA) {
        server.stubFor(get(urlEqualTo(checkRunsPath(sha))).willReturn(json([total_count: runs.size(), check_runs: runs])))
    }

    /** No Checks API: the platform answers the check-runs listing 404, as Gitea does. */
    static void noChecksApi(WireMockServer server, String sha = SHA) {
        server.stubFor(get(urlEqualTo(checkRunsPath(sha))).willReturn(aResponse().withStatus(404)))
    }

    static Map<String, ?> status(long id, String context, String state, String description = null) {
        [id: id, context: context, state: state, description: description]
    }

    static Map<String, ?> run(long id, String name, String status, String conclusion, String headSha = SHA) {
        [id: id, name: name, head_sha: headSha, status: status, conclusion: conclusion, output: [title: null]]
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

    private static def json(Object body) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
                .withBody(new ObjectMapper().writeValueAsString(body))
    }
}
