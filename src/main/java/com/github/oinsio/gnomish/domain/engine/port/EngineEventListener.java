package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;

/**
 * The port through which the engine delivers its {@link EngineEvent} stream to an
 * observer — logging, a status view, cost accounting (design D7). The listener is
 * observability only: it reads the run, it never changes it. An event delivered to
 * a listener has already happened; the listener can report it but not veto it.
 *
 * <p>Delivery is <em>synchronous</em>: the engine calls {@link #onEvent} inline on
 * the task's critical path, so a slow listener slows the task. A consumer that needs
 * to do real work (write to a database, call a service) must offload that internally
 * — hand the event to a queue or an executor and return at once. A listener that
 * throws does <em>not</em> fail the run: the engine catches the exception, logs it,
 * and swallows it (the listener can never be an effect, so its failure is never one
 * either).
 *
 * <p>Implements FR12 of add-stage-engine.
 */
public interface EngineEventListener {

    /**
     * Delivers one {@link EngineEvent} to the observer, synchronously on the engine's
     * critical path. The implementation must return promptly, offloading any real work
     * internally; it may throw, in which case the engine logs and swallows the
     * exception rather than failing the run (this call is observability, never an
     * effect).
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param event the event that just occurred; never null
     */
    void onEvent(EngineEvent event);
}
