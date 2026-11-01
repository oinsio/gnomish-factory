package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR13 of define-executor-contract (design D11): the recorded describe version of an executor is
 * compared with the version a later run's describe preflight obtained; a difference is a mismatch
 * naming both, an executor without a recorded version is in its first use.
 */
class ExecutorVersionCheckSpec extends Specification {

    static ExecutorVersions recorded(Map<String, String> versions) {
        new ExecutorVersions(versions.collectEntries { k, v ->
            [
                k,
                UntrustedText.branchDocument(v)
            ]
        })
    }

    static ExecutorVersions described(Map<String, String> versions) {
        new ExecutorVersions(versions.collectEntries { k, v ->
            [
                k,
                UntrustedText.subprocess(v)
            ]
        })
    }

    // FR13: a version recorded as 1 and described as 2 is a mismatch naming both
    def "a different current version is a pipeline mismatch naming the executor and both versions"() {
        when:
        def mismatch = ExecutorVersionCheck.compare(recorded(acme: '1'), described(acme: '2'))

        then:
        def cause = (MismatchCause.ExecutorVersion) mismatch.get().cause()
        cause.executor().raw() == 'acme'
        cause.recorded().raw() == '1'
        cause.current().raw() == '2'
    }

    // FR13: provenance never decides — the same text read off the branch and off describe agrees
    def "the same version from a different provenance agrees"() {
        expect:
        ExecutorVersionCheck.compare(recorded(acme: '1.4.0'), described(acme: '1.4.0')).isEmpty()
    }

    // FR13: absent versions are not compared on either side
    def "#row is not compared"() {
        expect:
        ExecutorVersionCheck.compare(recorded(was), described(now)).isEmpty()

        where:
        row | was | now
        'an executor in its first use' | [:] | [acme: '2']
        'an executor this run did not describe' | [acme: '1'] | [:]
        'a different executor' | [other: '1'] | [acme: '2']
        'nothing at all' | [:] | [:]
    }

    // FR13: several differing executors report the first by name, deterministically
    def "the first differing executor by name is reported"() {
        when:
        def mismatch = ExecutorVersionCheck.compare(
                recorded(zeta: '1', beta: '1', alpha: '1'), described(zeta: '2', beta: '2', alpha: '1'))

        then:
        ((MismatchCause.ExecutorVersion) mismatch.get().cause()).executor().raw() == 'beta'
    }

    def "versions are keyed by name and absent for an undescribed executor"() {
        given:
        def versions = described(acme: '3')

        expect:
        versions.of('acme').get().raw() == '3'
        versions.of('other').isEmpty()
        ExecutorVersions.none().byName().isEmpty()
    }

    def "the map is copied and unmodifiable"() {
        given:
        def source = [acme: UntrustedText.subprocess('1')]
        def versions = new ExecutorVersions(source)

        when:
        source.put('other', UntrustedText.subprocess('2'))

        then:
        versions.byName().keySet() == ['acme'] as Set

        when:
        versions.byName().put('x', UntrustedText.subprocess('1'))

        then:
        thrown(UnsupportedOperationException)
    }

    def "a blank #what is refused"() {
        when:
        new ExecutorVersions([(name): UntrustedText.subprocess(version)])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('blank executor name or version')

        where:
        what | name | version
        'name' | ' ' | '1'
        'version' | 'acme' | ' '
    }

    // FR13, D11: first version wins — a pinned executor keeps its version, a new one is pinned
    def "the pin keeps the first version and adds executors new to the task"() {
        given:
        def pin = recorded(acme: '1')

        when:
        def after = pin.pinFirstUse(described(acme: '2', other: '7'))

        then:
        after.of('acme').get().raw() == '1'
        after.of('other').get().raw() == '7'
    }

    def "a round that adds nothing returns the same pin"() {
        given:
        def pin = recorded(acme: '1')

        expect:
        pin.pinFirstUse(described(acme: '2')).is(pin)
        pin.pinFirstUse(ExecutorVersions.none()).is(pin)
    }
}
