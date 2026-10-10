package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR18 and design D22 of supervise-daemon-loops-and-embed-dashboard: the time-built slot policies
 * have one producer each — the terminal-write retry, the outcome dispatch and the abort handler are
 * derived from the slot's own time equipment (task 3.9), so a second construction site is a second
 * time source for one slot. Constructions are found by a detector, not a substring: a spaced call
 * and a constructor reference construct too. Scope and exclusions are {@link
 * TimeSourceOwnerBoundarySpec}'s.
 */
class SlotPolicyProducerBoundarySpec extends Specification {

    /** One-producer constructions (D22): type → the one production file allowed to construct it. */
    private static final Map<String, String> ONE_PRODUCER = [
        // A second producer of the time-built retry is the defect task 3.6 removed; task 3.9 moved
        // the one producer into the wiring, which derives it from the slot's own time equipment.
        'TerminalWriteRetry': 'application/src/main/java/com/github/oinsio/gnomish/app/SlotWiring.java',
        // Task 3.9: the slot's abort handler is built over its assembly's clock in exactly one place.
        'AbortHandler': 'application/src/main/java/com/github/oinsio/gnomish/app/SlotWiringFactory.java',
        'TakeOutcomeDispatch': 'application/src/main/java/com/github/oinsio/gnomish/app/SlotWiring.java',
    ]

    /** Whether {@code code} constructs {@code type}: a call, spaced or not, or a constructor reference. */
    static boolean constructs(String type, String code) {
        def name = Pattern.quote(type)
        code =~ /\bnew\s+${name}\s*\(|\b${name}\s*::\s*new\b/
    }

    // FR18, D22: a second construction site of a root-built policy is a second owner.
    def "FR18, D22: #literal is constructed only in its one producer"() {
        given:
        def sources = TimeSourceOwnerBoundarySpec.factorySources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def producers = sources.findAll {
            constructs(literal, RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }

        then: 'nothing else constructs it, and the allowlisted producer still does'
        producers == [ONE_PRODUCER[literal]]

        where:
        literal << ONE_PRODUCER.keySet().toList()
    }

    // A second producer spelled as a constructor reference or with a space is still a construction.
    def "FR18, D22: the producer detector finds #shape and leaves the bare name alone"() {
        expect:
        constructs('TerminalWriteRetry', source) == found

        where:
        shape | source || found
        'a constructor call' | 'var r = new TerminalWriteRetry(time);' || true
        'a spaced call' | 'var r = new TerminalWriteRetry (time);' || true
        'a constructor reference' | 'Function<T, R> f = TerminalWriteRetry::new;' || true
        'a type use' | 'TerminalWriteRetry retry = wiring.retry();' || false
    }
}
