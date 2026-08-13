package com.github.oinsio.gnomish.adapter.law

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19, NFR-S2, D14 of add-sandbox-core: {@link PipelineLawReader} freezes the
 * stage instructions and judge acceptance-criteria content of an invocation
 * once, from the law-source {@code .gnomish/} root, and {@link PipelineLaw}
 * hands it back for the invocation's lifetime.
 *
 * <p>The freeze contract: content is captured once and never re-read, so an
 * edit to a law file after freeze — the reward-hacking move where a running
 * gnome rewrites its own instructions or weakens its own criteria — has no
 * effect on the running task.
 */
class PipelineLawReaderSpec extends Specification {

    @TempDir
    Path lawRoot

    def "FR19: freezes each stage's instructions and every judge check's criteria content"() {
        given:
        write('instructions.md', 'Do the thing.')
        write('criteria.md', 'The output must be idempotent.')
        def definition = pipeline(stage('instructions.md', [
            new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)
        ]))

        when:
        def law = PipelineLawReader.freeze(lawRoot, definition)

        then:
        law.controlFile('instructions.md') == 'Do the thing.'
        law.controlFile('criteria.md') == 'The output must be idempotent.'
    }

    def "FR19, NFR-S2, D14: an edit to a law file after freeze does not affect the frozen law"() {
        given:
        write('instructions.md', 'Original instructions.')
        def definition = pipeline(stage('instructions.md', []))
        def law = PipelineLawReader.freeze(lawRoot, definition)

        when: 'the gnome rewrites the same file in its working copy after the law was bound'
        write('instructions.md', 'Weakened instructions the gnome planted.')

        then: 'the running task still sees the law bound at invocation start'
        law.controlFile('instructions.md') == 'Original instructions.'
    }

    def "FR13, D14: a missing law file is captured as unreadable and surfaces at use time"() {
        given:
        def definition = pipeline(stage('missing.md', []))
        def law = PipelineLawReader.freeze(lawRoot, definition)

        when:
        law.controlFile('missing.md')

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('missing.md')
    }

    // FR13, D14: the captured unreadable reason is the IOException's own message (here the
    // missing file's resolved path), not a bare exception class name — so the infrastructure
    // failure surfacing at use time diagnoses the actual cause.
    def "an unreadable law file's captured reason preserves the underlying IO cause"() {
        given:
        def definition = pipeline(stage('missing.md', []))
        def law = PipelineLawReader.freeze(lawRoot, definition)

        when:
        law.controlFile('missing.md')

        then: 'the reason carries NoSuchFileException\'s message: the resolved path itself'
        def e = thrown(UnreadableLawFileException)
        e.message.contains(lawRoot.resolve('missing.md').toString())
    }

    def "NFR-S2: a law reference escaping the law-source root is captured unreadable, never read"() {
        given:
        def definition = pipeline(stage('../secret.md', []))
        def law = PipelineLawReader.freeze(lawRoot, definition)

        when:
        law.controlFile('../secret.md')

        then:
        def e = thrown(UnreadableLawFileException)
        e.message.contains('../secret.md')
        e.message.contains('escapes')
    }

    private void write(String name, String content) {
        Files.writeString(lawRoot.resolve(name), content)
    }

    private static PipelineDefinition pipeline(StageDefinition stage) {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    private static StageDefinition stage(String instructionsRef, List<VerifyCheck> checks) {
        new StageDefinition(
                'implement',
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-opus', [:]),
                instructionsRef,
                checks,
                new AutonomyLimits(3),
                AdvancementMode.AUTO)
    }
}
