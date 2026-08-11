package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-manual-run; FR19, D14 of add-sandbox-core: the interactive {@link
 * InteractiveJudgeVoter} prints the acceptance-criteria content from the frozen
 * {@link PipelineLaw} before prompting once per vote — {@code pass} / {@code
 * fail} — collecting findings on {@code fail}; unrecognized input re-prompts
 * (UX1).
 */
class InteractiveJudgeVoterSpec extends Specification {

    @TempDir
    Path projectRoot

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['criteria.md': 'Acceptance: the widget spins.'])

    private static TaskContext sampleContext() {
        new TaskContext('TASK-1', 'title', 'body', [])
    }

    private static VerifyCheck.Judge sampleCheck() {
        new VerifyCheck.Judge('criteria.md', 'gpt-5', [:], 1)
    }

    private DirectoryWorkspace sampleWorkspace() {
        new DirectoryWorkspace(projectRoot)
    }

    def "criteria content is printed before the pass/fail prompt"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console, LAW)

        when:
        voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        io.printed.any { it.contains('Acceptance: the widget spins.') }
    }

    def "pass answer yields Vote with Verdict.Pass and an empty token map"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console, LAW)

        when:
        def vote = voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        vote.verdict() instanceof Verdict.Pass
        vote.tokensByModel().isEmpty()
    }

    def "fail answer collects findings until an empty line, yielding Vote with Verdict.Fail and an empty token map"() {
        given:
        def io = new ScriptedConsoleIO([
            'fail',
            'widget does not spin',
            'missing a screw',
            ''
        ])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console, LAW)

        when:
        def vote = voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        vote.verdict() instanceof Verdict.Fail
        vote.verdict().findings()*.message() == [
            'widget does not spin',
            'missing a screw'
        ]
        vote.tokensByModel().isEmpty()
    }

    def "D14: criteria content comes from the frozen law regardless of the workspace instance"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console, LAW)
        def opaqueWorkspace = new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}

        when:
        voter.vote(sampleCheck(), sampleContext(), opaqueWorkspace)

        then:
        io.printed.any { it.contains('Acceptance: the widget spins.') }
    }

    def "a criteria ref the frozen law could not read prints the exact could-not-be-read placeholder naming the ref"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def voter = new InteractiveJudgeVoter(console, PipelineLaw.ofContent([:]))
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
        def voter = new InteractiveJudgeVoter(console, LAW)

        when:
        def vote = voter.vote(sampleCheck(), sampleContext(), sampleWorkspace())

        then:
        vote.verdict() instanceof Verdict.Pass

        and: 'the re-prompt named the accepted answers'
        io.printed.any { it.contains('pass') && it.contains('fail') }
    }
}
