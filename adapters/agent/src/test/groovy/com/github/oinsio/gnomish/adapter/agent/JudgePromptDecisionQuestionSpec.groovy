package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The judge prompt renders a decision with the question it answers through the one decisions
 * renderer, as the stage round prompt does (design D4 as amended 2026-11-21, D8 row "The question a
 * decision answers" of extract-agent-round-runner).
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class JudgePromptDecisionQuestionSpec extends Specification {

    @TempDir
    Path workspaceRoot

    // FR9: the judge grades against the reply in the light of the question it answered
    def "the judge prompt carries a decision's question, options and reply"() {
        given:
        def asked = new EscalationReport.DecisionNeeded(UntrustedText.agent('Refactor or patch?'), [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ])
        def context = new TaskContext('task-1', UntrustedText.tracker('Fix the widget'), UntrustedText.tracker(''),
                [
                    new Decision('refactor', 'build', 'operator', null, asked)
                ])

        when:
        def prompt = new JudgePromptBuilder(PipelineLaw.ofContent(['criteria.md': 'criteria'])).build(
        new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1), context, new DirectoryWorkspace(workspaceRoot))

        then:
        prompt.contains('''\
- question: Refactor or patch?
  options:
    * refactor
    * patch
  reply (operator): refactor
''')
    }
}
