package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * FR13, NFR-R1, D8 of add-agent-executor; FR19, D14 of add-sandbox-core: {@link
 * JudgeCriteriaPreflight} is the judge-side mirror of the executor's control-file
 * read — where the executor lets an unreadable law file propagate as {@link
 * com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException} (an
 * infrastructure failure with no port-level exception channel to catch it), the
 * {@code JudgeVoter} port has no such channel: {@code vote()} never throws
 * (NFR-R1), so {@code CliJudgeVoter} must catch it here and return a {@link
 * com.github.oinsio.gnomish.domain.engine.Verdict.CannotVerify} Vote instead —
 * "before any process starts, never a criteria-less vote". Criteria come from
 * the frozen {@link PipelineLaw}, not the working copy.
 */
class JudgeCriteriaPreflightSpec extends Specification {

    def "a criteria file frozen readable in the law yields no CannotVerify signal"() {
        given:
        def law = PipelineLaw.ofContent(['criteria.md': 'The output must be idempotent.'])
        def check = new VerifyCheck.Judge('criteria.md', 'claude-opus', [:], 1)

        when:
        def result = JudgeCriteriaPreflight.checkReadable(law, check)

        then:
        result.isEmpty()
    }

    def "a criteria file the frozen law could not read yields CannotVerify naming the criteria file"() {
        given:
        def law = PipelineLaw.ofContent([:])
        def check = new VerifyCheck.Judge('missing-criteria.md', 'claude-opus', [:], 1)

        when:
        def result = JudgeCriteriaPreflight.checkReadable(law, check)

        then:
        result.isPresent()
        def verdict = result.get()
        !verdict.reason().isBlank()
        verdict.reason().contains('missing-criteria.md')
        verdict.details() != null
    }
}
