package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.app.sandboxlifecycle.SkippedVerdictSource
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.SweepVital
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import spock.lang.Specification

/**
 * The snapshot's {@code vitals.sweep.lastSkipSource} token through the real writer and reader
 * (D17, F90 of tighten-spec-contract): every {@link SkippedVerdictSource} constant round-trips,
 * no source travels as null, a document written before the field reads as no source (an additive
 * field under ADR 0016), and an unknown token is refused like every other snapshot token.
 *
 * NFR-O3 of add-serve-sandbox-lifecycle.
 */
class SnapshotSweepSourceWireSpec extends Specification {

    def mapper = new SnapshotJsonMapper()
    def reader = new SnapshotJsonReader()

    // F90: wire vocabulary round-trip over every constant (testing.md), plus null.
    def "every skipped-verdict source round-trips through the snapshot"() {
        expect:
        reader.read(mapper.serialize(withSource(source))).vitals().sweep().lastSkipSource() == source

        where:
        source << (SkippedVerdictSource.values() as List) + [null]
    }

    // F90: the tokens are the sandbox-lifecycle capability's words, lower case.
    def "each source is written as its own word"() {
        expect:
        mapper.toDto(withSource(source)).vitals().sweep().lastSkipSource() == token

        where:
        source | token
        SkippedVerdictSource.TRACKER | 'tracker'
        SkippedVerdictSource.RUNTIME | 'runtime'
        null | null
    }

    // ADR 0016: the field is additive — a document written before it still parses, with no source.
    def "a document without the field reads as no source"() {
        given:
        def json = mapper.serialize(withSource(SkippedVerdictSource.TRACKER))
        def older = json.replaceFirst(/,\s*"lastSkipSource" : "tracker"/, '')

        expect:
        older != json
        reader.read(older).vitals().sweep().lastSkipSource() == null
        reader.read(older).vitals().sweep().consecutiveSkippedTicks() == 1
    }

    // The reader's convention for every snapshot token: an unknown value fails loudly.
    def "an unknown source token is rejected"() {
        given:
        def json = mapper.serialize(withSource(SkippedVerdictSource.RUNTIME))
                .replace('"lastSkipSource" : "runtime"', '"lastSkipSource" : "network"')

        when:
        reader.read(json)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'unknown vitals.sweep.lastSkipSource: network'
    }

    private static Snapshot withSource(SkippedVerdictSource source) {
        def s = SnapshotJsonMapperSpec.referenceSnapshot()
        def v = s.vitals()
        def w = v.sweep()
        def sweep = new SweepVital(w.lastTickAt(), w.intervalSeconds(), w.counts(), w.kept(), w.keptTotal(),
                w.consecutiveSkippedTicks(), source)
        return new Snapshot(s.version(), s.writtenAt(), s.intervalSeconds(), s.instance(), s.lifecycle(),
                s.feed(), s.slots(), new VitalsSnapshot(v.heartbeat(), v.reaper(), v.janitor(), sweep),
                s.tracker(), s.remote())
    }
}
