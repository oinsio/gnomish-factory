package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D13 of extract-agent-round-runner, the guard-probe row (task 4.0; FR15, M6): the egress
 * guard's liveness is probed by one owner, {@code GuardedExecutor}, a decorator over the contract's
 * {@code Executor} applied where an executor is bound to an environment — the stage half ({@code
 * ProgramStageExecutor}, over what its {@code RoundExecutorFactory} binds to the round's environment)
 * and the check half ({@code ProgramCheckBinding}, over the fresh box of one request) — and the check
 * half of the {@code claude} entry ({@code AgentCheckBinding}, over the fresh box or the host
 * environment of one request; task 4.0a), whose stage half runs through the same stage wrapper. {@code
 * probeGuard(} is spelled in production only by the owner and by the environment types that declare or
 * forward it (M6): the two callers that predated the owner left with their round classes — the judge
 * round's with task 4.1 (a judge check is a check round of the {@code claude} entry's check half), the
 * agent stage round's ({@code ExecutorRoundExecution}) with task 5.1, since every agent stage round runs
 * through the stage wrapper. The decorator is applied by its three binding sites alone, the one
 * caller of a round-executor factory's binding hands the bound executor straight to it, and an agent
 * runner is bound only by the agent entry's two halves — the check half straight into the decorator, the
 * stage half as a round-executor factory's binding — so no binding site hands out an executor bound to
 * an environment without the probe.
 *
 * <p>A whole-tree text scan, comments stripped, that asserts it reached every listed file.
 *
 * <p>FR15 of extract-agent-round-runner.
 */
class GuardProbeOwnerSpec extends Specification {

    private static final String AGENT = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/'
    private static final String OWNER = AGENT + 'program/GuardedExecutor.java'
    private static final String STAGE_HALF = AGENT + 'program/ProgramStageExecutor.java'
    private static final String CHECK_HALF = AGENT + 'program/ProgramCheckBinding.java'
    /** The one choice of the operation a stage round sends (D11): it sends to the executor it is handed. */
    private static final String TURN_RUN = AGENT + 'program/PendingTurnRun.java'
    /** The check half of the {@code claude} entry (task 4.0a). */
    private static final String AGENT_CHECK_HALF = AGENT + 'runner/AgentCheckBinding.java'
    /** The stage half of the {@code claude} entry: a round-executor factory the stage wrapper decorates. */
    private static final String AGENT_STAGE_HALF = AGENT + 'runner/AgentStageRounds.java'
    private static final String DECLARED = 'sandbox/core/src/main/java/com/github/oinsio/gnomish/sandbox/TaskExecutionEnvironment.java'
    private static final String SELF_CHECKED =
    'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/SelfCheckedEnvironment.java'
    private static final String LEASED =
    'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/LeasedEnvironment.java'
    private static final String OBSERVED = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/conformance/WireObservation.java'

    @Shared
    List<File> sources = RepoSourceTree.productionSources()

    def "the scan reached the tree, and every listed file with it"() {
        expect:
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect {
            RepoSourceTree.relative(it)
        }.containsAll([
            OWNER,
            STAGE_HALF,
            CHECK_HALF,
            TURN_RUN,
            AGENT_CHECK_HALF,
            AGENT_STAGE_HALF,
            DECLARED,
            SELF_CHECKED,
            LEASED,
            OBSERVED
        ])
    }

    // Scenario: sandbox/sandbox-egress / A stage round and a check round are probed by one owner
    def "FR15, M6: '#form' is spelled in production only by #owners"() {
        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form || owners
        // the owner and the environment types that declare or forward it (M6)
        'probeGuard(' || [
            OWNER,
            DECLARED,
            SELF_CHECKED,
            LEASED,
            OBSERVED
        ]
        // the decorator is applied where the stage half and the check halves bind, and nowhere else
        'GuardedExecutor.over(' || [
            STAGE_HALF,
            CHECK_HALF,
            AGENT_CHECK_HALF
        ]
        // an agent runner is bound by the claude entry's two halves alone (task 4.0a)
        'runners.runner(' || [
            AGENT_CHECK_HALF,
            AGENT_STAGE_HALF
        ]
    }

    def "FR15: the owner probes before and after each request, and the stage half binds through it"() {
        given:
        def owner = code(OWNER)
        def stage = code(STAGE_HALF)

        expect: 'one probe site in the owner, reached before and after both start and poll'
        owner.count('probeGuard(') == 1
        owner.count('refusedBefore()') == 3
        owner.count('checkedAfter(') == 3

        and: "the factory's binding of the open round is taken only as the decorator's argument and as its adoption role (D11)"
        def flat = stage.replaceAll(/\s+/, ' ')
        stage.count('executors.executor(') == 1
        flat.contains('Executor bound = executors.executor(round);')
        flat.contains('GuardedExecutor.over(subject, name, round.environment(), bound);')
        flat.contains('PendingAdoption adoption = bound instanceof PendingAdoption role ? role : null;')
        stage.findAll(/\bbound\b/).size() == 3

        and: 'the decorated executor alone is sent the request, by the one choice of start or poll (D11)'
        stage.count('.start(') == 0
        stage.count('.poll(') == 0
        stage.count('PendingTurnRun.run(') == 1
        stage.contains('PendingTurnRun.run(executor, adoption, contractRequest, turn)')

        and: 'the choice sends to the executor it is handed and to nothing else'
        def turnRun = code(TURN_RUN)
        turnRun.count('.start(') == 1
        turnRun.contains('executor.start(request)')
        turnRun.count('.poll(') == 1
        turnRun.contains('executor.poll(request, turn.key())')
    }

    def "FR15: the claude entry's check half hands the runner it binds straight to the decorator"() {
        given:
        def check = code(AGENT_CHECK_HALF).replaceAll(/\s+/, ' ')

        expect:
        check.count('runners.runner(') == 1
        check.contains('GuardedExecutor.over( ProgramFailures.Subject.AGENT, runners.name(), environment,'
                + ' runners.runner(environment))')
    }

    private String code(String path) {
        RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == path
        })
    }
}
