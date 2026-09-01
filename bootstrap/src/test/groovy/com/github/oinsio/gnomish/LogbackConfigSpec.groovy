package com.github.oinsio.gnomish

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.status.Status
import ch.qos.logback.core.util.FileSize
import ch.qos.logback.core.util.StatusPrinter2
import java.nio.charset.StandardCharsets
import spock.lang.Specification

/**
 * `logback-spring.xml` is instance-local logging config (task 8.1 of add-manual-run, design D9,
 * hardened by tasks 2.1/2.2/2.5 of harden-logging-observability): a rolling file under
 * {@code ~/.gnomish/logs/} behind an asynchronous appender, a WARN+ stdout console appender and a
 * dedicated ERROR-to-stderr one, all UTF-8, wired onto a root logger whose level an operator can
 * raise for one run.
 *
 * <p>Each feature configures a <b>fresh</b> {@link LoggerContext} from the production file rather
 * than asserting against the JVM's live one. Two reasons, both consequences of this change:
 * `logback-test.xml` (task 2.3) now takes the test suite off the production configuration
 * entirely, so a booted context no longer carries it; and pointing {@code user.home} at a
 * temporary directory <em>through the context's own property scope</em> is what lets this spec
 * exercise a real {@code RollingFileAppender} without creating a file in the operator's home —
 * which is the very pollution task 2.3 exists to end. Nothing in the file uses a Spring-only tag
 * ({@code springProfile}/{@code springProperty}), so Joran reads it directly; the {@code -spring}
 * suffix governs who <em>discovers</em> the file, not who can parse it.
 *
 * <p>Proportionate to a config file: confirms the appenders/policy/pattern/levels/charset are
 * wired as designed without asserting actual file-rolling behavior (impractical and flaky in a
 * fast unit test).
 *
 * <p>Implements NFR-O1, NFR-O2, NFR-S1, NFR-S2 of add-manual-run and FR8, FR10, NFR-P1 of
 * harden-logging-observability.
 */
class LogbackConfigSpec extends Specification {

    private static final String PRODUCTION_CONFIG = '/logback-spring.xml'

    private final List<LoggerContext> configured = []
    private File home

    def setup() {
        home = File.createTempDir('logback-config-spec', '')
    }

    def cleanup() {
        configured*.stop()
        home.deleteDir()
    }

    // NFR-S1, NFR-S2: the log file lives under the operator home, outside any workspace or git tree
    def "the file appender writes gnomish.log under the operator home directory"() {
        given:
        RollingFileAppender<?> fileAppender = fileAppender(configure())

        expect:
        fileAppender.file == "${home.absolutePath}/.gnomish/logs/gnomish.log"
    }

    // FR10: the log directory is overridable for one run, which is what takes the E2E layer's
    // spawned production binary off the operator's file (task 2.3)
    def "GNOMISH_LOG_DIR redirects the file away from the operator home with no rebuild"() {
        given:
        File elsewhere = new File(home, 'redirected')

        when:
        RollingFileAppender<?> fileAppender = fileAppender(configure(GNOMISH_LOG_DIR: elsewhere.absolutePath))

        then:
        fileAppender.file == "${elsewhere.absolutePath}/gnomish.log"
        fileAppender.rollingPolicy.fileNamePattern.startsWith(elsewhere.absolutePath)
    }

    // NFR-O1: daily/size roll, ~7 days history, total size cap
    def "the rolling policy rolls daily and by size, keeps ~7 days, and caps total size"() {
        given:
        SizeAndTimeBasedRollingPolicy<?> policy =
                (SizeAndTimeBasedRollingPolicy<?>) fileAppender(configure()).rollingPolicy

        expect:
        policy.fileNamePattern.contains('%d{yyyy-MM-dd}')
        policy.fileNamePattern.contains('%i')
        policy.maxFileSize.size == FileSize.valueOf('10MB').size
        policy.maxHistory == 7
        policy.totalSizeCap.size == FileSize.valueOf('100MB').size
    }

    // NFR-O1 + FR8: the correlation keys the post-mortem greps by, including the daemon `component`
    def "every appender's pattern carries the taskId, stage, attempt and component MDC placeholders"() {
        given:
        LoggerContext context = configure()

        expect:
        [
            fileAppender(context),
            stdout(context),
            stderr(context)
        ].every { Appender<?> appender ->
            String pattern = encoderPattern(appender)
            pattern.contains('%X{taskId}') && pattern.contains('%X{stage}') &&
                    pattern.contains('%X{attempt}') && pattern.contains('%X{component}')
        }
    }

    // FR10: evidence must read the same on every machine, so no encoder inherits the platform default
    def "every encoder pins UTF-8 rather than the platform default charset"() {
        given:
        LoggerContext context = configure()

        expect:
        [
            fileAppender(context),
            stdout(context),
            stderr(context)
        ].every { Appender<?> appender ->
            ((LayoutWrappingEncoder<?>) appender.encoder).charset == StandardCharsets.UTF_8
        }
    }

