package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR7, D10 of add-agent-executor: the factory subscribes one listener to its agent rounds, the
 * shipped SLF4J renderer, and nothing else. A listener is a constructor argument of the two CLI
 * adapters, so the subscription is a construction site in production code; this scan finds every
 * one in the whole tree and reads the listener it passes. A second subscriber (a console, a
 * metrics hook) would have to be a new argument here, and fails the gate instead of passing as a
 * green wiring nobody asked for.
 *
 * <p>The per-round listener a sandboxed round source adds (the mid-round harvest and push
 * polls) is the round environment's own mechanism, not a subscription the factory makes, and is
 * not a construction argument of either adapter.
 */
class ProgressListenerWiringSpec extends Specification {

    static final String SELECTOR = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ExecutorAdapterSelector.java'
    static final String RENDERER = 'new LoggingAgentProgressListener()'
    static final List<String> ADAPTERS = [
        'new CliStageExecutor(',
        'new CliJudgeVoter('
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
            ADAPTERS.collectMany { adapter ->
                def sites = []
                int at = code.indexOf(adapter)
                while (at >= 0) {
                    sites << [file: RepoSourceTree.relative(file), args: arguments(code, at + adapter.length())]
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

        then: 'the scan reached the composition root: two executor sites and the judge site'
        sites*.file.unique() == [SELECTOR]
        sites.size() == 3

        and: 'each passes the renderer as its listener, and no other listener is built at the site'
        sites.every { site -> site.args[2] == RENDERER }
        sites.every { site -> site.args.count { it.contains('Listener') } == 1 }
    }

    // The argument reader is the gate's own parser — a nested call must stay one argument
    def "the argument reader keeps nested calls whole"() {
        given:
        def code = 'x = new CliJudgeVoter(a, f(b, c), new LoggingAgentProgressListener(), d);'

        expect:
        arguments(code, code.indexOf('(') + 1) == [
            'a',
            'f(b, c)',
            'new LoggingAgentProgressListener()',
            'd'
        ]
    }
}
