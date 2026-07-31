package com.github.oinsio.gnomish.app

import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR9 of add-tracker-port (task 5.13): {@code gnomish take}'s own, narrower flag matrix — no
 * {@code --mode}, no ad-hoc task source ({@code --task}/{@code --task-file}/{@code --task-id}), no
 * {@code --resume}, no {@code --from-stage} (design D4); the bare form additionally rejects
 * {@code --base}. Mirrors {@link RunArgumentsParserSpec}'s style.
 */
class TakeArgumentsParserSpec extends Specification {

    private final TakeArgumentsParser parser = new TakeArgumentsParser()

    private static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    def "explicit ref parses into TakeArguments.ref"() {
        when:
        TakeArguments result = parser.parse(args('take', '42'))

        then:
        result.ref() == '42'
        result.dir() == Path.of('.')
        result.interactiveMode() == RunArguments.InteractiveMode.NONE
        result.base() == null
        !result.discardWork()
        !result.takeover()
    }

    def "bare take (no positional ref) parses ref as null"() {
        when:
        TakeArguments result = parser.parse(args('take'))

        then:
        result.ref() == null
    }

    def "--dir is parsed like gnomish run's"() {
        when:
        TakeArguments result = parser.parse(args('take', '--dir=/tmp/workspace', '42'))

        then:
        result.dir() == Path.of('/tmp/workspace')
    }

    def "--base is accepted on the explicit form"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--base=main'))

        then:
        result.base() == 'main'
    }

    def "--discard-work is parsed as a bare flag"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--discard-work'))

        then:
        result.discardWork()
    }

    // FR6 of add-claim-heartbeat (task 6.2): --takeover is the headless authorization for the
    // explicit-mode Working takeover, parsed as a bare boolean flag like --discard-work.
    def "--takeover is parsed as a bare flag on the explicit form"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--takeover'))

        then:
        result.takeover()
    }

    // --takeover is a modifier meaningful only for 'take <ref>' — the bare form rejects it exactly
    // as it rejects --base (spec "Headless takeover needs the flag" applies to explicit mode only).
    def "bare take rejects --takeover"() {
        when:
        parser.parse(args('take', '--takeover'))

        then:
        thrown(UsageException)
    }

    @Unroll
    def "--interactive#suffix parses to #expected"() {
        when:
        TakeArguments result = parser.parse(args(*(['take', '42'] + flags)))

        then:
        result.interactiveMode() == expected

        where:
        suffix        | flags                          | expected
        ' bare'        | ['--interactive']              | RunArguments.InteractiveMode.ALL
        '=executor'    | ['--interactive=executor']     | RunArguments.InteractiveMode.EXECUTOR_ONLY
        '=judge'       | ['--interactive=judge']        | RunArguments.InteractiveMode.JUDGE_ONLY
        ' absent'      | []                             | RunArguments.InteractiveMode.NONE
    }

    // Flag validation scenario: take is always git mode, has no ad-hoc task source, no --resume,
    // and no --from-stage — each rejected before the tracker is ever touched.
    @Unroll
    def "explicit take rejects --#flag before touching the tracker"() {
        when:
        parser.parse(args(*(['take', '42'] + ["--$flag=x".toString()])))

        then:
        thrown(UsageException)

        where:
        flag << [
            'mode',
            'task',
            'task-file',
            'task-id',
            'resume',
            'from-stage'
        ]
    }

    @Unroll
    def "bare take rejects --#flag before touching the tracker"() {
        when:
        parser.parse(args(*(['take'] + ["--$flag=x".toString()])))

        then:
        thrown(UsageException)

        where:
        flag << [
            'mode',
            'task',
            'task-file',
            'task-id',
            'resume',
            'from-stage'
        ]
    }

    def "--resume is rejected even given as a bare flag with no value"() {
        when:
        parser.parse(args('take', '42', '--resume'))

        then:
        thrown(UsageException)
    }

    // Spec requirement text: "the bare form SHALL reject start modifiers (--base)".
    def "bare take rejects --base"() {
        when:
        parser.parse(args('take', '--base=main'))

        then:
        thrown(UsageException)
    }

    def "explicit take (with ref) accepts --base without error"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--base=main'))

        then:
        noExceptionThrown()
        result.base() == 'main'
    }
}
