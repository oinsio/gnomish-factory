package com.github.oinsio.gnomish.architecture

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.AllowsUnexpectedLogEvents
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.logging.LogExpectationGate
import com.github.oinsio.gnomish.testfixtures.logging.LogExpectationLedger
import com.github.oinsio.gnomish.testfixtures.logging.OperatorLogEvents
import com.github.oinsio.gnomish.testfixtures.logging.OperatorLogWatch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInvocation
import spock.lang.Specification

/**
 * FR17, M8 of harden-logging-observability, design D16: the runtime half of the log contract.
 * {@link LogContractGateSpec} proves a catalog code is named by some test source; this one proves
 * the behavioral backstop — an operator line is never emitted in silence, and a code nothing in a
 * run was watching does not pass.
 *
 * <p>The gate cannot be driven end to end from inside a spec it is itself watching, so the parts
 * are driven directly — watch, report, allowance lookup, ledger row — and the wiring that joins
 * them is asserted as a live registration. The verdict those rows feed is {@code
 * LogExpectationGateCheck} in {@code build-logic}, exercised by running the build.
 *
 * <p>Two constraints on what this spec may log: no <em>unwatched</em> coded row, which would
 * become the build's verdict (its fabricated lines carry no code, the row kind the verdict skips),
 * and no invented {@code GFnnn} literal at all — {@link LogContractGateSpec} pins {@code GF999} as
 * the code no test source names. Where a code is needed it comes from the catalog.
 */
class LogExpectationGateSpec extends Specification {

    private static final Logger FACTORY_LOG = LoggerFactory.getLogger(LogExpectationGateSpec)

    private static final Logger FOREIGN_LOG = LoggerFactory.getLogger('org.example.Foreign')

    // FR17: the gate is global, or it is a gate on the specs that remembered to ask for it.
    def "the extension is registered globally and really reached this spec"() {
        given: 'the registration Spock reads at discovery time'
        def declared = LogExpectationGate.classLoader
                .getResources('META-INF/services/' + IGlobalExtension.name)
                .toList()
                .collectMany { it.text.readLines() }

        expect: 'it names the gate, and the gate wrapped this very feature — live, not just present'
        LogExpectationGate.name in declared
        specificationContext.currentFeature.iterationInterceptors
                .any { it.class.name.startsWith(LogExpectationGate.name) }
    }

    // FR17: the defect is a degrade line nobody watched, so the watch cannot be keyed on emitters.
    @AllowsUnexpectedLogEvents(reason = 'the feature deliberately emits the unwatched line the gate exists to catch')
    def "an operator line no capture was watching is unwatched"() {
        given:
        def watch = OperatorLogWatch.start()

        when:
        FACTORY_LOG.warn('nobody is watching this one')

        then:
        watch.stop().with {
            unwatched*.formattedMessage == ['nobody is watching this one'] && watched.isEmpty()
        }
    }

    // FR17: attaching a capture IS the expectation declaration — there is no second API.
    def "an operator line an attached capture was watching is watched"() {
        given:
        def capture = LogCaptureSupport.attach(LogExpectationGateSpec)
        def watch = OperatorLogWatch.start()

        when:
        FACTORY_LOG.warn('this one is asserted')

        then:
        capture.list*.formattedMessage == ['this one is asserted']
        watch.stop().with {
            watched*.formattedMessage == ['this one is asserted'] && unwatched.isEmpty()
        }

        cleanup:
        capture.detach()
    }

    // NG5: the hand-rolled blocks that predate LogCaptureSupport assert their lines just as well,
    //      so the gate reads the attachment off Logback rather than a registration.
    def "an operator line a hand-rolled ListAppender was watching is watched too"() {
        given:
        def emitter = LoggerFactory.getLogger(LogExpectationGateSpec) as ch.qos.logback.classic.Logger
        def handRolled = new ListAppender<ILoggingEvent>()
        handRolled.start()
        emitter.addAppender(handRolled)
        def watch = OperatorLogWatch.start()

        when:
        FACTORY_LOG.warn('asserted through the older idiom')

        then:
        handRolled.list*.formattedMessage == [
            'asserted through the older idiom'
        ]
        watch.stop().unwatched.isEmpty()

        cleanup:
        emitter.detachAppender(handRolled)
        handRolled.stop()
    }

    // FR17: the factory's own operator plane — not INFO, not somebody else's stack.
    def "the watch judges the factory's WARN and ERROR lines only"() {
        given:
        def watch = OperatorLogWatch.start()

        when:
        FACTORY_LOG.info('an anchor line is not an operator event')
        FACTORY_LOG.debug('nor is detail')
        FOREIGN_LOG.error('and a third-party stack is somebody else\'s contract')

        then:
        watch.stop().with { unwatched.isEmpty() && watched.isEmpty() }
    }

