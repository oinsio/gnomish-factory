package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5: the interactive {@link InteractiveJudgeVoter} prints the acceptance
 * criteria file before prompting once per vote — {@code pass} / {@code fail} —
 * collecting findings on {@code fail}; unrecognized input re-prompts (UX1).
 */
class InteractiveJudgeVoterSpec extends Specification {

    @TempDir
    Path projectRoot

    private static TaskContext sampleContext() {
        new TaskContext('TASK-1', 'title', 'body', [])
    }

    private VerifyCheck.Judge sampleCheck() {
        Files.writeString(projectRoot.resolve('criteria.md'), 'Acceptance: the widget spins.')
        new VerifyCheck.Judge('criteria.md', 'gpt-5', [:], 1)
    }

    private DirectoryWorkspace sampleWorkspace() {
        new DirectoryWorkspace(projectRoot)
    }

    def "criteria file content is printed before the pass/fail prompt"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)

        when:
        voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        io.printed.any { it.contains('Acceptance: the widget spins.') }
    }

    def "pass answer yields Vote with Verdict.Pass and null tokens"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)

        when:
        def vote = voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        vote.verdict() instanceof Verdict.Pass
        vote.tokens() == null
    }

    def "fail answer collects findings until an empty line, yielding Vote with Verdict.Fail and null tokens"() {
        given:
        def io = new ScriptedConsoleIO([
            'fail',
            'widget does not spin',
            'missing a screw',
            ''
        ])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)

        when:
        def vote = voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        vote.verdict() instanceof Verdict.Fail
        vote.verdict().findings()*.message() == [
            'widget does not spin',
            'missing a screw'
        ]
        vote.tokens() == null
    }

    def "a workspace that is not a DirectoryWorkspace prints the exact unavailable placeholder"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)
        def opaqueWorkspace = new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}
        def check = new VerifyCheck.Judge('criteria.md', 'gpt-5', [:], 1)

        when:
        voter.vote(check, sampleContext(), opaqueWorkspace)

        then:
        io.printed.any { it == '(acceptance criteria unavailable: workspace is not a DirectoryWorkspace)' }
    }

    def "a criteria path escaping the workspace prints the exact escapes placeholder naming the path"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)
        def check = new VerifyCheck.Judge('../secret.md', 'gpt-5', [:], 1)

        when:
        voter.vote(check, sampleContext(), sampleWorkspace())

        then:
        io.printed.any {
            it == '(acceptance criteria could not be read: path escapes the workspace: ../secret.md)'
        }
    }

    def "a missing criteria file prints the exact could-not-be-read placeholder naming the path"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)
        def check = new VerifyCheck.Judge('does-not-exist.md', 'gpt-5', [:], 1)

        when:
        voter.vote(check, sampleContext(), sampleWorkspace())

        then:
        io.printed.any { it == '(acceptance criteria could not be read: does-not-exist.md)' }
    }

    def "unrecognized input re-prompts naming the accepted answers before an eventual valid answer"() {
        given:
        def io = new ScriptedConsoleIO(['bogus', 'pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console)

        when:
        def vote = voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        vote.verdict() instanceof Verdict.Pass

        and: 'the re-prompt named the accepted answers'
        io.printed.any { it.contains('pass') && it.contains('fail') }
    }
}
