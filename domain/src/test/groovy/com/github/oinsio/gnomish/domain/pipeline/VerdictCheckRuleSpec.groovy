package com.github.oinsio.gnomish.domain.pipeline

import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import java.time.Duration
import spock.lang.Specification

/**
 * VerdictCheckRule (FR4, NFR-S1 of migrate-verify-to-executor-contract; design D3, D6): an
 * {@code scm-status} check declares the contexts it waits for, and a check naming a law executor
 * granted credentials never runs in the round box, and a check names an executor of the registry. Pure: no process, no describe — the law's grant
 * equals the describe's credentials once the describe preflight agreed (GrantAgreement).
 */
class VerdictCheckRuleSpec extends Specification {

    private static VerifyCheck.Executor check(VerifyCheck.Token token, String executor, VerifyCheck.VerifyIn verifyIn,
            List<String> expected = []) {
        new VerifyCheck.Executor(token, executor, 'sonar/gate', [:], Duration.ofSeconds(5), Duration.ofMinutes(5),
        VerifyCheck.TimeoutClass.QUALITY, [], verifyIn, Threshold.DEFAULT, expected)
    }

    private static VerifyCheck.Executor check(String executor, VerifyCheck.VerifyIn verifyIn, List<String> expected = []) {
        check(VerifyCheck.Token.EXECUTOR, executor, verifyIn, expected)
    }

    private static ExecutorDeclaration declared(String name, List<String> credentials) {
        new ExecutorDeclaration(name, ExecutorDeclaration.Type.PROGRAM, [name], [:], credentials, [])
    }

