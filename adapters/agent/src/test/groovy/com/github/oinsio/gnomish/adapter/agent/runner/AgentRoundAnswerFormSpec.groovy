package com.github.oinsio.gnomish.adapter.agent.runner

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * How an agent round's answer is obtained (FR19 of extract-agent-round-runner, design D15 parts 1 and
 * 2): the dialect declares per role a schema-enforced reply or a file the agent writes; the runner
 * writes a valid schema-enforced answer to the open round's answer path before the round closes and
 * judges the answer against the contract in both forms; the agent of a schema-form dialect is granted
 * no write under {@code .gnomish-task/}, and only a file-form dialect gets {@code writeExactly}.
 * {@link FakeDialect} keeps the file form for {@code transform} by default, so both forms stay under
 * spec; the Claude dialect is the schema form's production instance.
 */
@Timeout(60)
class AgentRoundAnswerFormSpec extends Specification {

    static final ObjectMapper JSON = new ObjectMapper()

    static final String ENVELOPE_ANSWER =
    EnvelopePaths.answerFile(new AttemptKey('TASK-7', 'implement', 1), RoundToken.of('abc123')).relative()

    static final String ASKED = '{"kind":"decision-request","question":"which db?","options":["pg","sqlite"]}'

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent, answerFile: ENVELOPE_ANSWER)
    }

    /** A fixture for a second start over the same working copy, its dialect declaring {@code form} for transform. */
    private RunnerFixture freshFixture(AnswerForm form) {
        def next = new RunnerFixture(workspace: workspace, stderrParent: stderrParent, answerFile: ENVELOPE_ANSWER)
        next.dialect.forms[Role.TRANSFORM] = form
        next
    }

    // Scenario: executor/agent-round-runner / A schema-enforced decision request reaches the answer file
    def "FR19: a schema-enforced reply asking a decision is the result's answer, and the runner wrote it to the round's answer path"() {
        given: 'a dialect declaring the schema-enforced answer for transform; the agent writes no file'
        fixture.dialect.forms[Role.TRANSFORM] = FakeDialect.SCHEMA

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, "answer ${ASKED}\ndone\n"))

        then: 'the result carries the decision request'
        result.status() == Status.COMPLETED
        JSON.readTree(result.answer().toString()) == JSON.readTree(ASKED)

        and: 'the round\'s answer path holds it once the runner returns — before the caller closes the round'
        JSON.readTree(Files.readString(workspace.resolve(ENVELOPE_ANSWER))) == JSON.readTree(ASKED)
    }

    // Scenario: executor/agent-round-runner / A round with no answer completes
    def "FR19: a transform round #what completes with no answer"() {
        given:
        fixture.dialect.forms[Role.TRANSFORM] = form

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'hello m s\ndone\n'))

        then:
        result.status() == Status.COMPLETED
        result.answer() == null
        result.failureClass() == null

        and: 'no answer file was written for it'
        !Files.exists(workspace.resolve(ENVELOPE_ANSWER))

        where:
        what | form
        'whose schema-enforced reply asks nothing' | FakeDialect.SCHEMA
        'of a file-answer dialect that left no file at its answer path' | new AnswerForm.AgentFile()
    }

    // Scenario: executor/agent-round-runner / A decision request keeps the round's usage
    def "FR19: a round ending with a decision request carries the round's usage, as a completed round with no answer does (#form.class.simpleName)"() {
        given:
        fixture.dialect.forms[Role.TRANSFORM] = form
        fixture.dialect.wallMillis = 4321L

        when:
        def asked = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, "answer ${ASKED}\ndone\n"),
                { Path answer, Path err -> writeFile(form, answer, ASKED) })
        Files.deleteIfExists(workspace.resolve(ENVELOPE_ANSWER))
        fixture = freshFixture(form)
        fixture.dialect.wallMillis = 4321L
        def plain = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'done\n'))

        then:
        asked.answer() != null
        plain.answer() == null
        asked.usage() == plain.usage()
        asked.usage().wallMillis() == 4321L

        where:
        form << [
            FakeDialect.SCHEMA,
            new AnswerForm.AgentFile()
        ]
    }

    // Scenario: executor/agent-round-runner / A schema-enforced dialect's agent may not write the answer file
    def "FR19: a transform request of a schema-enforced dialect renders no write under .gnomish-task/, and only a file-form dialect gets writeExactly"() {
        given: 'the stage half of an entry over the Claude dialect, and one over a file-form dialect, opened on a round answering under .gnomish-task/'
        def claude = ClaudeDialect.of('claude', UntrustedText.factory('built-in'), ChildEnvAllowlist.none())
        def fileForm = new FakeDialect()

        when:
        def claudeRequest = stageRounds(claude).opened(RunnerFixture.request(Role.TRANSFORM), round())
        def claudeArgv = claude.render(claudeRequest).argv()
        def fileRequest = stageRounds(fileForm).opened(RunnerFixture.request(Role.TRANSFORM), round())

        then: 'the schema-form request keeps workspaceWrite, and its argv grants no Write allowance at all'
        claudeRequest.policy() instanceof Policy.WorkspaceWrite
        !claudeArgv.any { it.startsWith('Write(') }
        !claudeArgv.any { it.contains(EnvelopePaths.DIR_NAME) }

        and: 'the file-form request writes exactly the round\'s answer path'
        fileRequest.policy() == new Policy.WriteExactly(ENVELOPE_ANSWER)

        and: 'even a writeExactly of a path under .gnomish-task/ reaching the Claude dialect grants no write there'
        [
            '.gnomish-task/decisions/x.json',
            './.gnomish-task/a.json',
            '/work/.gnomish-task/decisions/y.json',
            'src/../.gnomish-task/z.json'
        ].every { path ->
            def argv = claude.render(RunnerFixture.request(Role.TRANSFORM, new Policy.WriteExactly(path))).argv()
            !argv.any { it.startsWith('Write(') }
        }
    }

    // Scenario: executor/agent-round-runner / A file answer is validated by the runner
    def "FR19, FR20: a decision request with one option is refused as invalid in either form, with one and the same finding"() {
        given:
        def oneOption = '{"kind":"decision-request","question":"which db?","options":["pg"]}'

        when: 'a file-form agent writes it to its answer path'
        def written = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'done\n'), { Path answer, Path err ->
            writeFile(new AnswerForm.AgentFile(), answer, oneOption)
        })

        and: 'a schema-form dialect replies with it'
        Files.deleteIfExists(workspace.resolve(ENVELOPE_ANSWER))
        fixture = freshFixture(FakeDialect.SCHEMA)
        def replied = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, "answer ${oneOption}\ndone\n"))

        then: 'both are the round\'s quality failure, refused by the one read, never passed on as a question'
        [written, replied].every {
            it.status() == Status.FAILED && it.failureClass() == FailureClass.QUALITY && it.answer() == null
        }
        written.reason().raw() == replied.reason().raw()
        written.reason().raw().startsWith("agent executor 'fake': ")
    }

    private static void writeFile(AnswerForm form, Path answer, String content) {
        if (form instanceof AnswerForm.AgentFile) {
            Files.createDirectories(answer.parent)
            Files.writeString(answer, content)
        }
    }

    private AgentStageRounds stageRounds(AgentDialect<?> dialect) {
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(new VirtualClock()),
                ChildEnvAllowlist.none().programGrant(), RoundScratchDirectory.HOST_TEMP, LastRegisteredCommit.NONE)
        new AgentStageRounds(new AgentRunners<>('agent', dialect, { event -> } as AgentProgressListener, equipment,
        StdoutCaps.DEFAULTS), PipelineLaw.ofContent([:]))
    }

    private static RoundEnvironmentSource.Round round() {
        [answerFilePath: {
                -> Path.of(ENVELOPE_ANSWER)
            }] as RoundEnvironmentSource.Round
    }
}
