package com.github.oinsio.gnomish.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.Appender
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.spi.AppenderAttachable
import ch.qos.logback.core.status.Status
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.logtext.LogText
import com.github.oinsio.gnomish.testsupport.AdversarialCorpus
import com.github.oinsio.gnomish.testsupport.InertText
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Shared
import spock.lang.Specification

/**
 * The end-to-end statement of the change (NFR-S1, NFR-S2, design D9 of
 * harden-untrusted-text-sinks): the two sinks are safe on the bytes they actually write, whatever
 * the call site assembled. The converter specs one directory up prove each link — this one proves
 * the links are joined, which is what `testing.md`'s "invariant specs across a flow" asks of a
 * design that claims a property holds end to end.
 *
 * <p>Two things make it a flow spec rather than a fourth component spec. It configures the
 * <b>real</b> Logback files — the production one and the one the suite itself runs on — so the
 * conversion rules, the pattern and the appender set are the shipped ones, not a fixture's idea of
 * them; and it captures <b>after the encoder</b>, over a byte buffer, because the property is about
 * rendered bytes and a {@code ListAppender} sees events before rendering. Each real appender's own
 * pattern is driven separately: a pattern that lost a safe conversion word in one appender only
 * would still be a hole, and the appender set is exactly where that is visible.
 *
 * <p>The console half drives the same corpus through the console owner, so both sinks answer the
 * same adversarial question in one place.
 *
 * <p>Not covered here, deliberately: the {@code gnomish.access} JSONL appender of
 * `add-subprocess-access-log` (design D1's exemption) — that change is sequenced before this one
 * but is not yet implemented, so the Logback files carry no such appender to assert about. The
 * exemption's regression guard lands with the appender itself; until then the plane-enumerating
 * feature below states the whole appender set of both files, so an appender that appeared without
 * a decision fails it.
 *
 * <p>Implements NFR-S1, NFR-S2 of harden-untrusted-text-sinks.
 */
class UntrustedTextSinkInvariantSpec extends Specification {

    private static final String PRODUCTION_CONFIG = '/logback-spring.xml'

    /** The configuration the suite itself runs on, which must render exactly as production does. */
    private static final String TEST_CONFIG = '/logback-test.xml'

    /**
     * Characters a record may spend on everything ahead of the neutralized field: the timestamp,
     * the thread name, the level, the four MDC labels and the logger name. Generous by an order of
     * magnitude — the assertion it serves is about a hostile field being bounded, not about the
     * head's own width.
     */
    private static final int HEAD_ALLOWANCE = 512

    /** The one MDC key each hostile-context feature drives; the pattern renders four. */
    private static final String HOSTILE_MDC_KEY = 'stage'

    @Shared
    private File home

    @Shared
    private List<LoggerContext> configured = []

    /**
     * One entry per real appender of both Logback files: the configured context it belongs to and
     * the pattern its encoder renders through. Configured once — a context per iteration would
     * open the production file appender several hundred times to prove nothing extra.
     */
    @Shared
    private List<Map<String, Object>> planes

    /**
     * Names each capture logger apart. Static because Spock builds a fresh specification instance
     * per iteration: a per-instance counter would hand every iteration the same logger, and the
     * previous iteration's capture appender would still be attached to it.
     */
    private static final AtomicInteger CAPTURES = new AtomicInteger()

    def setupSpec() {
        home = File.createTempDir('untrusted-text-sink-invariant', '')
        planes = [
            PRODUCTION_CONFIG,
            TEST_CONFIG
        ].collectMany { String config ->
            LoggerContext context = configure(config)
            appenders(context).collect { Appender<?> appender ->
                [config: config, appender: appender.name, pattern: encoderPattern(appender),
                    context: context] as Map<String, Object>
            }
        }
    }

    def cleanupSpec() {
        configured*.stop()
        home.deleteDir()
    }

    // NFR-S1, design D9: the planes the rest of this spec drives are the shipped appender set —
    // stated here so an appender added without a decision about this layer fails a spec rather than
    // silently rendering past it.
    def "the real appender set of both Logback files is the one this invariant covers"() {
        expect:
        planes.collect { "${it.config}:${it.appender}".toString() } as Set == [
            '/logback-spring.xml:FILE',
            '/logback-spring.xml:CONSOLE_STDOUT',
            '/logback-spring.xml:CONSOLE_STDERR',
            '/logback-test.xml:TEST_FILE',
            '/logback-test.xml:CONSOLE_STDOUT',
        ] as Set

        and: 'and every one of them renders the message, the throwable and the MDC safely'
        planes.every { Map<String, Object> plane ->
            String pattern = (String) plane.pattern
            pattern.contains('%safeMsg') && pattern.contains('%safeEx') &&
                    pattern.contains("%safeX{${HOSTILE_MDC_KEY}}")
        }
    }

