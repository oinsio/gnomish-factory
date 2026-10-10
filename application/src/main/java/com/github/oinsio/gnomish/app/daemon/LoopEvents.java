package com.github.oinsio.gnomish.app.daemon;

import com.github.oinsio.gnomish.logtext.FailureReason;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.status.DaemonComponent;

import java.io.Serial;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every line a {@link SupervisedLoop} emits, and the one call site of each {@code DAEMON_LOOP_*}
 * code (design D6 of supervise-daemon-loops-and-embed-dashboard). The loop is named by the {@code
 * component} MDC key its thread is framed with, not by the code, so one site serves every loop.
 *
 * <p>Failures of the tick and of the wait are edges (design D2): the streak is reported to a
 * {@link RepeatSuppressor} keyed by the component, its first occurrence and its periodic roll-ups
 * log WARN through the one {@link #warnFailing} site, repeats log DEBUG, and the first clean tick
 * after a streak logs one INFO recovery line.
 *
 * <p>Implements FR2, FR3, FR5, NFR-O1 of supervise-daemon-loops-and-embed-dashboard.
 */
final class LoopEvents {

    private static final Logger log = LoggerFactory.getLogger(SupervisedLoop.class);

    private final String streakKey;
    private final RepeatSuppressor suppressor;

    LoopEvents(DaemonComponent component, RepeatSuppressor suppressor) {
        this.streakKey = "daemon-loop:" + component.key();
        this.suppressor = suppressor;
    }

    /**
     * Reports a failure of the tick or the wait. The reason is the step plus the failure's {@link
     * FailureReason}, so a different fault restarts the streak; reading it is where a failure whose
     * {@code getMessage} itself throws escapes the guard and ends the thread — the policy's rung
     * then takes over.
     */
    void failed(String step, Throwable failure) {
        String reason = step + " failed: " + FailureReason.of(failure);
        switch (suppressor.failed(streakKey, reason)) {
            case RepeatOccurrence.First first -> warnFailing(first.reason(), 1, failure);
            case RepeatOccurrence.Repeat repeat ->
                log.debug("daemon loop still failing ({}x): {}", repeat.count(), repeat.reason());
            case RepeatOccurrence.RollUp rollUp -> warnFailing(rollUp.reason(), rollUp.count(), failure);
        }
    }

    private static void warnFailing(String reason, long count, Throwable failure) {
        log.warn(
                OperatorEvent.DAEMON_LOOP_TICK_FAILED.head() + "daemon loop {} ({}x so far); loop continues",
                reason,
                count,
                failure);
    }

    /** A clean tick: ends a failure streak with one INFO line, and is silent otherwise. */
    void tickSucceeded() {
        suppressor
                .recovered(streakKey)
                .ifPresent(recovery -> log.info(
                        "daemon loop recovered after {} failure(s) over {}: last was {}",
                        recovery.occurrences(),
                        recovery.outage(),
                        recovery.reason()));
    }

    void strayInterrupt() {
        log.warn(OperatorEvent.DAEMON_LOOP_STRAY_INTERRUPT.head()
                + "daemon loop thread interrupted with no stop requested; interrupt cleared, loop"
                + " continues with a full wait");
    }

    void workerDied(Thread dead, RestartDecision.Respawn respawn, Throwable cause) {
        withRenderableCause(
                cause,
                renderable -> log.error(
                        OperatorEvent.DAEMON_LOOP_WORKER_DIED.head()
                                + "daemon loop worker {} died; respawning after {} backoff (restart #{})",
                        dead.getName(),
                        respawn.backoff(),
                        respawn.restartCount(),
                        renderable));
    }

    void gaveUp(Thread dead, RestartDecision.GiveUp giveUp, Throwable cause) {
        withRenderableCause(
                cause,
                renderable -> log.error(
                        OperatorEvent.DAEMON_LOOP_GAVE_UP.head()
                                + "daemon loop worker {} died again after {} restart(s), more than {} within {};"
                                + " loop disabled, no respawn",
                        dead.getName(),
                        giveUp.restartCount(),
                        giveUp.maxRestarts(),
                        giveUp.window(),
                        renderable));
    }

    void backoffSleepFailed(Throwable failure) {
        withRenderableCause(
                failure,
                renderable -> log.warn(
                        OperatorEvent.DAEMON_LOOP_BACKOFF_SLEEP_FAILED.head()
                                + "backoff wait before a daemon loop respawn failed; respawning without further delay",
                        renderable));
    }

    // The death lines run in the worker's uncaught-exception handler, where the JVM drops whatever
    // is thrown — so a line that throws would also skip the respawn after it. A cause the logger
    // cannot render (its message throws) is replaced by a stand-in naming its type, and the same
    // line is written again with it.
    private static void withRenderableCause(Throwable cause, Consumer<Throwable> line) {
        try {
            line.accept(cause);
        } catch (Throwable unrenderable) {
            line.accept(new UnrenderableCause(cause));
        }
    }

    /** Stands in for a cause the logger could not render; its message names only the type. */
    static final class UnrenderableCause extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        UnrenderableCause(Throwable cause) {
            super("a " + cause.getClass().getName() + " that could not be rendered");
        }
    }
}
