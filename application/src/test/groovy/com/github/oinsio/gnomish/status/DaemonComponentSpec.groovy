package com.github.oinsio.gnomish.status

import org.slf4j.MDC
import spock.lang.Specification

/**
 * {@link DaemonComponent}: a serve run's five long-lived workers name themselves in every line
 * they emit (FR8, design D10 of harden-logging-observability).
 *
 * <p>This spec owns the vocabulary and the framing itself: the key appears inside the frame and
 * nothing is left behind for the next body. That a worker which really starts a thread really
 * runs inside the frame is the wiring claim, asserted against a genuine daemon by
 * {@code SnapshotWriterComponentMdcSpec} — the snapshot writer is the one whose thread a spec can
 * start and stop on demand.
 *
 * <p>Implements FR8 of harden-logging-observability.
 */
class DaemonComponentSpec extends Specification {

    def cleanup() {
        MDC.clear()
    }

    // FR8: the vocabulary is closed and matches the workers `.claude/rules/logging.md` names
    def "the component vocabulary is exactly the five daemon workers"() {
        expect:
        DaemonComponent.values()*.key() as Set == [
            'janitor',
            'reaper',
            'snapshot',
            'sweep',
            'heartbeat'
        ] as Set
    }

    // FR8: inside the frame the key is set; outside it, nothing is left behind for the next body
    def "#component frames a loop with its own key and clears the context afterwards"() {
        given:
        String observed = null

        when:
        component.framing({ observed = MDC.get('component') }).run()

        then:
        observed == component.key()

        and: 'the frame leaves no context behind'
        MDC.get('component') == null

        where:
        component << DaemonComponent.values()
    }
}
