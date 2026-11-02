package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.fasterxml.jackson.databind.node.NullNode
import spock.lang.Specification

/**
 * FR2, NFR-R3 of define-executor-contract: reading a result applies the unknown-token arms of the
 * status taxonomy — anything the taxonomy cannot name fails closed as {@code failed/infrastructure}
 * with a reason naming what was wrong — and the result's own invariants refuse a document no reader
 * should build.
 */
class ExecutorResultReadSpec extends Specification {

    private static ExecutorResult read(String json) {
        WireJson.readValue(json, ExecutorResult)
    }

    def "FR2: #json reads as failed/infrastructure with the reason '#reason'"() {
        when:
        def result = read(json)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.pendingKey() == null
        result.reason()?.raw() == reason

        where:
        json || reason
        '{"status":"done","reason":"x","usage":{"provenance":"ABSENT"}}' || "unknown status 'done': x"
        '{"usage":{"provenance":"ABSENT"}}' || 'the result has no status'
        '{"status":"failed","usage":{"provenance":"ABSENT"}}' || 'status failed without a failureClass'
        '{"status":"failed","failureClass":"oops","reason":"r"}' || "unknown failureClass 'oops': r"
        '{"status":"pending"}' || 'status pending without a pendingKey'
        '{"status":"pending","pendingKey":"  "}' || 'status pending without a pendingKey'
    }

    // Scenario: executor/wire-contract / Pending with an unusable key fails closed
    def "FR2, NFR-R3: a pending key that #why reads as failed/infrastructure naming the unusable key"() {
        when:
        def result = read(WireJson.writeValueAsString([status: 'pending', pendingKey: key, reason: 'r']))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.pendingKey() == null
        result.reason()?.raw() == "status pending with an unusable pendingKey: ${refusal}: r"

        where:
        why | key || refusal
        'holds a NUL byte' | 'job\u00001' || 'it holds a control character'
        'holds a newline' | 'job\n1' || 'it holds a control character'
        'holds a C1 control' | 'job\u00851' || 'it holds a control character'
        'is longer than the cap' | 'k' * (PendingKey.MAX_LENGTH + 1) || "it is longer than ${PendingKey.MAX_LENGTH} characters"
    }

    def "FR2: a pending key of exactly the cap, printable beyond ASCII, reads as itself"() {
        given:
        def key = 'ключ-' + 'k' * (PendingKey.MAX_LENGTH - 5)

        expect:
        read(WireJson.writeValueAsString([status: 'pending', pendingKey: key])).pendingKey() == new PendingKey(key)
    }

    def "FR2: a known status reads as itself, keeping its own reason, and a stray field of another status is dropped"() {
        expect:
        read('{"status":"completed","failureClass":"quality","pendingKey":"k","reason":"ok"}') ==
                new ExecutorResult(Status.COMPLETED, null, null, t('ok'), null, Usage.absent(), [])
        read('{"status":"failed","failureClass":"limit","pendingKey":"k","reason":"turns"}') ==
                new ExecutorResult(Status.FAILED, FailureClass.LIMIT, null, t('turns'), null, Usage.absent(), [])
        read('{"status":"pending","pendingKey":"k","failureClass":"quality"}') ==
                new ExecutorResult(Status.PENDING, null, new PendingKey('k'), null, null, Usage.absent(), [])
    }

    def "FR1: an absent usage reads as ABSENT, never as zero; absent denials read as none"() {
        when:
        def result = read('{"status":"completed"}')

        then:
        result.usage() == Usage.absent()
        result.denials() == []
    }

    def "FR3: a malformed answer never fails the read of its result; its typed reading is Malformed"() {
        when:
        def result = read('{"status":"completed","answer":{"kind":"decision-request","question":"q?","options":["only"]}}')

        then:
        result.status() == Status.COMPLETED
        WireJson.answer(result.answer()) instanceof AnswerReading.Malformed
    }

    def "FR3: the answer is carried unread, and a JSON null answer reads as no answer"() {
        expect:
        read('{"status":"completed","answer":"not an object"}').answer().asText() == 'not an object'
        read('{"status":"completed","answer":null}').answer() == null
        read('{"status":"failed","failureClass":"x","answer":{"kind":"select"}}').answer().path('kind').asText() == 'select'
    }

    def "FR2: the record refuses #why"() {
        when:
        new ExecutorResult(status, failureClass, key, null, null, Usage.absent(), [])

        then:
        thrown(IllegalArgumentException)

        where:
        why | status | failureClass | key
        'failed with no class' | Status.FAILED | null | null
        'a class on completed' | Status.COMPLETED | FailureClass.QUALITY | null
        'pending with no key' | Status.PENDING | null | null
        'a key on completed' | Status.COMPLETED | null | new PendingKey('k')
    }

    def "FR1: the denials list is copied, so the caller's later change does not reach the result"() {
        given:
        def denials = [
            new Denial(t('d'), null, null)
        ]
        def result = new ExecutorResult(Status.COMPLETED, null, null, null, NullNode.instance, Usage.absent(), denials)

        when:
        denials.clear()

        then:
        result.denials().size() == 1
    }
}
