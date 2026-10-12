package com.github.oinsio.gnomish.build

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The callers of {@code SpecContractCheck} run the one gate and hold no rule (design D5 of
 * tighten-spec-contract): the command the real {@code scripts/check-spec-contract.sh} spells is
 * run against a miniature repository that registers the task as the root build does, through
 * {@code spec-contract-conventions}, and must report exactly what the task reports. So is the
 * verify command of the real fix-artifacts and archive stage manifests, read with a YAML parser
 * ({@link StageSpecContractCommand}, which also pins the command's shape).
 */
class SpecContractLauncherFunctionalSpec extends Specification {

    /** A requirement without its traceability line: the one main-spec violation of the tree. */
    static final String BROKEN_SPEC = MiniSpecContractProject.DEMO_SPEC.replace('**Implements**: FR1 of demo-change\n', '')

    /** A delta requirement without its traceability line, reported at line 3. */
    static final String BROKEN_DELTA = '## ADDED Requirements\n\n### Requirement: New\nThe new SHALL work.\n'

    @TempDir
    Path dir

    MiniSpecContractProject project

    def setup() {
        project = new MiniSpecContractProject(dir).valid().conventions()
    }

    // Scenario: build/quality-gates / The manual launcher is the same gate
    def "FR6: #launcher reports the task's findings, the unarchived deltas' only when it judges them"() {
        given: 'two main specs and the deltas of two changes that each break the traceability line'
        project.spec('demo', BROKEN_SPEC)
                .spec('other', BROKEN_SPEC)
                .delta('feat', 'demo', BROKEN_DELTA)
                .delta('stray', 'demo', BROKEN_DELTA)

        when: 'the task runs directly, then as the launcher spells it'
        project.commandLine(direct).buildAndFail()
        String expected = project.report()
        project.commandLine(command).buildAndFail()

        then:
        project.report() == expected
        expected.contains('openspec/specs/demo/spec.md:5: [traceability line]')
        expected.contains('openspec/specs/other/spec.md:5: [traceability line]')
        expected.contains('openspec/changes/feat/specs/demo/spec.md:3: [traceability line]') == judgesDeltas
        expected.contains('openspec/changes/stray/specs/demo/spec.md:3: [traceability line]') == judgesOtherChange

        where: 'the fix-artifacts stage works on the change feat (F2)'
        launcher                  | command                                   | direct                                              | judgesDeltas | judgesOtherChange
        'the manual launcher'     | launcherCommand('--changes')              | ['checkSpecContract', '--changes']                  | true         | true
        'the fix-artifacts stage' | stageCommand('gnomish.fixArtifactsStage') | ['checkSpecContract', '--changes', '--change=feat'] | true         | false
        'the archive stage'       | stageCommand('gnomish.archiveStage')      | ['checkSpecContract']                               | false        | false
    }

    // F2: a stage working on one change is not judged by another change's defect.
    def "FR6: the change filter judges one change's deltas and the main specs, never another change's"() {
        given: 'a broken main spec, and the deltas of two changes that each break the line'
        project.spec('other', BROKEN_SPEC)
                .delta('feat', 'demo', BROKEN_DELTA)
                .delta('stray', 'demo', BROKEN_DELTA)

        when: 'one change is named through the property, then by the option'
        project.fails('-PspecContractChange=feat')
        String byProperty = project.report()
        project.fails('--change=feat')

        then: 'its delta and the main specs are judged, the other change is not'
        project.report() == byProperty
        byProperty.contains('openspec/specs/other/spec.md:5: [traceability line]')
        byProperty.contains('openspec/changes/feat/specs/demo/spec.md:3: [traceability line]')
        !byProperty.contains('openspec/changes/stray/')

        and: 'a name that is no unarchived change fails rather than judging nothing'
        ['missing', 'archive', '*', '../feat'].every { name ->
            project.fails("-PspecContractChange=${name}").output.contains("no unarchived change named '${name}'")
        }
    }

    def "FR6: the Gradle properties steer the registered task as its options do"() {
        given: 'a broken spec beside the valid one, and a delta that breaks the line'
        project.spec('other', BROKEN_SPEC)
                .delta('feat', 'demo', BROKEN_DELTA)
        GradleRunnerSupport.writeFile(dir, 'preview/demo/spec.md', MiniSpecContractProject.DEMO_SPEC)

        when: 'the deltas are judged through the property, and by the option'
        project.fails('-PspecContractChanges=true')
        String byProperty = project.report()
        project.fails('--changes')

        then:
        byProperty.contains('openspec/changes/feat/specs/demo/spec.md:3: [traceability line]')
        project.report() == byProperty

        and: 'the scope names spec ids, and the specs directory replaces openspec/specs'
        passesOverDemoOnly('-PspecContractScope=demo')
        passesOverDemoOnly("-PspecContractSpecsDir=${dir.resolve('preview')}")

        and: 'a relative specs directory is the same tree, resolved against the root project'
        project.passes('-PspecContractSpecsDir=preview').task(':checkSpecContract').outcome.name() == 'UP_TO_DATE'
    }

    private boolean passesOverDemoOnly(String argument) {
        project.passes(argument)
        project.report().readLines().last() == 'Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.'
    }

    /**
     * The Gradle arguments of the real launcher's one command, {@code "$@"} replaced by {@code
     * passedThrough}. The launcher holds no rule: that one command is its only Gradle invocation,
     * and it ends in {@code "$@"}.
     */
    private static List<String> launcherCommand(String... passedThrough) {
        List<String> lines = new File(GradleRunnerSupport.requiredProperty('gnomish.specContractLauncher')).readLines()
        List<String> invocations = lines.findAll { it.contains('gradlew') && !it.startsWith('#') }
        assert invocations.size() == 1
        def command = invocations[0] =~ /^exec \.\/gradlew (.+) "\$@"$/
        assert command.matches()
        (command.group(1).split(' ') as List<String>) + (passedThrough as List<String>)
    }

    /**
     * The Gradle arguments of the spec-contract check in the real stage manifest {@code property}
     * names, as the shell passes them when the stage works on the change {@code feat}.
     */
    private static List<String> stageCommand(String property) {
        StageSpecContractCommand.bind(
                StageSpecContractCommand.gradleArguments(new File(GradleRunnerSupport.requiredProperty(property))), 'feat')
    }
}
