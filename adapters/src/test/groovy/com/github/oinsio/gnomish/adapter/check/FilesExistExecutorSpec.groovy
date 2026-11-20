package com.github.oinsio.gnomish.adapter.check

import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.files
import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.results
import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.verdict

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of add-manual-run, FR1 of migrate-verify-to-executor-contract: the {@code files-exist} built-in
 * executor checks existence of literal workspace-relative paths, answering one {@code error} SARIF
 * result per missing path, and reports malformed settings or a workspace-escaping path as {@code
 * failed/infrastructure} (an infrastructure failure the check attempt is never burned for).
 */
class FilesExistExecutorSpec extends Specification {

    @TempDir
    Path tempDir

    private FilesExistExecutor executor() {
        new FilesExistExecutor(new FilesExistCheckRunner(), new DirectoryWorkspace(tempDir))
    }

    def "FR1: all configured files present completes with an answer of no results"() {
        given: 'two files that exist in the workspace'
        Files.writeString(tempDir.resolve('a.txt'), 'a')
        Files.writeString(tempDir.resolve('b.txt'), 'b')

        when:
        def result = executor().start(files(['a.txt', 'b.txt']))

        then:
        result.status() == Status.COMPLETED
        result.failureClass() == null
        results(result).isEmpty()
        result.answer().get('sarif').get('runs').get(0).get('tool').get('driver').get('name').textValue() == 'files-exist'
    }

    // Scenario: cli/manual-run / Missing files enumerated
    def "FR1: two of three missing files answer exactly two error results naming them"() {
        given: 'one existing file and two missing ones configured'
        Files.writeString(tempDir.resolve('present.txt'), 'ok')

        when:
        def result = executor().start(files([
            'present.txt',
            'missing-one.txt',
            'missing-two.txt'
        ]))

        then:
        result.status() == Status.COMPLETED
        results(result)*.level() == [
            SarifLevel.ERROR,
            SarifLevel.ERROR
        ]
        results(result)*.uri()*.raw() == [
            'missing-one.txt',
            'missing-two.txt'
        ]
        results(result)*.message()*.raw() == [
            'missing file: missing-one.txt',
            'missing file: missing-two.txt'
        ]
    }

    def "FR1: unusable settings are failed/infrastructure naming the defect"() {
        when:
        def result = executor().start(verdict(settings))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.answer() == null
        result.reason().raw().contains(named)
        !result.reason().raw().contains(absent)

        where:
        settings || named | absent
        [:] || "requires a 'files'" | 'Integer'
        [files: 'not-a-list'] || 'must be a list' | 'Integer'
        [files: ['a.txt', 42]] || 'Integer' | 'null'
        [files: ['a.txt', null]] || 'found null' | 'Integer'
    }

    // FR1, D5 (human decision 2026-11-10): the setting has no count limit; the answer stays within the result cap
    def "FR1: #missing missing paths answer #listed error results naming them, then #summary"() {
        given:
        def paths = (1..missing).collect {
            "m${it}.txt".toString()
        }

        when:
        def answered = results(executor().start(files(paths)))

        then: 'a judgement the check fails, never failed/infrastructure'
        answered.size() == Math.min(missing, 1000)
        answered*.level().every {
            it == SarifLevel.ERROR
        }
        answered.take(listed)*.uri()*.raw() == paths.take(listed)
        answered.take(listed)*.message()*.raw() == paths.take(listed).collect {
            "missing file: ${it}".toString()
        }
        answered.drop(listed)*.message()*.raw() == (summary ? [summary]: [])
        answered.drop(listed)*.uri() == (summary ? [null]: [])

        where:
        missing || listed | summary
        999 || 999 | ''
        1000 || 1000 | ''
        1001 || 999 | 'and 2 more missing files not listed'
        5000 || 999 | 'and 4001 more missing files not listed'
    }

    def "a workspace that is not a DirectoryWorkspace is failed/infrastructure naming its runtime type"() {
        given: 'the engine-side member-less Workspace marker, not the directory-backed adapter'
        def opaque = new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}

        when:
        def result = new FilesExistExecutor(new FilesExistCheckRunner(), opaque).start(files(['a.txt']))

        then: 'no existence check can be trusted without a real root, so the executor cannot verify'
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('DirectoryWorkspace')
    }

    // Scenario: cli/manual-run / Workspace escape refused
    def "a path escaping the workspace root is failed/infrastructure naming the offending path"() {
        when:
        def result = executor().start(files(['../secret']))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('../secret')
    }
}
