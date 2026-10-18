package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.RefusingLaunch
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 as amended 2026-10-17, twice, of fix-terminal-receipt-convergence: with the
 * launch owner's launcher in front of git, a git that cannot run — a missing binary, a missing
 * interpreter, or a refusal of the launcher itself, which this whole-capture waiter judges by the
 * code and the launcher's line — is still "could not launch git binary", never an exit code {@code
 * exists()} would read as "the ref does not exist".
 */
class GitExecLaunchFailureSpec extends Specification {

    @TempDir
    Path gitDir

    // Scenario: lifecycle/subprocess-supervision / A missing binary fails the launch before any process starts
    def "FR21: a git binary that does not exist is a launch failure naming it"() {
        given:
        GitExec exec = new GitExec(gitDir, '/nonexistent/gnomish-git', new ProcessSupervisor(), ProcessLaunch.grouped(gitDir))

        when:
        exec.run([
            'rev-parse',
            '--verify',
            'HEAD'
        ])

        then:
        GitObjectsException refused = thrown()
        refused.message.contains('could not launch git binary: /nonexistent/gnomish-git')
    }

    // Scenario: lifecycle/subprocess-supervision / A script whose interpreter is missing fails the launch before any process starts
    def "FR21: a git script whose interpreter is missing is a launch failure naming it"() {
        given: 'an executable whose interpreter is missing: the launch owner refuses it before any process exists'
        String git = StandIn.process('missing-interpreter').toString()
        GitExec exec = new GitExec(gitDir, git, new ProcessSupervisor(), ProcessLaunch.grouped(gitDir))

        when:
        exec.run([
            'rev-parse',
            '--verify',
            'HEAD'
        ])

        then:
        GitObjectsException refused = thrown()
        refused.message.contains("could not launch git binary: ${git}")
    }

    // Scenario: lifecycle/subprocess-supervision / A launcher that could not exec is a launch failure where stderr is captured whole
    def "FR21: a git the launcher could not exec is a launch failure, not an exit code"() {
        given: 'a launcher that started, could not exec git, and wrote its refusal line'
        GitExec exec = new GitExec(gitDir, 'gnomish-git', new ProcessSupervisor(), new RefusingLaunch())

        when:
        exec.run([
            'rev-parse',
            '--verify',
            'HEAD'
        ])

        then:
        GitObjectsException refused = thrown()
        refused.message.contains('could not launch git binary: gnomish-git')
        refused.message.contains('exit 127')
    }

    // Scenario: lifecycle/subprocess-supervision / A launcher that could not exec is a launch failure where stderr is captured whole
    def "FR21: a git that itself exits 127 without the launcher's line is an exit code"() {
        given:
        FakeProcess process = FakeProcess.running()
        process.writeStderr('git: something went wrong\n')
        process.exit(127)
        GitExec exec = new GitExec(gitDir, 'gnomish-git', new ProcessSupervisor(), { builder, stdin ->
            process
        } as ProcessLaunch)

        when:
        GitExec.Result result = exec.run([
            'rev-parse',
            '--verify',
            'HEAD'
        ])

        then:
        result.exitCode() == 127
        result.stderr().contains('something went wrong')
    }
}
