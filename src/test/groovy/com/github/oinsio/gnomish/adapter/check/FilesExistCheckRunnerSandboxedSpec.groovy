package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
 * FR21, D15 of add-sandbox-core: in sandboxed mode {@code files_exist} evaluates the harvested
 * attempt commit via bare git object reads in the factory clone — one implementation for every
 * environment adapter, no environment access, so uncommitted box residue never influences the
 * verdict. Runs on real temp repositories — no git mocking.
 */
class FilesExistCheckRunnerSandboxedSpec extends Specification implements GitObjectsFixture {

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

    private FilesExistCheckRunner runner() {
        new FilesExistCheckRunner().withAttemptReader(gitObjects)
    }

    private AttemptCommitWorkspace workspace() {
        def ref = new AttemptCommitRef()
        ref.record(attempt.hex())
        new AttemptCommitWorkspace(ref)
    }

    def "files present in the attempt commit yield Pass"() {
        given: 'paths that exist in the commit tree — a blob from base, the new blob, and a directory'
        def check = new VerifyCheck.Builtin('files_exist', [files: [
                'src/App.java',
                'docs/report.md',
                'docs'
            ]])

        expect:
        runner().run(check, workspace()) instanceof Verdict.Pass
    }

    def "a file absent from the attempt commit is missing, wherever else it may exist"() {
        given: 'the "Sandboxed check reads the commit, not the box" scenario: existence is the commit tree alone'
        def check = new VerifyCheck.Builtin('files_exist', [files: [
                'src/App.java',
                'uncommitted-residue.txt'
            ]])

        when:
        def verdict = runner().run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        ((Verdict.Fail) verdict).findings()*.location() == ['uncommitted-residue.txt']
    }

    def "a path escaping the workspace yields CannotVerify naming the path"() {
        given:
        def check = new VerifyCheck.Builtin('files_exist', [files: [path]])

        when:
        def verdict = runner().run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        ((Verdict.CannotVerify) verdict).reason().contains(path)

        where:
        path << [
            '/etc/passwd',
            '../outside.txt',
            '.git/config'
        ]
    }

    def "a sandboxed workspace with no factory-clone reader bound yields CannotVerify"() {
        given: 'the plain host-construction runner, no withAttemptReader rebind'
        def check = new VerifyCheck.Builtin('files_exist', [files: ['src/App.java']])

        when:
        def verdict = new FilesExistCheckRunner().run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        ((Verdict.CannotVerify) verdict).reason().contains('no factory-clone reader')
    }

    def "malformed params still fail before any git read"() {
        given:
        def check = new VerifyCheck.Builtin('files_exist', [:])

        expect:
        runner().run(check, workspace()) instanceof Verdict.CannotVerify
    }
}
