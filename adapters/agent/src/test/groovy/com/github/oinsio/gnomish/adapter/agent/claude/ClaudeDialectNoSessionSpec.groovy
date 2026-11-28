package com.github.oinsio.gnomish.adapter.agent.claude

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
 * The Claude dialect continues no session (FR9, NFR-R2 of extract-agent-round-runner; design D4 as
 * amended 2026-11-21): the CLI's session lives on the worker, so no argv of any role carries a session
 * flag and the stdin is the role's input — a continuation on the request included, whose reply the
 * dialect never reads.
 */
class ClaudeDialectNoSessionSpec extends Specification {

    static final List<String> SESSION_FLAGS = [
        '--resume',
        '--continue',
        '--session-id'
    ]

    def dialect = new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com')

    // Scenario: executor/claude-dialect / No argv carries a session flag
    def "FR9: a #role request #having a continuation renders no session flag, and the prompt is the role's input"() {
        when:
        def rendered = dialect.render(new ExecutorRequest(role, new Workspace('.'), policy, inputs,
                ExecutorContractJson.settingsTree([:]), Bounds.none(), [], continuation))

        then:
        rendered.argv().every { !(it in SESSION_FLAGS) }
        !rendered.argv().contains('sess-42')
        rendered.stdin() == prompt

        where:
        role | policy | inputs | continuation || prompt
        Role.TRANSFORM | new Policy.WorkspaceWrite() | RoleInputs.transform('build it') | null || 'build it'
        Role.TRANSFORM | new Policy.WorkspaceWrite() | RoleInputs.transform('build it') | CONTINUED || 'build it'
        Role.VERDICT | new Policy.ReadOnly() | RoleInputs.verdict('is it built?') | null || 'is it built?'
        Role.VERDICT | new Policy.ReadOnly() | RoleInputs.verdict('is it built?') | CONTINUED || 'is it built?'
        Role.DECISION | new Policy.ReadOnly() | RoleInputs.decision('which way?', ['left', 'right']) | null || 'which way?\n- left\n- right'
        Role.DECISION | new Policy.ReadOnly() | RoleInputs.decision('which way?', ['left', 'right']) | CONTINUED || 'which way?\n- left\n- right'

        having = continuation == null ? 'without' : 'with'
    }

    static final Continuation CONTINUED = new Continuation('sess-42', 'take the left one')
}
