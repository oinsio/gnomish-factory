package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives a round's {@code tokensByModel} map from a {@link
 * AgentEvent.ResultEvent}'s raw wire data (design D4, FR5): {@code
 * modelUsage} is authoritative when present — every key becomes an entry,
 * keyed by the CLI-resolved model id; when the wire event omitted {@code
 * modelUsage} entirely (older CLIs), the flat {@code usage} object is used
 * instead, keyed by the round's {@link AgentEvent.InitEvent#model()}.
 *
 * <p>Telemetry is best-effort (FR4, NFR-R2): this class never throws.
 * Numeric coercion or a missing field degrades gracefully, at two
 * granularities. A malformed <em>entry</em> inside an otherwise-usable {@code
 * modelUsage} map (missing/non-numeric field, or a value that is not even a
 * map) is skipped on its own — its sibling entries still map, since each
 * entry is an independent model's report and one bad entry says nothing
 * about the others. The flat {@code usage} fallback has only one entry to
 * report, so any field trouble there — or a missing init event to key it
 * with — degrades the <em>whole</em> map to empty rather than emitting a
 * partially-filled {@link TokenUsage}, which would misrepresent a real (if
 * incomplete) report as a fabricated zero in the missing fields.
 *
 * <p>Implements FR5, D4 of add-agent-executor.
 */
final class TokenUsageMapper {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageMapper.class);

    /**
     * Derives the round's {@code tokensByModel} map from the result event.
     *
     * @param resultEvent the round's result event; never null
     * @param initEvent the round's init event, used only for the fallback's
     *     model key, or {@code null} when none was parsed
     * @return {@code tokensByModel}, keyed by resolved model id; never null,
     *     empty when neither wire shape was interpretable (NFR-R2)
     */
    Map<String, TokenUsage> toTokensByModel(
            AgentEvent.ResultEvent resultEvent, AgentEvent.@Nullable InitEvent initEvent) {
        Map<String, Object> modelUsage = resultEvent.modelUsage();
        if (modelUsage != null) {
            return fromModelUsage(modelUsage);
        }
        return fromFlatUsage(resultEvent.usage(), initEvent);
    }

    private Map<String, TokenUsage> fromModelUsage(Map<String, Object> modelUsage) {
        Map<String, TokenUsage> tokensByModel = new LinkedHashMap<>();
        modelUsage.forEach((model, rawEntry) -> {
            TokenUsage tokens = toTokenUsage(
                    rawEntry, "inputTokens", "outputTokens", "cacheCreationInputTokens", "cacheReadInputTokens");
            if (tokens == null) {
                log.debug("stream-json: skipping modelUsage entry for model '{}' (unusable shape)", model);
            } else {
                tokensByModel.put(model, tokens);
            }
        });
        return tokensByModel;
    }

    private Map<String, TokenUsage> fromFlatUsage(
            @Nullable Map<String, Object> usage, AgentEvent.@Nullable InitEvent initEvent) {
        if (usage == null || initEvent == null) {
            return Map.of();
        }
        TokenUsage tokens = toTokenUsage(
                usage, "input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens");
        if (tokens == null) {
            log.debug("stream-json: skipping flat usage fallback (unusable shape)");
            return Map.of();
        }
        return Map.of(initEvent.model(), tokens);
    }

    private @Nullable TokenUsage toTokenUsage(
            @Nullable Object rawEntry,
            String inputKey,
            String outputKey,
            String cacheCreationKey,
            String cacheReadKey) {
        if (!(rawEntry instanceof Map<?, ?> entry)) {
            return null;
        }
        Long input = toLong(entry.get(inputKey));
        Long output = toLong(entry.get(outputKey));
        Long cacheCreation = toLong(entry.get(cacheCreationKey));
        Long cacheRead = toLong(entry.get(cacheReadKey));
        if (input == null || output == null || cacheCreation == null || cacheRead == null) {
            return null;
        }
        return new TokenUsage(input, output, cacheCreation, cacheRead);
    }

    private @Nullable Long toLong(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
