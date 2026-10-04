package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, M2 of fix-operator-blockers (design D5, single-owner row "rejectUnknownOptions"): the
 * unknown-option contract driven through the entrypoint, not the parser. {@code
 * CliArgumentsContractSpec} proves each parser rejects an unknown option; it cannot see a gate in
 * front of the parser, and {@code ManualRunRunner} once had one — its own copy of the {@code run}
 * flag list, which let {@code gnomish run --tsk=x} exit 0 having done nothing. This spec feeds an
 * unknown option to every {@link Subcommand} through {@link ManualRunRunner#run}, so a gate that
 * skips a parser fails a row, and pins the two command lines that legitimately reach no parser:
 * the empty one a Spring test context boots with, and the sole {@code --version} the entry point
 * answers before Spring starts (FR6, design D3 of add-release-pipeline).
 */
class CliEntrypointContractSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path clonePath

    @TempDir
    Path homeDir

    // FR8, NFR-O1, M2: every subcommand's unknown option surfaces at the entrypoint, by name
    def "FR8: the entrypoint rejects an unknown option of #subcommand, naming it and the subcommand"() {
        given:
        String token = subcommand.name().toLowerCase(Locale.ROOT)
        def runner = newManualRunRunner(clonePath, homeDir)

        when:
        runner.run(new DefaultApplicationArguments(token, '--no-such-option=1'))

        then:
        def e = thrown(UsageException)
        e.message.startsWith("unknown option --no-such-option for 'gnomish ${token}'")

        where:
        subcommand << Subcommand.values()
    }

    // FR8 of fix-operator-blockers, FR12 of add-manual-run: the empty command line reaches no parser
    def "FR8: an empty command line is a no-op that prints nothing"() {
        given:
        def runner = newManualRunRunner(clonePath, homeDir)
        def originalOut = System.out
        def originalErr = System.err
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        System.err = new PrintStream(err, true, 'UTF-8')

        when:
        try {
            runner.run(new DefaultApplicationArguments())
        } finally {
            System.out = originalOut
            System.err = originalErr
        }

        then:
        noExceptionThrown()
        out.toString('UTF-8').isEmpty()
        err.toString('UTF-8').isEmpty()
    }

    // FR6 of add-release-pipeline (design D3): a sole --version is answered before any parser runs
    def "FR6: a sole --version prints the factory version on one line and reaches no parser"() {
        given:
        def console = new ScriptedConsoleIO()

        when:
        boolean answered = VersionCommand.answer(['--version'] as String[], console)

        then: 'the version is printed on the human path, once, as one line'
        answered
        console.printed == [
            FactoryVersion.current().value() + '\n'
        ]
        console.printedMachine.isEmpty()
    }

    // FR6 of add-release-pipeline, FR8 of fix-operator-blockers: --version beside any other token
    // is no version request; it reaches the run parser and is rejected like any unknown option
    def "FR6: --version with another token (#tokens) reaches the run parser and is rejected"() {
        given:
        def console = new ScriptedConsoleIO()
        def runner = newManualRunRunner(clonePath, homeDir)

        when:
        boolean answered = VersionCommand.answer(tokens as String[], console)

        then: 'the entry point leaves it to Spring and prints nothing'
        !answered
        console.printed.isEmpty()

        when:
        runner.run(new DefaultApplicationArguments(tokens as String[]))

        then:
        def e = thrown(UsageException)
        e.message.startsWith("unknown option --version for 'gnomish run'")

        where:
        tokens << [
            ['run', '--version'],
            ['--version', '--dir=.'],
        ]
    }

    // FR6: only the exact token qualifies — no prefix, no value, no other spelling
    def "FR6: #tokens is not a version request"() {
        given:
        def console = new ScriptedConsoleIO()

        expect:
        !VersionCommand.answer(tokens as String[], console)
        console.printed.isEmpty()

        where:
        tokens << [
            [],
            ['--version=1'],
            ['-v'],
            ['version'],
        ]
    }
}
