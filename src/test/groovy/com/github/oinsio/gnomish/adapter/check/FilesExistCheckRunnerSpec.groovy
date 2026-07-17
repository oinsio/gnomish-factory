package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of add-manual-run: the real {@code files_exist} runner checks existence
 * of literal workspace-relative paths, reporting one finding per missing path
 * (a quality failure) and refusing malformed params or a workspace-escaping
 * path as CannotVerify (an infrastructure failure the check attempt is never
 * burned for).
 */
class FilesExistCheckRunnerSpec extends Specification {

    @TempDir
    Path tempDir

    def runner = new FilesExistCheckRunner()

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    def "all configured files present yields Pass"() {
        given: 'two files that exist in the workspace'
        Files.writeString(tempDir.resolve('a.txt'), 'a')
        Files.writeString(tempDir.resolve('b.txt'), 'b')
        def check = new VerifyCheck.Builtin('files_exist', [files: ['a.txt', 'b.txt']])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Pass
    }

    def "two of three missing files yields Fail with exactly two findings naming them"() {
        given: 'one existing file and two missing ones configured'
        Files.writeString(tempDir.resolve('present.txt'), 'ok')
        def check = new VerifyCheck.Builtin(
                'files_exist',
                [files: [
                        'present.txt',
                        'missing-one.txt',
                        'missing-two.txt'
                    ]])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = (Verdict.Fail) verdict
        fail.findings().size() == 2
        fail.findings()*.location() as Set == [
            'missing-one.txt',
            'missing-two.txt'
        ] as Set
    }

    def "missing 'files' param yields CannotVerify"() {
        given:
        def check = new VerifyCheck.Builtin('files_exist', [:])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
    }

    def "wrong-type 'files' param yields CannotVerify"() {
        given:
        def check = new VerifyCheck.Builtin('files_exist', [files: 'not-a-list'])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
    }

    def "non-string entry in 'files' param yields CannotVerify naming its type"() {
        given:
        def check = new VerifyCheck.Builtin('files_exist', [files: ['a.txt', 42]])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        def cannotVerify = verdict as Verdict.CannotVerify
        cannotVerify.reason().contains('Integer')
        !cannotVerify.reason().contains('null')
    }

    def "a null entry in 'files' param yields CannotVerify naming it null, not its (absent) type"() {
        given:
        def files = new ArrayList<>(['a.txt'])
        files.add(null)
        def check = new VerifyCheck.Builtin('files_exist', [files: files])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        def cannotVerify = verdict as Verdict.CannotVerify
        cannotVerify.reason().contains('null')
    }

    def "a workspace that is not a DirectoryWorkspace yields CannotVerify naming its runtime type"() {
        given: 'the engine-side member-less Workspace marker, not the directory-backed adapter'
        def check = new VerifyCheck.Builtin('files_exist', [files: ['a.txt']])
        def opaqueWorkspace = new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}

        when:
        def verdict = runner.run(check, opaqueWorkspace)

        then: 'no existence check can be trusted without a real root, so the runner cannot verify'
        verdict instanceof Verdict.CannotVerify
        def cannotVerify = (Verdict.CannotVerify) verdict
        cannotVerify.reason().contains('DirectoryWorkspace')
    }

    def "a path escaping the workspace root yields CannotVerify naming the offending path"() {
        given: 'a configured path that climbs out of the workspace via ..'
        def check = new VerifyCheck.Builtin('files_exist', [files: ['../secret']])

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        def cannotVerify = (Verdict.CannotVerify) verdict
        cannotVerify.reason().contains('../secret')
    }
}
