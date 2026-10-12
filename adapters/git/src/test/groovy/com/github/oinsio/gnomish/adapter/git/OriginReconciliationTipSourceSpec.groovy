package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3 of fix-lifecycle-push (design D3): the touchpoint check compares origin with the local tip
 * its caller supplies — host mode's worktree reader, sandboxed mode's bare-object {@code resolveRef}
 * — and never reads a tip of its own. Shown by handing it a tip that differs from what the clone's
 * branch ref says: the check acts on the supplied tip alone.
 */
class OriginReconciliationTipSourceSpec extends Specification implements BareGitRepoFixture {

    private static final String BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
    private Path clone

    def setup() {
        Path origin = initBareRepo(tempDir, 'origin.git')
        clone = tempDir.resolve('clone')
        seedClone(tempDir, origin.toString(), clone, '-q')
        Files.writeString(clone.resolve('a.txt'), 'base')
        commitAll(clone, 'init')
        gitOutput(clone, 'checkout', '-q', '-b', BRANCH)
    }

    private String commitOnBranch(String content) {
        Files.writeString(clone.resolve('a.txt'), content)
        commitAll(clone, content)
        gitOutput(clone, 'rev-parse', 'HEAD')
    }

    private Optional<String> originTip() {
        new RemoteBranchTip(runner).read(clone, BRANCH)
    }

    // FR3, design D3 of fix-lifecycle-push: the mode's own reader supplies the tip; a clone ref that
    //     has moved past it is not what the check compares, so origin already holding the supplied
    //     tip is up to date and nothing is pushed.
    // Scenario: git/git-task-persistence / Each touchpoint compares its own mode's tip
    def "FR3: the check compares origin with the supplied tip, not with the clone's branch ref"() {
        given: 'origin holds the commit the mode reader reports, the clone ref has moved one past it'
        def supplied = commitOnBranch('one')
        assert new RefspecPush(runner).push(clone, BRANCH).exitCode() == 0
        def cloneRef = commitOnBranch('two')

        when:
        new OriginReconciliation(runner).reconcile('PROJ-1', 'terminal-boundary', clone, BRANCH, supplied)

        then: 'origin is left at the supplied tip — a check that read the clone ref would have pushed it'
        originTip() == Optional.of(supplied)
        cloneRef != supplied
    }
}
