package com.github.oinsio.gnomish.testfixtures.executor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * An in-process {@link Executor}: the built-in the conformance kit drives through the mirror interface
 * alone (task 8.2a of define-executor-contract; design D2, D18, D19), the in-process twin of the
 * stand-in reference executor and its misbehaving fake.
 *
 * <p>Well behaved, it describes protocol 1, the three live roles and {@code poll}; answers {@code
 * transform} completed, {@code verdict} an empty SARIF log, {@code decision} a choice of the first
 * offered option; refuses a role it does not list ({@code failed}/infrastructure); and goes pending
 * by construction — a start in a working copy named {@code pending-poll} answers {@code pending} with
 * the key {@link #PENDING_KEY}, whatever the settings, and its poll answers the role. A poll with a key
 * it did not issue is refused.
 *
 * <p>{@link #breaking} gives the misbehaving variant: told the name of one conformance case, it breaks
 * that case only, recognising it as the stand-in fake does, by the request's workspace path ending in
 * {@code /<case>}; a wire-only case has nothing in process to break, so its run answers as the well
 * behaved double does. Every request, start and poll alike, is recorded.
 *
 * <p>Test fake; not production code, never PIT-mutated.
 */
class InProcessExecutorDouble implements Executor {

    /** The key a pending run is polled with. */
    static final String PENDING_KEY = 'in-process-deferred'

    /** The case names this double knows how to break in process. */
    static final List<String> BREAKABLE = [
        'describe',
        'settings-schema',
        'transform',
        'verdict',
        'decision',
        'unknown-role',
        'pending-poll',
        'malformed-answer'
    ]

    static final String VERDICT = '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"in-process"}},"results":[]}]}}'
    static final String ONE_OPTION = '{"kind":"decision-request","question":"Which build tool?","options":["gradle"]}'
    static final String REMOTE_SCHEMA = '{"$ref":"https://schemas.example/in-process-settings.json"}'

    /** The case this double breaks, or {@code null} for the well-behaved double. */
    final String broken

    /** Every operation asked of this double, in call order: {@code [op: 'start'|'poll', request: ...]}. */
    final List<Map> asked = [].asSynchronized()

    InProcessExecutorDouble() {
        this(null)
    }

    private InProcessExecutorDouble(String broken) {
        this.broken = broken
    }

    /**
     * The misbehaving variant, breaking the one case {@code kase} names.
     *
     * @param kase a conformance case's name, as its working copy is named
     */
    static InProcessExecutorDouble breaking(String kase) {
        new InProcessExecutorDouble(kase)
    }

    @Override
    Describe describe() {
        List<Role> roles = [
            Role.TRANSFORM,
            Role.VERDICT,
            Role.DECISION
        ]
        if (broken == 'describe') {
            // A role listed twice: the DTO holds it, the describe schema's uniqueItems does not, and every
            // role still runs, as with the stand-in fake's out-of-schema enforcement level.
            roles = roles + [Role.TRANSFORM]
        }
        ObjectNode schema = tree(broken == 'settings-schema' ? REMOTE_SCHEMA : '{"type":"object"}') as ObjectNode
        new Describe(1, UntrustedText.subprocess('1'), roles, [Capability.POLL], schema, [], [], null, [],
        EnforcementLevel.PROMPT_ONLY, false)
    }

    @Override
    ExecutorResult start(ExecutorRequest request) {
        asked << [op: 'start', request: request]
        String kase = caseOf(request)
        if (kase == 'pending-poll') {
            return new ExecutorResult(Status.PENDING, null, new PendingKey(PENDING_KEY), null, null, Usage.absent(), [])
        }
        answer(request, kase)
    }

    @Override
    ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        asked << [op: 'poll', request: request, key: key]
        if (key != new PendingKey(PENDING_KEY) || broken == 'pending-poll') {
            return failed("no pending run has the key '${key.value()}'")
        }
        answer(request, null)
    }

    private ExecutorResult answer(ExecutorRequest request, String kase) {
        boolean breaks = kase != null && kase == broken
        switch (request.role()) {
            case Role.TRANSFORM:
                return breaks && kase == 'transform' ? failed('the change was not made') : completed(null)
            case Role.VERDICT:
                return completed(breaks && kase == 'verdict' ? null : VERDICT)
            case Role.DECISION:
                if (breaks && kase == 'malformed-answer') {
                    return completed(ONE_OPTION)
                }
                String chosen = breaks && kase == 'decision' ? 'not-an-option' : request.roleInputs().options().first()
                ObjectNode select = tree('{"kind":"select"}') as ObjectNode
                select.put('chosen', chosen)
                return completed(select)
            default:
                return breaks && kase == 'unknown-role' ? completed(null) : failed('the request names no role this executor answers')
        }
    }

    private static String caseOf(ExecutorRequest request) {
        String path = request.workspace().path()
        path.substring(path.lastIndexOf('/') + 1)
    }

    private static ExecutorResult completed(Object answer) {
        JsonNode node = answer instanceof String ? tree(answer) : answer as JsonNode
        new ExecutorResult(Status.COMPLETED, null, null, null, node, Usage.absent(), [])
    }

    private static ExecutorResult failed(String reason) {
        new ExecutorResult(Status.FAILED, FailureClass.INFRASTRUCTURE, null, UntrustedText.subprocess(reason), null,
                Usage.absent(), [])
    }

    private static JsonNode tree(String json) {
        ExecutorContractJson.reader().readTree(json)
    }
}
