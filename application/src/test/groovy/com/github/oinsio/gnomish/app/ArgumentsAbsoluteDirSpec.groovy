package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR7 of fix-operator-blockers: the {@code dir} component of every subcommand's arguments record
 * refuses a relative path at construction, so a directory that bypassed {@link
 * ArgumentsParsingSupport#projectDir} cannot reach any component (design D5, single-owner row
 * "enforced by"). {@link CliArgumentsContractSpec} pins the parser side; this spec pins the
 * record side, one row per record.
 */
class ArgumentsAbsoluteDirSpec extends Specification {

    private static final Map<String, Closure> RECORDS = [
        run : { Path d ->
            new RunArguments(d, new TaskSource.Inline('t'), null, null, RunArguments.InteractiveMode.NONE,
            RunArguments.Mode.GIT, null, null, false)
        },
        take : { Path d ->
            new TakeArguments(d, [], RunArguments.InteractiveMode.NONE, null, false, false)
        },
        serve : { Path d -> new ServeArguments(d, null, false) },
        status : { Path d -> new StatusArguments(d, null, false) },
        usage : { Path d -> new UsageArguments(d, 'task-1', false) },
        board : { Path d -> new BoardArguments(d, false, 50) },
        dashboard: { Path d -> new DashboardArguments(d, null, false) },
    ]

    def "FR7: the #name arguments record refuses a relative directory, naming it"() {
        when:
        RECORDS[name].call(Path.of('widgets'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'project directory must be absolute: widgets'

        where:
        name << RECORDS.keySet()
    }

    def "FR7: the #name arguments record keeps an absolute directory as given"() {
        given:
        Path dir = Path.of('').toAbsolutePath().resolve('widgets')

        expect:
        RECORDS[name].call(dir).dir() == dir

        where:
        name << RECORDS.keySet()
    }

    def "FR7: a record exists here for every subcommand"() {
        expect:
        RECORDS.keySet() == Subcommand.values().collect {
            it.name().toLowerCase()
        } as Set
    }
}
