package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import org.jspecify.annotations.Nullable
import spock.lang.Specification

/**
 * FR15, NFR-S2, D9 of add-sandbox-core: sandboxed judge votes run in a fresh environment
 * materialized from the attempt commit — never the gnome-touched round environment — with
 * votes of one attempt sharing the fresh box (judges are read-only) and a new attempt
 * disposing the previous box before materializing its own.
 */
class FreshJudgeEnvironmentsSpec extends Specification {

    private final List<RecordingEnvironment> created = []

    private FreshJudgeEnvironments source() {
        new FreshJudgeEnvironments({
            def env = new RecordingEnvironment()
            created << env
            env
        }, 'gnomish/task-1')
    }

    private static AttemptCommitWorkspace workspaceAt(String sha) {
        def ref = new AttemptCommitRef()
        ref.record(sha)
        new AttemptCommitWorkspace(ref)
    }

    def "a vote's environment is fresh and materialized from the attempt commit"() {
        given:
        def environments = source()

        when:
        def env = environments.environmentFor(workspaceAt('abc123'))

        then: 'a brand-new environment pinned at the attempt commit, not any pre-existing box'
        created.size() == 1
        env.is(created[0])
        created[0].materializedBranch == 'gnomish/task-1'
        created[0].materializedPin == 'abc123'
    }

    def "votes of the same attempt share one fresh environment"() {
        given:
        def environments = source()

        when: 'three votes of one attempt ask for their environment'
        def first = environments.environmentFor(workspaceAt('abc123'))
        def second = environments.environmentFor(workspaceAt('abc123'))
        def third = environments.environmentFor(workspaceAt('abc123'))

        then: 'one materialization serves all three'
        created.size() == 1
        first.is(second) && second.is(third)
    }

    def "a new attempt disposes the previous judge box and materializes a fresh one"() {
        given:
        def environments = source()
        environments.environmentFor(workspaceAt('abc123'))

        when: 'the next attempt casts its first vote'
        def next = environments.environmentFor(workspaceAt('def456'))

        then:
        created.size() == 2
        created[0].disposed
        next.is(created[1])
        created[1].materializedPin == 'def456'
        !created[1].disposed
    }

    def "disposeCurrent tears down the last box and is idempotent"() {
        given:
        def environments = source()
        environments.environmentFor(workspaceAt('abc123'))

        when:
        environments.disposeCurrent()
        environments.disposeCurrent()

        then:
        created[0].disposed
        created[0].disposeCalls == 1
    }

    private static final class RecordingEnvironment implements TaskExecutionEnvironment {
        String materializedBranch
        String materializedPin
        boolean disposed = false
        int disposeCalls = 0

        void materialize(String branch, @Nullable String commitPin) {
            materializedBranch = branch
            materializedPin = commitPin
        }

        ExecHandle exec(ExecCommand command) {
            throw new UnsupportedOperationException('not exercised')
        }

        void putFile(String path, byte[] content) {}

        Optional<byte[]> readFile(String path, long sizeCap) {
            Optional.empty()
        }

        void harvest() {}

        void dispose() {
            disposed = true
            disposeCalls++
        }

        String scratchRoot() {
            '/scratch'
        }

        CapabilityPassport passport() {
            throw new UnsupportedOperationException('not exercised')
        }
    }
}
