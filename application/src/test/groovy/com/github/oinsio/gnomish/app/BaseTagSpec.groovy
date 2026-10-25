package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR9 of add-sandbox-base-image (design D11): a base tag is {@code X.Y} or a full pre-release
 * version {@code X.Y.Z-<pre>}, and no other shape is constructible.
 */
class BaseTagSpec extends Specification {

    def "FR9: the shape '#value' is a base tag that reads as itself"() {
        expect:
        new BaseTag(value).toString() == value

        where:
        value << [
            '0.1',
            '10.20',
            '0.4.1-rc.1',
            '1.0.0-beta.2',
            '1.0.0-rc.1-2'
        ]
    }

    def "FR9: the shape '#value' is refused"() {
        when:
        new BaseTag(value)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "base tag '${value}' is neither X.Y nor a pre-release version X.Y.Z-<pre>"

        where:
        value << [
            '',
            '1',
            '1.2.3',
            '01.2',
            '1.02',
            'v1.2',
            '1.2-rc.1',
            '1.2.3-',
            '1.2.03-rc.1',
            '1.2.3+b',
            ' 1.2',
            '1.2\n'
        ]
    }
}
