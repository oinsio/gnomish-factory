package com.github.oinsio.gnomish

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import spock.lang.Specification

/**
 * `logback-spring.xml` is instance-local logging config (task 8.1 of
 * add-manual-run, design D9): a single rolling file under
 * {@code ~/.gnomish/logs/}, a WARN+ stdout console appender, and a
 * dedicated ERROR-to-stderr console appender, wired onto the root logger.
 * `logback-spring.xml` (the Spring-Boot-aware filename) is applied by Boot's
 * {@code LoggingApplicationListener} during context startup, not by plain
 * Logback auto-configuration, so a real {@code @SpringBootTest} context boot
 * is required to observe it — this spec then asserts directly against the
 * live {@code LoggerContext} the boot produced. Proportionate to a config
 * file: confirms the appenders/policy/pattern/levels are wired as designed
 * without asserting actual file-rolling behavior (impractical and flaky in
 * a fast unit test).
 *
 * <p>Implements NFR-O1, NFR-O2, NFR-S1, NFR-S2 of add-manual-run.
 */
@SpringBootTest(classes = FactoryApplication)
class LogbackConfigSpec extends Specification {

    private static final Logger ROOT = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)

    // NFR-O1: the durable diagnostic record is a rolling file appender
    def "the root logger has a rolling file appender writing under ~/.gnomish/logs"() {
        given:
        RollingFileAppender<?> fileAppender = appenderNamed('FILE', RollingFileAppender)

        expect: 'the log file lives outside any workspace or this project\'s own git tree, under the operator home directory (NFR-S1, NFR-S2)'
        fileAppender.file == "${System.getProperty('user.home')}/.gnomish/logs/gnomish.log"
    }

    // NFR-O1: daily/size roll, ~7 days history, total size cap
    def "the rolling policy rolls daily and by size, keeps ~7 days, and caps total size"() {
        given:
        RollingFileAppender<?> fileAppender = appenderNamed('FILE', RollingFileAppender)
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) fileAppender.rollingPolicy

        expect:
        policy.fileNamePattern.contains('%d{yyyy-MM-dd}')
        policy.fileNamePattern.contains('%i')
        policy.maxFileSize.size == FileSize.valueOf('10MB').size
        policy.maxHistory == 7
        policy.totalSizeCap.size == FileSize.valueOf('100MB').size
    }

    // NFR-O1: taskId/stage/attempt MDC placeholders present in the shared pattern
    def "the file appender pattern carries taskId, stage and attempt MDC placeholders"() {
        given:
        RollingFileAppender<?> fileAppender = appenderNamed('FILE', RollingFileAppender)
        String pattern = encoderPattern(fileAppender)

        expect:
        pattern.contains('%X{taskId}')
        pattern.contains('%X{stage}')
        pattern.contains('%X{attempt}')
    }

    // NFR-O2: stdout belongs to the dialog - console appender passes WARN+ only
    def "the stdout console appender targets System.out and is filtered to WARN and above"() {
        given:
        ConsoleAppender<?> stdout = appenderNamed('CONSOLE_STDOUT', ConsoleAppender)

        expect:
        stdout.target == 'System.out'
        stdout.copyOfAttachedFiltersList.any { it instanceof ThresholdFilter && ((ThresholdFilter) it).level == Level.WARN }
    }

    // NFR-O2: ERROR is duplicated to stderr via a dedicated appender
    def "the stderr console appender targets System.err and is filtered to ERROR only"() {
        given:
        ConsoleAppender<?> stderr = appenderNamed('CONSOLE_STDERR', ConsoleAppender)

        expect:
        stderr.target == 'System.err'
        stderr.copyOfAttachedFiltersList.any { it instanceof ThresholdFilter && ((ThresholdFilter) it).level == Level.ERROR }
    }

    // NFR-O1, NFR-O2: all three appenders are attached to the root logger at INFO
    def "the root logger is set to INFO and has all three appenders attached"() {
        expect:
        ROOT.level == Level.INFO
        appenderNames() as Set == [
            'FILE',
            'CONSOLE_STDOUT',
            'CONSOLE_STDERR'
        ] as Set
    }

    private static <T extends Appender> T appenderNamed(String name, Class<T> type) {
        Appender<?> appender = ROOT.getAppender(name)
        assert appender != null: "no appender named ${name} attached to root"
        assert type.isInstance(appender)
        return (T) appender
    }

    private static List<String> appenderNames() {
        List<String> names = []
        ROOT.iteratorForAppenders().forEachRemaining { names << it.name }
        return names
    }

    private static String encoderPattern(RollingFileAppender<?> appender) {
        def encoder = appender.encoder
        return encoder.layout?.pattern ?: encoder.pattern
    }
}
