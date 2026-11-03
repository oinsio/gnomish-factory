package com.github.oinsio.gnomish.domain.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The usage of one executor invocation within a round (glossary "Participant usage"; design D14 of
 * define-executor-contract): its {@link ExecutorRole}, the executor's name, the vote index of a
 * {@code verdict}, its {@link UsageProvenance}, the optional wall time and per-tool aggregates, and
 * per resolved model id the {@link ModelUsage} — the four token counts with an optional cost. A
 * round's {@link AttemptRecord} holds one list of them: the stage's executor as a {@code transform}
 * entry and each judge vote as its own {@code verdict} entry. A list keyed by the role grows nothing
 * when a role is added, where a role-named slot per participant would grow a field and a DTO tree.
 *
 * <p>{@code vote} is present exactly on a {@code verdict} entry and is never negative: the vote's
 * 0-based position among the round's judge votes. {@code wallTime} is {@code null} when unknown and
 * non-negative when present. {@code tools} and {@code byModel} are defensively copied and
 * unmodifiable; an {@code ABSENT} entry carries an empty {@code byModel}, which keeps unknown
 * distinct from zero. Inert value data compared by content. The entries the engine records are
 * built by {@link Participants}; the checks live in {@link ParticipantUsageChecks}, outside the
 * record, for the mutation gate (hcoles/pitest#1285).
 *
 * <p>Implements FR15 of define-executor-contract.
 *
 * @param role the executor role the invocation ran under; never null
 * @param executor the executor's name; never blank
 * @param vote the 0-based vote index of a {@code verdict} entry; {@code null} on every other role
 * @param provenance whether the executor reported its usage; never null
 * @param wallTime the invocation's wall time, or {@code null} when unknown; never negative
 * @param tools the per-tool aggregates; possibly empty
 * @param byModel the usage per resolved model id; empty when nothing was reported
 */
public record ParticipantUsage(
        ExecutorRole role,
        String executor,
        @Nullable Integer vote,
        UsageProvenance provenance,
        @Nullable Duration wallTime,
        List<ToolUsage> tools,
        Map<String, ModelUsage> byModel) {

    public ParticipantUsage {
        executor = ParticipantUsageChecks.requireExecutor(executor);
        vote = ParticipantUsageChecks.requireVoteOfRole(role, vote);
        wallTime = ParticipantUsageChecks.requireNonNegativeWall(wallTime);
        tools = List.copyOf(tools);
        byModel = Map.copyOf(byModel);
    }
}
