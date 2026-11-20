package com.github.oinsio.gnomish.app

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusExecutor
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusReader
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.PendingPoll
import com.github.oinsio.gnomish.domain.engine.PendingRound
import com.github.oinsio.gnomish.domain.engine.PendingState
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.ReadResult
import com.github.oinsio.gnomish.executorcontract.ResultFileReader
import com.github.oinsio.gnomish.executorcontract.ResultSource
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR9, NFR-R2 of migrate-verify-to-executor-contract (design D2, D19): the engine's pending
 * poll driving real check rounds — the {@link CheckRound} wrapper over a contract executor, its
 * result mapped onto the engine's shape by the one mapping — on virtual time. The engine's half of
 * "Rate limit backs off" ({@code scm-status} over a WireMock SCM; the executor's half is pinned in
 * {@code :adapters:scm}), and an unknown pending-state token read off the wire keeping the declared
 * timeout class.
 */
class PendingPollCheckRoundSpec extends Specification {

    static final String SHA = 'a' * 40
    static final String STATUS_PATH = "/repos/acme/widgets/commits/${SHA}/status?per_page=100"
    static final String RUNS_PATH = "/repos/acme/widgets/commits/${SHA}/check-runs?per_page=100"

    @TempDir
    Path resultDir

    WireMockServer scm = new WireMockServer(0)
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    private static VerifyCheck.Executor check(String executor, List<String> expected, Duration timeout) {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, executor, 'ci', [:], Duration.ofSeconds(30), timeout,
        VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, expected)
    }

    /** The check's answer through the pending poll over {@code bound}; each round's result noted with its instant. */
    private VerdictAnswer answer(Executor bound, VerifyCheck.Executor check, List<List<Object>> rounds) {
        def checkRound = new CheckRound(bound, null, check)
        def noting = { StageExecutor.Request request ->
            def result = checkRound.execute(request)
            rounds << [clock.instant(), result]
            result
        } as StageExecutor
        def poll = new PendingPoll(noting, new InMemoryAttemptPersistence(), AttemptDelivery.assumedDelivered(),
                VirtualTimeEquipment.on(clock, sleeper), new RecordingEventListener())
        def round = new PendingRound(VerifyingStages.of([check]), new TaskContext('TASK-1', UntrustedText.tracker('t'),
        UntrustedText.tracker('b'), []), AttemptCommitWorkspaces.at(SHA), new AttemptKey('TASK-1', 'build', 0),
        TaskState.atStageStart('build'), RoundToken.of('c' * 40))
        poll.answer(check, CheckRef.of(0, check), round).answer()
    }

    private ScmStatusReader reader() {
        def connection = new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback']))
        .read(ScmStatusExecutor.NAME, [('api-url'): scm.baseUrl(), repo: 'acme/widgets'], ScmStatusExecutor.ENDPOINT, [])
        ScmStatusReader.connect(connection, { name ->
            Optional.of('scm-token')
        } as SecretsProvider)
    }

    // Scenario: verification/verdict-executors / Rate limit backs off
    def "FR2, NFR-R2: a rate-limited read is pending unobserved with the retry-after hint, and the key is not polled sooner"() {
        given: 'the SCM refuses the first read for its rate limit, then reports the context green'
        scm.stubFor(get(urlEqualTo(STATUS_PATH)).inScenario('limit').whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader('retry-after', '120')).willSetStateTo('open'))
        scm.stubFor(get(urlEqualTo(STATUS_PATH)).inScenario('limit').whenScenarioStateIs('open')
                .willReturn(json([state: 'success', sha: SHA, total_count: 1,
                    statuses: [
                        [id: 1, context: 'build', state: 'success', description: null]
                    ]])))
        scm.stubFor(get(urlEqualTo(RUNS_PATH)).willReturn(aResponse().withStatus(404)))
        def ci = check(ScmStatusExecutor.NAME, ['build'], Duration.ofMinutes(10))
        def rounds = []

        when:
        def answer = answer(new ScmStatusExecutor(reader(), ci, AttemptCommitWorkspaces.at(SHA)), ci, rounds)

        then: 'the start answered pending, unobserved, with the pause as its hint'
        def first = rounds[0][1].status() as ExecutionResult.Pending
        first.state() == PendingState.UNOBSERVED
        first.pollAfter() == Duration.ofSeconds(120)
        first.key() == new PendingKey(SHA)

        and: 'the one poll of the key came no sooner than the hint, not at the 30 s interval'
        rounds.size() == 2
        Duration.between(rounds[0][0], rounds[1][0]) == Duration.ofSeconds(120)
        sleeper.slept == [Duration.ofSeconds(120)]
        scm.verify(2, getRequestedFor(urlEqualTo(STATUS_PATH)))

        and: 'the green context passes'
        answer == new VerdictAnswer.Sarif([], null)
    }

    // Scenario: executor/wire-contract / Unknown pending state keeps the declared class
    def "FR9: a pending answer whose state token is unknown on the wire keeps the declared quality class at the timeout"() {
        given: 'an executor whose every answer is read off the wire with a state token the contract does not know'
        def wire = '{"status":"pending","pendingKey":"k-1","pendingState":"stalled","reason":"still going","usage":{"provenance":"ABSENT"}}'
        def written = resultDir.resolve('result.json')
        Files.writeString(written, wire)
        def read = new ResultFileReader(ResultFileReader.MAX_CAP_BYTES, { String text ->
            UntrustedText.subprocess(text)
        })
        .readJson(ResultSource.hostFile(written), ExecutorResult)
        def stalled = (read as ReadResult.Parsed<ExecutorResult>).value()
        def bound = new Executor() {
                    Describe describe() {
                        throw new UnsupportedOperationException('not asked')
                    }

                    ExecutorResult start(ExecutorRequest request) {
                        stalled
                    }

                    ExecutorResult poll(ExecutorRequest request, PendingKey key) {
                        stalled
                    }
                }
        def ci = check('stalling', [], Duration.ofMinutes(2))
        def rounds = []

        when:
        def answer = answer(bound, ci, rounds)

        then:
        (rounds[0][1].status() as ExecutionResult.Pending).state() == PendingState.RUNNING
        answer == new VerdictAnswer.QualityFailure([
            new Finding("external check 'ci' did not complete within PT2M", null, 'still going')
        ])
    }

    private static def json(Object body) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
                .withBody(new ObjectMapper().writeValueAsString(body))
    }
}
