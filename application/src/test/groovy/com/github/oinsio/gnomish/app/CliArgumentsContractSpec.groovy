package com.github.oinsio.gnomish.app

import java.nio.file.Path
import org.springframework.boot.ApplicationArguments
import spock.lang.Specification

/**
 * FR7, FR8, M2 of fix-operator-blockers: one contract over every {@link Subcommand} — an option
 * the subcommand does not accept is a usage error naming it, the subcommand and the accepted set;
 * property options and Spring Boot's own switches pass through; {@code --dir} reaches the
 * parsed arguments absolute and normalized. Each row drives the subcommand's own parser from a
 * minimal valid command line, so a subcommand added without a row fails the first feature.
 */
class CliArgumentsContractSpec extends Specification implements ApplicationArgumentsFixture {

    /** A minimal valid command line and the parser that owns it, per subcommand. */
    private static final Map<Subcommand, Map> CONTRACT = [
        (Subcommand.RUN) : [tokens: [
                'run',
                '--task=fix the flaky spec'
            ], dirRequired: false,
            parse : { ApplicationArguments a ->
                new RunArgumentsParser().parse(a)
            }],
        (Subcommand.STATUS) : [tokens: ['status'], dirRequired: true,
            parse : { ApplicationArguments a ->
                new StatusArgumentsParser().parse(a)
            }],
        (Subcommand.USAGE) : [tokens: ['usage', 'task-1'], dirRequired: true,
            parse : { ApplicationArguments a ->
                new UsageArgumentsParser().parse(a)
            }],
        (Subcommand.TAKE) : [tokens: ['take'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new TakeArgumentsParser().parse(a)
            }],
        (Subcommand.SERVE) : [tokens: ['serve'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new ServeArgumentsParser().parse(a)
            }],
        (Subcommand.BOARD) : [tokens: ['board'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new BoardArgumentsParser().parse(a)
            }],
        (Subcommand.DASHBOARD): [tokens: ['dashboard'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new DashboardArgumentsParser().parse(a)
            }],
    ]

    private static final Path WORKING_DIRECTORY = Path.of('').toAbsolutePath()

    private static Object parse(Subcommand subcommand, String... extra) {
        def row = CONTRACT[subcommand]
        String[] raw = (row.tokens + (row.dirRequired ? ['--dir=/tmp/clone'] : []) + extra.toList()) as String[]
        (row.parse as Closure).call(args(raw))
    }

    private static String token(Subcommand subcommand) {
        (CONTRACT[subcommand].tokens as List<String>).first()
    }

    // M2: the contract covers every subcommand the entrypoint dispatches
    def "M2: every subcommand has a contract row"() {
        expect:
        CONTRACT.keySet() == Subcommand.values() as Set
    }

    // FR8, NFR-O1, UX2: an unknown option names itself, the subcommand and the accepted set
    def "FR8: #subcommand rejects an option it does not accept, naming it and the accepted options"() {
        when:
        parse(subcommand, '--no-such-option=1')

        then:
        def e = thrown(UsageException)
        e.message.contains('unknown option --no-such-option')
        e.message.contains("'gnomish ${token(subcommand)}'")
        e.message.contains('accepted:')
        e.message.contains('--dir')

        where:
        subcommand << Subcommand.values()
    }

    // FR8: dotted Spring properties and Spring Boot's own switches reach configuration untouched
    def "FR8: #subcommand lets #option pass through"() {
        when:
        parse(subcommand, option)

        then:
        noExceptionThrown()

        where:
        [subcommand, option] << [
            Subcommand.values(),
            [
                '--factory.serve.slots=2',
                '--spring.main.banner-mode=off',
                '--logging.level.root=INFO',
                '--debug',
                '--trace'
            ]
        ].combinations()
    }

    // FR8, UX2: status tells the operator the task id is positional
    def "FR8, UX2: status --task is rejected with a hint that the task id is positional"() {
        when:
        parse(Subcommand.STATUS, '--task=github:acme/widgets#7')

        then:
        def e = thrown(UsageException)
        e.message.contains('unknown option --task')
        e.message.contains('--json')
        e.message.contains('positional')
    }

    // FR7, M2: a relative --dir reaches the parsed arguments absolute and normalized.
    // Q2 of fix-operator-blockers, reproduced with the packaged jar: `run --dir=.` in git mode fails
    // with "cannot start a git-mode task in .: it is not a git repository". GitExec launches git
    // with the git dir as its working directory AND passes --git-dir=<the same path>, so a relative
    // ./.git resolves to ./.git/.git, HEAD peels to nothing, and ManualRunLawBinding refuses.
    def "FR7: #subcommand resolves a relative --dir against the working directory"() {
        when:
        def row = CONTRACT[subcommand]
        String[] raw = (row.tokens + ['--dir=widgets/./src/..']) as String[]
        Path dir = (row.parse as Closure).call(args(raw)).dir()

        then:
        dir.isAbsolute()
        dir == WORKING_DIRECTORY.resolve('widgets').normalize()

        where:
        subcommand << Subcommand.values()
    }

    // FR7: an absent --dir defaults to the absolute working directory where it is optional
    def "FR7: #subcommand defaults an absent --dir to the absolute working directory"() {
        expect:
        parse(subcommand).dir() == WORKING_DIRECTORY

        where:
        subcommand << Subcommand.values().findAll { !CONTRACT[it].dirRequired }
    }

    // FR7: status and usage keep refusing an absent --dir, as before
    def "FR7: #subcommand still requires --dir"() {
        when:
        String[] raw = CONTRACT[subcommand].tokens as String[]
        (CONTRACT[subcommand].parse as Closure).call(args(raw))

        then:
        def e = thrown(UsageException)
        e.message.contains('--dir is required')

        where:
        subcommand << Subcommand.values().findAll { CONTRACT[it].dirRequired }
    }
}
