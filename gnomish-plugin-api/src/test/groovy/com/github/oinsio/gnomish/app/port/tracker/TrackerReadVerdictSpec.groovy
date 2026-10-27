package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * TrackerReadVerdict (FR3 of add-doctor-command, design D7): the read probe's verdict is inert
 * value data — compared by content, and holding its own copy of the names startup will create.
 *
 * Implements FR3 of add-doctor-command.
 */
class TrackerReadVerdictSpec extends Specification {

    def "FR3: a readable verdict holds its own copy of the names startup will create"() {
        given:
        def names = ['gnomish:ready']
        def verdict = new TrackerReadVerdict.ReadConfirmed(names)

        when:
        names << 'gnomish:working'

        then:
        verdict.toProvision() == ['gnomish:ready']
    }

    def "FR3: verdicts compare by content"() {
        expect:
        new TrackerReadVerdict.NotApplicable() == new TrackerReadVerdict.NotApplicable()
        new TrackerReadVerdict.ReadConfirmed(['a']) == new TrackerReadVerdict.ReadConfirmed(['a'])
        new TrackerReadVerdict.Refused(403, 'cause', Optional.of(UntrustedText.tracker('issues=write'))) ==
                new TrackerReadVerdict.Refused(403, 'cause', Optional.of(UntrustedText.tracker('issues=write')))
        new TrackerReadVerdict.Malformed('cause') != new TrackerReadVerdict.Malformed('other')
    }
}
