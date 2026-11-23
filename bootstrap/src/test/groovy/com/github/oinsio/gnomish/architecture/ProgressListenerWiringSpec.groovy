package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR7, D10 of add-agent-executor: the factory subscribes one listener to its agent rounds, the
 * shipped SLF4J renderer, and nothing else. A listener is a constructor argument of the {@code claude}
 * entry's runner construction, which every agent stage round (task 5.1a of extract-agent-round-runner)
 * and a judge check's round (task 4.1) run on, and of the CLI stage adapter task 5.1 deletes, which no
 * production path builds any more, so the subscription is a construction site in production code; this scan finds every
 * one in the whole tree and reads the listener it passes. A second subscriber (a console, a
 * metrics hook) would have to be a new argument here, and fails the gate instead of passing as a
 * green wiring nobody asked for.
 *
 * <p>The per-round listener a round source adds (the mid-round harvest and push polls) is the round
 * environment's own mechanism, not a subscription the factory makes: the stage half joins it to the
 * renderer per open round, and it is no construction argument of the runner construction.
 */
class ProgressListenerWiringSpec extends Specification {

    static final String CLAUDE_ENTRY = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ClaudeEntry.java'
    static final String RENDERER = 'new LoggingAgentProgressListener()'
    /** Each construction, with the index of its listener argument. */
    static final Map<String, Integer> ADAPTERS = [
        'new CliStageExecutor(': 2,
        'new AgentRunners<>(': 2
    ]

    /** The top-level arguments of the call whose opening parenthesis ends at {@code open}. */
    static List<String> arguments(String code, int open) {
        List<String> args = []
        int depth = 1
        int start = open
        for (int i = open; i <code.length(); i++) {
            char c = code.charAt(i)
            if (c == '(' as char) {
                depth++
            } else if (c == ')' as char && --depth == 0) {
                args << code.substring(start, i).trim()
                return args
            } else if (c == ',' as char && depth == 1) {
                args << code.substring(start, i).trim()
                start = i + 1
            }
        }
        throw new IllegalStateException('unbalanced call at ' + open)
    }

    /** Every adapter construction in production code: file, arguments. */
    static List<Map> constructions() {
        def sources = RepoSourceTree.productionSources()
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collectMany { file ->
            def code = RepoSourceTree.code(file)
            ADAPTERS.collectMany { adapter, listener ->
                def sites = []
                int at = code.indexOf(adapter)
                while (at >= 0) {
                    sites << [file: RepoSourceTree.relative(file), args: arguments(code, at + adapter.length()), listener: listener]
                    at = code.indexOf(adapter, at + 1)
                }
                sites
            }
        }
    }

    // FR7, D10: every round and vote the factory wires is subscribed to the renderer alone
    // Scenario: executor/agent-executor / The log renderer is the one shipped listener
    def "FR7, D10: every production construction of a CLI adapter subscribes the SLF4J renderer and no other listener"() {
        when:
        def sites = constructions()

        // Task 5.1a of extract-agent-round-runner: the type-keyed agent arm is gone, so the claude entry
        //     is the one construction, for stage rounds and judge checks alike.
        then: 'the scan reached the composition root: the claude entry, and no CLI adapter is built'
        sites*.file == [CLAUDE_ENTRY]

        and: 'each passes the renderer as its listener, and no other listener is built at the site'
        sites.every { site -> site.args[site.listener] == RENDERER }
        sites.every { site -> site.args.count { it.contains('Listener') } == 1 }
    }

    // The argument reader is the gate's own parser — a nested call must stay one argument
    def "the argument reader keeps nested calls whole"() {
        given:
        def code = 'x = new AgentRunners<>(a, f(b, c), new LoggingAgentProgressListener(), d);'

        expect:
        arguments(code, code.indexOf('(') + 1) == [
            'a',
            'f(b, c)',
            'new LoggingAgentProgressListener()',
            'd'
        ]
    }
}
