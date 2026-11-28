package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.executorcontract.Bounds
import spock.lang.Specification

/**
 * FR11, D7 of add-agent-executor; NFR-C1 of extract-agent-round-runner: {@code --model} always
 * renders first from first-class manifest data, then the limit hints — {@code --max-turns} from the
 * tighter of the request's turn bound and the legacy {@code maxTurns} setting, {@code
 * --max-budget-usd} from the request's money bound — each omitted when absent. The tool sets and
 * the permission mode belong to {@link ClaudePolicyCompiler} and never render here.
 */
class AgentInvocationOptionsSpec extends Specification {

    def "FR11: the model always renders first as --model, and nothing else renders with no settings and no bounds"() {
        expect:
        AgentInvocationOptions.render('claude-sonnet-4-5', [:], Bounds.none()) == [
            '--model',
            'claude-sonnet-4-5'
        ]
    }

    def "FR11: a maxTurns setting renders as --max-turns, accepting any integral number"() {
        expect:
        AgentInvocationOptions.render('m', [maxTurns: maxTurns], Bounds.none()) == [
            '--model',
            'm',
            '--max-turns',
            '5'
        ]

        where:
        maxTurns << [
            5,
            5L,
            5 as Integer,
            5 as Long
        ]
    }

    def "NFR-C1: the turn hint is the tighter of the request's turn bound and the maxTurns setting"() {
        expect:
        AgentInvocationOptions.render('m', settings, bounds(turns, null)) == [
            '--model',
            'm',
            '--max-turns',
            expected
        ]

        where:
        settings | turns || expected
        [:] | 9L || '9'
        [maxTurns: 3] | 9L || '3'
        [maxTurns: 12]| 9L || '9'
        [maxTurns: 9] | 9L || '9'
    }

    def "NFR-C1: the money bound renders as --max-budget-usd in plain decimal notation"() {
        expect:
        AgentInvocationOptions.render('m', [:], bounds(null, new BigDecimal(money))) ==
        [
            '--model',
            'm',
            '--max-budget-usd',
            expected
        ]

        where:
        money || expected
        '2.5' || '2.5'
        '1E+1' || '10'
        '0.01' || '0.01'
    }

    def "FR11: roundTimeout, the tool lists and an unrecognized key never render here"() {
        expect:
        AgentInvocationOptions.render('m', [roundTimeout: '30s', allowedTools: ['Read'], disallowedTools: ['Bash'],
            temperature: 0, maxTurns: 'many'], Bounds.none()) == ['--model', 'm']
    }

    private static Bounds bounds(Long turns, BigDecimal money) {
        new Bounds(turns, null, money, null, null, null)
    }
}
