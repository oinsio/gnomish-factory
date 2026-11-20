package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.check.PendingAdoption
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR3, FR11, NFR-R1 of migrate-verify-to-executor-contract (design D14): the {@code RECORDED} arm of
 * the check round — a key the resume path read from the pending record is handed to the executor's
 * {@link PendingAdoption} before its poll, and only then; an {@code ISSUED} key and an executor that
 * takes no role are polled as they are. The role is read from the executor the entry bound, before the
 * pin decorator wraps it.
 */
class CheckRoundAdoptionSpec extends Specification {

    private static final VerifyCheck.Executor CHECK = VerifyChecks.builtin('files', [files: ['present.txt']])

    private static final PendingKey KEY = new PendingKey('recorded-1')

    private static StageExecutor.Request poll(PendingTurn.Origin origin) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'i.md', [], new AutonomyLimits(3), AdvancementMode.AUTO), new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}, 0, [],
        CHECK, CheckRef.of(0, CHECK), new PendingTurn(KEY, origin))
    }

    private static ExecutorResult pending() {
        new ExecutorResult(Status.PENDING, null, new PendingKey('next'), null, null, Usage.knownZero(), [])
    }

    def "FR3, D14: a RECORDED key is adopted by the executor's role, with the poll's own verdict request, before the poll and never started"() {
        given:
        def bound = Mock(Executor)
        def adoption = Mock(PendingAdoption)
        ExecutorRequest adopted = null

        when:
        def result = new CheckRound(bound, adoption, CHECK).execute(poll(PendingTurn.Origin.RECORDED))

        then:
        1 * adoption.adopt(_, KEY) >> { ExecutorRequest r, PendingKey k ->
            adopted = r
        }

        then:
        1 * bound.poll({ it.is(adopted) }, KEY) >> pending()
        0 * bound.start(_)
        adopted.role() == Role.VERDICT
        (result.status() as ExecutionResult.Pending).key() == new PendingKey('next')
    }

    def "FR3, D14: an ISSUED key is polled with no adoption, whatever role the executor takes"() {
        given:
        def bound = Mock(Executor)
        def adoption = Mock(PendingAdoption)

        when:
        new CheckRound(bound, adoption, CHECK).execute(poll(PendingTurn.Origin.ISSUED))

        then:
        0 * adoption.adopt(_, _)
        1 * bound.poll(_, KEY) >> pending()
    }

    def "FR3, D14: the adoption role is the bound executor's own when it takes one, and absent otherwise"() {
        given:
        def adopting = Mock(Executor, additionalInterfaces: [PendingAdoption])
        def plain = Mock(Executor)

        expect:
        CheckRound.adoptionOf(adopting).is(adopting)
        CheckRound.adoptionOf(plain) == null
    }
}
