package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.status.AnchorLog
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * Which path a {@link ServeCommand} with a live tracker takes, and with what configuration: the
 * ordinary run hands the assembled automaton to the forever-loop starter, {@code --drain} drives
 * the drain path instead, {@code --slots} overrides the configured slot count, and the start anchor
 * names the configuration the daemon actually resolved.
 *
 * <p>What the assembled runtime is wired with is proven by its effects in {@link
 * ServeRuntimeWiringSpec}, not here.
 *
 * <p>Implements FR2, FR4, FR10, FR12, NFR-O2, M3 of add-factory-serve. Implements FR2 of
 * harden-logging-observability.
 */
class ServeLaunchSpec extends ServeCommandSpecBase {

    def "a reachable tracker binding hands the assembled scheduler to the starter without claiming"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def starter = new CapturingStarter()
        def command = newCommand([github: fakeFactory(tracker)], starter)

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir")) }

        then: 'the command returns normally: the fake starter never drove a real feed cycle'
        noExceptionThrown()
        starter.captured != null

        and: 'no claim attempt was ever made — the scheduler was assembled but never actually run'
        0 * tracker.listReady(_)
        0 * tracker.claim(_, _)
    }

    // FR10, NFR-O2, M3 (task 5.4): --drain takes a wholly different path than the ordinary
    //     forever-loop starter — it drives FeedAutomaton#drain() directly and returns normally
    //     once the (here: empty) queue drains, exercising M3's "--drain on an empty queue exits 0
    //     with an empty-run report" without ever touching the CapturingStarter.
    def "--drain drives the drain path instead of starter.start, claiming nothing on an empty queue"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def starter = new CapturingStarter()
        def command = newCommand([github: fakeFactory(tracker)], starter)

        when:
        runsToCompletion {
            command.run(args('serve', "--dir=$projectDir", '--drain'))
        }

        then: 'the ordinary forever-loop starter was never invoked'
        noExceptionThrown()
        starter.captured == null

        and: 'ServeShutdownWiring.runDrain really ran automaton.drain(), which polled exactly once — ' +
        'through the very tracker the startup smoke test created (FR12)'
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []

        and: 'the drain path polled once, found nothing eligible, and claimed nothing'
        0 * tracker.claim(_, _)
    }

    def "--slots overrides ServeProperties#slots() without failing SlotLedger construction"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def starter = new CapturingStarter()
        def command = newCommand([github: fakeFactory(tracker)], starter)

        when:
        runsToCompletion {
            command.run(args('serve', "--dir=$projectDir", '--slots=3'))
        }

        then:
        noExceptionThrown()
        starter.captured != null
    }

    // FR2 of harden-logging-observability: the start anchor states the configuration the daemon
    // actually resolved — flags folded over properties folded over defaults — so a post-mortem
    // reads the effective settings instead of re-deriving them from three sources.
    def "the serve start anchor names the resolved configuration, including a --slots override"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def command = newCommand([github: fakeFactory(tracker)], new CapturingStarter())
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        runsToCompletion {
            command.run(args('serve', "--dir=$projectDir", '--slots=3'))
        }

        then: 'exactly one start anchor, at INFO'
        def starts = capture.list.findAll {
            it.formattedMessage.startsWith('serve started:')
        }
        starts.size() == 1
        starts[0].level == Level.INFO

        and: 'it carries the overridden slot count and every other configured value'
        String message = starts[0].formattedMessage
        message.contains("instance=$INSTANCE_NAME-")
        message.contains('slots=3')
        message.contains('wipLimit=')
        message.contains('idlePoll=PT30S')
        message.contains('sigtermGrace=PT30S')

        cleanup:
        capture.detach()
    }
}
