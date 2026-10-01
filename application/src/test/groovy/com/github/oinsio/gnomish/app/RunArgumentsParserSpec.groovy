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

    def "FR10: --interactive absent parses to InteractiveMode.NONE"() {
        when:
        RunArguments result = parser.parse(args('--task=t'), CLONE)

        then:
        result.interactiveMode() == RunArguments.InteractiveMode.NONE
    }

    def "FR10/D6: bare --interactive parses to InteractiveMode.ALL"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--interactive'), CLONE)

        then:
        result.interactiveMode() == RunArguments.InteractiveMode.ALL
    }

    def "FR10/D6: --interactive=executor parses to InteractiveMode.EXECUTOR_ONLY"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--interactive=executor'), CLONE)

        then:
        result.interactiveMode() == RunArguments.InteractiveMode.EXECUTOR_ONLY
    }

    def "FR10/D6: --interactive=judge parses to InteractiveMode.JUDGE_ONLY"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--interactive=judge'), CLONE)

        then:
        result.interactiveMode() == RunArguments.InteractiveMode.JUDGE_ONLY
    }

    def "UX1: --interactive=garbage is a usage error naming the accepted values"() {
        when:
        parser.parse(args('--task=t', '--interactive=garbage'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--interactive')
        ex.message.contains('executor')
        ex.message.contains('judge')
    }

    def "UX1: --interactive given twice (bare) is a usage error"() {
        when:
        parser.parse(args('--task=t', '--interactive', '--interactive'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--interactive')
    }

    def "UX1: --interactive given twice with scoped values is a usage error"() {
        when:
        parser.parse(args('--task=t', '--interactive=executor', '--interactive=judge'), CLONE)

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--interactive')
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
}
