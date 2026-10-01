package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import org.springframework.boot.ApplicationArguments
import spock.lang.Specification

/**
 * FR7, FR8, M2 of fix-operator-blockers: one contract over every {@link Subcommand} — an option
 * the subcommand does not accept is a usage error naming it, the subcommand and the accepted set;
 * property options and Spring Boot's own switches pass through. {@code --dir} is resolved once, by
 * {@link ArgumentsParsingSupport}'s owners the configuration loader calls, absolute and normalized;
 * every project-scoped parser then fills its {@code dir} from the registered clone the loader
 * matched (FR3, design D9 of add-project-registry), and only {@code project add} resolves {@code
 * --dir} itself. Each row drives the subcommand's own parser from a
 * minimal valid command line, so a subcommand added without a row fails the first feature.
 */
class CliArgumentsContractSpec extends Specification implements ApplicationArgumentsFixture {

    /** A minimal valid command line and the parser that owns it, per subcommand. */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))

    private static final Map<Subcommand, Map> CONTRACT = [
        (Subcommand.RUN) : [tokens: [
                'run',
                '--task=fix the flaky spec'
            ], dirRequired: false,
            parse : { ApplicationArguments a ->
                new RunArgumentsParser().parse(a, CLONE)
            }],
        (Subcommand.STATUS) : [tokens: ['status'], dirRequired: true,
            parse : { ApplicationArguments a ->
                new StatusArgumentsParser().parse(a, CLONE)
            }],
        (Subcommand.USAGE) : [tokens: ['usage', 'task-1'], dirRequired: true,
            parse : { ApplicationArguments a ->
                new UsageArgumentsParser().parse(a, CLONE)
            }],
        (Subcommand.TAKE) : [tokens: ['take'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new TakeArgumentsParser().parse(a, CLONE)
            }],
        (Subcommand.SERVE) : [tokens: ['serve'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new ServeArgumentsParser().parse(a, CLONE)
            }],
        (Subcommand.BOARD) : [tokens: ['board'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new BoardArgumentsParser().parse(a, CLONE)
            }],
        (Subcommand.DASHBOARD): [tokens: ['dashboard'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new DashboardArgumentsParser().parse(a, CLONE)
            }],
        // FR2 of add-project-registry: `project add` is the verb that reads --dir
        (Subcommand.PROJECT) : [tokens: ['project', 'add', 'widgets'], dirRequired: false,
            parse : { ApplicationArguments a ->
                new ProjectArgumentsParser().parse(a)
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

    // FR3, D9 of add-project-registry: a project-scoped parser resolves nothing — its dir is the
    // registered clone's path, whatever --dir spelled (the loader matched that spelling already)
    def "FR3: #subcommand fills dir from the registered clone, not from --dir"() {
        when:
        String[] raw = (CONTRACT[subcommand].tokens + ['--dir=widgets/./src/..']) as String[]
        Path dir = (CONTRACT[subcommand].parse as Closure).call(args(raw)).dir()

        then:
        dir == CLONE.clonePath()

        where:
        subcommand << Subcommand.values().findAll { it != Subcommand.PROJECT }
    }

    // FR7, M2: a relative --dir reaches the resolved directory absolute and normalized — in the
    // owner the loader calls, and in `project add`, the one parser that registers a path.
    // Q2 of fix-operator-blockers, reproduced with the packaged jar: `run --dir=.` in git mode fails
    // with "cannot start a git-mode task in .: it is not a git repository". GitExec launches git
    // with the git dir as its working directory AND passes --git-dir=<the same path>, so a relative
    // ./.git resolves to ./.git/.git, HEAD peels to nothing, and ManualRunLawBinding refuses.
    def "FR7: #resolver resolves a relative --dir against the working directory"() {
        when:
        Path dir = resolve.call(args('status', '--dir=widgets/./src/..'))

        then:
        dir.isAbsolute()
        dir == WORKING_DIRECTORY.resolve('widgets').normalize()

        where:
        resolver | resolve
        'projectDir' | { a -> ArgumentsParsingSupport.projectDir(a) }
        'requiredProjectDir' | { a ->
            ArgumentsParsingSupport.requiredProjectDir(a, 'status')
        }
        'project add' | { a ->
            new ProjectArgumentsParser().parse(args('project', 'add', 'widgets', '--dir=widgets/./src/..')).dir()
        }
    }

    // FR7: an absent --dir defaults to the absolute working directory where it is optional
    def "FR7: projectDir defaults an absent --dir to the absolute working directory"() {
        expect:
        ArgumentsParsingSupport.projectDir(args('take')) == WORKING_DIRECTORY
        new ProjectArgumentsParser().parse(args('project', 'add', 'widgets')).dir() == WORKING_DIRECTORY
    }

    // FR7: status and usage keep refusing an absent --dir, as before
    def "FR7: requiredProjectDir refuses an absent --dir for #token"() {
        when:
        ArgumentsParsingSupport.requiredProjectDir(args(token), token)

        then:
        def e = thrown(UsageException)
        e.message.contains('--dir is required')
        e.message.contains("gnomish ${token} --dir=")

        where:
        token << ['status', 'usage']
    }

    // FR7, FR8: an empty --dir is a usage error, never the working directory in disguise
    def "FR7: #resolver refuses an empty --dir"() {
        when:
        resolve.call(args('status', '--dir='))

        then:
        def e = thrown(UsageException)
        e.message.contains('--dir requires a value')

        where:
        resolver | resolve
        'projectDir' | { a -> ArgumentsParsingSupport.projectDir(a) }
        'requiredProjectDir' | { a ->
            ArgumentsParsingSupport.requiredProjectDir(a, 'status')
        }
    }

    // FR8: a --dir given twice is refused rather than silently resolved to the last one
    def "FR8: projectDir refuses a --dir given twice"() {
        when:
        ArgumentsParsingSupport.projectDir(args('take', '--dir=/a', '--dir=/b'))

        then:
        def e = thrown(UsageException)
        e.message.contains('--dir may be given only once')
    }
}
