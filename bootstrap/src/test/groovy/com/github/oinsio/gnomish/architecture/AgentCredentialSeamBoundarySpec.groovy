package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR5, NFR-S3 of fix-operator-blockers (design D3, single-owner row 2); NFR-S1, design D8 of
 * extract-agent-round-runner (the credential row): the agent CLI's credentials are named by the
 * Claude dialect's describe and nowhere else.
 *
 * <p>{@code ClaudeEnvironment} holds the names the dialect's describe declares; the round resolves
 * exactly those through the run's allowlist, and only agent rounds and judge votes do, which is the
 * reach NFR-S3 allows. A second production source that spells one of them would be a second route,
 * with no guarantee it stays off {@code command} checks, so this whole-tree scan keeps the dialect the
 * only one. It covers every production source of the build and asserts it reached the two trees that
 * spawn processes, {@code adapters/} and {@code sandbox/}, so a moved tree fails loudly instead of
 * passing over nothing. {@code :bootstrap} owns it for the reason {@link ClaimlessGitBoundarySpec}
 * gives: it is the one module that sees every layer at once.
 */
class AgentCredentialSeamBoundarySpec extends Specification {

    /** The two trees whose production sources launch agent and command processes. */
    private static final List<String> SCANNED_TREES = ['adapters/', 'sandbox/']

    /** The dialect's environment: the only production source allowed to spell the credential names. */
    private static final String OWNER =
    'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/claude/ClaudeEnvironment.java'

    /** Every credential the dialect declares; {@code ANTHROPIC_} also catches a provider name added later. */
    private static final List<String> CREDENTIAL_NAMES = [
        'ANTHROPIC_BASE_URL',
        'ANTHROPIC_AUTH_TOKEN',
        'ANTHROPIC_MODEL',
        'CLAUDE_CODE_OAUTH_TOKEN',
        'ANTHROPIC_API_KEY'
    ]

    // FR5, NFR-S3: comments are stripped — a javadoc may name the variables; only code that the
    //     compiler sees can read or set one.
    // Scenario: executor/claude-dialect / No source names the credentials but the dialect
    def "NFR-S1: only the Claude dialect's environment spells the agent credential names in production code"() {
        given: 'every production source of the build, as the compiler sees it'
        def code = RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }

        expect: 'the scan reached both process-spawning trees'
        SCANNED_TREES.every { tree ->
            code.keySet().any {
                it.startsWith(tree)
            }
        }

        and: 'the owner sits in the dialect package and really spells every name, so a renamed or moved owner fails here'
        OWNER.contains('/adapter/agent/claude/')
        CREDENTIAL_NAMES.every { code[OWNER]?.contains("\"${it}\"") }

        and: 'no other production source spells any of them, nor any other provider variable'
        code.findAll { path, text ->
            path != OWNER && (text.contains('ANTHROPIC_') || CREDENTIAL_NAMES.any {
                text.contains(it)
            })
        }.keySet().isEmpty()
    }
}
