package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * Shared fixture for the JudgeVoting specs (FR3, FR7, NFR-C1 of add-stage-engine):
 * the common workspace/context, the check and vote builders, and the voting factory
 * reused across {@link JudgeVotingSpec} and {@link JudgeVotingCannotVerifySpec}.
 */
abstract class JudgeVotingSpecBase extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    static VerifyCheck.Judge judge(int votes) {
        new VerifyCheck.Judge('criteria.md', 'model', [:], votes)
    }

    static JudgeVoter.Vote pass(Map<String, TokenUsage> tokensByModel = [:]) {
        new JudgeVoter.Vote(new Verdict.Pass(), tokensByModel)
    }

    static JudgeVoter.Vote fail(List<Finding> findings, Map<String, TokenUsage> tokensByModel = [:]) {
        new JudgeVoter.Vote(new Verdict.Fail(findings), tokensByModel)
    }

    static JudgeVoter.Vote cannotVerify(String reason, String details, Map<String, TokenUsage> tokensByModel = [:]) {
        new JudgeVoter.Vote(new Verdict.CannotVerify(reason, details), tokensByModel)
    }

    JudgeVoting voting(ScriptedJudgeVoter voter) {
        new JudgeVoting(voter)
    }
}
