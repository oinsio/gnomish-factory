package com.github.oinsio.gnomish.app

import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16, UX4 of harden-task-branch-contract: {@code gnomish status} renders a task branch by the
 * shape the classifier gives its tip. Every legal shape renders calmly; the quarantine shapes
 * refuse single-task inspection with a diagnosis naming the offending file, and are one diagnostic
 * row each in the listing. Each shape is built on a real clone the way the factory leaves it.
 */
class StatusShapeClassificationSpec extends Specification implements BranchShapeFixture, StdoutCaptureFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    private String status(String... args) {
        captureStdout { command().run(arguments(args)) }
    }

    private StatusCommand command() {
        new StatusCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(registeredClone), liveConsole())
    }

    private DefaultApplicationArguments arguments(String... args) {
        new DefaultApplicationArguments((['status', '--dir=' + cloneDir] + args.toList()) as String[])
    }

    // FR16: the shape is the classifier's verdict — the listing names it — and every legal shape
    //     renders a single-task status calmly.
    // Scenario: cli/task-inspection / The shape classifier decides how a branch renders
    def "FR16: a #shape branch is listed as #shape and its status renders without an error"() {
        given:
        branchOfShape(shape, 'T-1')

        when:
        def listing = status()
        def single = status('T-1')

        then:
        listing.readLines().find { it.contains('T-1') }.contains(shape)
        single.contains('Task: T-1')

        where:
        shape << [
            'Bare',
            'Created',
            'Answered',
            'AwaitingApproval',
            'InProgress',
            'Parked',
            'CompletedUncleaned',
            'Delivered'
        ]
    }

    // FR16, UX4: a quarantine shape refuses inspection with its diagnosis — no stack trace, no write.
    // Scenario: cli/task-inspection / A corrupt or unknown branch refuses inspection
    def "FR16: a #shape branch refuses inspection, naming #file, and mutates nothing"() {
        given:
        branchOfShape(shape, 'BAD-1')
        def refsBefore = gitOutput(cloneDir, 'for-each-ref')

        when:
        def output = captureStdoutExpectingThrow(BranchShapeRefusedException) {
            command().run(arguments('BAD-1'))
        }

        then:
        output.contains('Shape: ' + shape)
        output.readLines().find { it.startsWith('Diagnosis: ') }.contains(file)

        and:
        gitOutput(cloneDir, 'for-each-ref') == refsBefore

        where:
        shape | file
        'Corrupt' | 'state.json'
        'Unknown' | 'task.json'
    }

    // FR16, UX4: the listing survives every refusing shape, one row each with its diagnosis.
    // Scenario: cli/task-inspection / Every refusing shape is one diagnostic row
    def "FR16: Corrupt, UnsupportedVersion and Unknown branches are one diagnostic row each beside a healthy task"() {
        given:
        [
            'Corrupt',
            'UnsupportedVersion',
            'Unknown',
            'InProgress'
        ].each {
            branchOfShape(it, it.toUpperCase())
        }

        when:
        def rows = status().readLines()

        then:
        rows.count { it.contains('CORRUPT') } == 1
        rows.find {
            it.contains('CORRUPT')
        }.contains('Corrupt') && rows.find {
            it.contains('CORRUPT')
        }.contains('corrupt content (state.json')
        rows.count { it.contains('UNSUPPORTEDVERSION') } == 1
        rows.find {
            it.contains('UNSUPPORTEDVERSION')
        }.contains('state.json declaring version 2 where this factory supports 1')
        rows.count { it.contains('UNKNOWN') } == 1
        rows.find {
            it.contains('UNKNOWN')
        }.contains('an unrecognized combination (state.json present without task.json)')

        and: 'the healthy task renders normally'
        rows.find { it.contains('INPROGRESS') }.contains('implement')
    }
}
