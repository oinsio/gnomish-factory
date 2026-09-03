package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * ClaimEpochTrailer: the claim epoch as a commit-message trailer — the branch's half of the
 * epoch fence (FR13 of harden-task-branch-contract, design D6).
 */
class ClaimEpochTrailerSpec extends Specification {

    // FR13: a stamped commit says which tenure wrote it
    def "stamps the epoch as a trailer below the service subject"() {
        expect:
        ClaimEpochTrailer.stamp('gnomish: round build#1', new ClaimEpoch(4711)) ==
                'gnomish: round build#1\n\nGnomish-Claim-Epoch: 4711'
    }

    // FR13: the subject stays verbatim — SnapshotTipCheck and the cleanup search match it exactly
    def "leaves the subject line untouched"() {
        when:
        def stamped = ClaimEpochTrailer.stamp(ServiceCommitMessages.cleanup(), new ClaimEpoch(9))

        then:
        stamped.readLines().first() == ServiceCommitMessages.cleanup()
    }

    // FR13: a writer holding no claim writes the message it always wrote
    def "stamps nothing when there is no tenure"() {
        expect:
        ClaimEpochTrailer.stamp('gnomish: salvage', null) == 'gnomish: salvage'
    }

    // FR13: what one tenure stamps, the next pickup reads back
    def "reads back the epoch it stamped"() {
        expect:
        ClaimEpochTrailer.parse(ClaimEpochTrailer.stamp('gnomish: task started', new ClaimEpoch(epoch)))
                .orElse(null) == new ClaimEpoch(epoch)

        where:
        epoch << [0L, 1L, 4711L, Long.MAX_VALUE]
    }

    // FR13: a pre-contract tip carries no trailer, and that is legal — not stale, not corrupt
    def "answers empty for a message carrying no trailer"() {
        expect:
        ClaimEpochTrailer.parse('gnomish: round build#1').isEmpty()
    }

    // NFR-R2: content never throws — an unreadable trailer value leaves the tip outside the fence
    def "answers empty rather than throwing for an unreadable trailer value"() {
        expect:
        ClaimEpochTrailer.parse('gnomish: round build#1\n\nGnomish-Claim-Epoch: ' + value).isEmpty()

        where:
        value << [
            '',
            'not-a-number',
            '-1',
            '9999999999999999999999',
            '1 2'
        ]
    }

    // FR5 of harden-logging-observability: a trailer the factory itself wrote that does not read
    // back as an epoch leaves the tip fencing against nothing — indistinguishable, without a line,
    // from a tip that was never stamped. DEBUG: the read still answers correctly, nothing is lost.
    def "FR5: a trailer value that is not an epoch leaves a DEBUG trace naming it (#value)"() {
        given:
        def logs = LogCaptureSupport.attach(ClaimEpochTrailer, Level.DEBUG)

        when:
        def parsed = ClaimEpochTrailer.parse('gnomish: round build#1\n\nGnomish-Claim-Epoch: ' + value)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        parsed.isEmpty()

        and:
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('claim-epoch trailer')
        events[0].formattedMessage.contains(value as String)

        where:
        value << [
            'not-a-number',
            '-1',
            '9999999999999999999999',
            '1 2'
        ]
    }

    // FR13: git's own "last trailer wins" reading, so an amended message resolves the same way
    def "answers the last readable trailer when a message carries several"() {
        expect:
        ClaimEpochTrailer.parse('subject\n\nGnomish-Claim-Epoch: 1\nGnomish-Claim-Epoch: 2')
                .orElse(null) == new ClaimEpoch(2)
    }

    // FR13: a trailer git wrote with trailing whitespace still reads back
    def "tolerates surrounding whitespace on the trailer line"() {
        expect:
        ClaimEpochTrailer.parse('subject\n\n  Gnomish-Claim-Epoch: 77  \n').orElse(null) == new ClaimEpoch(77)
    }
}