    // NFR-S1: (a) the corpus as a message argument — the laundering path the accessor gate cannot
    // see, since the hostile text arrives as a String with no accessor left at the call site
    def "a hostile message argument writes one inert, bounded record: #shape on #plane.appender of #plane.config"() {
        given:
        Capture capture = capture(plane)

        when:
        capture.logger.info('stage reported: {}', hostile)

        then:
        String written = capture.text()
        InertText.isInert(written)
        lines(written).size() == 1
        written.length() <= HEAD_ALLOWANCE + LogText.RECORD_CAP_CHARS

        where:
        [plane, shape, hostile] << corpusOverPlanes()
    }

    // NFR-S1: (b) the corpus as the message of a thrown-and-caught exception passed as the trailing
    // argument — the widest laundering path of all, and the one part of a record that is
    // legitimately many lines, so "one record" here means "no line at column 0 but the first"
    def "a hostile exception message writes an inert, bounded record no line of which forges a second: #shape on #plane.appender of #plane.config"() {
        given:
        Capture capture = capture(plane)

        when:
        capture.logger.error('stage failed', caught(hostile))

        then:
        String written = capture.text()
        InertText.isInert(written)
        written.length() <= HEAD_ALLOWANCE + LogText.RECORD_CAP_CHARS

        and: 'the one line the rendering opens at column 0 is the head Logback writes there'
        lines(written)[1].startsWith("${IllegalStateException.name}: ")

        and: 'and every line after it is indented, so a message line cannot look like a new record'
        lines(written).drop(2).every { it.startsWith('\t') }

        where:
        [plane, shape, hostile] << corpusOverPlanes()
    }

    // FR3: bounding the record must not cost the diagnosis — a hostile message that fits the cap
    // keeps its stack trace, indented and readable, with the forged line marked as a continuation.
    // Asserted apart from the feature above because the megabyte entry of the corpus overruns the
    // cap in its head line alone, where dropping the trace is the bound working, not a regression.
    def "a hostile exception that fits the cap keeps its stack trace: #plane.appender of #plane.config"() {
        given:
        Capture capture = capture(plane)

        when:
        capture.logger.error('stage failed', caught(AdversarialCorpus.ENTRIES['CRLF forged record']))

        then: 'the frames are there, at the indentation a reader follows'
        lines(capture.text()).any { it.startsWith('\tat ') }

        and: 'and the line the message tried to forge a record with sits behind the continuation marker'
        lines(capture.text()).any {
            it.startsWith('\t| 2026-08-31 12:00:00 ERROR [main] compromised')
        }

        where:
        plane << planes
    }

    // NFR-S1: (c) the corpus as an MDC value — a stage name read out of the target repository's own
    // manifest, rendered ahead of the message where a forged prefix would be most convincing
    def "a hostile MDC value writes one inert, bounded record: #shape on #plane.appender of #plane.config"() {
        given:
        Capture capture = capture(plane)

        when:
        capture.withMdc(HOSTILE_MDC_KEY, hostile) {
            capture.logger.info('claimed task GNOME-17')
        }

        then:
        String written = capture.text()
        InertText.isInert(written)
        lines(written).size() == 1
        written.length() <= HEAD_ALLOWANCE + LogText.RECORD_CAP_CHARS

        where:
        [plane, shape, hostile] << corpusOverPlanes()
    }

    // FR2, G3: the layer is invisible to a call site that used the choke point — the record carries
    // the prepared text byte for byte, with no second escaping of the visible newline marker and no
    // second cap below the first
    def "choke-point output reaches the bytes unchanged: #shape on #plane.appender of #plane.config"() {
        given:
        Capture capture = capture(plane)
        String prepared = LogText.forLog(hostile)

        when:
        capture.logger.info('{}', prepared)

        then:
        capture.text().endsWith(prepared + System.lineSeparator())

        where:
        [plane, shape, hostile] << corpusOverPlanes()
    }

