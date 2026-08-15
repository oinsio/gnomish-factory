package com.github.oinsio.gnomish

import java.time.Duration
import spock.lang.Specification

/**
 * ServeProperties: immutable typed configuration record for the {@code serve}
 * scheduler instance knobs (design D3, D10). Validation is plain Java in the
 * compact constructor, mirroring FactoryPropertiesSpec — no Spring context
 * needed here.
 *
 * Implements FR1, FR5, FR11, FR14 of add-factory-serve; FR1, FR15 of add-serve-observability.
 */
class ServePropertiesSpec extends Specification {

    // FR1/D3: slots defaults to 2 when unset
    def "slots defaults to 2 when unset (0)"() {
        when: 'a properties record is created without an explicit slots value'
        def properties = new ServeProperties(0, null, null, null, null, null)

        then: 'the accessor returns the design D3 default'
        properties.slots() == 2
    }

    // FR1/D3: an explicit slots value overrides the default
    def "slots of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit slots value'
        def properties = new ServeProperties(5, null, null, null, null, null)

        then: 'the accessor returns exactly the configured value'
        properties.slots() == 5
    }

    // FR1: a non-positive slots value is rejected
    def "slots of #value is rejected with the property name in the message"() {
        when: 'a properties record is created with a non-positive slots value'
        new ServeProperties(value, null, null, null, null, null)

        then: 'construction fails and the message names factory.serve.slots'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.serve.slots')

        where:
        value << [-1, -100]
    }

    // FR1: pins the exact slots<0 threshold — 1 (the smallest positive value, immediately above
    // the boundary) must be accepted unchanged, so a shifted boundary (< vs <=) that started
    // rejecting 0 (already impossible, caught earlier) or, symmetrically, stopped rejecting -1
    // would show up here paired with the existing -1/-100 rejection rows above.
    def "slots of 1, the smallest positive value immediately above the boundary, is accepted unchanged"() {
        when: 'a properties record is created with the smallest valid positive slots value'
        def properties = new ServeProperties(1, null, null, null, null, null)

        then: 'the accessor returns exactly the configured value'
        properties.slots() == 1
    }

    // FR5/D3: idle-poll-interval defaults to 30s when unset
    def "idle-poll-interval defaults to 30 seconds when unset"() {
        when: 'a properties record is created without an explicit idle-poll-interval'
        def properties = new ServeProperties(0, null, null, null, null, null)

        then: 'the accessor returns the design D3 default'
        properties.idlePollInterval() == Duration.ofSeconds(30)
    }

    // FR5/D3: an explicit idle-poll-interval overrides the default
    def "idle-poll-interval of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit idle-poll-interval'
        def properties = new ServeProperties(0, Duration.ofSeconds(45), null, null, null, null)

        then: 'the accessor returns exactly the configured value'
        properties.idlePollInterval() == Duration.ofSeconds(45)
    }

    // FR5: a non-positive idle-poll-interval is rejected
    def "idle-poll-interval of #value is rejected with the property name in the message"() {
        when: 'a properties record is created with a non-positive idle-poll-interval'
        new ServeProperties(0, value, null, null, null, null)

        then: 'construction fails and the message names factory.serve.idle-poll-interval'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.serve.idle-poll-interval')

        where:
        value << [
            Duration.ZERO,
            Duration.ofSeconds(-1)
        ]
    }

    // FR11/D3: sigterm-grace defaults to 30s when unset
    def "sigterm-grace defaults to 30 seconds when unset"() {
        when: 'a properties record is created without an explicit sigterm-grace'
        def properties = new ServeProperties(0, null, null, null, null, null)

        then: 'the accessor returns the design D3 default'
        properties.sigtermGrace() == Duration.ofSeconds(30)
    }

    // FR11/D3: an explicit sigterm-grace overrides the default
    def "sigterm-grace of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit sigterm-grace'
        def properties = new ServeProperties(0, null, Duration.ofSeconds(10), null, null, null)

        then: 'the accessor returns exactly the configured value'
        properties.sigtermGrace() == Duration.ofSeconds(10)
    }

    // FR11: a non-positive sigterm-grace is rejected
    def "sigterm-grace of #value is rejected with the property name in the message"() {
        when: 'a properties record is created with a non-positive sigterm-grace'
        new ServeProperties(0, null, value, null, null, null)

        then: 'construction fails and the message names factory.serve.sigterm-grace'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.serve.sigterm-grace')

        where:
        value << [
            Duration.ZERO,
            Duration.ofSeconds(-1)
        ]
    }

    // FR14/D10: worktree-age-threshold defaults to 14 days when unset
    def "worktree-age-threshold defaults to 14 days when unset"() {
        when: 'a properties record is created without an explicit worktree-age-threshold'
        def properties = new ServeProperties(0, null, null, null, null, null)

        then: 'the accessor returns the design D10 default'
        properties.worktreeAgeThreshold() == Duration.ofDays(14)
    }

    // FR14/D10: an explicit worktree-age-threshold overrides the default
    def "worktree-age-threshold of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit worktree-age-threshold'
        def properties = new ServeProperties(0, null, null, Duration.ofDays(7), null, null)

        then: 'the accessor returns exactly the configured value'
        properties.worktreeAgeThreshold() == Duration.ofDays(7)
    }

    // FR14: a non-positive worktree-age-threshold is rejected
    def "worktree-age-threshold of #value is rejected with the property name in the message"() {
        when: 'a properties record is created with a non-positive worktree-age-threshold'
        new ServeProperties(0, null, null, value, null, null)

        then: 'construction fails and the message names factory.serve.worktree-age-threshold'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.serve.worktree-age-threshold')

        where:
        value << [
            Duration.ZERO,
            Duration.ofSeconds(-1)
        ]
    }

    // FR1/D10: snapshot-interval defaults to 30 seconds when unset
    def "snapshot-interval defaults to 30 seconds when unset"() {
        when: 'a properties record is created without an explicit snapshot-interval'
        def properties = new ServeProperties(0, null, null, null, null, null)

        then: 'the accessor returns the design D10 default'
        properties.snapshotInterval() == Duration.ofSeconds(30)
    }

    // FR1/D10: an explicit snapshot-interval overrides the default
    def "snapshot-interval of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit snapshot-interval'
        def properties = new ServeProperties(0, null, null, null, Duration.ofSeconds(15), null)

        then: 'the accessor returns exactly the configured value'
        properties.snapshotInterval() == Duration.ofSeconds(15)
    }

    // FR1: a non-positive snapshot-interval is rejected
    def "snapshot-interval of #value is rejected with the property name in the message"() {
        when: 'a properties record is created with a non-positive snapshot-interval'
        new ServeProperties(0, null, null, null, value, null)

        then: 'construction fails and the message names factory.serve.snapshot-interval'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.serve.snapshot-interval')

        where:
        value << [
            Duration.ZERO,
            Duration.ofSeconds(-1)
        ]
    }

    // FR15/D10: ledger-retention-days defaults to 30 when unset
    def "ledger-retention-days defaults to 30 when unset"() {
        when: 'a properties record is created without an explicit ledger-retention-days'
        def properties = new ServeProperties(0, null, null, null, null, null)

        then: 'the accessor returns the design D10 default'
        properties.ledgerRetentionDays() == 30
    }

    // FR15/D10: an explicit ledger-retention-days overrides the default
    def "ledger-retention-days of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit ledger-retention-days'
        def properties = new ServeProperties(0, null, null, null, null, 7)

        then: 'the accessor returns exactly the configured value'
        properties.ledgerRetentionDays() == 7
    }

    // FR15/D10: 0 is a valid explicit value meaning "keep forever", not the unset sentinel
    def "ledger-retention-days of 0 is accepted unchanged and means keep forever"() {
        when: 'a properties record is created with an explicit ledger-retention-days of 0'
        def properties = new ServeProperties(0, null, null, null, null, 0)

        then: 'the accessor returns exactly 0, not the default'
        properties.ledgerRetentionDays() == 0
    }

    // FR15: a negative ledger-retention-days is rejected
    def "ledger-retention-days of #value is rejected with the property name in the message"() {
        when: 'a properties record is created with a negative ledger-retention-days'
        new ServeProperties(0, null, null, null, null, value)

        then: 'construction fails and the message names factory.serve.ledger-retention-days'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.serve.ledger-retention-days')

        where:
        value << [-1, -100]
    }

    // FR1/FR5/FR11/FR14/FR15: the properties type is an immutable record without setters
    def "the properties type is an immutable record without setter methods"() {
        given: 'the ServeProperties class'
        def type = ServeProperties

        expect: 'it is a Java record (final, all components final)'
        type.isRecord()

        and: 'no public method follows the mutable setter convention'
        type.methods.every {
            !(it.name.startsWith('set') && it.parameterCount> 0)
        }
    }
}