    private static PipelineDefinition pipeline(List<VerifyCheck> checks, List<ExecutorDeclaration> executors = []) {
        def stage = new StageDefinition('verify', 'Purpose of verify', [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:]),
        'stages/verify/instructions.md', checks, new AutonomyLimits(2), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(2), [stage], null, executors)
    }

    // FR4, D3: expected contexts are the only cure for "pending forever"
    // Scenario: pipeline/pipeline-config / Missing expected list
    def "FR4: an scm-status check declaring no expected contexts is a located error naming the check"() {
        expect:
        VerdictCheckRule.validate(pipeline([
            check(VerifyCheck.Executor.SCM_STATUS, VerifyCheck.VerifyIn.SAME_BOX)
        ])) == [
            new ConfigError('stages/verify/stage.yaml', 'verify[0].expected',
            "scm-status check 'sonar/gate' declares no expected contexts; list the context names it waits for under 'expected'")
        ]
    }

    // FR4: the rule binds the scm-status executor whatever form names it, and no other executor
    def "FR4: #executor declared as #token with expected #expected yields #count error(s)"() {
        expect:
        VerdictCheckRule.validate(pipeline([
            check(token, executor, VerifyCheck.VerifyIn.SAME_BOX, expected)
        ])).size() == count

        where:
        token | executor | expected || count
        VerifyCheck.Token.EXTERNAL | 'scm-status' | [] || 1
        VerifyCheck.Token.EXECUTOR | 'scm-status' | ['ci/build'] || 0
        VerifyCheck.Token.EXECUTOR | 'github-actions' | [] || 0
    }

    // NFR-S1, D6: a credentialed check declaring same-box would hand the token to the gnome's box
    // Scenario: pipeline/pipeline-config / Same-box with a secret is refused
    def "NFR-S1: a check naming an executor granted SONAR_TOKEN and declaring same-box is a located error naming the check and the credential"() {
        expect:
        VerdictCheckRule.validate(pipeline([
            check('sonarqube', VerifyCheck.VerifyIn.SAME_BOX)
        ], [
            declared('sonarqube', ['SONAR_TOKEN', 'SONAR_HOST'])
        ])) == [
            new ConfigError('stages/verify/stage.yaml', 'verify[0].verifyIn',
            "check 'sonar/gate' names executor 'sonarqube', which is granted credentials SONAR_TOKEN, SONAR_HOST;"
            + " a credentialed check runs in a fresh box: declare verify-in fresh-box or omit it")
        ]
    }

    // NFR-S1: only a credentialed law executor in the round box is refused
    def "NFR-S1: #what yields no error"() {
        expect:
        VerdictCheckRule.validate(pipeline([check], executors)) == []

        where:
        what | check | executors
        'a credentialed executor in a fresh box' | check('sonarqube', VerifyCheck.VerifyIn.FRESH_BOX) | [
            declared('sonarqube', ['SONAR_TOKEN'])
        ]
        'an executor granted no credential in the round box' | check('sonarqube', VerifyCheck.VerifyIn.SAME_BOX) | [
            declared('sonarqube', [])
        ]
        'a built-in no law declares, in the round box' | check('github-actions', VerifyCheck.VerifyIn.SAME_BOX) | [
            declared('sonarqube', ['SONAR_TOKEN'])
        ]
        'a command check under a name a law shadows' | check(VerifyCheck.Token.COMMAND, 'shell', VerifyCheck.VerifyIn.SAME_BOX) | [
            declared('shell', ['SONAR_TOKEN'])
        ]
    }

    // NFR-S1: the credentialed refusal binds the external form too, and errors keep check order
    def "NFR-S1: every offending check is reported in check order"() {
        when:
        def errors = VerdictCheckRule.validate(pipeline([
            check(VerifyCheck.Token.EXTERNAL, 'sonarqube', VerifyCheck.VerifyIn.SAME_BOX),
            JudgeChecks.agent('criteria.md', 'claude-sonnet-4-5'),
            check(VerifyCheck.Executor.SCM_STATUS, VerifyCheck.VerifyIn.SAME_BOX)
        ], [
            declared('sonarqube', ['SONAR_TOKEN'])
        ]))

        then:
        errors*.where() == [
            'verify[0].verifyIn',
            'verify[2].expected'
        ]
    }

    // FR1, UX2 (design D18): a check names an executor of the registry — a built-in or a declaration
    def "FR1, UX2: a #token check naming an unknown executor is a located error at #field listing the six and the law's names"() {
        expect:
        VerdictCheckRule.validate(pipeline([
            check(token, 'sonar', VerifyCheck.VerifyIn.FRESH_BOX)
        ], [
            declared('acme', [])
        ])) == [
            new ConfigError('stages/verify/stage.yaml', "verify[0].${field}".toString(),
            "check 'sonar/gate' names unknown executor 'sonar'; the registry's executors are "
            + 'files-exist, shell, github-actions, scm-status, http, command, acme')
        ]

        where:
        token | field
        VerifyCheck.Token.EXTERNAL | 'provider'
        VerifyCheck.Token.EXECUTOR | 'executor'
    }

    // FR1: a built-in, a law declaration, and a blank name (the sanity rule's) are no unknown executor
    def "FR1: a check naming '#executor' yields no name error"() {
        expect:
        VerdictCheckRule.validate(pipeline([
            check(executor, VerifyCheck.VerifyIn.FRESH_BOX, ['ci'])
        ], [
            declared('acme', [])
        ])) == []

        where:
        executor << BuiltInExecutors.CHECK_NAMES + ['acme', '']
    }

    // FR14, D12 of extract-agent-round-runner: reserving 'claude' leaves a check naming it an unknown
    //     executor, as before the reservation — an agent reaches a check only through a judge block
    def "FR14: a check naming the reserved agent entry 'claude' is an unknown executor"() {
        when:
        def errors = VerdictCheckRule.validate(pipeline([
            check('claude', VerifyCheck.VerifyIn.FRESH_BOX, ['ci'])
        ], [
            declared('acme', [])
        ]))

        then:
        errors*.message() == [
            "check 'sonar/gate' names unknown executor 'claude'; the registry's executors are "
            + 'files-exist, shell, github-actions, scm-status, http, command, acme'
        ]
    }
}
