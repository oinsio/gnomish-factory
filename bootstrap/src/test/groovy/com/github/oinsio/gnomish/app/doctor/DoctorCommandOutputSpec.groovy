package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorCommandFixture.check
import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.TRACKER_TOKEN

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.app.DoctorFailedException
import com.github.oinsio.gnomish.app.RunExitCodeMapper
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.project.FactoryHome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, FR6, NFR-S1, UX2 of add-doctor-command (design D5): the command prints its report through
 * the console's human path, or under {@code --json} through the machine path only; a {@code fail}
 * result ends the run with exit code 1 once the report is out, anything else ends it normally;
 * a present secret's value appears in neither rendering.
 */
class DoctorCommandOutputSpec extends Specification {

    private static final String END = ConsoleIO.LINE_END
    /** The value of the planted tracker token; it must never reach the output. */
    private static final String SECRET_VALUE = 'ghp_doctorSecretValue0123456789'

    ConsoleIO console = Mock()

    @TempDir
    Path tmp

    // Scenario: cli/operator-doctor / Healthy setup
    def "UX2, FR6: a healthy run prints its ok lines and the summary through the human path, and ends normally"() {
        given:
        def command = DoctorCommandFixture.command([
            check(DoctorCheckId.DOCKER) {
                [
                    new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.OK, 'reachable', null)
                ]
            }
        ], console)

        when:
        command.run(new DefaultApplicationArguments('doctor'))

        then: 'exit code 0: nothing is thrown'
        noExceptionThrown()
        1 * console.print('ok  Docker runtime: reachable' + END + 'summary: 1 ok, 0 warn, 0 fail, 0 skipped; no check failed' + END)
        0 * console._
    }

    def "FR6: a warning alone fails nothing"() {
        when:
        DoctorCommandFixture.command([
            check(DoctorCheckId.GUARD_IMAGE) {
                [
                    new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.WARN, 'absent', 'docker pull g'),
                    new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.SKIPPED, 'no box', null),
                ]
            }
        ], console).run(new DefaultApplicationArguments('doctor'))

        then:
        noExceptionThrown()
        1 * console.print('warn  guard image: absent; fix: docker pull g' + END + 'skipped  guard image: no box' + END +
                'summary: 0 ok, 1 warn, 0 fail, 1 skipped; no check failed' + END)
    }

    // FR6: the report is out before the exit code is decided, in either rendering
    def "FR6: a fail result is reported in full and then ends the run with exit code 1 (#arguments)"() {
        given:
        def printed = []
        def machine = []
        console.print(_ as String) >> { String text -> printed << text }
        console.printMachine(_ as String) >> { String text -> machine << text }
        def command = DoctorCommandFixture.command([
            check(DoctorCheckId.GIT_FLOOR) {
                [
                    new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.FAIL, 'old', 'upgrade')
                ]
            },
            check(DoctorCheckId.HOOKS_PATH) {
                [
                    new DoctorVerdict(DoctorCheckId.HOOKS_PATH, DoctorStatus.FAIL, 'unread', 'look')
                ]
            },
        ], console)

        when:
        command.run(new DefaultApplicationArguments(arguments as String[]))

        then:
        def failed = thrown(DoctorFailedException)
        failed.message == 'doctor found 2 failing checks'
        new RunExitCodeMapper().getExitCode(failed) == 1

        and: 'the run printed exactly one rendering, holding both results'
        (printed + machine).size() == 1
        machine.size() == (json ? 1 : 0)
        (printed + machine)[0].contains('upgrade')
        (printed + machine)[0].contains('unread')

        where:
        arguments || json
        ['doctor'] || false
        ['doctor', '--json'] || true
    }

    def "FR5: --json prints one document through the machine path and nothing for a person"() {
        given:
        def command = DoctorCommandFixture.command([
            check(DoctorCheckId.DOCKER) {
                [
                    new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.OK, 'reachable', null)
                ]
            }
        ], console)
        String document = null

        when:
        command.run(new DefaultApplicationArguments('doctor', '--json'))

        then:
        1 * console.printMachine(_ as String) >> { String text ->
            document = text
        }
        0 * console._
        def tree = new ObjectMapper().readTree(document)
        tree.get('checks').size() == 1
        tree.get('checks').get(0).get('id').textValue() == 'docker'
        tree.get('checks').get(0).get('status').textValue() == 'ok'
    }

    // Scenario: cli/operator-doctor / Secret named, never shown
    def "NFR-S1: a present tracker token is reported found and where, and its value appears in neither rendering (#arguments)"() {
        given: 'the token in the host secrets folder, read by the real secrets adapter'
        def home = FactoryHome.at(tmp.resolve('home'))
        def file = home.hostSecret(TRACKER_TOKEN)
        Files.createDirectories(file.parent)
        Files.writeString(file, SECRET_VALUE + '\n')
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rw-------'))
        def secrets = new SecretsDoctorCheck(DoctorSpecSupport.readings(), new EnvFileSecretsProvider(home, null))
        def command = DoctorCommandFixture.command([
            check(DoctorCheckId.SECRETS) {
                secrets.run(DoctorSpecSupport.bound(false, true, DoctorSpecSupport.definition(true)))
            }
        ], console)
        def out = new StringBuilder()
        console.print(_ as String) >> { String text -> out << text }
        console.printMachine(_ as String) >> { String text -> out << text }

        when:
        command.run(new DefaultApplicationArguments(arguments as String[]))

        then:
        noExceptionThrown()
        out.toString().contains(TRACKER_TOKEN)
        out.toString().contains(file.toString())
        !out.toString().contains(SECRET_VALUE)

        where:
        arguments << [
            ['doctor'],
            ['doctor', '--json']
        ]
    }
}