    // NFR-S2: the console half of the same invariant — the human path shows what a terminal would
    // obey instead of obeying it, and keeps the line structure an operator report is written in
    def "the console owner's human path leaves the corpus inert and keeps its line breaks: #shape"() {
        given:
        def out = new ByteArrayOutputStream()
        def console = new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), out)

        when:
        console.print(hostile)

        then:
        String written = out.toString(StandardCharsets.UTF_8)
        InertText.isInert(written)

        and: 'no carriage return either: the console plane renders it as the two characters \\r'
        !written.contains('\r')

        and: 'and every line break the operator was meant to see survived'
        written.count('\n') == hostile.count('\n')

        where:
        shape << AdversarialCorpus.ENTRIES.keySet()
        hostile << AdversarialCorpus.ENTRIES.values()
    }

    // NFR-S2, UX3: the machine path is read by a parser, not a terminal — JSON already bounds its
    // own metacharacters, and altering it would corrupt `--json`
    def "the console owner's machine path writes the corpus byte for byte: #shape"() {
        given:
        def out = new ByteArrayOutputStream()
        def console = new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), out)

        when:
        console.printMachine(hostile)

        then:
        out.toString(StandardCharsets.UTF_8) == hostile

        where:
        shape << AdversarialCorpus.ENTRIES.keySet()
        hostile << AdversarialCorpus.ENTRIES.values()
    }

    /** Every corpus entry against every real appender pattern: the whole cross product, by design. */
    private List<List<Object>> corpusOverPlanes() {
        return planes.collectMany { Map<String, Object> plane ->
            AdversarialCorpus.ENTRIES.collect { String shape, String hostile ->
                [plane, shape, hostile] as List<Object>
            }
        }
    }

    /** A byte buffer behind one real appender's own pattern, and the logger that feeds it. */
    private Capture capture(Map<String, Object> plane) {
        LoggerContext context = (LoggerContext) plane.context
        def buffer = new ByteArrayOutputStream()
        def encoder = new PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = (String) plane.pattern
        encoder.charset = StandardCharsets.UTF_8
        encoder.start()
        def appender = new OutputStreamAppender<>()
        appender.context = context
        appender.name = 'CAPTURE'
        appender.encoder = encoder
        appender.outputStream = buffer
        appender.start()
        Logger logger = context.getLogger("untrusted-text-sink-invariant-${CAPTURES.incrementAndGet()}")
        logger.additive = false
        logger.level = Level.TRACE
        logger.addAppender(appender)
        return new Capture(context: context, logger: logger, buffer: buffer)
    }

    /**
     * Configures a fresh context from one of the real files. {@code user.home} is seeded as a
     * context property — the scope Logback consults before system properties — so the production
     * file's rolling appender resolves under a temporary directory and this spec never writes a
     * byte into the operator's own log.
     */
    private LoggerContext configure(String config) {
        LoggerContext context = new LoggerContext()
        context.name = "untrusted-text-sink-invariant-${configured.size()}"
        context.setMDCAdapter(new LogbackMDCAdapter())
        configured << context
        context.putProperty('user.home', home.absolutePath)
        JoranConfigurator configurator = new JoranConfigurator()
        configurator.context = context
        configurator.doConfigure(getClass().getResource(config))
        List<Status> problems = context.statusManager.copyOfStatusList.findAll {
            it.level> Status.INFO
        }
        assert problems.isEmpty(): "${config} did not configure cleanly: ${problems}"
        return context
    }

    /** The appenders a configuration really renders through, the async wrapper descended into. */
    private static List<Appender<?>> appenders(LoggerContext context) {
        List<Appender<?>> found = []
        collect(context.getLogger(Logger.ROOT_LOGGER_NAME), found)
        return found
    }

    private static void collect(AppenderAttachable<?> attachable, List<Appender<?>> found) {
        attachable.iteratorForAppenders().forEachRemaining { Appender<?> appender ->
            if (appender instanceof AppenderAttachable) {
                collect((AppenderAttachable<?>) appender, found)
            } else {
                found << appender
            }
        }
    }

    private static String encoderPattern(Appender<?> appender) {
        def encoder = (LayoutWrappingEncoder<?>) appender.encoder
        return encoder.layout.pattern
    }

    /** The lines of one written record, without the empty remainder after its final separator. */
    private static List<String> lines(String written) {
        List<String> split = written.split('\\R', -1) as List<String>
        return split.last().isEmpty() ? split.dropRight(1) : split
    }

    /** A caught exception, so the rendering carries a real stack the trace-line shapes apply to. */
    private static Throwable caught(String message) {
        try {
            throw new IllegalStateException(message)
        } catch (IllegalStateException caught) {
            return caught
        }
    }

    /** One plane's captured bytes: the logger that writes them and the buffer they land in. */
    private static class Capture {

        LoggerContext context
        Logger logger
        ByteArrayOutputStream buffer

        String text() {
            return buffer.toString(StandardCharsets.UTF_8)
        }

        void withMdc(String key, String value, Closure<?> body) {
            context.getMDCAdapter().put(key, value)
            try {
                body.call()
            } finally {
                context.getMDCAdapter().remove(key)
            }
        }
    }
}
