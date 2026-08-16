package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import java.time.Duration
import java.util.function.Supplier
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * Shared fixtures for the {@link ServeShutdown} specs — the SIGTERM sequence (FR11, design D9, M3):
 * interrupt the feed thread, flag every occupied slot's claim as gracefully stopping in the SAME
 * {@link com.github.oinsio.gnomish.app.lease.ClaimLossFlag} the round-boundary check already
 * consults, wait up to the grace window, then always kill the process tree. The process-tree step is
 * seamed behind {@link ProcessTreeKiller} so sequencing is provable without spawning or killing real
 * OS processes (the fake {@link RecordingKiller} stands in; {@link RealProcessTreeKiller} is the
 * untested production implementation).
 *
 * <p>The scenarios are split across sibling files to stay within the 200-line file cap:
 * {@link ServeShutdownSpec} (the sequence steps), {@link ServeShutdownDrainLoggingSpec} (the
 * grace-window summary line), and {@link ServeShutdownDrainRaceSpec} (concurrent drain races driven
 * by real threads). This base holds only what more than one of them needs.
 *
 * Implements FR11, D9, M3 of add-factory-serve; fix-reaper-idle-liveness FR4.
 */
abstract class ServeShutdownSpecBase extends Specification {

    protected static final TaskRef A = new TaskRef('github:o/r#1')
    protected static final TaskRef B = new TaskRef('github:o/r#2')

    // Most scenarios are about shutdown()'s interrupt/flag/drain/kill sequence, not about the
    // standing reaper (fix-reaper-idle-liveness FR4, covered by its own scenario) — an inert,
    // never-started StandingReaper is a harmless collaborator for all of them.
    protected static StandingReaper inertReaper() {
        new StandingReaper(
                ReaperDuty.NONE, { Duration d -> } as Sleeper, Duration.ofSeconds(30), {
                    []
                } as Supplier, new SystemClock())
    }

    // Captures ServeShutdown's log output so the grace-window summary line — the only observable
    // effect of awaitDrainedQuietly's boolean result and of the "any slot occupied" branch guarding
    // it — can be asserted on directly, the same pattern ReaperSpec uses.
    protected static List<ILoggingEvent> capture(Closure<Void> emit) {
        LogbackLogger logbackLogger = (LogbackLogger) LoggerFactory.getLogger(ServeShutdown)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }
}
