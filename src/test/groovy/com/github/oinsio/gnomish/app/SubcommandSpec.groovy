package com.github.oinsio.gnomish.app

import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification

/**
 * FR13, FR14 of add-git-workflow: the entrypoint recognizes exactly three subcommands —
 * {@code run}, {@code status}, {@code usage} — from the first non-option (non {@code --...})
 * source argument. Absent entirely, {@code run} is implicit, preserving the pre-existing
 * flag-only invocation ({@code gnomish --dir=... --task=...}) that {@link ManualRunRunner}
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
    }

    // FR13, FR14: a flag-only invocation with no subcommand token still defaults to RUN, even
    // when a later positional-looking value appears only inside a --flag's value
    def "parse() ignores option values when looking for the subcommand token"() {
        given:
        def args = new DefaultApplicationArguments('--task=status update')

        expect:
        Subcommand.parse(args) == Subcommand.RUN
    }
}
