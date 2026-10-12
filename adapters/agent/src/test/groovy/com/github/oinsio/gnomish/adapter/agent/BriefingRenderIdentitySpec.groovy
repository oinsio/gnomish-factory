package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.briefing.BriefingSections
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4 of remove-interactive-console: the briefing sections are one shared renderer over pre-read
 * data, so the text a section renders is the same whichever adapter asks for it — the stage round
 * prompt, the judge prompt, or a spec calling {@link BriefingSections} directly.
 */
class BriefingRenderIdentitySpec extends Specification {

    @TempDir
    Path workspaceDir

    static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.', 'criteria.md': 'Correct.'])
    static final TaskContext CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('Add login'),
    UntrustedText.tracker('Implement OAuth login.'), [
        new Decision('Use postgres', 'build', 'alice', null)
    ])
    static final List<CheckResult> FEEDBACK = [
        new CheckResult(new CheckRef(0, UntrustedText.manifest('command:make check')),
        new Verdict.Fail([
            new Finding('tests are red', 'BuildSpec.groovy', null)
        ]), Duration.ZERO)
    ]

    private static String section(Closure render) {
        def out = new StringBuilder()
        render(out)
        out.toString()
    }

    // FR4: the stage round prompt carries each section exactly as the shared renderer writes it
    // Scenario: executor/agent-executor / Extraction is invisible to the console
    def "FR4: the executor prompt and a direct render of #name over the same data agree"() {
        given:
        def first = FakeAgentSupport.requestFor(workspaceDir)
        def request = new StageExecutor.Request(CONTEXT, first.stage(), first.workspace(), 1, FEEDBACK)

        expect:
        new ExecutorPromptBuilder(LAW).build(request).contains(section(render))

        where:
        name | render
        'the task goal' | { StringBuilder out ->
            BriefingSections.renderTaskGoal(out, CONTEXT)
        }
        'the decisions' | { StringBuilder out ->
            BriefingSections.renderDecisions(out, CONTEXT.decisions())
        }
        'the feedback' | { StringBuilder out ->
            BriefingSections.renderFeedback(out, FEEDBACK)
        }
        'the control file' | { StringBuilder out ->
            BriefingSections.renderControlFile(out, 'instructions.md', 'Do the thing.')
        }
    }

    // FR4: the judge prompt's subset renders the same text for the same sections
    // Scenario: executor/agent-executor / Extraction is invisible to the console
    def "FR4: the judge prompt and a direct render of the task goal and decisions agree"() {
        when:
        def prompt = new JudgePromptBuilder(LAW).build(
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1), CONTEXT, new DirectoryWorkspace(workspaceDir))

        then:
        prompt.contains(section { StringBuilder out ->
            BriefingSections.renderTaskGoal(out, CONTEXT)
        })
        prompt.contains(section { StringBuilder out ->
            BriefingSections.renderDecisions(out, CONTEXT.decisions())
        })
    }
}
