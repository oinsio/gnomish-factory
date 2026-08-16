package com.github.oinsio.gnomish.adapter.briefing

import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import java.time.Duration
import spock.lang.Specification

/**
 * FR14, D8 of add-agent-executor: {@link BriefingSections} renders each
 * briefing section in isolation from pre-read data only — no file I/O, no
 * coupling to {@code StageExecutor.Request} — so callers other than the
 * interactive adapter (the judge, task 6/7) can compose their own subset.
 */
class BriefingSectionsSpec extends Specification {

    def "renders the task goal with a non-empty body"() {
        given:
        def out = new StringBuilder()
        def context = new TaskContext('task-1', 'Add login page', 'Implement OAuth login.', [])

        when:
        BriefingSections.renderTaskGoal(out, context)

        then:
        out.toString() == "=== Task goal ===\nAdd login page\nImplement OAuth login.\n\n"
    }

    def "renders the task goal without a body line when body is empty"() {
        given:
        def out = new StringBuilder()
        def context = new TaskContext('task-1', 'Title only', '', [])

        when:
        BriefingSections.renderTaskGoal(out, context)

        then:
        out.toString() == "=== Task goal ===\nTitle only\n\n"
    }

    def "renders input artifacts by kind and producer id"() {
        given:
        def out = new StringBuilder()
        def inputs = [
            new ArtifactInput.Internal('design-doc'),
            new ArtifactInput.Source()
        ]

        when:
        BriefingSections.renderInputArtifacts(out, inputs)

        then:
        out.toString() == "=== Input artifacts ===\n" +
                "- internal: produced by design-doc\n" +
                "- source: arrives with the task's working copy\n\n"
    }

    def "renders (none) for empty input artifacts"() {
        given:
        def out = new StringBuilder()

        when:
        BriefingSections.renderInputArtifacts(out, [])

        then:
        out.toString() == "=== Input artifacts ===\n(none)\n\n"
    }

    def "renders prior-attempt feedback with findings for a failed check"() {
        given:
        def out = new StringBuilder()
        def feedback = [
            new CheckResult(new CheckRef(0, 'command:./gradlew test'),
            new Verdict.Fail([
                new Finding('tests are red', 'BuildSpec.groovy', null)
            ]),
            Duration.ofSeconds(1))
        ]

        when:
        BriefingSections.renderFeedback(out, feedback)

        then:
        out.toString() == "=== Prior-attempt feedback ===\n" +
                "- command:./gradlew test: failed\n    * tests are red\n\n"
    }

    def "renders a cannot-verify feedback result with its reason"() {
        given:
        def out = new StringBuilder()
        def feedback = [
            new CheckResult(new CheckRef(1, 'external:ci'),
            new Verdict.CannotVerify('ci unreachable', ''), Duration.ofSeconds(2))
        ]

        when:
        BriefingSections.renderFeedback(out, feedback)

        then:
        out.toString() == "=== Prior-attempt feedback ===\n- external:ci: cannot verify: ci unreachable\n\n"
    }

    def "renders (none) for empty feedback"() {
        given:
        def out = new StringBuilder()

        when:
        BriefingSections.renderFeedback(out, [])

        then:
        out.toString() == "=== Prior-attempt feedback ===\n(none)\n\n"
    }

    def "renders an attributed decision with its author"() {
        given:
        def out = new StringBuilder()
        def decisions = [
            new Decision('Use Google OAuth only', 'build', 'alice', null)
        ]

        when:
        BriefingSections.renderDecisions(out, decisions)

        then:
        out.toString() == "=== Decisions ===\n- alice: Use Google OAuth only\n\n"
    }

    def "renders a null-author decision as unattributed"() {
        given:
        def out = new StringBuilder()
        def decisions = [
            new Decision('Ship it', 'build', null, null)
        ]

        when:
        BriefingSections.renderDecisions(out, decisions)

        then:
        out.toString() == "=== Decisions ===\n- unattributed: Ship it\n\n"
    }

    def "renders (none) for empty decisions"() {
        given:
        def out = new StringBuilder()

        when:
        BriefingSections.renderDecisions(out, [])

        then:
        out.toString() == "=== Decisions ===\n(none)\n\n"
    }

    def "renders the control-file section with the given ref and pre-read content verbatim"() {
        given:
        def out = new StringBuilder()

        when:
        BriefingSections.renderControlFile(out, 'instructions.md', 'Follow the coding standard.')

        then:
        out.toString() == "=== Control file (instructions.md) ===\nFollow the coding standard.\n"
    }

    def "renders whatever placeholder string the caller hands in, without opinion"() {
        given:
        def out = new StringBuilder()

        when:
        BriefingSections.renderControlFile(out, 'missing.md', '(caller-chosen placeholder)')

        then:
        out.toString() == "=== Control file (missing.md) ===\n(caller-chosen placeholder)\n"
    }

    def "renderExecutorBriefing composes all five sections in order"() {
        given:
        def out = new StringBuilder()
        def context = new TaskContext('task-1', 'Add login page', 'Implement OAuth login.',
                [
                    new Decision('Use Google OAuth only', 'build', 'alice', null)
                ])
        def inputs = [
            new ArtifactInput.Internal('design-doc')
        ]
        def feedback = [
            new CheckResult(new CheckRef(0, 'command:./gradlew test'),
            new Verdict.Fail([
                new Finding('tests are red', 'BuildSpec.groovy', null)
            ]),
            Duration.ofSeconds(1))
        ]

        when:
        BriefingSections.renderExecutorBriefing(
                out, context, inputs, feedback, 'instructions.md', 'Follow the coding standard.')
        def rendered = out.toString()

        then:
        rendered == "=== Task goal ===\nAdd login page\nImplement OAuth login.\n\n" +
                "=== Input artifacts ===\n- internal: produced by design-doc\n\n" +
                "=== Prior-attempt feedback ===\n- command:./gradlew test: failed\n    * tests are red\n\n" +
                "=== Decisions ===\n- alice: Use Google OAuth only\n\n" +
                "=== Control file (instructions.md) ===\nFollow the coding standard.\n"
    }
}
