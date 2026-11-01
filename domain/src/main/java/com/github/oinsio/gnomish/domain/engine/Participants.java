package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@link ParticipantUsage} entries the engine records for a round (design D14 of
 * define-executor-contract): the stage's executor as a {@code transform} entry, each judge vote as
 * a {@code verdict} entry with its vote index. The engine's per-invocation carriers — {@link
 * ExecutorUsage} for the executor, a per-model {@link ModelUsage} map for a vote — say whether the
 * usage was reported: the executor's carrier holds its provenance (a contract executor's own word,
 * the agent CLI's derived from its map), and a vote's map is {@code ABSENT} exactly when empty
 * ({@link UsageProvenance#of}). Each model's tokens and cost pass into the
 * entry as the carrier holds them: a cost the executor did not report stays absent (FR22).
 *
 * <p>Implements FR15, FR22 of define-executor-contract.
 */
public final class Participants {

    /**
     * The executor a judge vote runs on: the agent CLI, the one {@code JudgeVoter} the factory has,
     * named by its type word like a stage executor the law names no executor for. The judge becomes
     * a {@code verdict} executor named by the law in {@code migrate-verify-to-executor-contract}.
     */
    public static final String JUDGE_EXECUTOR = typeWord(ExecutorType.AGENT_CLI);

    private Participants() {}

    /**
     * The law's own spelling of an executor type — {@code agent-cli}, {@code api} — the name a round
     * carries when no described executor names it (task 4.5 of define-executor-contract).
     *
     * @param type the stage's executor type; never null
     * @return the type's word; never blank
     */
    public static String typeWord(ExecutorType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * The {@code transform} entry of the executor that ran a round.
     *
     * @param executor the executor's name; never blank
     * @param usage what the executor reported for the round; never null
     * @return the entry, with the provenance the carrier holds
     */
    public static ParticipantUsage transform(String executor, ExecutorUsage usage) {
        return new ParticipantUsage(
                ExecutorRole.TRANSFORM,
                executor,
                null,
                usage.provenance(),
                usage.wallTime(),
                usage.tools(),
                usage.byModel());
    }

    /**
     * The {@code verdict} entry of one judge vote.
     *
     * @param executor the executor that cast the vote; never blank
     * @param vote the vote's 0-based index among the round's judge votes; never negative
     * @param byModel the vote's per-model usage; empty when unreported
     * @return the entry, {@code ABSENT} when no usage was reported
     */
    public static ParticipantUsage verdict(String executor, int vote, Map<String, ModelUsage> byModel) {
        return new ParticipantUsage(
                ExecutorRole.VERDICT, executor, vote, UsageProvenance.of(byModel), null, List.of(), byModel);
    }
}