    // FR17: a line emitted in silence is the defect, so the report names it whatever the verdict is.
    def "the report names the feature and the line it emitted"() {
        expect:
        with(LogExpectationGate.complaint('SomeSpec.some feature', [
            event('a degrade nobody pinned')
        ])) {
            it.contains('SomeSpec.some feature')
            it.contains('a degrade nobody pinned')
            it.contains('LogCaptureSupport')
        }
    }

    // The `real-time-wiring` shape: the hatch is allowed, an undocumented hatch is not.
    def "an allowance carries its reason, and a blank one fails outright"() {
        expect: 'a reason is handed back, and no allowance is no reason'
        LogExpectationGate.reasonOf('SomeSpec.some feature', allowanceOf('quiet on purpose')) == 'quiet on purpose'
        LogExpectationGate.reasonOf('SomeSpec.some feature', null) == null

        when:
        LogExpectationGate.reasonOf('SomeSpec.some feature', allowanceOf('  '))

        then:
        def failure = thrown(AssertionError)
        failure.message.contains('blank reason')
    }

    def "the allowance of a feature is found on the feature method"() {
        expect:
        LogExpectationGate.allowance(invocationOf('an operator line no capture was watching is unwatched')) != null
    }

    def "a feature with no allowance anywhere in its hierarchy has none"() {
        expect:
        LogExpectationGate.allowance(invocationOf('the report names the feature and the line it emitted')) == null
    }

    // FR17: the ledger is what the build task's by-code verdict is computed from.
    //
    // On a ledger of its own, and on events built rather than emitted — both deliberate. A row
    // recorded into the run's ledger would mark a real catalog code asserted for the whole
    // `:bootstrap` run, and an emitted one would do it through the gate's own interceptor even if
    // this feature touched no ledger at all. Either way the gate would be weakened by the feature
    // that proves it works, so here the code is data and the ledger is this feature's.
    def "the ledger records a watched code and an unwatched line"() {
        given: 'a coded line some capture was watching, and an uncoded one nothing was'
        def code = OperatorEvent.values().first().code()
        def ledger = new LogExpectationLedger()
        def events = new OperatorLogEvents(
                [
                    unemitted("[$code] a real catalog code, watched")
                ],
                [
                    unemitted('an uncoded line nothing watched')
                ])

        when:
        ledger.record('SomeSpec.some feature', events, false)

        then: 'the code joins the watched set, and the unwatched line is a row of its own'
        def observations = ledger.observations()
        observations.contains("watched\t$code")
        observations.contains('unwatched\t-\tSomeSpec.some feature\tWARN\t')
        observations.contains('an uncoded line nothing watched')

        and: 'and they are left where the build task looks for them'
        LogExpectationGate.reportDirectory().path.contains('log-expectation-gate')
    }

    // FR17: the run's ledger is the gate's own, so nothing a spec records can reach the verdict.
    def "a ledger a spec builds is not the one the run's verdict is computed from"() {
        given: 'a fabricated watched code, recorded into a ledger of this feature\'s own'
        def code = OperatorEvent.values().first().code()
        def mine = new LogExpectationLedger()
        def theirs = new LogExpectationLedger()

        when:
        mine.record('SomeSpec.some feature', new OperatorLogEvents([
            unemitted("[$code] fabricated")
        ], []), false)

        then: 'the second ledger never saw it — the state is per instance, not per JVM'
        mine.observations().contains("watched\t$code")
        !theirs.observations().contains(code)
    }

    /**
     * A WARN event as data: never handed to a logger, so it reaches neither the operator plane nor
     * the gate's interceptor. What the ledger reads off an event is its level, logger name and
     * message, all of which a constructed event carries.
     */
    private static ILoggingEvent unemitted(String message) {
        def event = new LoggingEvent()
        event.level = Level.WARN
        event.loggerName = LogExpectationGateSpec.name
        event.message = message
        event
    }

    private static ILoggingEvent event(String message) {
        def logged = LogCaptureSupport.attach(LogExpectationGateSpec)
        try {
            FACTORY_LOG.warn(message)
            return logged.list.first()
        } finally {
            logged.detach()
        }
    }

    private static AllowsUnexpectedLogEvents allowanceOf(String reason) {
        [reason: {
                reason
            }, annotationType: {
                AllowsUnexpectedLogEvents
            }] as AllowsUnexpectedLogEvents
    }

    /** A minimal stand-in for the invocation the interceptor is handed, carrying only what it reads. */
    private IMethodInvocation invocationOf(String featureName) {
        def spec = specificationContext.currentSpec.bottomSpec
        def feature = spec.allFeatures.find { it.name == featureName }
        assert feature != null: "no such feature: $featureName"
        [getFeature: { feature }, getSpec: { spec }] as IMethodInvocation
    }
}
