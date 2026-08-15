package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.time.Duration
import spock.lang.Specification

/**
 * Abstract port contract for
 * {@link com.github.oinsio.gnomish.domain.engine.port.EngineEventListener}: the
 * behavioural guarantees the engine relies on from ANY listener adapter it delivers
 * its {@link EngineEvent} stream to. This is a VOID "sink" port — {@code onEvent}
 * returns nothing — so the observable effect lives inside the adapter (a recording
 * list, log lines, a held status snapshot). The suite drives the port with one of
 * every sealed {@link EngineEvent} variant, in order, then delegates "what did the
 * adapter observe?" to the {@link #observedEvents} hook a concrete subclass implements
 * per its own effect. The status-snapshot (§6.2), MDC (§8.2) and logging (§8.3)
 * listeners subclass this SAME suite through that hook.
 *
 * <p>The engine-side guarantee that a THROWN listener exception is swallowed (so a
 * broken listener never breaks a run) is the engine's obligation, covered by engine
 * specs, NOT the port's own contract — it is deliberately not re-tested here.
 *
 * <p>FR14 of add-manual-run: interactive and real adapters pass the same
 * port-contract suites as the fakes (metric M2). Underlying obligations come from
 * FR12 of add-stage-engine.
 */
abstract class EngineEventListenerContract extends Specification implements PortContractSupport {

    /**
     * The arrangement seam: build the listener-under-test, ready to receive events;
     * or return {@link Optional#empty} to declare it unproducible. The recording and
     * production listeners always produce, so no row is skipped for them.
     *
     * @return the listener under test, or empty when unproducible
     */
    protected abstract Optional<?> arrange()

    /**
     * The observation hook: return, in delivery order, the {@link EngineEvent}s the
     * arranged {@code adapter} observed. A recording fake returns its list; a logging
     * listener maps its parsed log lines; a snapshot listener maps the events it folded
     * into its held state. This is the per-effect seam §6.2/§8.2/§8.3 reuse.
     *
     * @param adapter the adapter arranged by {@link #arrange}
     * @return the observed events, in the order they were delivered
     */
    protected abstract List<EngineEvent> observedEvents(Object adapter)

    /** One of each of the seven sealed {@link EngineEvent} variants, in a fixed order. */
    static List<EngineEvent> everyVariant() {
        def key = new AttemptKey('TASK-1', 'build', 0)
        def state = TaskState.atStageStart('build')
        def trace = new ToolTrace(key, [])
        [
            new EngineEvent.RunStarted('TASK-1', new Position.AtStage('build'), 0),
            new EngineEvent.AttemptStarted(key),
            new EngineEvent.ExecutionFinished(key, ExecutorUsage.none()),
            new EngineEvent.CheckStarted(key, new CheckRef(0, 'files_exist')),
            new EngineEvent.CheckFinished(key,
            new CheckResult(new CheckRef(0, 'files_exist'), new Verdict.Pass(), Duration.ZERO)),
            new EngineEvent.AttemptFinished(key, state, trace),
            new EngineEvent.TaskFinished('TASK-1', new TaskOutcome.Completed(state)),
        ]
    }

    private Object deliver(List<EngineEvent> events) {
        def adapter = arrange()
        assumeProducible(adapter, 'EngineEventListener', 'listener')
        events.each { adapter.get().onEvent(it) }
        adapter.get()
    }

    // FR14: the listener accepts every sealed EngineEvent variant, completing normally (FR12)
    def "the listener accepts every EngineEvent variant"() {
        given: 'one of every sealed EngineEvent variant'
        def events = everyVariant()

        when: 'each is delivered to the listener'
        def adapter = deliver(events)

        then: 'the listener completed normally and observed one event per variant'
        observedEvents(adapter).size() == events.size()
    }

    // FR14: the listener observes the delivered events in delivery order (FR12)
    def "the listener observes events in delivery order"() {
        given: 'the sealed variants in a fixed delivery order'
        def events = everyVariant()

        when: 'they are delivered in that order'
        def adapter = deliver(events)

        then: 'the listener observed exactly that sequence, in order'
        observedEvents(adapter) == events
    }
}
