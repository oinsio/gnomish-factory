package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import java.time.Instant

/**
 * Builds {@link AttemptRecord}s for specs whose subject is not the round's identity (task 5.1 of
 * define-executor-contract): the record's {@code taskId}, {@code stage} and {@code roundToken} are
 * the fixed {@link #TASK_ID}, {@link #STAGE} and {@link #TOKEN}, and the remaining components are
 * the spec's own. A spec that compares a record with one the engine or a mapper produced, or that
 * asserts the identity itself, names the identity through {@link #identified} instead.
 *
 * <p>A spec whose subject is not the participant list hands the executor's {@link ExecutorUsage};
 * the record's usage is then the one {@code transform} entry the engine builds from it, under the
 * executor name {@link #EXECUTOR} — the name a stage with no described executor records (FR15 of
 * define-executor-contract). A spec that needs judge votes or another role hands the list itself.
 *
 * <p>Test fixture, not production code; the production record has no shorter constructor.
 */
final class AttemptRecords {

    /** The task every fixture record belongs to. */
    static final String TASK_ID = 'T-1'

    /** The stage every fixture record names. */
    static final String STAGE = 'build'

    /** The round token every fixture record carries: a branch-mode round's tip. */
    static final RoundToken TOKEN = RoundToken.of('0123456789abcdef0123456789abcdef01234567')

    /** The executor every fixture record's {@code transform} entry names: the agent CLI's type word. */
    static final String EXECUTOR = Participants.typeWord(ExecutorType.AGENT_CLI)

    private AttemptRecords() {}

    /** The participant list the engine records for a round whose executor reported {@code usage}. */
    static List<ParticipantUsage> executed(ExecutorUsage usage) {
        [
            Participants.transform(EXECUTOR, usage)
        ]
    }

    /** A record under the fixture identity, the other components as given. */
    static AttemptRecord of(int round, AttemptRecord.Result result, Instant startedAt, List<CheckResult> checks,
            ExecutorUsage usage, List<Denial> denials, Stop stop) {
        of(round, result, startedAt, checks, executed(usage), denials, stop)
    }

    /** A record under the fixture identity with the given participant list. */
    static AttemptRecord of(int round, AttemptRecord.Result result, Instant startedAt, List<CheckResult> checks,
            List<ParticipantUsage> usage, List<Denial> denials, Stop stop) {
        identified(TASK_ID, STAGE, TOKEN, round, result, startedAt, checks, usage, denials, stop)
    }

    /** A record under the given identity whose executor reported {@code usage}. */
    static AttemptRecord identified(String taskId, String stage, RoundToken token, int round,
            AttemptRecord.Result result, Instant startedAt, List<CheckResult> checks, ExecutorUsage usage,
            List<Denial> denials, Stop stop) {
        identified(taskId, stage, token, round, result, startedAt, checks, executed(usage), denials, stop)
    }

    /** A record under the given identity: the canonical constructor, spelled by name. */
    static AttemptRecord identified(String taskId, String stage, RoundToken token, int round,
            AttemptRecord.Result result, Instant startedAt, List<CheckResult> checks, List<ParticipantUsage> usage,
            List<Denial> denials, Stop stop) {
        new AttemptRecord(taskId, stage, token, round, result, startedAt, checks, usage, denials, stop)
    }
}
