package com.github.oinsio.gnomish.app

import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR1, UX1, design D5 of add-manual-run: --key=value argument parsing and first-tier
 * validation (well-formedness, mutual consistency) via Spring Boot's ApplicationArguments,
 * independent of pipeline load (task 7.1 scope only).
 */
class RunArgumentsParserSpec extends Specification {

    private final RunArgumentsParser parser = new RunArgumentsParser()

    private static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    def "FR1: --project and --task both present parse correctly"() {
        when:
        RunArguments result = parser.parse(args('--project=/tmp/workspace', '--task=fix the flaky spec'))

        then:
        result.project() == Path.of('/tmp/workspace')
        result.taskSource() == new TaskSource.Inline('fix the flaky spec')
        result.taskId() == null
        result.fromStage() == null
    }

    def "D3: --project absent defaults to the current working directory"() {
        when:
        RunArguments result = parser.parse(args('--task=fix the flaky spec'))

        then:
        result.project() == Path.of('.')
    }

    def "FR1: --task-file parses to TaskSource.FromFile"() {
        when:
        RunArguments result = parser.parse(args('--task-file=task.md'))

        then:
        result.taskSource() == new TaskSource.FromFile(Path.of('task.md'))
    }

    def "FR1/UX1: both --task and --task-file present is a usage error naming the conflict and the = form"() {
        when:
        parser.parse(args('--task=inline text', '--task-file=task.md'))

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task')
        ex.message.contains('--task-file')
        ex.message.contains('=')
    }

    def "FR1/UX1: neither --task nor --task-file present is a usage error"() {
        when:
        parser.parse(args())

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task')
        ex.message.contains('--task-file')
    }

    @Unroll
    def "FR1: --task-id=#taskId with a valid charset parses through"() {
        when:
        RunArguments result = parser.parse(args('--task=t', "--task-id=${taskId}"))

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
        parser.parse(args('--task=t', "--task-id=${taskId}"))

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
        RunArguments result = parser.parse(args('--task=t'))

        then:
        result.fromStage() == null
    }

    def "FR1: --from-stage present parses through as a plain string (definition-validity is task 7.3)"() {
        when:
        RunArguments result = parser.parse(args('--task=t', '--from-stage=build'))

        then:
        result.fromStage() == 'build'
    }

    def "UX1: --from-stage= (blank) is a usage error"() {
        when:
        parser.parse(args('--task=t', '--from-stage='))

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--from-stage')
    }

    def "UX1: repeated --task is a usage error"() {
        when:
        parser.parse(args('--task=a', '--task=b'))

        then:
        UsageException ex = thrown(UsageException)
        ex.message.contains('--task')
    }
}
