package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR1, UX1, design D5 of add-manual-run: --key=value argument parsing and first-tier
 * validation (well-formedness, mutual consistency) via Spring Boot's ApplicationArguments,
 * independent of pipeline load (task 7.1 scope only).
 *
 * <p>FR7 of add-git-workflow, design D8: {@code --project} was renamed to {@code --dir}
 * (the directory itself is the registered clone the loader resolved, FR3 of
 * add-project-registry) and {@code --mode git|in-place} was
 * added, defaulting to {@code git} when absent.
 */
class RunArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    private final RunArgumentsParser parser = new RunArgumentsParser()


    /** The clone the configuration loader resolved: the {@code dir} every parse fills (FR3, D9 of add-project-registry). */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))
    def "FR1: --dir and --task both present parse correctly"() {
        when:
        RunArguments result = parser.parse(args('--dir=/tmp/workspace', '--task=fix the flaky spec'), CLONE)

        then:
        result.dir() == CLONE.clonePath() // FR3 of add-project-registry: the loader resolved --dir
        result.taskSource() == new TaskSource.Inline('fix the flaky spec')
        result.taskId() == null
        result.fromStage() == null
    }

    def "FR3: with --dir absent, dir is still the registered clone's path"() {
        when:
        RunArguments result = parser.parse(args('--task=fix the flaky spec'), CLONE)

        then:
        result.dir() == CLONE.clonePath()
    }

    def "FR7/D8: --mode absent defaults to Mode.GIT"() {
        when:
        RunArguments result = parser.parse(args('--task=t'), CLONE)

        then:
        result.mode() == RunArguments.Mode.GIT
    }

    def "FR7/D8: --mode=git parses to Mode.GIT"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--mode=git'), CLONE)

        then:
        result.mode() == RunArguments.Mode.GIT
    }

    def "FR7/D8: --mode=in-place parses to Mode.IN_PLACE"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--mode=in-place'), CLONE)

        then:
        result.mode() == RunArguments.Mode.IN_PLACE
    }

    def "FR7/UX1: --mode=garbage is a usage error naming the accepted values"() {
        when:
        parser.parse(args('--task=t', '--mode=garbage'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--mode')
        ex.message.contains('git')
        ex.message.contains('in-place')
    }

    def "UX1: repeated --mode is a usage error"() {
        when:
        parser.parse(args('--task=t', '--mode=git', '--mode=in-place'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--mode')
    }

    def "FR1: --task-file parses to TaskSource.FromFile"() {
        when:
        RunArguments result = parser.parse(args('--task-file=task.md'), CLONE)

        then:
        result.taskSource() == new TaskSource.FromFile(Path.of('task.md'))
    }

    def "FR1/UX1: both --task and --task-file present is a usage error naming the conflict and the = form"() {
        when:
        parser.parse(args('--task=inline text', '--task-file=task.md'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task')
        ex.message.contains('--task-file')
        ex.message.contains('=')
    }

    def "FR1/UX1: neither --task nor --task-file present is a usage error"() {
        when:
        parser.parse(args(), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task')
        ex.message.contains('--task-file')
    }

    @Unroll
    def "FR1: --task-id=#taskId with a valid charset parses through"() {
        when:
        RunArguments result = parser.parse(args('--task=t', "--task-id=${taskId}"), CLONE)

        then:
        result.taskId() == taskId

        where:
        taskId << [
            'abc123',
            'my-task-1',
            'My_Task',
            'a',
            '---'
        ]
    }

    @Unroll
    def "UX1: --task-id=#taskId with an invalid character is a usage error"() {
        when:
        parser.parse(args('--task=t', "--task-id=${taskId}"), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task-id')

        where:
        taskId << [
            'has space',
            'has/slash',
            'has\\backslash',
            'has.dot'
        ]
    }

    def "FR1: --from-stage absent parses to null"() {
        when:
        RunArguments result = parser.parse(args('--task=t'), CLONE)

        then:
        result.fromStage() == null
    }

    def "FR1: --from-stage present parses through as a plain string (definition-validity is task 7.3)"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--from-stage=build'), CLONE)

        then:
        result.fromStage() == 'build'
    }

    def "UX1: --from-stage= (blank) is a usage error"() {
        when:
        parser.parse(args('--task=t', '--from-stage='), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--from-stage')
    }

    def "UX1: repeated --task is a usage error"() {
        when:
        parser.parse(args('--task=a', '--task=b'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task')
    }

    // FR1 of remove-interactive-console: no flag swaps a role for a human any more, so every
    // spelling of the retired --interactive is refused the way any unknown option is.
    def "FR1 of remove-interactive-console: #flag is rejected as an unknown option"() {
        when:
        parser.parse(args('--task=t', flag), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.startsWith('unknown option --interactive')

        where:
        flag << [
            '--interactive',
            '--interactive=executor',
            '--interactive=judge'
        ]
    }

    // FR1 of remove-interactive-console: the usage error is the operators' only --help, so its
    // accepted list is the run flag surface — exactly these options, none of them a role swap.
    def "FR1 of remove-interactive-console: the usage error lists exactly the run options"() {
        when:
        parser.parse(args('--task=t', '--no-such-flag'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message == "unknown option --no-such-flag for 'gnomish run'; accepted: --dir, --task, --task-file," +
                ' --task-id, --from-stage, --mode, --base, --resume, --discard-work, --decision'
    }

    def "FR7/D7: --base parses through as a plain ref string"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--base=origin/main'), CLONE)

        then:
        result.base() == 'origin/main'
    }

    def "FR7: --base absent parses to null"() {
        when:
        RunArguments result = parser.parse(args('--task=t'), CLONE)

        then:
        result.base() == null
    }

    def "FR8/D9: --resume alone parses through, taskSource left unresolved"() {
        when:
        RunArguments result = parser.parse(args('--resume=my-task'), CLONE)

        then:
        result.resume() == 'my-task'
        result.taskSource() == null
    }

    def "FR10/D10: --discard-work with --resume parses to discardWork true"() {
        when:
        RunArguments result = parser.parse(args('--resume=my-task', '--discard-work'), CLONE)

        then:
        result.resume() == 'my-task'
        result.discardWork()
    }

    def "no flags at all: discardWork defaults to false, resume/base default to null"() {
        when:
        RunArguments result = parser.parse(args('--task=t'), CLONE)

        then:
        !result.discardWork()
        result.resume() == null
    }

    @Unroll
    def "FR8/UX1: --resume with #conflictingFlag is a usage error naming the conflict"() {
        when:
        parser.parse(args('--resume=my-task', conflictingFlag), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--resume')
        ex.message.contains(conflictingFlagName)

        where:
        conflictingFlag | conflictingFlagName
        '--task=x' | '--task'
        '--task-file=task.md' | '--task-file'
        '--task-id=abc' | '--task-id'
        '--from-stage=build' | '--from-stage'
    }

    @Unroll
    def "FR7/UX1: #gitOnlyFlag with --mode=in-place is a usage error naming the conflict"() {
        when:
        parser.parse(args('--task=t', '--mode=in-place', gitOnlyFlag), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--mode')
        ex.message.contains(gitOnlyFlagName)

        where:
        gitOnlyFlag | gitOnlyFlagName
        '--base=main' | '--base'
        '--discard-work' | '--discard-work'
        '--decision=x' | '--decision'
    }

    def "FR7/UX1: --resume with --mode=in-place is a usage error naming the conflict"() {
        when:
        parser.parse(args('--resume=my-task', '--mode=in-place'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--mode')
        ex.message.contains('--resume')
    }

    def "FR10/UX1: --discard-work without --resume is a usage error"() {
        when:
        parser.parse(args('--task=t', '--discard-work'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--discard-work')
        ex.message.contains('--resume')
    }

    // FR3, FR9, design D2 of make-run-headless: --decision is the only source of an operator
    // decision in `run`; it rides on --resume, is git-only, and never arrives blank.
    def "FR3/D2 of make-run-headless: --decision with --resume parses through as the decision text"() {
        when:
        RunArguments result = parser.parse(args('--resume=my-task', '--decision=use approach A'), CLONE)

        then:
        result.resume() == 'my-task'
        result.decision() == 'use approach A'
    }

    def "FR3/D2 of make-run-headless: --decision absent parses to null"() {
        when:
        RunArguments result = parser.parse(args('--resume=my-task'), CLONE)

        then:
        result.decision() == null
    }

    @Unroll
    def "FR9/D2 of make-run-headless: #flags is a usage error naming #named"() {
        when:
        parser.parse(args(*flags), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        named.every { ex.message.contains(it) }

        where:
        flags | named
        ['--task=t', '--decision=x'] | ['--decision', '--resume']
        ['--decision=x'] | ['--decision', '--resume']
        [
            '--resume=my-task',
            '--mode=in-place',
            '--decision=x'
        ] | [
            '--decision',
            '--mode=in-place'
        ]
        [
            '--mode=in-place',
            '--decision=x'
        ] | [
            '--decision',
            '--mode=in-place'
        ]
        [
            '--resume=my-task',
            '--decision='
        ] | ['--decision', 'blank']
        [
            '--resume=my-task',
            '--decision=   '
        ] | ['--decision', 'blank']
        [
            '--resume=my-task',
            '--decision'
        ] | ['--decision', 'value']
        [
            '--resume=my-task',
            '--decision=a',
            '--decision=b'
        ] | ['--decision', 'once']
    }

    def "FR9 of make-run-headless (Typo re-prompts): --decison with --resume is an unknown option naming the accepted flags"() {
        when:
        parser.parse(args('--resume=my-task', '--decison=x'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.startsWith('unknown option --decison')
        ex.message.contains('--decision')
    }

    def "FR9/D2 of make-run-headless: --decision without --resume is reported as the missing --resume, not as a missing task"() {
        when:
        parser.parse(args('--decision=x'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.startsWith('--decision requires --resume')
    }
}
