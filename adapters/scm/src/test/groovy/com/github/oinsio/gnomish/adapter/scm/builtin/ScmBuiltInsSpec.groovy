package com.github.oinsio.gnomish.adapter.scm.builtin

import com.github.oinsio.gnomish.adapter.scm.ScmConnection
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.adapter.scm.ScmTokenException
import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusExecutor
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import java.time.Duration
import spock.lang.Specification

/**
 * FR1, FR13, NFR-S2 of migrate-verify-to-executor-contract (design D18 as amended 2026-11-10): the
 * registry entries' bindings of the two SCM built-ins. Run assembly reads each connection once
 * through the one reader, with the built-in's own endpoint declaration and describe egress; the
 * entry is built over the admitted connection with its token, carries the built-in's describe, and
 * binds a new executor per check and working copy.
 */
class ScmBuiltInsSpec extends Specification {

    private static final Map SUBSECTION = [('api-url'): 'http://127.0.0.1:3000', repo: 'acme/widgets']

    private static final ScmConnectionReader READER =
    new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback']))

    private static SecretsProvider secrets(Map<String, String> values) {
        { String name -> Optional.ofNullable(values[name]) } as SecretsProvider
    }

    private static VerifyCheck.Executor check(String executor) {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, executor, 'ci.yml', [:], Duration.ofSeconds(1),
        Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
        Threshold.ERROR, ['build'])
    }

    def "NFR-S2: github-actions is admitted under the key it is read from, with its describe egress in the admitted set"() {
        when:
        ScmConnection connection = ScmBuiltIns.admit(READER, 'github-actions', SUBSECTION)

        then:
        connection.endpoint().key() == 'factory.check.github-actions.api-url'
        connection.endpoint().admitted().containsAll(GithubActionsExecutor.EGRESS)
        connection.settings() == SUBSECTION
    }

    def "NFR-S2: scm-status is admitted with no egress beyond its endpoint"() {
        when:
        ScmConnection connection = ScmBuiltIns.admit(READER, 'scm-status', SUBSECTION)

        then:
        connection.endpoint().key() == 'factory.check.scm-status.api-url'
        !connection.endpoint().admitted().contains('api.github.com')
    }

    def "a name that is no SCM built-in is refused"() {
        when:
        ScmBuiltIns.admit(READER, 'http', SUBSECTION)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "'http' is not an SCM built-in"
    }

    def "FR16: the token's name is the connection's credential, or #executor's default"() {
        given:
        def connection = ScmBuiltIns.admit(READER, executor, SUBSECTION)
        def renamed = ScmBuiltIns.admit(READER, executor, SUBSECTION + [credential: 'MY_TOKEN'])

        expect:
        ScmBuiltIns.credential(connection, executor) == expected
        ScmBuiltIns.credential(renamed, executor) == 'MY_TOKEN'

        where:
        executor || expected
        'github-actions' || 'GNOMISH_GITHUB_ACTIONS_TOKEN'
        'scm-status' || 'GNOMISH_SCM_STATUS_TOKEN'
    }

    // Task 5.2 of migrate-verify-to-executor-contract: read off the resolved subsection before any
    //     admission, the name the run's scrub and never-allowlist sets hold — the built-in's
    //     describe credential by default, the connection's own when it renames it.
    def "FR16, NFR-S1: before admission the token's name is read off the subsection: #executor"() {
        expect:
        ScmBuiltIns.credential(executor, [:]) == expected
        ScmBuiltIns.credential(executor, [credential: 'MY_TOKEN']) == 'MY_TOKEN'

        where:
        executor || expected
        'github-actions' || GithubActionsExecutor.describeAnswer().credentials().first()
        'scm-status' || 'GNOMISH_SCM_STATUS_TOKEN'
    }

    def "FR13: the #executor entry carries its describe and binds a new executor per check"() {
        given:
        def connection = ScmBuiltIns.admit(READER, executor, SUBSECTION)
        def builtIn = build(connection)
        def workspace = AttemptCommitWorkspaces.at('a' * 40)

        when:
        def first = builtIn.bind(check(executor), workspace)
        def second = builtIn.bind(check(executor), workspace)

        then:
        builtIn.describe() == describe
        type.isInstance(first)
        type.isInstance(second)
        !first.is(second)

        where:
        executor | build || type | describe
        'github-actions' | { c ->
            ScmBuiltIns.actions(c, secrets([GNOMISH_GITHUB_ACTIONS_TOKEN: 't']), new VirtualClock())
        } ||
        GithubActionsExecutor | GithubActionsExecutor.describeAnswer()
        'scm-status' | { c ->
            ScmBuiltIns.statuses(c, secrets([GNOMISH_SCM_STATUS_TOKEN: 't']))
        } ||
        ScmStatusExecutor | ScmStatusExecutor.describeAnswer(
        com.github.oinsio.gnomish.adapter.scm.status.ScmStatusReader.connect(
                ScmBuiltIns.admit(READER, 'scm-status', SUBSECTION), secrets([GNOMISH_SCM_STATUS_TOKEN: 't'])))
    }

    def "NFR-S2: an entry whose token does not resolve is not built: #executor"() {
        when:
        build(ScmBuiltIns.admit(READER, executor, SUBSECTION))

        then:
        thrown(ScmTokenException)

        where:
        executor | build
        'github-actions' | { c ->
            ScmBuiltIns.actions(c, secrets([:]), new VirtualClock())
        }
        'scm-status' | { c -> ScmBuiltIns.statuses(c, secrets([:])) }
    }

    def "the github-actions entry needs an owner/name repository"() {
        when:
        ScmBuiltIns.actions(ScmBuiltIns.admit(READER, 'github-actions', [('api-url'): 'http://127.0.0.1:3000']),
        secrets([GNOMISH_GITHUB_ACTIONS_TOKEN: 't']), new VirtualClock())

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'factory.check.github-actions.repo is required'
    }
}
