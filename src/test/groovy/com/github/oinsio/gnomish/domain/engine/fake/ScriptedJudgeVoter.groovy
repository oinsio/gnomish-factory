package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * A scripted {@link JudgeVoter}: returns a queued sequence of {@link JudgeVoter.Vote}s
 * from {@link #vote} (one per call, in order) so a spec can drive the engine's
 * majority loop. Exposes {@link #voteCount} and records every {@link TaskContext}
 * received (to assert the FR7 decisions pass-through). When {@link #toThrow} is set
 * it throws that exception instead. An exhausted script fails loudly.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class ScriptedJudgeVoter implements JudgeVoter {

    /** Votes handed back in order; each {@code vote} call consumes the head. */
    final List<JudgeVoter.Vote> scripted = []

    /** Every context received, in call order, for FR7 pass-through assertions. */
    final List<TaskContext> contexts = []

    /** When non-null, {@code vote} throws this instead of returning a vote. */
    RuntimeException toThrow = null

    ScriptedJudgeVoter(List<JudgeVoter.Vote> scripted = []) {
        this.scripted.addAll(scripted)
    }

    /** Number of times {@code vote} has been invoked. */
    int getVoteCount() {
        contexts.size()
    }

    @Override
    JudgeVoter.Vote vote(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        contexts << context
        if (toThrow != null) {
            throw toThrow
        }
        if (scripted.isEmpty()) {
            throw new IllegalStateException('ScriptedJudgeVoter script exhausted after ' + contexts.size() + ' call(s)')
        }
        scripted.removeFirst()
    }
}
