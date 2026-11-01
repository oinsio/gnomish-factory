package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.ExecutorVerifyFailedException
import com.github.oinsio.gnomish.app.RunExitCodeMapper
import com.github.oinsio.gnomish.app.UsageException
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification

/**
 * FR12, UX2 of define-executor-contract: {@code gnomish executor verify <program>
 * [--pending-settings=<json>]} prints one line per case on the human path, ends with exit code 1
 * when a case failed and 0 otherwise, and leaves no scratch directory behind. The candidate's child
 * environment is composed from the secrets port, here the test JVM's own base set.
 */
class ExecutorVerifyCommandSpec extends Specification {

    private final ScriptedConsoleIO console = new ScriptedConsoleIO()
    private final SecretsProvider secrets = { String name ->
        TestChildEnvironment.PROCESS_VALUES.find(name)
    } as SecretsProvider

    private final ExecutorVerifyCommand command = new ExecutorVerifyCommand(new GitProcessRunner(), VirtualTimeEquipment.create(), secrets, console)

    private static Set<Path> scratchDirectories() {
        Files.list(Path.of(System.getProperty('java.io.tmpdir'))).withCloseable { paths ->
            paths.filter {
                it.fileName.toString().startsWith('gnomish-conformance-')
            }.toList().toSet()
        }
    }

    // Scenario: executor/conformance-kit / Reference executor passes every case
    def "UX2: the reference executor gets twelve pass lines and the command returns, exit code zero"() {
        given:
        def before = scratchDirectories()

        when:
        command.run(new DefaultApplicationArguments('executor', 'verify', StandIn.executor('reference').toString(),
                '--pending-settings={"defer":true}'))

        then:
        noExceptionThrown()
        console.printed == KitCase.values().collect {
            "pass ${it.word()}\n".toString()
        }
        scratchDirectories() == before
    }

    // Scenario: executor/conformance-kit / Misbehaving fake fails each case it is told to break
    def "UX2: a failed case prints its line with the detail, and the command ends with exit code 1"() {
        given:
        def before = scratchDirectories()

        when:
        command.run(new DefaultApplicationArguments('executor', 'verify', StandIn.executor('break-transform').toString(),
                '--pending-settings={"defer":true}'))

        then:
        def failed = thrown(ExecutorVerifyFailedException)
        failed.message == 'the candidate failed 1 conformance case'
        new RunExitCodeMapper().getExitCode(failed) == 1
        console.printed.size() == 12
        console.printed.find {
            it.startsWith('FAIL transform: ')
        }.contains('wrote no result')
        console.printed.findAll { it.startsWith('pass ') }.size() == 11
        scratchDirectories() == before
    }

    // UX2: a malformed command line is refused before any process runs
    def "UX2: a malformed command line is refused before anything is printed"() {
        when:
        command.run(new DefaultApplicationArguments('executor', 'verify'))

        then:
        thrown(UsageException)
        console.printed == []
    }
}
