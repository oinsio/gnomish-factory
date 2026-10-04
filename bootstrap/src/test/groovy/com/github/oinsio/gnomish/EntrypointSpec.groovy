package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.FactoryVersion
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import spock.lang.Specification

/**
 * FR6 of add-release-pipeline (design D3): the entry point's one decision — answer a sole
 * {@code --version} and stop, or boot the application — in process, inside the mutation gate.
 * {@code FactoryApplicationSpec} drives the same branch through the packaged jar, but it is out of
 * PIT's reach; this spec is what kills an inverted or dropped branch.
 */
class EntrypointSpec extends Specification {

    def "FR6: a sole --version is answered and the application is not booted"() {
        given:
        def console = new ScriptedConsoleIO()
        int boots = 0

        when:
        Entrypoint.start(['--version'] as String[], console) { boots++ }

        then:
        boots == 0
        console.printed == [
            FactoryVersion.current().value() + '\n'
        ]
    }

    def "FR6: command line #tokens boots the application once and prints nothing"() {
        given:
        def console = new ScriptedConsoleIO()
        int boots = 0

        when:
        Entrypoint.start(tokens as String[], console) { boots++ }

        then:
        boots == 1
        console.printed.isEmpty()

        where:
        tokens << [
            [],
            ['run'],
            ['run', '--version'],
        ]
    }
}
