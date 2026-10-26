package com.github.oinsio.gnomish.app

import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification

/**
 * FR13, FR14 of add-git-workflow: the entrypoint recognizes exactly three subcommands —
 * {@code run}, {@code status}, {@code usage} — from the first non-option (non {@code --...})
 * source argument. Absent entirely, {@code run} is implicit, preserving the pre-existing
 * flag-only invocation ({@code gnomish --dir=... --task=...}) that {@code ManualRunRunner}
 * already supported before subcommand dispatch existed. Any other first positional token is a
 * usage error (exit code 2 via {@link RunExitCodeMapper}).
 */
class SubcommandSpec extends Specification {

    // FR13, FR14: no positional token at all -> RUN, the implicit default
    def "parse() defaults to RUN when no positional token is present"() {
        given:
        def args = new DefaultApplicationArguments('--dir=/tmp/x', '--task=fix it')

        expect:
        Subcommand.parse(args) == Subcommand.RUN
    }

    // FR13, FR14: an explicit 'run' token dispatches to RUN
    def "parse() recognizes an explicit 'run' token"() {
        given:
        def args = new DefaultApplicationArguments('run', '--dir=/tmp/x', '--task=fix it')

        expect:
        Subcommand.parse(args) == Subcommand.RUN
    }

    // FR13: a 'status' token dispatches to STATUS
    def "parse() recognizes a 'status' token"() {
        given:
        def args = new DefaultApplicationArguments('status', '--dir=/tmp/x')

        expect:
        Subcommand.parse(args) == Subcommand.STATUS
    }

    // FR14: a 'usage' token dispatches to USAGE
    def "parse() recognizes a 'usage' token"() {
        given:
        def args = new DefaultApplicationArguments('usage', '--dir=/tmp/x', 'task-1')

        expect:
        Subcommand.parse(args) == Subcommand.USAGE
    }

    // FR2 of add-factory-serve (task 5.1): a 'serve' token dispatches to SERVE
    def "parse() recognizes a 'serve' token"() {
        given:
        def args = new DefaultApplicationArguments('serve', '--dir=/tmp/x')

        expect:
        Subcommand.parse(args) == Subcommand.SERVE
    }

    // FR1 of add-board-command (design D1): a 'board' token dispatches to BOARD
    def "parse() recognizes a 'board' token"() {
        given:
        def args = new DefaultApplicationArguments('board', '--dir=/tmp/x')

        expect:
        Subcommand.parse(args) == Subcommand.BOARD
    }

    // FR2, FR4 of add-project-registry: a 'project' token dispatches to PROJECT
    def "parse() recognizes a 'project' token"() {
        given:
        def args = new DefaultApplicationArguments('project', 'list')

        expect:
        Subcommand.parse(args) == Subcommand.PROJECT
    }

    // FR7 of add-doctor-command: a 'doctor' token dispatches to DOCTOR
    def "FR7: parse() recognizes a 'doctor' token"() {
        given:
        def args = new DefaultApplicationArguments('doctor', '--dir=/tmp/x')

        expect:
        Subcommand.parse(args) == Subcommand.DOCTOR
    }

    // FR7 of add-doctor-command (design D2): namesDoctor and parse read one token, so the loader's
    // collect mode and the runner's dispatch cannot disagree about a command line parse accepts
    def "FR7: namesDoctor() agrees with parse() for #argv"() {
        given:
        def args = new DefaultApplicationArguments(argv as String[])

        expect:
        Subcommand.namesDoctor(args) == (Subcommand.parse(args) == Subcommand.DOCTOR)

        where:
        argv << [
            ['doctor'],
            [
                '--json',
                'doctor',
                '--online'
            ],
            ['run', '--task=doctor'],
            ['project', 'list'],
            [],
        ]
    }

    // FR13, FR14: an unrecognized first positional token is a usage error (exit 2 family)
    def "parse() rejects an unrecognized subcommand"() {
        given:
        def args = new DefaultApplicationArguments('frobnicate', '--dir=/tmp/x')

        when:
        Subcommand.parse(args)

        then:
        UsageException ex = thrown()
        ex.message.contains('frobnicate')
        ex.message.contains('run')
        ex.message.contains('status')
        ex.message.contains('usage')
        ex.message.contains("'gnomish project'")
        ex.message.contains("'gnomish doctor'")
    }

    // FR13, FR14: a flag-only invocation with no subcommand token still defaults to RUN, even
    // when a later positional-looking value appears only inside a --flag's value
    def "parse() ignores option values when looking for the subcommand token"() {
        given:
        def args = new DefaultApplicationArguments('--task=status update')

        expect:
        Subcommand.parse(args) == Subcommand.RUN
    }

    // FR7 of add-doctor-command (design D2): the configuration loader's collect mode is chosen by
    // the first positional token alone
    def "FR7: namesDoctor() is #expected for #argv"() {
        expect:
        Subcommand.namesDoctor(new DefaultApplicationArguments(argv as String[])) == expected

        where:
        argv || expected
        ['doctor', '--dir=/tmp/x'] || true
        ['--json', 'doctor'] || true
        ['run', 'doctor'] || false
        ['--task=doctor'] || false
        [] || false
    }
}
