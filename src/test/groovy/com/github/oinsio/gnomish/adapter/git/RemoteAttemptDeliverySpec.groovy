package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21 of add-sandbox-core: delivery of the attempt commit to the remote is a verified
 * precondition of an external check's poll loop — confirmed from the remote tip's ancestry when
 * possible, delivered by a re-attempted push otherwise; an undeliverable commit (push failing
 * twice, or no remote at all) reports Undeliverable, which the engine resolves as CannotVerify.
 * Runs on real local repositories — no git mocking.
 */
class RemoteAttemptDeliverySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    private Path clone
    private Path origin
    private static final String BRANCH = 'gnomish/task-1'

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        Files.writeString(clone.resolve('a.txt'), 'base')
        commitAll(clone, 'base')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
        gitOutput(clone, 'checkout', '-b', BRANCH)
    }

    private String commitChange(String content) {
        Files.writeString(clone.resolve('a.txt'), content)
        commitAll(clone, 'round')
        gitOutput(clone, 'rev-parse', 'HEAD').trim()
    }

    private static AttemptCommitWorkspace workspaceAt(String sha) {
        def ref = new AttemptCommitRef()
        ref.record(sha)
        new AttemptCommitWorkspace(ref)
    }

    private RemoteAttemptDelivery delivery() {
        new RemoteAttemptDelivery(runner, clone, BRANCH)
    }

    /** Installs a pre-receive hook on the bare origin; hooks run with cwd = the bare repo. */
    private void installPreReceiveHook(String script) {
        def hook = origin.resolve('hooks').resolve('pre-receive').toFile()
        hook.parentFile.mkdirs()
        hook.text = "#!/bin/sh\n" + script + "\n"
        hook.setExecutable(true)
    }

    def "an already-pushed attempt commit is confirmed from the remote tip without another push"() {
        given:
        def sha = commitChange('one')
        gitOutput(clone, 'push', 'origin', "$BRANCH:$BRANCH")

        expect:
        delivery().ensureDelivered(workspaceAt(sha)) == new AttemptDelivery.Outcome.Delivered()
    }

    def "an attempt commit behind a further-advanced remote tip still counts as delivered"() {
        given: 'the attempt commit is pushed, then more work lands on the remote branch'
        def sha = commitChange('one')
        commitChange('two')
        gitOutput(clone, 'push', 'origin', "$BRANCH:$BRANCH")

        expect: 'ancestry of the remote tip proves delivery of the earlier attempt commit'
        delivery().ensureDelivered(workspaceAt(sha)) == new AttemptDelivery.Outcome.Delivered()
    }

    def "an unpushed attempt commit is delivered by the re-attempted push"() {
        given:
        def sha = commitChange('one')

        when:
        def outcome = delivery().ensureDelivered(workspaceAt(sha))

        then:
        outcome == new AttemptDelivery.Outcome.Delivered()

        and: 'the remote branch now carries the commit'
        gitOutput(clone, 'ls-remote', 'origin', "refs/heads/$BRANCH").contains(sha)
    }

    def "delivery is confirmed from the remote tip alone even when a push could not succeed"() {
        given: 'the attempt commit is on the remote, but the local branch sits behind the remote tip'
        def sha = commitChange('one')
        commitChange('two')
        gitOutput(clone, 'push', 'origin', "$BRANCH:$BRANCH")
        gitOutput(clone, 'reset', '--hard', sha)

        expect: 'ancestry of the remote tip settles it — a push here would only fail as non-fast-forward'
        delivery().ensureDelivered(workspaceAt(sha)) == new AttemptDelivery.Outcome.Delivered()
    }

    def "an attempt commit ahead of the remote tip is pushed, never assumed delivered"() {
        given: 'the remote branch exists but stops one commit short of the attempt'
        commitChange('one')
        gitOutput(clone, 'push', 'origin', "$BRANCH:$BRANCH")
        def sha = commitChange('two')

        when:
        def outcome = delivery().ensureDelivered(workspaceAt(sha))

        then:
        outcome == new AttemptDelivery.Outcome.Delivered()

        and: 'the remote tip advanced to the attempt commit'
        gitOutput(clone, 'ls-remote', 'origin', "refs/heads/$BRANCH").contains(sha)
    }

    def "a push failing once is re-attempted and delivers"() {
        given: 'a remote that rejects exactly the first push'
        def sha = commitChange('one')
        installPreReceiveHook('if [ ! -f rejected-once ]; then : > rejected-once; exit 1; fi; exit 0')

        when:
        def outcome = delivery().ensureDelivered(workspaceAt(sha))

        then: 'the single retry delivered the commit'
        outcome == new AttemptDelivery.Outcome.Delivered()
        gitOutput(clone, 'ls-remote', 'origin', "refs/heads/$BRANCH").contains(sha)

        and: 'the hook really rejected the first push — the retry was exercised, not skipped'
        Files.exists(origin.resolve('rejected-once'))
    }

    def "a commit that cannot be delivered is Undeliverable, never a silent pass"() {
        given: 'the origin URL points at a path that no longer exists'
        def sha = commitChange('one')
        gitOutput(clone, 'remote', 'set-url', 'origin', tempDir.resolve('gone.git').toString())

        when:
        def outcome = delivery().ensureDelivered(workspaceAt(sha))

        then:
        outcome instanceof AttemptDelivery.Outcome.Undeliverable
        ((AttemptDelivery.Outcome.Undeliverable) outcome).reason().contains('could not be delivered')
        ((AttemptDelivery.Outcome.Undeliverable) outcome).details().contains(sha)
    }

    def "no configured origin is Undeliverable"() {
        given:
        def sha = commitChange('one')
        gitOutput(clone, 'remote', 'remove', 'origin')

        when:
        def outcome = delivery().ensureDelivered(workspaceAt(sha))

        then:
        outcome instanceof AttemptDelivery.Outcome.Undeliverable
        ((AttemptDelivery.Outcome.Undeliverable) outcome).reason().contains('no remote')
    }

    def "a workspace without an attempt commit is Undeliverable"() {
        when:
        def outcome = delivery().ensureDelivered(new DirectoryWorkspace(clone))

        then:
        outcome instanceof AttemptDelivery.Outcome.Undeliverable
        ((AttemptDelivery.Outcome.Undeliverable) outcome).details().contains('DirectoryWorkspace')
    }
}
