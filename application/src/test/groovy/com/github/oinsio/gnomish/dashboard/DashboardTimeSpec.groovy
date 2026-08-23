package com.github.oinsio.gnomish.dashboard

import java.time.Instant
import spock.lang.Specification

/**
 * Verifies every server-written timestamp reaches the page as a {@code
 * <time>} element the client script can re-present without inventing a
 * value (task 2.3 of redesign-dashboard): full ISO in {@code datetime},
 * epoch millis in {@code data-epoch}, and a legible absolute instant as the
 * element's own text, which is what a reader with scripting disabled sees
 * and what the script moves into {@code title}.
 *
 * FR8, NFR-R2, UX2 of redesign-dashboard (design D4).
 */
class DashboardTimeSpec extends Specification {

    private static final Instant AT = Instant.parse('2026-08-06T09:00:00Z')

    def "a timestamp carries its ISO instant, its epoch millis, and its absolute text"() {
        given:
        def out = new StringBuilder()

        when:
        DashboardTime.append(out, AT, null)

        then:
        out.toString() == '<time datetime="2026-08-06T09:00:00Z" data-epoch="1786006800000">2026-08-06T09:00:00Z</time>'
    }

    def "sub-second precision is truncated so the absolute text stays scannable"() {
        given:
        def out = new StringBuilder()

        when:
        DashboardTime.append(out, Instant.parse('2026-08-06T09:00:00.123456Z'), null)

        then: 'the displayed instant and the datetime attribute agree, both to the second'
        out.toString().contains('datetime="2026-08-06T09:00:00Z"')
        out.toString().endsWith('>2026-08-06T09:00:00Z</time>')

        and: 'data-epoch keeps the untruncated instant the script measures against'
        out.toString().contains('data-epoch="1786006800123"')
    }

    def "a caller-supplied class lands on the element so numeric ages get the mono treatment"() {
        given:
        def out = new StringBuilder()

        when:
        DashboardTime.append(out, AT, 'row__age num')

        then:
        out.toString().startsWith('<time class="row__age num" datetime=')
    }

    // Every caller passes a literal today, but the parameter is still an attribute
    // sink — escaping keeps it from ever becoming a way out of the attribute.
    def "a class value is escaped like every other attribute"() {
        given:
        def out = new StringBuilder()

        when:
        DashboardTime.append(out, AT, 'a"b')

        then:
        out.toString().startsWith('<time class="a&quot;b" datetime=')
    }

    // NFR-R2: nothing here may be left for the script to compute — the absolute value is complete.
    def "the absolute text alone identifies the instant with no script involved"() {
        given:
        def out = new StringBuilder()

        when:
        DashboardTime.append(out, AT, null)

        then:
        Instant.parse(out.toString().replaceAll('(?s).*>([^<]+)</time>', '$1')) == AT
    }
}
