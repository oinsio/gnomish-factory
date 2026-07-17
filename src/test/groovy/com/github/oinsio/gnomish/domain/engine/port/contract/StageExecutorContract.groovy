package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import spock.lang.Specification

/**
 * Abstract port contract for {@link StageExecutor}: the behavioural guarantees the
 * engine relies on from ANY executor adapter — fake, real or interactive — when it
 * runs one round and reads back an {@link ExecutionResult}. A concrete subclass
 * binds an adapter-under-test through the {@link #arrange} seam; the fully-supported
 * scripted-fake subclass produces every variant, while an adapter that cannot
 * produce a given variant returns {@link Optional#empty} and the row is recorded as
 * a port-shape finding and skipped (see {@link PortContractSupport}).
 *
 * <p>FR14 of add-manual-run: interactive and real adapters pass the same
 * port-contract suites as the fakes (metric M2). Underlying obligations come from
 * FR1/FR6/FR13/D2 of add-stage-engine.
 */
abstract class StageExecutorContract extends Specification implements PortContractSupport {

    /**
     * The arrangement seam a subclass fills: build an adapter whose next
     * {@link StageExecutor#execute} returns the given {@code variant}, or return
     * {@link Optional#empty} to declare this adapter cannot produce that variant.
     *
     * @param variant which {@link ExecutionResult} variant the row needs
     * @return the adapter-under-test, or empty when the variant is unproducible
     */
    protected abstract Optional<StageExecutor> arrange(ExecutorVariant variant)

    /** The {@link ExecutionResult} variants the engine switches on. */
    enum ExecutorVariant {
        COMPLETED, DECISION_NEEDED
    }

    private static StageDefinition sampleStage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static StageExecutor.Request sampleRequest() {
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                sampleStage(), new Workspace() {}, 0, [])
    }

    // FR14: execute returns a non-null ExecutionResult of the declared sealed type (FR1)
    def "execute returns a non-null ExecutionResult"() {
        given: 'an adapter arranged to complete a round'
        def adapter = arrange(ExecutorVariant.COMPLETED)
        assumeProducible(adapter, 'StageExecutor', 'Completed')

        when: 'the engine runs one round'
        def result = adapter.get().execute(sampleRequest())

        then: 'a non-null sealed ExecutionResult comes back'
        result != null
        result instanceof ExecutionResult
    }

    // FR14: the Completed variant is reachable and carries non-null usage and trace (FR6/FR13)
    def "execute can yield Completed carrying non-null usage and trace"() {
        given: 'an adapter arranged to complete a round'
        def adapter = arrange(ExecutorVariant.COMPLETED)
        assumeProducible(adapter, 'StageExecutor', 'Completed')

        when: 'the engine runs one round'
        def result = adapter.get().execute(sampleRequest())

        then: 'it is a Completed carrying the shared telemetry the engine records'
        result instanceof ExecutionResult.Completed
        result.usage() != null
        result.trace() != null
    }

    // FR14: the DecisionNeeded variant is reachable with a non-blank question,
    // carried options and non-null usage/trace (FR6/FR13, design D6)
    def "execute can yield DecisionNeeded with a non-blank question and carried options"() {
        given: 'an adapter arranged to hand control back with a question'
        def adapter = arrange(ExecutorVariant.DECISION_NEEDED)
        assumeProducible(adapter, 'StageExecutor', 'DecisionNeeded')

        when: 'the engine runs one round'
        def result = adapter.get().execute(sampleRequest())

        then: 'it is a DecisionNeeded the engine can escalate verbatim'
        result instanceof ExecutionResult.DecisionNeeded
        !result.question().isBlank()
        result.options() != null
        result.usage() != null
        result.trace() != null
    }

    // FR14: the options a DecisionNeeded carries are unmodifiable — the engine
    // hands them to the human verbatim and nothing downstream may mutate them (FR6)
    def "a DecisionNeeded's options list is unmodifiable"() {
        given: 'an adapter arranged to hand control back with a question'
        def adapter = arrange(ExecutorVariant.DECISION_NEEDED)
        assumeProducible(adapter, 'StageExecutor', 'DecisionNeeded')

        and: 'the DecisionNeeded it produced'
        def result = adapter.get().execute(sampleRequest())

        when: 'a caller tries to mutate the options'
        result.options().add('sneaked')

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }
}
