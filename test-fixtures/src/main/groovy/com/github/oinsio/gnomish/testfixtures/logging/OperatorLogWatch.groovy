package com.github.oinsio.gnomish.testfixtures.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * A root-level capture of the operator plane for the duration of one feature: every WARN/ERROR
 * event a {@code com.github.oinsio.gnomish} logger emits while it runs that no spec was watching.
 *
 * <p>Root-level rather than per-emitter on purpose (FR17 of harden-logging-observability): the
 * defect the gate exists to catch is a degrade line nobody thought about, so the watch cannot be
 * keyed on the classes a spec already knows it drives. The package filter keeps third-party noise
 * (Testcontainers, Spring, the docker client) out of the verdict — the log contract is about the
 * factory's own lines.
 *
 * <p>"Some spec was watching" is read off Logback itself: a {@link ListAppender} attached anywhere
 * in the emitting logger's chain. That is what {@link LogCaptureSupport} attaches, and it is also
 * what the hand-rolled attach/detach blocks that predate it attach — so the gate judges the two
 * alike instead of failing specs that do assert their lines through the older idiom (NG5 of the
 * change: those blocks migrate when their spec is next touched, not in a sweep). The question is
 * asked at append time, while the attachment is live, so a capture detached in the spec's own
 * cleanup still counts.
 *
 * <p>Attaching to the root logger sees everything regardless of the levels individual specs pin:
 * Logback checks the effective level at the emitting logger and then walks the whole appender
 * chain upward, so a capture that pinned its own logger at INFO still lets its WARN reach here.
 */
final class OperatorLogWatch {

    private final Logger root
    private final OperatorAppender appender

    /** Starts watching. */
    static OperatorLogWatch start() {
        new OperatorLogWatch()
    }

    private OperatorLogWatch() {
        root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = new OperatorAppender(root.loggerContext)
        appender.start()
        root.addAppender(appender)
    }

    /**
     * Stops watching and hands back what the feature emitted on the operator plane, split by
     * whether a spec's capture was watching it.
     */
    OperatorLogEvents stop() {
        root.detachAppender(appender)
        appender.stop()
        new OperatorLogEvents(appender.watched(), appender.unwatched())
    }

    private static final class OperatorAppender extends AppenderBase<ILoggingEvent> {

        /**
         * The factory's own loggers. Anything else in the build's logs is somebody else's
         * contract. Declared here rather than on the enclosing class because Groovy resolves the
         * appender's field reads dynamically, and Logback swallows what {@code append} throws —
         * an outer private would have made this filter silently match nothing.
         */
        private static final String FACTORY_PACKAGE = 'com.github.oinsio.gnomish.'

        private final LoggerContext loggers

        private final List<ILoggingEvent> watched = Collections.synchronizedList(new ArrayList<ILoggingEvent>())

        private final List<ILoggingEvent> unwatched = Collections.synchronizedList(new ArrayList<ILoggingEvent>())

        OperatorAppender(LoggerContext loggers) {
            this.loggers = loggers
            context = loggers
        }

        /**
         * Logback's {@code AppenderBase.doAppend} swallows any {@link Exception} an appender
         * throws into its status manager, so a broken judgement here would leave the gate
         * silently reporting nothing at all — the one failure mode a gate must not have (observed
         * once during this task, from a method call that did not resolve). Rethrown as an
         * {@link AssertionError}, which {@code doAppend} does not catch, so the emitting feature
         * goes red instead.
         */
        @Override
        protected void append(ILoggingEvent event) {
            try {
                if (event.level.isGreaterOrEqual(Level.WARN)
                        && event.loggerName.startsWith(FACTORY_PACKAGE)) {
                    (watchedBySpec(event.loggerName) ? watched : unwatched).add(event)
                }
            } catch (Exception problem) {
                throw new AssertionError('the log-expectation gate could not judge a log event', problem)
            }
        }

        List<ILoggingEvent> watched() {
            copyOf(watched)
        }

        List<ILoggingEvent> unwatched() {
            copyOf(unwatched)
        }

        private static List<ILoggingEvent> copyOf(List<ILoggingEvent> events) {
            synchronized (events) {
                return new ArrayList<ILoggingEvent>(events)
            }
        }

        /** Whether a spec's capture sits anywhere on this logger's chain, up to and including root. */
        private boolean watchedBySpec(String loggerName) {
            for (String name = loggerName; name != null; name = parentOf(name)) {
                // `exists` rather than `getLogger`: asking creates loggers, and the gate must not
                // grow the context it is observing.
                def logger = name == Logger.ROOT_LOGGER_NAME ? loggers.getLogger(name) : loggers.exists(name)
                if (logger != null && logger.iteratorForAppenders().any {
                            it instanceof ListAppender
                        }) {
                    return true
                }
            }
            false
        }

        private static String parentOf(String name) {
            if (name == Logger.ROOT_LOGGER_NAME) {
                return null
            }
            int dot = name.lastIndexOf('.')
            dot < 0 ? Logger.ROOT_LOGGER_NAME : name.substring(0, dot)
        }
    }
}
