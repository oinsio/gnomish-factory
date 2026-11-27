package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * FR13, NFR-O2, UX3 of define-executor-contract (design D11; task 6.1); NFR-O1, FR14 of
 * extract-agent-round-runner (D12): what the describe preflight obtained for a run is every described
 * executor's version; which executor a block runs is the registry entry it resolves to, so the round
 * writer's pin, the name a record carries and the poll decision come from one derivation.
 */
class DescribedExecutorsSpec extends Specification {

    static final UntrustedText V2 = UntrustedText.subprocess('2')
    static final UntrustedText BUILT_IN = UntrustedText.factory('built-in')

    static final DescribedExecutors DESCRIBED = new DescribedExecutors(
    new ExecutorVersions(acme: V2, lint: UntrustedText.subprocess('5')), ExecutorCredentialNames.none(), [] as Set)

    static StageDefinition stage(ExecutorType type, String name) {
        new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(type, 'm', [:], Sandbox.none(), name),
        'i.md', [], new AutonomyLimits(1), AdvancementMode.AUTO)
    }

    // FR13: a stage's round pins only the executor the stage names
    def "a stage naming a described executor runs exactly that executor at its version"() {
        expect:
        DESCRIBED.ranBy(stage(ExecutorType.PROGRAM, 'acme')) == new ExecutorVersions(acme: V2)
        DESCRIBED.executorOf(stage(ExecutorType.PROGRAM, 'acme')) == new RoundExecutor('acme', V2)
    }

    // FR13: nothing to pin for an undescribed name; the round still carries the law's name
    def "a stage naming an undescribed executor pins nothing and is named unversioned"() {
        expect:
        DESCRIBED.ranBy(stage(ExecutorType.PROGRAM, 'ghost')) == ExecutorVersions.none()
        DESCRIBED.executorOf(stage(ExecutorType.PROGRAM, 'ghost')) == new RoundExecutor('ghost', null)
    }

    // NFR-O1, FR14 of extract-agent-round-runner (D12, "The name in the record"): a block is named by
    //     the registry entry dispatch resolves it to, a built-in at the built-in version
    def "a #type block is named #name at the built-in version"() {
        expect:
        DESCRIBED.executorOf(stage(type, null)) == new RoundExecutor(name, BUILT_IN)

        where:
        type || name
        ExecutorType.AGENT_CLI || BuiltInExecutors.CLAUDE
        ExecutorType.API || BuiltInExecutors.CLAUDE
        ExecutorType.COMMAND || BuiltInExecutors.COMMAND
    }

    // NFR-O1 of extract-agent-round-runner (D12, "The version of a built-in"): shown, never pinned —
    //     even a run whose versions held the entry's name pins no built-in
    def "a built-in's round pins nothing: #type"() {
        given:
        def holding = new DescribedExecutors(new ExecutorVersions(claude: V2, command: V2), ExecutorCredentialNames.none(),
        [] as Set)

        expect:
        holding.ranBy(stage(type, null)) == ExecutorVersions.none()
        holding.executorOf(stage(type, null)).version() == BUILT_IN

        where:
        type << [
            ExecutorType.AGENT_CLI,
            ExecutorType.COMMAND
        ]
    }

    // NFR-O1 of extract-agent-round-runner: a check is named as a round is — its entry and that entry's version
    def "a check naming #name is named #expected"() {
        expect:
        DESCRIBED.executorOf(check(name)) == expected

        where:
        name || expected
        'acme' || new RoundExecutor('acme', V2)
        'ghost' || new RoundExecutor('ghost', null)
        BuiltInExecutors.SHELL || new RoundExecutor(BuiltInExecutors.SHELL, BUILT_IN)
        BuiltInExecutors.CLAUDE || new RoundExecutor(BuiltInExecutors.CLAUDE, BUILT_IN)
    }

    static VerifyCheck.Executor check(String name) {
        VerifyChecks.external('ci', name, Duration.ZERO, Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY)
    }

    def "none describes nothing"() {
        expect:
        DescribedExecutors.none().all() == ExecutorVersions.none()
        DescribedExecutors.none().ranBy(stage(ExecutorType.PROGRAM, 'acme')) == ExecutorVersions.none()
    }

    // FR10 of extract-agent-round-runner (D11): a stage's pending is polled only when the executor it
    //     names was described as declaring poll
    def "polls is true exactly for a stage naming a described executor that declares poll: #name"() {
        given:
        def described = new DescribedExecutors(DESCRIBED.all(), ExecutorCredentialNames.none(), ['acme'] as Set)

        expect:
        described.polls(stage(type, name)) == polls

        where:
        type | name || polls
        ExecutorType.PROGRAM | 'acme' || true
        ExecutorType.PROGRAM | 'lint' || false
        ExecutorType.PROGRAM | 'ghost' || false
        ExecutorType.AGENT_CLI | null || false
        ExecutorType.COMMAND | null || false
    }

    def "the pollable names are copied and none declares none"() {
        given:
        def names = ['acme'] as Set
        def described = new DescribedExecutors(DESCRIBED.all(), ExecutorCredentialNames.none(), names)

        when:
        names << 'lint'

        then:
        described.pollable() == ['acme'] as Set
        DescribedExecutors.none().pollable().isEmpty()
        !DescribedExecutors.none().polls(stage(ExecutorType.PROGRAM, 'acme'))
    }
}
