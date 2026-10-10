package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR8 of supervise-daemon-loops-and-embed-dashboard: {@link SwitchFlag} is the one reading of a
 * switch — present means on, absent means off, and a value ({@code --dashboard=false}) is a usage
 * error rather than the silent "on" Spring's {@code containsOption} would answer.
 */
class SwitchFlagSpec extends Specification implements ApplicationArgumentsFixture {

    def "FR8: an absent switch is off"() {
        expect:
        !SwitchFlag.isOn(args('serve', '--other'), 'dashboard')
    }

    def "FR8: a bare switch is on"() {
        expect:
        SwitchFlag.isOn(args('serve', '--dashboard'), 'dashboard')
    }

    def "FR8: a switch given #raw is a usage error naming the switch"() {
        when:
        SwitchFlag.isOn(args('serve', raw), 'dashboard')

        then:
        UsageException ex = thrown()
        ex.message.contains('--dashboard is a switch and takes no value')

        where:
        raw << [
            '--dashboard=false',
            '--dashboard=true',
            '--dashboard='
        ]
    }
}
