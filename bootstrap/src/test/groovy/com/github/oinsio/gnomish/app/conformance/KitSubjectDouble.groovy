package com.github.oinsio.gnomish.app.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.executorcontract.WireDocument
import com.github.oinsio.gnomish.executorcontract.WireViolation
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * An in-process {@link KitSubject} for the kit core's specs (FR8, FR12 of define-executor-contract):
 * its executor answers each request through {@link #answer} — by default as a conforming executor
 * would — keeps every request it was asked, and reports the breaches a spec planted per case. No
 * process, no wire: the core's decisions are reached directly.
 */
class KitSubjectDouble implements KitSubject {

    static final List<Role> LIVE = [
        Role.TRANSFORM,
        Role.VERDICT,
        Role.DECISION
    ]
    static final String SELECT_FIRST = '{"kind":"select","chosen":"first option"}'
    static final String VERDICT = '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[]}}'
    static final String QUESTION = '{"kind":"decision-request","question":"q","options":["a","b"]}'

    boolean wire = true
    Described described = new Described(describeOf(), tree('{"settingsSchema":{}}'), [], [])
    Closure<ExecutorResult> answer = { ExecutorRequest r, PendingKey k ->
        conforming(r)
    }
    Map<KitCase, List<WireBreach>> breaches = [:]
    List<Map> asked = []

    boolean onWire() {
        wire
    }

    Described describe() {
        described
    }

    CaseRun open(KitCase kase, Describe describe) {
        def subject = this
        def start = { ExecutorRequest r ->
            subject.asked << [kase: kase, op: 'start', request: r]; subject.answer(r, null)
        }
        def poll = { ExecutorRequest r, PendingKey k ->
            subject.asked << [kase: kase, op: 'poll', request: r, key: k]; subject.answer(r, k)
        }
        def executor = [describe: {
                describe
            }, start: start, poll: poll] as Executor
        def workspace = new Workspace("/w/${kase.word()}")
        [executor: {
                executor
            }, workspace: {
                workspace
            }, violations: {
                subject.breaches[kase] ?: []
            }, close: {}] as CaseRun
    }

    /** The keys of every poll the executor was asked, in the order asked. */
    List<String> polledKeys() {
        asked.findAll {
            it.op == 'poll'
        }*.key*.value()
    }

    /** What a conforming executor answers in the request's role. */
    static ExecutorResult conforming(ExecutorRequest r) {
        switch (r.role()) {
            case Role.VERDICT: return result(Status.COMPLETED, VERDICT)
            case Role.DECISION: return result(Status.COMPLETED, SELECT_FIRST)
            case Role.EFFECT: return failed('no such role')
            default: return result(Status.COMPLETED)
        }
    }

    static Describe describeOf(List<Role> roles = LIVE, List<Capability> capabilities = [Capability.POLL]) {
        new Describe(1, UntrustedText.subprocess('1'), roles, capabilities, JsonNodeFactory.instance.objectNode(), [], [],
        null, [], EnforcementLevel.PROMPT_ONLY, false)
    }

    static ExecutorResult result(Status status, String answer = null, String key = null) {
        new ExecutorResult(status, null, key == null ? null : new PendingKey(key), null,
                answer == null ? null : tree(answer), Usage.absent(), [])
    }

    static ExecutorResult failed(String reason) {
        new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null,
                reason == null ? null : UntrustedText.subprocess(reason), null, Usage.absent(), [])
    }

    static JsonNode tree(String json) {
        ExecutorContractJson.reader().readTree(json)
    }

    static WireBreach breach(WireDocument document = WireDocument.DESCRIBE) {
        new WireBreach(document, new WireViolation('enforcementLevel', 'enum', UntrustedText.subprocess('not in the enum')))
    }
}
