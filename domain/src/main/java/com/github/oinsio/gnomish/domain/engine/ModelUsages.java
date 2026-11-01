package com.github.oinsio.gnomish.domain.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The token-only view of a per-model {@link ModelUsage} map (task 5.3 of define-executor-contract),
 * for the readers that display tokens only. Kept outside the record for the mutation gate: methods
 * of a record with a type-annotated component crash PIT's minion (hcoles/pitest#1285). It adds
 * nothing: merging two reports of one model is {@link UsageFold#sameModel}, the one owner of usage
 * arithmetic.
 *
 * <p>Implements FR22 of define-executor-contract.
 */
public final class ModelUsages {

    private ModelUsages() {}

    /**
     * The token counts of a per-model map, without the costs — the view of the readers that
     * account or display tokens only.
     *
     * @param byModel usage keyed by model id; never null
     * @return each model's token counts; unmodifiable, empty when {@code byModel} is
     */
    public static Map<String, TokenUsage> tokensOf(Map<String, ModelUsage> byModel) {
        var tokens = new LinkedHashMap<String, TokenUsage>();
        byModel.forEach((model, usage) -> tokens.put(model, usage.tokens()));
        return Map.copyOf(tokens);
    }
}
