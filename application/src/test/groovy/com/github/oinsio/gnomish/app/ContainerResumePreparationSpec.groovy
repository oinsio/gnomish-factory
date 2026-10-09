package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.Position
import spock.lang.Specification

/**
 * FR18 of make-checkpoint-gate-durable (design D6): the one container resume preparation both
 * {@code run --resume} and {@code take} call before their own drive — the pending-snapshot check,
 * then discard or reattach, then salvage only when no snapshot is pending (FR6, FR21 of
 * add-sandbox-core). Driven over the {@link SandboxRunSupport} port only; no container is started.
 */
class ContainerResumePreparationSpec extends Specification {

    SandboxRunSupport support = Mock(SandboxRunSupport)

    static PendingVerification snapshot() {
        new PendingVerification('sha-1', 'build', 0, RoundToken.of('0a1b2c'), Optional.empty())
    }

    // FR18, FR6: the ordinary interrupted visit — the snapshot check first, then the box is made
    // live for the recorded stage, then the leftovers are salvaged in it.
    def "checks for a pending snapshot, reattaches for the recorded stage, then salvages the leftovers"() {
        when:
        def pending = ContainerResumePreparation.prepare(support, false, new Position.AtStage('build'), 'PROJ-1')

        then:
        1 * support.pendingVerification() >> Optional.empty()

        then:
        1 * support.reattachFor('build')

        then:
        1 * support.salvageLeftovers('PROJ-1')
        0 * support.disposeExistingEnvironment()
        pending == null
    }

    // FR18, FR21: a snapshot commit unrecorded in state.json is an interrupted verification — the
    // box is reattached (the verification runs in it) but salvage never commits over a finished
    // round, and the pending verification is handed back for the drive.
    def "reattaches but never salvages while a snapshot is pending, returning it to the drive"() {
        given:
        def recorded = snapshot()

        when:
        def pending = ContainerResumePreparation.prepare(support, false, new Position.AtStage('build'), 'PROJ-1')

        then:
        1 * support.pendingVerification() >> Optional.of(recorded)

        then:
        1 * support.reattachFor('build')
        0 * support.salvageLeftovers(_)
        0 * support.disposeExistingEnvironment()
        pending.is(recorded)
    }

    // FR18, FR6: --discard-work disposes whatever survives instead — no reattach, no salvage — and
    // the snapshot check still runs first, its result still returned.
    def "under --discard-work disposes the existing environment, reattaching and salvaging nothing"() {
        given:
        def recorded = snapshot()

        when:
        def pending = ContainerResumePreparation.prepare(support, true, new Position.AtStage('build'), 'PROJ-1')

        then:
        1 * support.pendingVerification() >> Optional.of(recorded)

        then:
        1 * support.disposeExistingEnvironment()
        0 * support.reattachFor(_)
        0 * support.salvageLeftovers(_)
        pending.is(recorded)
    }

    // FR1 of make-checkpoint-gate-durable: at a gate the box of the manual stage that passed is the
    // one reattached — the stage whose round the gate's commit recorded.
    def "at a gate reattaches the box of the stage that passed"() {
        when:
        ContainerResumePreparation.prepare(support, false, new Position.AwaitingApproval('review'), 'PROJ-1')

        then:
        1 * support.pendingVerification() >> Optional.empty()
        1 * support.reattachFor('review')
        1 * support.salvageLeftovers('PROJ-1')
    }

    // FR18: past the pipeline's end there is no stage, so nothing is reattached and nothing salvaged.
    def "at the pipeline end reattaches nothing and salvages nothing"() {
        when:
        def pending = ContainerResumePreparation.prepare(support, false, new Position.PipelineEnd(), 'PROJ-1')

        then:
        1 * support.pendingVerification() >> Optional.empty()
        0 * support.reattachFor(_)
        0 * support.salvageLeftovers(_)
        0 * support.disposeExistingEnvironment()
        pending == null
    }
}
