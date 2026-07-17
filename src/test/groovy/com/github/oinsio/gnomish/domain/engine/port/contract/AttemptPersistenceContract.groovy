package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.time.Instant
import spock.lang.Specification

/**
 * Abstract port contract for
 * {@link com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence}: the
 * durability guarantees the engine relies on from ANY persistence adapter it calls
 * after every executed round. This is a VOID "sink" port — {@code persist} returns
 * nothing — so what was retained lives inside the adapter (an in-memory list, a git
 * commit, a database row). The suite persists a representative sequence of rounds and
 * delegates "what did the adapter retain?" to the {@link #retained} hook a concrete
 * subclass implements per its own storage, mapping it onto the suite-local
 * {@link PersistedEntry} triple. The production in-memory persistence (§7.10)
 * subclasses this SAME suite through that hook.
 *
 * <p>The engine-side behaviour that a THROWN persist turns the run into {@code Aborted}
 * (ordering / abort) is the engine's obligation, covered by engine specs, NOT the
 * port's own contract — it is deliberately not re-tested here.
 *
 * <p>FR14 of add-manual-run: interactive and real adapters pass the same
 * port-contract suites as the fakes (metric M2). Underlying obligations come from
 * FR11 of add-stage-engine.
 */
abstract class AttemptPersistenceContract extends Specification implements PortContractSupport {

    /** The persisted triple, adapter-agnostic, compared by value. */
    static record PersistedEntry(String taskId, TaskState state, ToolTrace trace) {}

    /**
     * The arrangement seam: build the persistence-under-test, ready to accept persists;
     * or return {@link Optional#empty} to declare it unproducible. The in-memory and
     * production adapters always produce, so no row is skipped for them.
     *
     * @return the persistence adapter under test, or empty when unproducible
     */
    protected abstract Optional<?> arrange()

    /**
     * The observation hook: return, in persist order, what the arranged {@code adapter}
     * durably retained, each mapped onto the suite-local {@link PersistedEntry}. An
     * in-memory fake maps its entry list; a git adapter would map its commits. This is
     * the per-storage seam §7.10 reuses.
     *
     * @param adapter the adapter arranged by {@link #arrange}
     * @return the retained triples, in the order they were persisted
     */
    protected abstract List<PersistedEntry> retained(Object adapter)

    /** Three successive rounds of a task, each a distinct (taskId, state, trace). */
    static List<PersistedEntry> threeRounds() {
        def key = { int round -> new AttemptKey('TASK-1', 'build', round) }
        def record = { int round ->
            new AttemptRecord(round, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH,
            [], ExecutorUsage.none(), JudgeUsage.none())
        }
        def s0 = TaskState.atStageStart('build')
        def s1 = s0.recordQualityFailure(record(0))
        def s2 = s1.recordQualityFailure(record(1))
        [
            new PersistedEntry('TASK-1', s0, new ToolTrace(key(0), [])),
            new PersistedEntry('TASK-1', s1, new ToolTrace(key(1), [])),
            new PersistedEntry('TASK-1', s2, new ToolTrace(key(2), [])),
        ]
    }

    private Object persistAll(List<PersistedEntry> rounds) {
        def adapter = arrange()
        assumeProducible(adapter, 'AttemptPersistence', 'persistence')
        rounds.each { adapter.get().persist(it.taskId(), it.state(), it.trace()) }
        adapter.get()
    }

    // FR14: successive persists across rounds accumulate rather than clobber (FR11)
    def "successive persists accumulate one retained entry per round"() {
        given: 'three successive rounds of a task'
        def rounds = threeRounds()

        when: 'each round is persisted in turn'
        def adapter = persistAll(rounds)

        then: 'all three are retained, none clobbered'
        retained(adapter).size() == rounds.size()
    }

    // FR14: the retained (taskId, state, trace) equal the inputs, by value (FR11)
    def "the retained entries equal the inputs by value, in persist order"() {
        given: 'three successive rounds of a task'
        def rounds = threeRounds()

        when: 'they are persisted'
        def adapter = persistAll(rounds)

        then: 'each retained triple equals the one handed in, in order'
        retained(adapter) == rounds
    }
}
