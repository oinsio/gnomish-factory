package com.github.oinsio.gnomish.build

import spock.lang.Specification

/**
 * A stage's spec-contract launcher holds no rule (design D5 of tighten-spec-contract): its text
 * has one shape, and a text that runs anything besides the task, or more than one task, is
 * refused before any Gradle build would run it. The verdict half — that the arguments it does
 * pass leave the findings intact — is {@code SpecContractLauncherFunctionalSpec}'s.
 */
class StageSpecContractCommandSpec extends Specification {

    static final String LAUNCHER = '''\
if ! out=$(./gradlew -q checkSpecContract -PspecContractChanges=true 2>&1); then
  printf '%s\\n' "$out" | tail -40
  echo "read .claude/rules/traceability.md and escalate through \\$GNOMISH_DECISION_FILE"
  exit 1
fi
'''

    def "FR6: a launcher of the pinned shape yields the Gradle arguments it passes"() {
        expect:
        StageSpecContractCommand.parse(LAUNCHER) == ['-q', 'checkSpecContract', '-PspecContractChanges=true']
    }

    // F2: the fix-artifacts launcher names its task's change, so only that change's deltas are judged.
    def "FR6: a launcher that names its change passes it as the change filter, bound by the preamble"() {
        given:
        String named = StageSpecContractCommand.CHANGE_PREAMBLE + LAUNCHER.replace('=true 2>&1', '=true -PspecContractChange="$change" 2>&1')

        when:
        List<String> arguments = StageSpecContractCommand.parse(named)

        then:
        arguments == ['-q', 'checkSpecContract', '-PspecContractChanges=true', StageSpecContractCommand.CHANGE_ARGUMENT]
        StageSpecContractCommand.bind(arguments, 'feat') == ['-q', 'checkSpecContract', '-PspecContractChanges=true', '-PspecContractChange=feat']
    }

    def "FR6: a launcher that #divergence is refused"() {
        when:
        StageSpecContractCommand.parse(command)

        then:
        thrown(IllegalArgumentException)

        where:
        divergence                              | command
        'holds a rule of its own'               | LAUNCHER + "grep -rLn 'Implements' openspec/specs && exit 1\n"
        'runs a second command before the task' | 'set -e\n' + LAUNCHER
        'substitutes a command into its echo'   | LAUNCHER.replace('read .claude', '$(grep -c x openspec) .claude')
        'calls a task other than the gate'      | LAUNCHER.replace('checkSpecContract', 'check')
        'calls another task beside the gate'    | LAUNCHER.replace('checkSpecContract', 'checkSpecContract test')
        'chains a command onto the task'        | LAUNCHER.replace('=true 2>&1', '=true; grep x y 2>&1')
        'names a change without the preamble'   | LAUNCHER.replace('=true 2>&1', '=true -PspecContractChange="$change" 2>&1')
        'alters the preamble naming the change' | StageSpecContractCommand.CHANGE_PREAMBLE.replace('head -1', 'sh') + LAUNCHER
        'substitutes into another argument'     | StageSpecContractCommand.CHANGE_PREAMBLE + LAUNCHER.replace('-q', '-P"$change"')
    }

    def "FR6: the real stage manifests carry exactly one launcher each, of the pinned shape"() {
        expect:
        StageSpecContractCommand.gradleArguments(new File(GradleRunnerSupport.requiredProperty(property))) == arguments

        where:
        property                    | arguments
        'gnomish.fixArtifactsStage' | ['-q', 'checkSpecContract', '-PspecContractChanges=true', StageSpecContractCommand.CHANGE_ARGUMENT]
        'gnomish.archiveStage'      | ['-q', 'checkSpecContract']
    }
}
