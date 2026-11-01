package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.TokenUsage

/**
 * Builds a per-model usage map that carries no cost, for specs whose subject is the token counts
 * and not the money (task 5.3 of define-executor-contract): every model keeps its tokens and its
 * cost is absent, exactly as an executor that priced nothing reports it. A spec whose subject is
 * the cost builds its {@link ModelUsage} entries itself.
 *
 * <p>Test fixture, not production code.
 */
final class Unpriced {

    private Unpriced() {}

    /**
     * @param tokensByModel token counts keyed by model id; never null
     * @return the same models, each with its tokens and no cost, in the given order
     */
    static Map<String, ModelUsage> of(Map<String, TokenUsage> tokensByModel) {
        Map<String, ModelUsage> byModel = [:]
        tokensByModel.each { model, tokens ->
            byModel[model] = new ModelUsage(tokens, null)
        }
        byModel
    }
}
