package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification

/**
 * A continuation key is reserved vocabulary, read and dropped (FR9, NFR-R2 of
 * extract-agent-round-runner; design D4 as amended 2026-11-21): a {@code program} round whose
 * {@code decision-request} answer carries a key crosses into the engine as the question and its
 * options alone, and the next round, started after the decision is answered, is a {@code start} the
 * one request builder ({@link ProgramRequests}) makes with no continuation.
 */
class ProgramContinuationKeySpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)

    // Scenario: executor/wire-contract / A returned continuation key is not sent back
    def "FR9: a decision request's continuation key is dropped, and the next round's start carries no continuation"() {
        given: 'a program round answered a decision request carrying a continuation key'
        def answer = new ObjectMapper().valueToTree([kind: 'decision-request', question: 'Which?', options: ['a', 'b'],
            continuationKey: 'session-9'])
        def asked = ProgramResultMapping.toEngine('acme', Role.TRANSFORM, KEY,
                new ExecutorResult(Status.COMPLETED, null, null, null, answer, Usage.absent(), []), [])

        expect: 'the engine sees the question and its options alone'
        asked.answer() == new ExecutionResult.DecisionRequest(UntrustedText.subprocess('Which?'),
                [
                    UntrustedText.subprocess('a'),
                    UntrustedText.subprocess('b')
                ])

        when: 'the decision is answered and the next round is started'
        def answered = new TaskContext('TASK-7', UntrustedText.tracker('title'), UntrustedText.tracker('body'),
                [
                    new Decision('a', 'build', 'operator', Instant.EPOCH)
                ])
        def next = ProgramRequests.of(
                new StageExecutor.Request(answered, ProgramFixtures.stage(), new DirectoryWorkspace(Path.of('.')), 1, []),
                PipelineLaw.ofContent(['instructions.md': 'build it\n']), ProgramFixtures.declaration(['unused']),
                ProgramBounds.DEFAULTS)

        then:
        next.role() == Role.TRANSFORM
        next.continuation() == null
    }
}
