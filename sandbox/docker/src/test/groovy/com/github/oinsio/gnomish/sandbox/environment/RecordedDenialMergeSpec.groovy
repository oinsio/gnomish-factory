package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.DenialIdentity
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * FR7, NFR-R3 of fix-denial-attribution-durability: attaching denials to a record is idempotent.
 * A read merged against what the branch tip already records attaches only the events not yet
 * recorded, so the FR4 fallback — an unreadable, absent, or foreign position sending the read back
 * over the whole log tail — costs a merge instead of a doubled report.
 */
class RecordedDenialMergeSpec extends Specification {

    // An environment key (see the glossary), not a credential.
    private static final String KEY = 'gnomish-PROJ-9' // gitleaks:allow

    private static Denial denial(String host, String at = null) {
        def finding = new Finding("egress denied: ${host}:443", host, 'kind=connect')
        at == null ? Denial.unidentified(finding) : new Denial(finding, new DenialIdentity('src-1', at))
    }

    def "FR7: nothing recorded means nothing merged away"() {
        given:
        def merge = new RecordedDenialMerge(KEY)
        def read = [
            denial('a.example.com', '2026-08-19T10:00:00Z')
        ]

        expect:
        merge.merge(read) == read
        !merge.recordsAny()
    }

    def "FR7: a denial already recorded at the tip is not attached again"() {
        given:
        def merge = new RecordedDenialMerge(KEY)
        merge.restore([
            new DenialIdentity('src-1', '2026-08-19T10:00:00Z')
        ] as Set)

        when:
        def merged = merge.merge([
            denial('a.example.com', '2026-08-19T10:00:00Z'),
            denial('b.example.com', '2026-08-19T10:05:00Z')
        ])

        then: 'exactly the unrecorded tail is recovered, in read order'
        merged*.finding()*.message() == [
            'egress denied: b.example.com:443'
        ]
        merge.recordsAny()
    }

    // FR7: an unidentified denial matches nothing, so the merge keeps it — design D3 prefers a
    //     duplicate a reviewer can see over an event nobody ever hears about
    def "FR7: a denial with no identity is kept, whatever the tip records"() {
        given:
        def merge = new RecordedDenialMerge(KEY)
        merge.restore([
            new DenialIdentity('src-1', '2026-08-19T10:00:00Z')
        ] as Set)

        expect:
        merge.merge([denial('a.example.com')])*.finding()*.message() == [
            'egress denied: a.example.com:443'
        ]
    }

    def "FR7: merging the same read twice attaches nothing the second time"() {
        given:
        def merge = new RecordedDenialMerge(KEY)
        def read = [
            denial('a.example.com', '2026-08-19T10:00:00Z')
        ]
        merge.restore(read*.identity() as Set)

        expect: 'idempotent by construction: re-attaching what is recorded is a no-op'
        merge.merge(read) == []
        merge.merge(read) == []
    }

    // FR7: the merge is reported so duplicates — and their absence — are explainable
    def "FR7: a merge that dropped something says how many were present and how many recovered"() {
        given:
        def logs = LogCaptureSupport.attach(RecordedDenialMerge)
        def merge = new RecordedDenialMerge(KEY)
        merge.restore([
            new DenialIdentity('src-1', '2026-08-19T10:00:00Z'),
            new DenialIdentity('src-1', '2026-08-19T10:01:00Z')
        ] as Set)

        when:
        merge.merge([
            denial('a.example.com', '2026-08-19T10:00:00Z'),
            denial('b.example.com', '2026-08-19T10:01:00Z'),
            denial('c.example.com', '2026-08-19T10:05:00Z')
        ])

        then:
        logs.list.any {
            it.level == Level.INFO && it.formattedMessage.contains('2 already present, 1 recovered')
        }
        logs.list.any { it.formattedMessage.contains(KEY) }

        cleanup:
        logs.detach()
    }

    def "FR7: a merge that dropped nothing says nothing"() {
        given:
        def logs = LogCaptureSupport.attach(RecordedDenialMerge)
        def merge = new RecordedDenialMerge(KEY)
        merge.restore([
            new DenialIdentity('src-1', '2026-08-19T10:00:00Z')
        ] as Set)

        when:
        merge.merge([
            denial('c.example.com', '2026-08-19T10:05:00Z')
        ])

        then:
        logs.list.isEmpty()

        cleanup:
        logs.detach()
    }
}
