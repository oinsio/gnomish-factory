package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14 of make-checkpoint-gate-durable: the one removal of consumed requests — its tree edit names
 * the gnome-writable subtree, and the worktree removal refuses loudly when git cannot stage it. The
 * effect on each repository's commit is pinned by {@link GitTaskRepositoryConsumedRequestSpec} and
 * {@link GitObjectsTaskRepositoryConsumedRequestSpec}.
 */
class ConsumedRequestRemovalSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    def "FR14: the bare-object removal deletes the gnome-writable subtree"() {
        expect:
        ConsumedRequestRemoval.treeEdit() == new TreeEdit.DeletePath(FactoryOwnedPaths.GNOME_WRITABLE)
    }

    def "FR14: a worktree with no request stages nothing and does not fail"() {
        given:
        Path work = initWorkingRepo(tempDir, 'work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')

        when:
        ConsumedRequestRemoval.stage(runner, work, 'PROJ-1', TaskLifecycleEvent.RESUMED)

        then:
        noExceptionThrown()
        runner.run(work, 'status', '--porcelain').stdout().isBlank()
    }

    def "FR14: a removal git cannot stage fails the write, naming the task and the event"() {
        given: 'a held index lock, so git rm cannot write the index'
        Path work = initWorkingRepo(tempDir, 'work')
        Path request = work.resolve(FactoryOwnedPaths.GNOME_WRITABLE).resolve('implement-a0-0123abcd.json')
        Files.createDirectories(request.parent)
        Files.writeString(request, '{}')
        commitAll(work, 'asked')
        Files.createFile(work.resolve('.git/index.lock'))

        when:
        ConsumedRequestRemoval.stage(runner, work, 'PROJ-1', TaskLifecycleEvent.APPROVED)

        then:
        def failure = thrown(GitTaskRepositoryException)
        failure.message.contains('PROJ-1')
        failure.message.contains(TaskLifecycleEvent.APPROVED.toString())
        failure.message.contains('git rm -r')
        Files.exists(request)
    }
}
