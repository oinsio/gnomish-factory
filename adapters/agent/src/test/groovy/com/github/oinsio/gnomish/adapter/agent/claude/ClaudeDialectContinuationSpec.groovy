package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.adapter.agent.runner.ContinuationKey
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Continuation
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The Claude dialect's side of decision continuation (FR9, NFR-R2, design D4 of
 * extract-agent-round-runner): the {@code init} event's session id is the round's continuation key; a
 * {@code start} carrying a continuation renders {@code --resume <session id>} and hands the CLI the
 * reply on stdin; and a resume the CLI refused — it exits non-zero before any session started, with
 * the refusal its 2.1.325 bundle prints ({@code No conversation found with session ID: <id>}, or
 * {@code Failed to resume session} for a transcript it could not load) — is told apart from every
 * other failure.
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class ClaudeDialectContinuationSpec extends Specification {

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    static final String INIT = '{"type":"system","subtype":"init","session_id":"sess-42","model":"claude-x"}'

    def "FR9: the continuation key is the session id of the init event, and none without one"() {
        expect:
        dialect.continuation(events(INIT, ClaudeDialectAnswerSpec.finalText('done'))) == Optional.of(new ContinuationKey('sess-42'))
        dialect.continuation(events(ClaudeDialectAnswerSpec.finalText('done'))).isEmpty()
    }

    // Scenario: executor/claude-dialect / Resume flag on continuation
    def "FR9: a start carrying a continuation resumes the session by its id and hands the CLI the reply"() {
        when:
        def rendered = dialect.render(request(new Continuation('sess-42', 'take the left one')))

        then:
        def resume = rendered.argv().indexOf('--resume')
        resume> 0
        rendered.argv()[resume + 1] == 'sess-42'
        rendered.stdin() == 'take the left one'
    }

    def "FR9: a start with no continuation resumes nothing and hands the CLI its instructions"() {
        when:
        def rendered = dialect.render(request(null))

        then:
        !rendered.argv().contains('--resume')
        rendered.stdin() == 'build'
    }

    def "NFR-R2: a resume is refused when #what"() {
        expect:
        dialect.resumeRefused(exitCode, events(*lines), UntrustedText.agent(stderr)) == refused

        where:
        what | exitCode | lines | stderr || refused
        'the CLI found no conversation with the session' | 1 | [] | 'No conversation found with session ID: sess-42\n' || true
        'the CLI could not load the session' | 1 | [] | 'Failed to resume session sess-42\n' || true
        'not: the CLI exited zero' | 0 | [] | 'No conversation found with session ID: sess-42\n' || false
        'not: a session started' | 1 | [INIT] | 'No conversation found with session ID: sess-42\n' || false
        'not: the CLI failed for another reason' | 1 | [] | 'Invalid API key\n' || false
    }

    private static ExecutorRequest request(Continuation continuation) {
        new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(), RoleInputs.transform('build'),
                ExecutorContractJson.settingsTree([:]), Bounds.none(), [], continuation)
    }

    private def events(String... lines) {
        ClaudeDialectAnswerSpec.events(dialect, lines)
    }
}
