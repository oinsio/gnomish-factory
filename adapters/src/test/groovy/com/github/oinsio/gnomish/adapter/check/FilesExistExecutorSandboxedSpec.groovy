package com.github.oinsio.gnomish.adapter.check

import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.files
import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.results
import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.verdict

import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.app.workspace.fake.ClosedRounds
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, D15 of add-sandbox-core; FR1 of migrate-verify-to-executor-contract: in sandboxed mode the
 * {@code files-exist} executor evaluates the harvested attempt commit via bare git object reads in
 * the factory clone — one implementation for every environment adapter, no environment access, so
 * uncommitted box residue never influences the answer. Runs on real temp repositories — no git
 * mocking.
 */
class FilesExistExecutorSandboxedSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    GitObjects gitObjects
    ObjectId attempt

    def setup() {
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        gitObjects = openGitObjects(bare, tempDir)
        def base = gitObjects.resolveRef('refs/heads/base').orElseThrow()
        attempt = gitObjects.commit(new CommitRequest('refs/heads/gnomish/task-1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('docs/report.md', 'report'.getBytes(StandardCharsets.UTF_8))
                ], metadata()))
    }

    private FilesExistExecutor executor() {
        new FilesExistExecutor(new FilesExistCheckRunner().withAttemptReader(gitObjects), workspace())
    }

    private RecordedAttemptCommitWorkspace workspace() {
        new RecordedAttemptCommitWorkspace(ClosedRounds.at(attempt.hex()))
    }

    // Scenario: git/git-task-persistence / Every check reads the attempt commit its own way
    def "files present in the attempt commit complete with an answer of no results"() {
        when: 'paths that exist in the commit tree — a blob from base, the new blob, and a directory'
        def result = executor().start(files([
            'src/App.java',
            'docs/report.md',
            'docs'
        ]))

        then:
        result.status() == Status.COMPLETED
        results(result).isEmpty()
    }

    // Scenario: git/git-task-persistence / Verification judges the attempt commit
    // Scenario: cli/manual-run / Sandboxed check reads the commit, not the box
    def "a file absent from the attempt commit is missing, wherever else it may exist"() {
        when: 'the "Sandboxed check reads the commit, not the box" scenario: existence is the commit tree alone'
        def result = executor().start(files([
            'src/App.java',
            'uncommitted-residue.txt'
        ]))

        then:
        result.status() == Status.COMPLETED
        results(result)*.level() == [SarifLevel.ERROR]
        results(result)*.uri()*.raw() == ['uncommitted-residue.txt']
    }

    def "a path escaping the workspace is failed/infrastructure naming the path"() {
        when:
        def result = executor().start(files([path]))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains(path)

        where:
        path << [
            '/etc/passwd',
            '../outside.txt',
            '.git/config'
        ]
    }

    def "a sandboxed workspace with no factory-clone reader bound is failed/infrastructure"() {
        given: 'the plain host-construction runner, no withAttemptReader rebind'
        def unbound = new FilesExistExecutor(new FilesExistCheckRunner(), workspace())

        when:
        def result = unbound.start(files(['src/App.java']))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('no factory-clone reader')
    }

    def "malformed settings still fail before any git read"() {
        expect:
        executor().start(verdict([:])).failureClass() == FailureClass.INFRASTRUCTURE
    }
}
