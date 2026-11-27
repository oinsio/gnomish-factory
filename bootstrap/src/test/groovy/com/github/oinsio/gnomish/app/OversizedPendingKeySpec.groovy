package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.PendingPoll
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerifyOrchestrator
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
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
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR20 of migrate-verify-to-executor-contract (design D20): a pending key over {@link
 * PendingKey#MAX_LENGTH} is refused where the contract result is read ({@code ResultReading}), so the
 * check round's wrapper maps a {@code failed/infrastructure} naming the cap, and the verify chain —
 * {@link VerifyOrchestrator} over {@link PendingPoll}, the production path every check takes since
 * task 4.2f — resolves the check as cannot-verify with no pending write and no poll.
 */
class OversizedPendingKeySpec extends Specification {

    @TempDir
    Path resultDir

    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    // Scenario: verification/pending-poll / Oversized key is infrastructure
    def "FR2: a pending key over the cap is refused at the read, and the check is cannot-verify naming the cap, with no poll"() {
        given: 'an executor whose start answer, read off the wire, carries a key one character over the cap'
        def wire = '{"status":"pending","pendingKey":"' + ('k' * (PendingKey.MAX_LENGTH + 1)) + '","usage":{"provenance":"ABSENT"}}'
        def written = resultDir.resolve('result.json')
        Files.writeString(written, wire)
        def read = new ResultFileReader(ResultFileReader.MAX_CAP_BYTES, { String text ->
            UntrustedText.subprocess(text)
        })
        .readJson(ResultSource.hostFile(written), ExecutorResult)
        def polls = 0
        def bound = new Executor() {
                    Describe describe() {
                        throw new UnsupportedOperationException('not asked')
                    }

                    ExecutorResult start(ExecutorRequest request) {
                        (read as ReadResult.Parsed<ExecutorResult>).value()
                    }

                    ExecutorResult poll(ExecutorRequest request, PendingKey key) {
                        polls++
                        throw new AssertionError('an unusable key is never polled')
                    }
                }
        def ci = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'oversized', 'ci', [:], Duration.ofSeconds(30),
        Duration.ofMinutes(2), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, [])
        def persistence = new InMemoryAttemptPersistence()
        def listener = new RecordingEventListener()
        def chain = new VerifyOrchestrator(new PendingPoll(new CheckRound(bound, null, ci), persistence,
                AttemptDelivery.assumedDelivered(), VirtualTimeEquipment.on(clock, sleeper), listener), DescribedExecutors.none(), clock, listener)

        when:
        def result = chain.verify(VerifyingStages.of([ci]), new TaskContext('TASK-1', UntrustedText.tracker('t'),
        UntrustedText.tracker('b'), []), AttemptCommitWorkspaces.at('a' * 40), new AttemptKey('TASK-1', 'build', 0),
        TaskState.atStageStart('build'), RoundToken.of('c' * 40))

        then: 'cannot-verify, its reason naming the cap; no pending write and no poll'
        def verdict = result.results()[0].verdict() as Verdict.CannotVerify
        verdict.reason().forLog().contains('unusable pendingKey')
        verdict.reason().forLog().contains(String.valueOf(PendingKey.MAX_LENGTH))
        persistence.pending.isEmpty()
        polls == 0
        sleeper.slept.isEmpty()
    }
}
