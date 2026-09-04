package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.SelfCheckFailedException
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

    private static RecordedAttemptCommitWorkspace workspaceAt(String sha) {
        def ref = new AttemptCommitRef()
        ref.record(sha)
        new RecordedAttemptCommitWorkspace(ref)
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

    // FR3 of polish-sandbox-forensics: a judge box rejected by its own self-check is evidence —
    // the container adapter has already stopped and kept it. Today nothing here disposes it, because
    // a failed materialize is never assigned to `current` (disposeCurrent runs BEFORE materialize).
    // This pins that: a refactor that started tracking half-built boxes would destroy the evidence.
    def "FR3: a judge box whose self-check failed is left kept — nothing here disposes it"() {
        given: 'the previous attempt has a live judge box, and the next box fails its self-check'
        def environments = source()
        environments.environmentFor(workspaceAt('abc123'))
        RecordingEnvironment.failMaterialize = true

        when:
        environments.environmentFor(workspaceAt('def456'))

        then: 'the rejection propagates unchanged as the infrastructure failure it is'
        thrown(SelfCheckFailedException)

        and: 'and the rejected box is untouched — it is the evidence, kept for the operator'
        created.size() == 2
        !created[1].disposed

        when: 'the stage ends and the source tears down what it still owns'
        environments.disposeCurrent()

        then: 'still nothing reaches the kept box: it was never tracked as current'
        !created[1].disposed

        cleanup:
        RecordingEnvironment.failMaterialize = false
    }

    private static final class RecordingEnvironment implements TaskExecutionEnvironment {
        static boolean failMaterialize = false

        String materializedBranch
        String materializedPin
        boolean disposed = false
        int disposeCalls = 0

        void materialize(String branch, @Nullable String commitPin) {
            if (failMaterialize) {
                throw new SelfCheckFailedException('non-root', 'the in-box user is root (uid 0)')
            }
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
