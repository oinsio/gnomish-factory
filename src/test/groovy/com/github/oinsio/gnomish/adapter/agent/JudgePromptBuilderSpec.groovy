package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, D5, D8: {@link JudgePromptBuilder} composes the judge round prompt from
 * a narrower section subset than the executor's — goal, decisions, acceptance
 * criteria, verdict instruction — deliberately excluding prior-attempt
 * feedback, input artifacts and the control file (a vote grades current
 * state, not process history).
 */
class JudgePromptBuilderSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def builder = new JudgePromptBuilder()

    def "FR8: prompt contains the task goal, decisions, criteria content and verdict instruction in order"() {
        given:
        Files.writeString(workspaceRoot.resolve('criteria.md'), 'The output must be idempotent.')
        def context = new TaskContext('task-1', 'Fix the widget', 'body text',
                [
                    new Decision('use approach A', null, 'alice', null)
                ])
        def check = new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)
        def workspace = new DirectoryWorkspace(workspaceRoot)

        when:
        def prompt = builder.build(check, context, workspace)

        then:
        def goalIdx = prompt.indexOf('=== Task goal ===')
        def decisionsIdx = prompt.indexOf('=== Decisions ===')
        def criteriaIdx = prompt.indexOf('The output must be idempotent.')
        def verdictIdx = prompt.indexOf('passed')

        goalIdx >= 0
        decisionsIdx > goalIdx
        criteriaIdx > decisionsIdx
        verdictIdx > criteriaIdx

        prompt.contains('Fix the widget')
        prompt.contains('use approach A')
    }

    def "FR8: structured-verdict instruction tells the judge to emit a JSON verdict with passed and findings"() {
        given:
        Files.writeString(workspaceRoot.resolve('criteria.md'), 'criteria text')
        def context = new TaskContext('task-1', 'title', '', [])
        def check = new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)

        when:
        def prompt = builder.build(check, context, new DirectoryWorkspace(workspaceRoot))

        then:
        prompt.contains('"passed"')
        prompt.contains('"findings"')
        prompt.toLowerCase().contains('json')
    }

    def "D8: prompt does NOT contain prior-attempt feedback, input-artifact or control-file sections"() {
        given:
        Files.writeString(workspaceRoot.resolve('criteria.md'), 'criteria text')
        def context = new TaskContext('task-1', 'title', '', [])
        def check = new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)

        when:
        def prompt = builder.build(check, context, new DirectoryWorkspace(workspaceRoot))

        then:
        !prompt.contains('=== Prior-attempt feedback ===')
        !prompt.contains('=== Input artifacts ===')
        !prompt.contains('=== Control file')
    }

    def "FR13: an unreadable criteria file throws UnreadableControlFileException before any process would spawn"() {
        given:
        def context = new TaskContext('task-1', 'title', '', [])
        def check = new VerifyCheck.Judge('missing-criteria.md', 'claude-opus', [:], 1)

        when:
        builder.build(check, context, new DirectoryWorkspace(workspaceRoot))

        then:
        def e = thrown(ControlFilePreflight.UnreadableControlFileException)
        e.message.contains('missing-criteria.md')
    }
}
