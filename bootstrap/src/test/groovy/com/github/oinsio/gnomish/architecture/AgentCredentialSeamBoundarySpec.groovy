package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR5, NFR-S3 of fix-operator-blockers (design D3, single-owner row 2): the agent CLI's own
 * credentials reach a process through the AI seam and nowhere else.
 *
 * <p>{@code AgentAiSeam} is applied by the two round executions only — an agent round and a judge
 * vote — which is exactly the reach NFR-S3 allows. A second production source that reads or sets
 * {@code CLAUDE_CODE_OAUTH_TOKEN} or {@code ANTHROPIC_API_KEY} would be a second route, with no
 * guarantee it stays off {@code command} checks; this whole-tree scan is what keeps the seam the
 * only one. It covers the two trees that spawn processes, {@code adapters/} and {@code sandbox/},
 * and asserts it reached both, so a moved tree fails loudly instead of passing over nothing.
 * {@code :bootstrap} owns it for the reason {@link ClaimlessGitBoundarySpec} gives: it is the one
 * module that sees every layer at once.
 */
class AgentCredentialSeamBoundarySpec extends Specification {

    /** The two trees whose production sources launch agent and command processes. */
    private static final List<String> SCANNED_TREES = ['adapters/', 'sandbox/']

    /** The seam: the only production source allowed to spell the credential names. */
    private static final String OWNER =
    'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/AgentAiSeam.java'

    private static final List<String> CREDENTIAL_NAMES = [
        'CLAUDE_CODE_OAUTH_TOKEN',
        'ANTHROPIC_API_KEY'
    ]

    // FR5, NFR-S3: comments are stripped — a javadoc may name the variables; only code that the
    //     compiler sees can read or set one.
    def "FR5, NFR-S3: only AgentAiSeam spells the agent credential names under adapters/ and sandbox/"() {
        given: 'every production source of the two trees, as the compiler sees it'
        def sources = RepoSourceTree.productionSources { path ->
            SCANNED_TREES.any { path.startsWith(it) }
        }
        def code = sources.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }

        expect: 'the scan reached both trees'
        SCANNED_TREES.every { tree ->
            code.keySet().any {
                it.startsWith(tree)
            }
        }

        and: 'the owner really spells every name, so a renamed or moved seam fails here'
        CREDENTIAL_NAMES.every { code[OWNER]?.contains("\"${it}\"") }

        and: 'no other production source spells any of them'
        code.findAll { path, text ->
            path != OWNER && CREDENTIAL_NAMES.any { text.contains(it) }
        }.keySet().isEmpty()
    }
}
