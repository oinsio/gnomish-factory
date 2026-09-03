package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testsupport.LogCallSites
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR14 of harden-logging-observability: the four {@code :domain} emitters of ADR 0004's accepted
 * deviation 1 carry their operator-event code as a literal message head, because {@code :domain}
 * must not acquire a {@code :logtext} edge just to reach the catalog. A literal is a copy, and a
 * copy drifts — so this spec is the pair's round-trip check, in the shape
 * {@code .claude/rules/testing.md} prescribes for a wire vocabulary: every literal head resolves
 * to its catalog constant, and every constant listed here is really emitted by the class named
 * beside it. Deleting either side goes red.
 *
 * <p>Lives in {@code :bootstrap} because it needs both ends at once — the {@code :domain} source
 * tree (through the {@code repoRoot} this module's {@code test} task wires) and the {@code
 * :logtext} catalog — and no module below sees both.
 */
class DomainOperatorEventHeadSpec extends Specification {

    /** The pair: the domain emitter, and the catalog constant whose code it repeats literally. */
    private static final Map<String, OperatorEvent> PINNED = [
        'AttemptJournal': OperatorEvent.ATTEMPT_PERSIST_FAILED,
        'Events': OperatorEvent.ENGINE_EVENT_LISTENER_THREW,
        'RoundExecution': OperatorEvent.EXECUTOR_THREW,
        'VerifyOrchestrator': OperatorEvent.CHECK_ADAPTER_THREW,
    ]

    /** A code head as it appears in a message literal: {@code "[GF042] …}. */
    private static final Pattern HEAD = Pattern.compile('"\\[(GF\\d{3})\\] ')

    /** Only the operator plane carries codes; the domain's INFO/DEBUG lines are none of this. */
    private static final Pattern OPERATOR_CALL = Pattern.compile('\\.(?:warn|error|atWarn|atError)\\s*\\(')

    // FR14: each recorded domain emitter really renders its catalog constant's head.
    def "#emitter emits the literal head of #event"() {
        given:
        def heads = operatorCallHeads(emitter)

        expect: 'exactly one operator line in the class, carrying exactly that code'
        heads == [event.code()]

        where:
        emitter << PINNED.keySet()
        event << PINNED.values()
    }

    // The back direction: a literal that names no catalog entry, or an entry no longer emitted.
    def "every literal head in the domain resolves to its catalog constant"() {
        given: 'every code the domain sources actually spell out'
        def emitted = PINNED.keySet().collectMany { operatorCallHeads(it) }

        expect: 'the scan really reached the four sources rather than passing over an empty set'
        emitted.size() == PINNED.size()

        and: 'and every code found is a catalog code, resolved back to the constant that owns it'
        emitted.every { code ->
            OperatorEvent.values().count { it.code() == code } == 1
        }

        and: 'and the two sides name the same set — a deleted constant or literal fails here'
        emitted.toSet() == PINNED.values().collect { it.code() }.toSet()
    }

    // The domain's operator plane is exactly these four lines; a fifth needs its own catalog
    //     entry and its own row above, the same way ADR 0004 pins the logger list itself.
    def "the domain has no operator line beyond the recorded four"() {
        given:
        def domainCalls = RepoSourceTree.productionSources {
            it.startsWith('domain/')
        }
        .collectMany { file ->
            LogCallSites.inSource(RepoSourceTree.code(file), RepoSourceTree.relative(file))
        }
        .findAll { OPERATOR_CALL.matcher(it.text).find() }

        expect:
        domainCalls.size() == PINNED.size()
    }

    /** The codes spelled out by the operator-level log calls of one domain class. */
    private static List<String> operatorCallHeads(String simpleName) {
        RepoSourceTree.productionSources {
            it.startsWith('domain/') && it.endsWith("/${simpleName}.java")
        }
        .collectMany { file ->
            LogCallSites.inSource(RepoSourceTree.code(file), RepoSourceTree.relative(file))
        }
        .findAll { OPERATOR_CALL.matcher(it.text).find() }
        .collectMany { call ->
            def matcher = HEAD.matcher(call.text)
            def found = []
            while (matcher.find()) {
                found << matcher.group(1)
            }
            found
        }
    }
}
