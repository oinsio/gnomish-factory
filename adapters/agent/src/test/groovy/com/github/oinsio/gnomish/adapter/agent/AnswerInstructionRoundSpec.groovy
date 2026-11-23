package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The answer instruction of a stage round's prompt is the dialect's (FR19 of
 * extract-agent-round-runner, design D15 part 1): driven through the {@code claude} entry's stage half
 * over the host round source ({@link ClaudeStages}) against the fake agent binary, which records the
 * prompt it received on its stdin — so the words checked are the ones the CLI was given, composed by
 * the production path, not a builder called by hand.
 */
class AnswerInstructionRoundSpec extends Specification {

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    // Scenario: executor/agent-executor / A schema-enforced dialect is not told to write a file
    def "FR19: the prompt of a Claude transform round asks through the final structured reply and names no answer file"() {
        given:
        def capture = workspaceDir.resolve('plain-round.log')
        def properties = new FactoryProperties('factory-01', FakeAgentSupport.binaryCapturingStdin('plain-round', capture), null, null)

        when:
        def result = ClaudeStages.executor(properties, clock, LAW).execute(FakeAgentSupport.requestFor(workspaceDir))
        def prompt = Files.readString(capture)

        then: 'the round ran and the prompt carries the Claude dialect\'s instruction as declared'
        result.status() instanceof ExecutionResult.Completed
        prompt.contains(ClaudeStages.answerInstruction())
        prompt.contains('final structured reply')
        prompt.contains('"decision_request"')

        and: 'it names no answer file: neither the variable nor a path under the envelope directory'
        !prompt.contains(ProgramChannel.ANSWER_FILE_VARIABLE)
        !prompt.contains(EnvelopePaths.DIR_NAME)
        !prompt.contains('answer file')
    }
}
