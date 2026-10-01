package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FR9 of add-tracker-port (task 5.13): {@code gnomish take}'s own, narrower flag matrix — no
 * {@code --mode}, no ad-hoc task source ({@code --task}/{@code --task-file}/{@code --task-id}), no
 * {@code --resume}, no {@code --from-stage} (design D4); the bare form additionally rejects
 * {@code --base}. Mirrors {@link RunArgumentsParserSpec}'s style.
 */
class TakeArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    private final TakeArgumentsParser parser = new TakeArgumentsParser()


    /** The clone the configuration loader resolved: the {@code dir} every parse fills (FR3, D9 of add-project-registry). */
    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), Path.of('/tmp/registered-clone'))
    def "explicit ref parses into TakeArguments.ref"() {
        when:
        TakeArguments result = parser.parse(args('take', '42'), CLONE)

        then:
        result.ref() == '42'
        result.dir() == CLONE.clonePath() // FR3 of add-project-registry
        result.interactiveMode() == RunArguments.InteractiveMode.NONE
        result.base() == null
        !result.discardWork()
        !result.takeover()
    }

    def "bare take (no positional ref) parses ref as null"() {
        when:
        TakeArguments result = parser.parse(args('take'), CLONE)

        then:
        result.ref() == null
    }

    def "FR3: an explicit --dir is accepted and dir is still the registered clone's path"() {
        when:
        TakeArguments result = parser.parse(args('take', '--dir=/tmp/workspace', '42'), CLONE)

        then:
        result.dir() == CLONE.clonePath()
    }

    def "--base is accepted on the explicit form"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--base=main'), CLONE)

        then:
        result.base() == 'main'
    }

    def "--discard-work is parsed as a bare flag"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--discard-work'), CLONE)

        then:
        result.discardWork()
    }

    // FR6 of add-claim-heartbeat (task 6.2): --takeover is the headless authorization for the
    // explicit-mode Working takeover, parsed as a bare boolean flag like --discard-work.
    def "--takeover is parsed as a bare flag on the explicit form"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--takeover'), CLONE)

        then:
        result.takeover()
    }

    // --takeover is a modifier meaningful only for 'take <ref>' — the bare form rejects it exactly
    // as it rejects --base (spec "Headless takeover needs the flag" applies to explicit mode only).
    def "bare take rejects --takeover"() {
        when:
        parser.parse(args('take', '--takeover'), CLONE)

        then:
        thrown(UsageException)
    }

    @Unroll
    def "--interactive#suffix parses to #expected"(String suffix, List<String> flags, RunArguments.InteractiveMode expected) {
        when:
        String[] tokens = (['take', '42'] + flags) as String[]
        TakeArguments result = parser.parse(args(tokens), CLONE)

        then:
        result.interactiveMode() == expected

        where:
        suffix | flags | expected
        ' bare' | ['--interactive'] | RunArguments.InteractiveMode.ALL
        '=executor' | ['--interactive=executor'] | RunArguments.InteractiveMode.EXECUTOR_ONLY
        '=judge' | ['--interactive=judge'] | RunArguments.InteractiveMode.JUDGE_ONLY
        ' absent' | [] | RunArguments.InteractiveMode.NONE
    }

    // Flag validation scenario: take is always git mode, has no ad-hoc task source, no --resume,
    // and no --from-stage — each rejected before the tracker is ever touched.
    @Unroll
    def "explicit take rejects --#flag before touching the tracker"(String flag) {
        when:
        List<String> tokens = [
            'take',
            '42',
            "--$flag=x".toString()
        ]
        parser.parse(args(*tokens), CLONE)

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
    def "bare take rejects --#flag before touching the tracker"(String flag) {
        when:
        List<String> tokens = [
            'take',
            "--$flag=x".toString()
        ]
        parser.parse(args(*tokens), CLONE)

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
        parser.parse(args('take', '42', '--resume'), CLONE)

        then:
        thrown(UsageException)
    }

    // Spec requirement text: "the bare form SHALL reject start modifiers (--base)".
    def "bare take rejects --base"() {
        when:
        parser.parse(args('take', '--base=main'), CLONE)

        then:
        thrown(UsageException)
    }

    def "explicit take (with ref) accepts --base without error"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '--base=main'), CLONE)

        then:
        noExceptionThrown()
        result.base() == 'main'
    }

    // FR2 of add-factory-serve: 'take <ref> <ref> ...' (two or more positional refs) is a distinct
    // batch form, parsed without error and carrying every ref in order.
    def "batch take accepts two or more refs"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '43', '44'), CLONE)

        then:
        result.refs() == ['42', '43', '44']
        !result.discardWork()
    }

    def "single explicit ref still parses as a one-element refs list"() {
        when:
        TakeArguments result = parser.parse(args('take', '42'), CLONE)

        then:
        result.refs() == ['42']
    }

    def "bare take parses refs as an empty list"() {
        when:
        TakeArguments result = parser.parse(args('take'), CLONE)

        then:
        result.refs() == []
    }

    // Spec requirement text: "the batch form SHALL reject --interactive and --base". Scenario
    // "Batch rejects interactivity": take 42 43 --interactive fails validation before touching the
    // tracker (FR3 of add-factory-serve).
    def "batch take rejects --interactive"() {
        when:
        parser.parse(args('take', '42', '43', '--interactive'), CLONE)

        then:
        thrown(UsageException)
    }

    def "batch take rejects --interactive=executor"() {
        when:
        parser.parse(args('take', '42', '43', '--interactive=executor'), CLONE)

        then:
        thrown(UsageException)
    }

    // FR3 of add-factory-serve: --base is a start modifier for a single fresh explicit-mode claim,
    // meaningless once two or more refs are being worked.
    def "batch take rejects --base"() {
        when:
        parser.parse(args('take', '42', '43', '--base=main'), CLONE)

        then:
        thrown(UsageException)
    }

    // --takeover is not in the batch-rejected set (spec text names only --interactive and --base);
    // it stays available so a headless batch run can take over Working refs without a TTY prompt.
    def "batch take accepts --takeover without error"() {
        when:
        TakeArguments result = parser.parse(args('take', '42', '43', '--takeover'), CLONE)

        then:
        noExceptionThrown()
        result.takeover()
    }
}
