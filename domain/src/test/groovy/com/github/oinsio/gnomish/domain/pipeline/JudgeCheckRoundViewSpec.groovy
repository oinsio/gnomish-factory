package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * The judge as the check-round chain reads it (design D2 of extract-agent-round-runner): one
 * {@code verdict} request in the one type the chain is typed on, {@link VerifyCheck.Executor}, to
 * the registry name its executor block resolves to (design D12). Implements FR4, FR14, M3 of
 * extract-agent-round-runner.
 */
class JudgeCheckRoundViewSpec extends Specification {

    // FR4, M3 of extract-agent-round-runner: a judge is one verdict request on the check path, in the
    // one type the check-round chain reads
    def "a judge's check-round view names its block's executor, settings and model, and the criteria file"() {
        when:
        def view = new VerifyCheck.Judge('criteria/review.md',
                new StageDefinition.Executor(type, model, [roundTimeout: '60s'], Sandbox.none(), name)).asExecutorCheck()

        then:
        view.token() == VerifyCheck.Token.JUDGE
        view.executorName() == executor
        view.checkId() == 'criteria/review.md'
        view.settings() == settings
        view.interval() == Duration.ZERO
        view.timeout() == Duration.ZERO
        view.timeoutClass() == VerifyCheck.TimeoutClass.QUALITY
        view.pinPaths() == []
        view.verifyIn() == VerifyCheck.VerifyIn.FRESH_BOX
        view.threshold() == Threshold.DEFAULT
        view.expected() == []
        !view.polled()

        where:
        type | model | name || executor | settings
        ExecutorType.AGENT_CLI | 'opus' | null || BuiltInExecutors.CLAUDE | [roundTimeout: '60s', model: 'opus']
        ExecutorType.PROGRAM | '' | 'my-judge' || 'my-judge' | [roundTimeout: '60s']
    }

    // FR4, FR14 of extract-agent-round-runner: the registry name an executor block resolves to
    def "an executor block of type #type resolves to the registry name #expected"() {
        expect:
        new StageDefinition.Executor(type, 'model', [:], Sandbox.none(), 'declared').registryName() == expected

        where:
        type || expected
        ExecutorType.AGENT_CLI || BuiltInExecutors.CLAUDE
        ExecutorType.API || BuiltInExecutors.CLAUDE
        ExecutorType.PROGRAM || 'declared'
        ExecutorType.COMMAND || BuiltInExecutors.COMMAND
    }

    def "a program block naming no declaration has no registry name"() {
        when:
        new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:]).registryName()

        then:
        def refused = thrown(NullPointerException)
        refused.message.contains('ExecutorReferenceRule')
    }
}
