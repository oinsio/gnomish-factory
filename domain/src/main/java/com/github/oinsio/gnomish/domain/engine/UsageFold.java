package com.github.oinsio.gnomish.domain.engine;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one pure fold that sums usage (glossary "Usage fold"; design D15 of define-executor-contract):
 * every token and cost addition of the factory's recorded usage lives here. {@link #fold} adds one
 * recorded round's participant entries to a {@link UsageSnapshot}; {@link #foldAll} folds a list of
 * rounds from {@link UsageSnapshot#EMPTY}. The readers that display a sum take it from a snapshot
 * through {@link #byModel}, {@link #total}, {@link #roleTotal}, {@link #unpricedTotal} or {@link
 * #count}, and the two places that add beyond one task's rounds
 * go through it as well: {@link #addByModel} for the drain run's summary over several tasks, and
 * {@link #sameModel} for one executor report that names one model twice. No other file adds
 * tokens or cost; {@code UsageFoldOwnerBoundarySpec} in {@code :bootstrap} keeps it so.
 *
 * <p>{@code through} is the fold's idempotency key: a round whose token equals the snapshot's
 * {@code through} folds to the snapshot unchanged, so a round persisted again after a kill is never
 * counted twice. A round with no token — the in-place run only, which re-persists nothing — folds
 * without a key. A round with no participant entry is refused: every executed round records at
 * least the entry of the executor that ran it, so an empty list is a broken record, never zero.
 *
 * <p>Implements FR17, NFR-O3 of define-executor-contract.
 */
public final class UsageFold {

    private static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    private UsageFold() {}

    /**
     * The snapshot with {@code round} folded in.
     *
     * @param snapshot the snapshot so far; never null
     * @param round the recorded round to fold; never null
     * @return {@code snapshot} unchanged when the round's token is its {@code through}, otherwise
     *     the snapshot with every participant entry added and its round count advanced by one
     * @throws IllegalArgumentException when the round carries no participant entry
     */
    public static UsageSnapshot fold(UsageSnapshot snapshot, AttemptRecord round) {
        if (round.usage().isEmpty()) {
            throw new IllegalArgumentException(
                    "UsageFold: round " + round.round() + " of stage '" + round.stage() + "' carries no usage entry");
        }
        RoundToken token = round.roundToken();
        if (token != null && token.equals(snapshot.through())) {
            return snapshot;
        }
        var byRole = mutable(snapshot.byRole());
        var unpriced = mutable(snapshot.cost().unpricedTokens());
        BigDecimal priced = snapshot.cost().priced();
        boolean complete = snapshot.cost().complete();
        for (ParticipantUsage entry : round.usage()) {
            complete &= entry.provenance() == UsageProvenance.REPORTED;
            for (var model : entry.byModel().entrySet()) {
                TokenUsage tokens = model.getValue().tokens();
                add(byRole, entry.role(), model.getKey(), tokens);
                BigDecimal cost = model.getValue().costUsd();
                if (cost == null) {
                    add(unpriced, entry.role(), model.getKey(), tokens);
                    complete = false;
                } else {
                    priced = priced.add(cost);
                }
            }
        }
        @Nullable RoundToken through = token == null ? snapshot.through() : token;
        return new UsageSnapshot(byRole, new UsageCost(priced, unpriced, complete), snapshot.rounds() + 1, through);
    }

    /**
     * The snapshot of {@code rounds}, folded in order from {@link UsageSnapshot#EMPTY}.
     *
     * @param rounds the recorded rounds, oldest first; never null
     * @return the folded snapshot; never null
     */
    public static UsageSnapshot foldAll(List<AttemptRecord> rounds) {
        UsageSnapshot snapshot = UsageSnapshot.EMPTY;
        for (AttemptRecord round : rounds) {
            snapshot = fold(snapshot, round);
        }
        return snapshot;
    }

    /**
     * The snapshot's tokens per model, summed over the roles: the per-model map the ledger and the
     * run summary carry.
     *
     * @param snapshot the snapshot; never null
     * @return the tokens per model id; unmodifiable, empty when nothing was reported
     */
    public static Map<String, TokenUsage> byModel(UsageSnapshot snapshot) {
        Map<String, TokenUsage> models = Map.of();
        for (Map<String, TokenUsage> roleModels : snapshot.byRole().values()) {
            models = addByModel(models, roleModels);
        }
        return models;
    }

    /**
     * The snapshot's tokens summed over every role and model: a display total.
     *
     * @param snapshot the snapshot; never null
     * @return the four counts summed; all zero when nothing was reported
     */
    public static TokenUsage total(UsageSnapshot snapshot) {
        return sumAll(byModel(snapshot).values());
    }

    /** One role's tokens summed over its models; all zero when the role reported nothing. */
    public static TokenUsage roleTotal(UsageSnapshot snapshot, ExecutorRole role) {
        return sumAll(snapshot.byRole().getOrDefault(role, Map.of()).values());
    }

    /**
     * The tokens a cost leaves unpriced, summed over every role and model: the count a lower bound
     * names beside its priced sum.
     *
     * @param cost the snapshot's cost; never null
     * @return the unpriced counts summed; all zero when every reported model carried a cost
     */
    public static TokenUsage unpricedTotal(UsageCost cost) {
        TokenUsage total = ZERO;
        for (Map<String, TokenUsage> models : cost.unpricedTokens().values()) {
            total = sum(total, sumAll(models.values()));
        }
        return total;
    }

    /** The four kinds of one figure counted together: the single number a table cell shows. */
    public static long count(TokenUsage tokens) {
        return tokens.input() + tokens.output() + tokens.cacheCreation() + tokens.cacheRead();
    }

    /**
     * Two per-model maps added by model id: the union of the ids, the counts of a shared id summed.
     *
     * @param first one map; never null
     * @param second the other map; never null
     * @return the summed map; unmodifiable
     */
    public static Map<String, TokenUsage> addByModel(Map<String, TokenUsage> first, Map<String, TokenUsage> second) {
        var sum = new HashMap<>(first);
        second.forEach((model, tokens) -> sum.merge(model, tokens, UsageFold::sum));
        return Map.copyOf(sum);
    }

    /**
     * One model reported twice within one executor report — two wire entries the model-id gate maps
     * onto one key: the counts summed, the cost summed only when both halves carry one, so a merged
     * entry never claims a cost that covers tokens nobody priced. Normalisation of one report, not a
     * fold across rounds.
     *
     * @param first one report of the model; never null
     * @param second the other report; never null
     * @return the merged usage; its cost is absent when either half's is
     */
    public static ModelUsage sameModel(ModelUsage first, ModelUsage second) {
        BigDecimal one = first.costUsd();
        BigDecimal other = second.costUsd();
        BigDecimal cost = one == null || other == null ? null : one.add(other);
        return new ModelUsage(sum(first.tokens(), second.tokens()), cost);
    }

    private static TokenUsage sumAll(Collection<TokenUsage> figures) {
        TokenUsage total = ZERO;
        for (TokenUsage tokens : figures) {
            total = sum(total, tokens);
        }
        return total;
    }

    private static TokenUsage sum(TokenUsage a, TokenUsage b) {
        return new TokenUsage(
                a.input() + b.input(),
                a.output() + b.output(),
                a.cacheCreation() + b.cacheCreation(),
                a.cacheRead() + b.cacheRead());
    }

    private static void add(
            Map<ExecutorRole, Map<String, TokenUsage>> byRole, ExecutorRole role, String model, TokenUsage tokens) {
        byRole.computeIfAbsent(role, ignored -> new HashMap<>()).merge(model, tokens, UsageFold::sum);
    }

    private static Map<ExecutorRole, Map<String, TokenUsage>> mutable(
            Map<ExecutorRole, Map<String, TokenUsage>> byRole) {
        var copy = new EnumMap<ExecutorRole, Map<String, TokenUsage>>(ExecutorRole.class);
        byRole.forEach((role, models) -> copy.put(role, new HashMap<>(models)));
        return copy;
    }
}
