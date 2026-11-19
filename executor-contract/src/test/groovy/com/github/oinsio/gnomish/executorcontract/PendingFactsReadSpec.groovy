package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.github.oinsio.gnomish.pendingkey.PendingKey
import spock.lang.Specification

/**
 * FR2, NFR-R2 of migrate-verify-to-executor-contract (design D19): a {@code pending} result may carry
 * the wait hint {@code pollAfterMillis} and the pending state {@code pendingState}; either field on
 * another status, and a hint that is no integer from 0 to 86 400 000, fail closed — on the wire as
 * {@code failed/infrastructure} naming the field, in process as a refused construction — and the
 * schema refuses the same documents.
 */
class PendingFactsReadSpec extends Specification {

    private static ExecutorResult read(String json) {
        WireJson.readValue(json, ExecutorResult)
    }

    // Scenario: executor/wire-contract / Misplaced or out-of-range pending fields fail closed
    def "FR2, NFR-R2: #why reads as failed/infrastructure naming the field, the executor's reason after it"() {
        when:
        def result = read(json)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.pollAfterMillis() == null && result.pendingState() == null
        result.reason().raw() == reason

        and: 'the schema refuses the same document'
        !WireSchemas.judge(WireDocument.RESULT, json).isEmpty()

        where:
        why | json || reason
        'a negative hint' | pending('"pollAfterMillis":-1') || 'pollAfterMillis is not an integer from 0 to 86400000: -1: r'
        'a hint past one day' | pending('"pollAfterMillis":86400001') || 'pollAfterMillis is not an integer from 0 to 86400000: 86400001: r'
        'a fractional hint' | pending('"pollAfterMillis":1.5') || 'pollAfterMillis is not an integer from 0 to 86400000: 1.5: r'
        'a hint in words' | pending('"pollAfterMillis":"soon"') || 'pollAfterMillis is not an integer from 0 to 86400000: "soon": r'
        'a hint past a long' | pending('"pollAfterMillis":99999999999999999999') || 'pollAfterMillis is not an integer from 0 to 86400000: 99999999999999999999: r'
        'a state on completed' | '{"status":"completed","pendingState":"running","reason":"r","usage":{"provenance":"ABSENT"}}' || 'pendingState on a completed result: r'
        'a hint on completed' | '{"status":"completed","pollAfterMillis":5,"reason":"r","usage":{"provenance":"ABSENT"}}' || 'pollAfterMillis on a completed result: r'
        'a state on failed' | '{"status":"failed","failureClass":"quality","pendingState":"unknown","reason":"r","usage":{"provenance":"ABSENT"}}' || 'pendingState on a failed result: r'
    }

    def "FR2: a pending result carries its hint #hint and its state #token as read"() {
        when:
        def result = read(pending("\"pollAfterMillis\":${hint},\"pendingState\":\"${token}\""))

        then:
        result.status() == Status.PENDING
        result.pendingKey() == new PendingKey('k')
        result.pollAfterMillis() == hint
        result.pendingState() == state
        result.reason() == t('r')

        where:
        hint | token || state
        0L | 'running' || PendingState.RUNNING
        86_400_000L | 'unreported' || PendingState.UNREPORTED
        1L | 'unobserved' || PendingState.UNOBSERVED
        30_000L | 'stalled' || null
    }

    def "FR2: a pending state that is no string, or a JSON null, reads as no state; a null hint as no hint"() {
        expect:
        read(pending('"pendingState":7')).pendingState() == null
        read(pending('"pendingState":null,"pollAfterMillis":null')).with {
            status() == Status.PENDING && pendingState() == null && pollAfterMillis() == null
        }
    }

    def "FR2, NFR-R2: the record refuses #why"() {
        when:
        new ExecutorResult(status, failureClass, key, null, null, Usage.absent(), [], hint, state, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(named)

        where:
        why | status | failureClass | key | hint | state || named
        'a hint on completed' | Status.COMPLETED | null | null | 5L | null || 'pollAfterMillis'
        'a state on failed' | Status.FAILED | FailureClass.QUALITY | null | null | PendingState.RUNNING || 'pendingState'
        'a negative hint' | Status.PENDING | null | new PendingKey('k') | -1L | null || 'got -1'
        'a hint past one day' | Status.PENDING | null | new PendingKey('k') | 86_400_001L | null || 'got 86400001'
    }

    def "FR2: the record takes the bounds of the range on pending"() {
        expect:
        new ExecutorResult(Status.PENDING, null, new PendingKey('k'), null, null, Usage.absent(), [], hint, null, null)
        .pollAfterMillis() == hint

        where:
        hint << [
            0L,
            ExecutorResult.MAX_POLL_AFTER_MILLIS
        ]
    }

    def "FR2: a copy with another answer or usage keeps the pending facts and the details"() {
        given:
        def result = new ExecutorResult(Status.PENDING, null, new PendingKey('k'), t('r'), null, Usage.absent(), [],
        7L, PendingState.UNOBSERVED, t('d'))
        def usage = Usage.knownZero()
        def answer = WireJson.readTree('{"kind":"select","chosen":"a"}')

        expect:
        result.withUsage(usage) == new ExecutorResult(Status.PENDING, null, new PendingKey('k'), t('r'), null, usage, [],
        7L, PendingState.UNOBSERVED, t('d'))
        result.withAnswer(answer) == new ExecutorResult(Status.PENDING, null, new PendingKey('k'), t('r'), answer,
                Usage.absent(), [], 7L, PendingState.UNOBSERVED, t('d'))
    }

    def "FR2: the schema refuses a #field of the wrong kind on pending, at its path"() {
        when:
        def violations = WireSchemas.judge(WireDocument.RESULT, pending(member))

        then:
        violations*.path.contains(field)

        where:
        field | member
        'pollAfterMillis' | '"pollAfterMillis":-1'
        'pollAfterMillis' | '"pollAfterMillis":"soon"'
        'pendingState' | '"pendingState":"stalled"'
    }

    private static String pending(String members) {
        '{"status":"pending","pendingKey":"k","reason":"r","usage":{"provenance":"ABSENT"},' + members + '}'
    }
}
