package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR2, FR7 of add-doctor-command: {@code gnomish doctor [--dir] [--online] [--json]}. The
 * unknown-option contract and the registered clone's path as {@code dir} are pinned with every other
 * subcommand in {@link CliArgumentsContractSpec}; this spec pins what is doctor's own: the two
 * switches, and a directory no project registers leaving {@code dir} absent rather than refused.
 */
class DoctorArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    private static final Path CLONE_PATH = Path.of('/tmp/registered-clone')

    // FR1, FR3, FR5: --online and --json are switches, each read on its own
    def "FR1: doctor reads #argv as online=#online, json=#json"() {
        given:
        def clone = RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), CLONE_PATH)

        when:
        def parsed = new DoctorArgumentsParser().parse(args(argv as String[]), clone)

        then:
        parsed == new DoctorArguments(CLONE_PATH, online, json)

        where:
        argv || online | json
        ['doctor'] || false | false
        ['doctor', '--online'] || true | false
        ['doctor', '--json'] || false | true
        [
            'doctor',
            '--json',
            '--online'
        ] || true | true
    }

    // FR2: outside any registered clone doctor still parses, with no directory to work in
    def "FR2: doctor without a registered clone has no dir"() {
        expect:
        new DoctorArgumentsParser().parse(args('doctor', '--dir=/tmp/elsewhere'), null) ==
                new DoctorArguments(null, false, false)
    }

    // FR7 of fix-operator-blockers through SwitchFlag: a switch given a value is a usage error
    def "FR1: --#name given a value is refused"() {
        when:
        new DoctorArgumentsParser().parse(args('doctor', "--${name}=false".toString()), null)

        then:
        def e = thrown(UsageException)
        e.message.startsWith("--${name} is a switch and takes no value")

        where:
        name << ['online', 'json']
    }
}