    // NFR-P1: INFO-volume traffic never blocks a worker thread on file I/O
    def "the file appender is reached through an async appender that never discards"() {
        given:
        AsyncAppender async = asyncAppender(configure())

        expect: 'the async wrapper is what the root logger holds, and the file appender sits behind it'
        async.getAppender('FILE') instanceof RollingFileAppender

        and: 'discarding is off, so a full queue blocks the producer instead of dropping INFO events'
        async.discardingThreshold == 0
        !async.neverBlock

        and: 'the queue is sized above Logback\'s 256 default for the serve-tick fan-out burst'
        async.queueSize == 1024
    }

    // D7: the consoles stay synchronous — an ERROR is often a dying process's last word
    def "the console appenders are attached synchronously, not through the async wrapper"() {
        given:
        LoggerContext context = configure()

        expect:
        stdout(context) instanceof ConsoleAppender
        stderr(context) instanceof ConsoleAppender

        and: 'the async wrapper wraps the file appender alone'
        asyncAppender(context).iteratorForAppenders().collect {
            it.name
        } == ['FILE']
    }

    // NFR-O2: stdout belongs to the dialog - console appender passes WARN+ only
    def "the stdout console appender targets System.out and is filtered to WARN and above"() {
        given:
        ConsoleAppender<?> stdout = stdout(configure())

        expect:
        stdout.target == 'System.out'
        stdout.copyOfAttachedFiltersList.any {
            it instanceof ThresholdFilter && ((ThresholdFilter) it).level == Level.WARN
        }
    }

    // NFR-O2: ERROR is duplicated to stderr via a dedicated appender
    def "the stderr console appender targets System.err and is filtered to ERROR only"() {
        given:
        ConsoleAppender<?> stderr = stderr(configure())

        expect:
        stderr.target == 'System.err'
        stderr.copyOfAttachedFiltersList.any {
            it instanceof ThresholdFilter && ((ThresholdFilter) it).level == Level.ERROR
        }
    }

    // NFR-O1, NFR-O2: the async file wrapper and both consoles are the root logger's appenders
    def "the root logger carries the async file appender and both consoles"() {
        expect:
        appenderNames(configure()) as Set == [
            'ASYNC_FILE',
            'CONSOLE_STDOUT',
            'CONSOLE_STDERR'
        ] as Set
    }

    // FR10: verbosity is raisable for one run without a rebuild, and defaults to INFO
    def "the root level is #expected when GNOMISH_LOG_LEVEL is #override"() {
        given:
        Map<String, String> properties = override == null ? [:] : [GNOMISH_LOG_LEVEL: override]

        expect:
        root(configure(properties)).level == expected

        where:
        override | expected
        null | Level.INFO
        'DEBUG' | Level.DEBUG
        'WARN' | Level.WARN
    }

    /**
     * Configures a fresh context from the production file. {@code user.home} is seeded as a
     * context property — the scope Logback consults before system properties — so the default
     * log location resolves under a temporary directory and this spec never writes a byte into
     * the operator's own log.
     */
    private LoggerContext configure(Map<String, String> properties = [:]) {
        LoggerContext context = new LoggerContext()
        context.name = 'logback-config-spec'
        configured << context
        context.putProperty('user.home', home.absolutePath)
        properties.each { String key, String value ->
            context.putProperty(key, value)
        }
        JoranConfigurator configurator = new JoranConfigurator()
        configurator.context = context
        configurator.doConfigure(getClass().getResource(PRODUCTION_CONFIG))
        List<Status> problems = context.statusManager.copyOfStatusList.findAll {
            it.level> Status.INFO
        }
        if (!problems.isEmpty()) {
            new StatusPrinter2().print(context)
            throw new AssertionError("logback-spring.xml did not configure cleanly: ${problems}" as Object)
        }
        return context
    }

    private static Logger root(LoggerContext context) {
        return context.getLogger(Logger.ROOT_LOGGER_NAME)
    }

    private static AsyncAppender asyncAppender(LoggerContext context) {
        return (AsyncAppender) appenderNamed(context, 'ASYNC_FILE')
    }

    private static RollingFileAppender<?> fileAppender(LoggerContext context) {
        return (RollingFileAppender<?>) asyncAppender(context).getAppender('FILE')
    }

    private static ConsoleAppender<?> stdout(LoggerContext context) {
        return (ConsoleAppender<?>) appenderNamed(context, 'CONSOLE_STDOUT')
    }

    private static ConsoleAppender<?> stderr(LoggerContext context) {
        return (ConsoleAppender<?>) appenderNamed(context, 'CONSOLE_STDERR')
    }

    private static Appender<?> appenderNamed(LoggerContext context, String name) {
        Appender<?> appender = root(context).getAppender(name)
        assert appender != null: "no appender named ${name} attached to root"
        return appender
    }

    private static List<String> appenderNames(LoggerContext context) {
        List<String> names = []
        root(context).iteratorForAppenders().forEachRemaining {
            names << it.name
        }
        return names
    }

    private static String encoderPattern(Appender<?> appender) {
        def encoder = appender.encoder
        return encoder.layout?.pattern ?: encoder.pattern
    }
}
