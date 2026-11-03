package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import spock.lang.Specification

/**
 * FR1, FR11 of define-executor-contract: the request's values refuse what their schema refuses — a
 * role without the inputs it needs, an unknown role, a bound outside its range, a
 * blank text where the schema wants one.
 */
class RequestValuesSpec extends Specification {

    def "FR1: role #role refuses #inputs and accepts #accepted"() {
        when:
        WireSamples.request(role, inputs)

        then:
        thrown(IllegalArgumentException)

        when:
        def request = WireSamples.request(role, accepted)

        then:
        request.roleInputs() == accepted

        where:
        role | inputs | accepted
        Role.TRANSFORM | RoleInputs.verdict('c') | RoleInputs.transform('i')
        Role.VERDICT | RoleInputs.transform('i') | RoleInputs.verdict('c')
        Role.DECISION | RoleInputs.decision('q', ['only']) | RoleInputs.decision('q', ['a', 'b'])
        Role.DECISION | new RoleInputs(null, null, null, ['a', 'b']) | RoleInputs.decision('q', ['a', 'b'])
    }

    def "FR1: effect takes no inputs"() {
        expect:
        WireSamples.request(Role.EFFECT, RoleInputs.none()).roleInputs() == new RoleInputs(null, null, null, [])
    }

    def "FR1: a request naming an unknown role is refused"() {
        given:
        def json = WireJson.writeValueAsString(WireSamples.request(Role.VERDICT, RoleInputs.verdict('c')))

        when:
        WireJson.readValue(json.replace('"verdict"', '"review"'), ExecutorRequest)

        then:
        def refusal = thrown(ValueInstantiationException)
        refusal.message.contains('role is unknown or absent')
    }

    def "FR1: a blank #field is refused"() {
        when:
        build()

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "${field} must not be blank"

        where:
        field | build
        'workspace.path' | { new Workspace(' ') }
        'policy.path' | { new Policy.WriteExactly('') }
        'continuation.key' | { new Continuation(' ', 'r') }
        'pendingKey' | { new PendingKey('') }
        'roleInputs.instructions' | { RoleInputs.transform(' ') }
        'roleInputs.criteria' | { RoleInputs.verdict(' ') }
        'roleInputs.question' | { RoleInputs.decision(' ', ['a', 'b']) }
        'roleInputs.options[]' | { RoleInputs.decision('q', ['a', ' ']) }
    }

    def "FR1: the feedback and option lists are copied"() {
        given:
        def feedback = [new Finding('f', null, null)]
        def options = ['a', 'b']
        def request = new ExecutorRequest(Role.DECISION, new Workspace('/w'), new Policy.ReadOnly(),
                RoleInputs.decision('q', options), WireSamples.object('{}'), Bounds.none(), feedback, null)

        when:
        feedback.clear()
        options.clear()

        then:
        request.feedback().size() == 1
        request.roleInputs().options() == ['a', 'b']
    }

    def "NFR-R2: bounds accept the least value of each"() {
        expect:
        new Bounds(1L, 1L, new BigDecimal('0.01'), 1L, 1L, 1L).turns() == 1L
    }

    def "NFR-R2: bounds refuse #why"() {
        when:
        new Bounds(*refused)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == message

        where:
        why | refused || message
        'zero turns' | [
            0L,
            null,
            null,
            null,
            null,
            null
        ] || 'bounds.turns must be at least 1, was 0'
        'zero tokens' | [
            null,
            0L,
            null,
            null,
            null,
            null
        ] || 'bounds.tokens must be at least 1, was 0'
        'zero startup' | [
            null,
            null,
            null,
            0L,
            null,
            null
        ] || 'bounds.startupReadMillis must be at least 1, was 0'
        'zero silence' | [
            null,
            null,
            null,
            null,
            0L,
            null
        ] || 'bounds.silenceMillis must be at least 1, was 0'
        'zero overall' | [
            null,
            null,
            null,
            null,
            null,
            0L
        ] || 'bounds.overallMillis must be at least 1, was 0'
        'zero money' | [
            null,
            null,
            BigDecimal.ZERO,
            null,
            null,
            null
        ] || 'bounds.moneyUsd must be above 0, was 0'
    }
}
