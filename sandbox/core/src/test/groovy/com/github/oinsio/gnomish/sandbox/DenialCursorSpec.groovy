package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * FR5 of fix-denial-report-attachment: the durable denial read position and the
 * identity of the source it was read from. A blank component is refused —
 * matching a cursor against a live source is the whole safety mechanism, and a
 * cursor that names nothing would silently degrade into "read from the start".
 */
class DenialCursorSpec extends Specification {

    def "FR5: a cursor carries the position and the source it was read from"() {
        given:
        def cursor = new DenialCursor('sha256:guard-container', '2026-08-19T10:00:00.000000001Z')

        expect:
        cursor.source() == 'sha256:guard-container'
        cursor.position() == '2026-08-19T10:00:00.000000001Z'
    }

    def "FR5: a blank #component is refused"() {
        when:
        new DenialCursor(source, position)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "DenialCursor.${component} must not be blank"

        where:
        component | source | position
        'source' | '  ' | '2026-08-19T10:00:00Z'
        'position' | 'guard-1' | ''
    }
}
