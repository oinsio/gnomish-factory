package com.github.oinsio.gnomish.sandbox

import com.github.oinsio.gnomish.domain.engine.DenialIdentity
import spock.lang.Specification

/**
 * The port's denial surface as an environment without a denial source sees it
 * (FR3, FR6, FR7 of fix-denial-attribution-durability): the three defaults answer
 * truthfully rather than failing, and the two value types carry the empty and
 * position-only shapes a branch offers.
 */
class DenialSurfaceSpec extends Specification {

    /** A guardless environment: every denial method is left at the port's default. */
    static class GuardlessEnvironment implements TaskExecutionEnvironment {

        @Override
        void materialize(String branch, String commitPin) {
            throw new UnsupportedOperationException()
        }

        @Override
        ExecHandle exec(ExecCommand command) {
            throw new UnsupportedOperationException()
        }

        @Override
        void putFile(String path, byte[] content) {
            throw new UnsupportedOperationException()
        }

        @Override
        Optional<byte[]> readFile(String path, long sizeCap) {
            throw new UnsupportedOperationException()
        }

        @Override
        void harvest() {
            throw new UnsupportedOperationException()
        }

        @Override
        void dispose() {
            throw new UnsupportedOperationException()
        }

        @Override
        String scratchRoot() {
            throw new UnsupportedOperationException()
        }

        @Override
        CapabilityPassport passport() {
            throw new UnsupportedOperationException()
        }
    }

    def "FR3: an environment with no denial source reads nothing and offers no position"() {
        given:
        def environment = new GuardlessEnvironment()

        when:
        def read = environment.readDenials()

        then:
        read == DenialRead.none()
        read.denials().isEmpty()
        read.positionAfter().isEmpty()
    }

    def "FR3: its current position is empty, and asking does not consume anything"() {
        given:
        def environment = new GuardlessEnvironment()

        expect:
        environment.denialCursor().isEmpty()
        environment.readDenials().denials().isEmpty()
    }

    def "FR7: a restoration offered to it is ignored rather than refused"() {
        given:
        def environment = new GuardlessEnvironment()
        def restoration = new DenialRestoration(
                Optional.of(new DenialCursor('guard-1', '2026-08-19T10:00:00.000000001Z')),
                Set.of(new DenialIdentity('guard-1', '2026-08-19T10:00:00.000000001Z')))

        when:
        environment.restoreDenials(restoration)

        then:
        noExceptionThrown()
        environment.denialCursor().isEmpty()
    }

    def "FR3: the empty read carries no denials and no position"() {
        expect:
        DenialRead.none().denials() == []
        DenialRead.none().positionAfter() == Optional.empty()
    }

    def "FR7: nothing recorded means no position and no identities to merge away"() {
        expect:
        DenialRestoration.none().position() == Optional.empty()
        DenialRestoration.none().recorded() == [] as Set
    }

    def "FR7: a branch written before identities existed offers its position alone"() {
        given:
        def cursor = new DenialCursor('guard-1', '2026-08-19T10:00:00.000000001Z')

        when:
        def restoration = DenialRestoration.at(cursor)

        then:
        restoration.position() == Optional.of(cursor)
        restoration.recorded() == [] as Set
    }
}
