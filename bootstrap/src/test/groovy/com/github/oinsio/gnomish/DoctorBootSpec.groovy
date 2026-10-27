package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.adapter.git.HooksPathLeftover
import com.github.oinsio.gnomish.adapter.git.HooksPathVerdict
import com.github.oinsio.gnomish.app.DoctorFailedException
import com.github.oinsio.gnomish.app.OperatorHomeFixture
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.RunExitCodeMapper
import com.github.oinsio.gnomish.app.SecretLocator
import com.github.oinsio.gnomish.app.ServePreconditions
import com.github.oinsio.gnomish.app.doctor.DoctorCheckId
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR7 of add-doctor-command (design D2, D3): {@code gnomish doctor} booted the way the
 * packaged jar boots it — the configuration loader in collect mode, every doctor bean from the real
 * context — in a directory no project registers. The host-level checks run, every project check is
 * skipped with the directory's own answer, the registration line carrying the registry's {@code
 * project add} line, and a configuration with a violation still boots and lists it. Outside a
 * project no stage is bound, so nothing here reaches Docker or a launch helper.
 */
class DoctorBootSpec extends Specification {

    @TempDir
    Path tmp

    OperatorHomeFixture operatorHome
    PrintStream originalOut
    ByteArrayOutputStream out

    def setup() {
        operatorHome = OperatorHomeFixture.install(tmp.resolve('home'))
        originalOut = System.out
        out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
    }

    def cleanup() {
        System.out = originalOut
        operatorHome.close()
    }

    // Scenario: cli/operator-doctor / Outside any project
    def "FR2: outside any project the host checks run and the project checks are skipped, with the project add line"() {
        when:
        FactoryBoot.boot([:], 'doctor').close()

        then: 'every check has its line, but the online probe, which --online adds (FR3)'
        def lines = out.toString('UTF-8').readLines()
        !lines.any {
            it.contains("  ${DoctorCheckId.TRACKER_PROBE.displayName()}: ")
        }
        DoctorCheckId.values().findAll { !it.online() }.every { id ->
            lines.any {
                it.contains("  ${id.displayName()}: ")
            }
        }

        and: 'the host-level checks ran'
        lines.any { it.startsWith('ok  factory version: gnomish ') }
        lines.any {
            it.contains('  git version floor: git ') && !it.startsWith('skipped')
        }
        lines.contains('ok  operator configuration: no violation')
        lines.contains("ok  pre-registry layout: no leftover in ${operatorHome.home.root()}".toString())

        and: 'the registration line is skipped, naming the directory and the project add line'
        def cwd = Path.of('').toAbsolutePath().normalize()
        lines.any {
            it.startsWith("skipped  project registration: ${cwd} is not a registered clone; register it with: gnomish project add ")
        }

        and: 'every project check is skipped because the directory is not registered'
        [
            DoctorCheckId.REF_FORMAT,
            DoctorCheckId.PIPELINE,
            DoctorCheckId.SECRETS,
            DoctorCheckId.AGENT_CREDENTIAL,
            DoctorCheckId.DOCKER,
            DoctorCheckId.SANDBOX_IMAGE,
            DoctorCheckId.GUARD_IMAGE,
            DoctorCheckId.AGENT_CLI,
            DoctorCheckId.LAUNCHER_HELPER,
            DoctorCheckId.HOOKS_PATH,
        ].every { id ->
            lines.contains("skipped  ${id.displayName()}: ${cwd} is not a registered clone".toString())
        }
    }

    // Scenario: operator/operator-configuration / Doctor lists violations instead of stopping
    def "FR7: a configuration with a violation still boots doctor, which lists the violation among its lines"() {
        given:
        operatorHome.hostConfig('factory:\n  git-netwrok-timeout: 5m\n')

        when:
        FactoryBoot.boot([:], 'doctor').close()

        then: 'the run reports every check, then ends with the failed-checks carrier: exit code 1 (FR6)'
        def failed = thrown(DoctorFailedException)
        new RunExitCodeMapper().getExitCode(failed) == 1

        and:
        def lines = out.toString('UTF-8').readLines()
        lines.any {
            it.startsWith('fail  operator configuration: factory.git-netwrok-timeout is not a known key')
        }
        lines.any { it.contains('  pre-registry layout: ') }
        lines.last().startsWith('summary: ')
    }

    // Scenario: cli/operator-doctor / Absent guard image and old layout leftovers only warn
    def "FR1: a leftover of the pre-registry layout only warns"() {
        given:
        Files.createDirectories(operatorHome.home.root().resolve('serve'))

        when:
        FactoryBoot.boot([:], 'doctor').close()

        then:
        out.toString('UTF-8').readLines().any {
            it.startsWith("warn  pre-registry layout: ${operatorHome.home.root().resolve('serve')} is a leftover")
        }
    }

    def "FR4: the owners doctor asks are the process's own beans — one preconditions owner, one secrets search, the runner's hooks-path reader"() {
        given:
        def context = FactoryBoot.boot()
        def notAClone = Files.createDirectories(tmp.resolve('plain'))
        def clone = RegisteredCloneFixture.unregistered(operatorHome.home.root(), notAClone)

        expect: 'the preconditions owner answers the ref format of a directory git cannot read with a verdict'
        context.getBean(ServePreconditions).refFormatVerdict(clone) != null

        and: 'the secret locator is the secrets port itself'
        context.getBean(SecretLocator).is(context.getBean(SecretsProvider))

        and: 'the hooks-path reader runs through the factory runner'
        context.getBean(HooksPathLeftover).assess(notAClone) instanceof HooksPathVerdict.Unread

        cleanup:
        context?.close()
    }
}
