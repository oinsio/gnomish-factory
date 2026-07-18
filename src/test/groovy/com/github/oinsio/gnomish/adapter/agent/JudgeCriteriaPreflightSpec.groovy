package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-R1, D8: {@link JudgeCriteriaPreflight} is the judge-side mirror of
 * {@link ControlFilePreflight} — where the executor lets an unreadable
 * control/criteria file propagate as {@link
 * ControlFilePreflight.UnreadableControlFileException} (an infrastructure
 * failure with no port-level exception channel to catch it), the {@code
 * JudgeVoter} port has no such channel: {@code vote()} never throws (NFR-R1),
 * so the eventual {@code CliJudgeVoter} (task 7.5) must catch the exception
 * itself and return a {@link com.github.oinsio.gnomish.domain.engine.Verdict.CannotVerify}
 * Vote instead — "before any process starts, never a criteria-less vote".
 */
class JudgeCriteriaPreflightSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def "a readable criteria file yields no CannotVerify signal"() {
        given:
        Files.writeString(workspaceRoot.resolve('criteria.md'), 'The output must be idempotent.')
        def check = new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)

        when:
        def result = JudgeCriteriaPreflight.checkReadable(workspaceRoot, check)

        then:
        result.isEmpty()
    }

    def "a missing criteria file yields CannotVerify naming the criteria file"() {
        given:
        def check = new VerifyCheck.Judge('missing-criteria.md', 'claude-opus', [:], 1)

        when:
        def result = JudgeCriteriaPreflight.checkReadable(workspaceRoot, check)

        then:
        result.isPresent()
        def verdict = result.get()
        !verdict.reason().isBlank()
        verdict.reason().contains('missing-criteria.md')
        verdict.details() != null
    }

    def "a criteria file reference escaping the workspace yields CannotVerify naming the criteria file"() {
        given:
        def check = new VerifyCheck.Judge('../secret.md', 'claude-opus', [:], 1)

        when:
        def result = JudgeCriteriaPreflight.checkReadable(workspaceRoot, check)

        then:
        result.isPresent()
        def verdict = result.get()
        verdict.reason().contains('../secret.md')
        verdict.reason().contains('escapes')
    }
}
