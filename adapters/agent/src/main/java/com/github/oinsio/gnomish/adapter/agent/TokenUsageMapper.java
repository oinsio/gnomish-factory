package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
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
 * <p>Entries that share a key are summed, not overwritten. Distinct keys in the
 * wire map can still collide here, because {@link ModelIdSyntax} maps every id
 * it refuses onto one placeholder: a round reporting two unusable ids has two
 * entries and one key. Overwriting would lose the first entry's tokens from the
 * round's whole accounting while leaving the map non-empty, so nothing would
 * report the loss — the degradation this class is allowed is an unnamed model,
 * never an unreported cost.
 *
 * <p>Implements FR5, D4 of add-agent-executor.
 */
@UntrustedParser
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
        Map<String, TokenUsage> tokensByModel =
                modelUsage != null ? fromModelUsage(modelUsage) : fromFlatUsage(resultEvent.usage(), initEvent);
        if (tokensByModel.isEmpty()) {
            // The per-entry lines above are DEBUG because one skipped model says nothing about the
            // round; an extraction that yields nothing at all is different — the round's whole cost
            // is lost to the budget and the summary, and the operator sees "unreported" with no way
            // to tell it from a round that genuinely spent nothing (FR5 of
            // harden-logging-observability).
            // throwable-not-subject: the shapes were classified above, not thrown.
            log.warn(OperatorEvent.TOKEN_USAGE_UNREPORTED.head()
                    + "stream-json: the round reported no usable token usage; its cost reads as unreported");
        }
        return tokensByModel;
    }

    private Map<String, TokenUsage> fromModelUsage(Map<String, Object> modelUsage) {
        Map<String, TokenUsage> tokensByModel = new LinkedHashMap<>();
        modelUsage.forEach((model, rawEntry) -> {
            TokenUsage tokens = toTokenUsage(
                    rawEntry, "inputTokens", "outputTokens", "cacheCreationInputTokens", "cacheReadInputTokens");
            // The keys are whatever the agent wrote, and they travel into state.json and onto the
            // dashboard, so each is held to ModelIdSyntax before it becomes a key (design D11).
            String id = modelKey(model);
            if (tokens == null) {
                log.debug("stream-json: skipping modelUsage entry for model '{}' (unusable shape)", id);
            } else {
                // Merged, never overwritten. ModelIdSyntax is many-to-one — every id it refuses
                // becomes the same placeholder — so two refused ids in one round arrive at one key,
                // and a put would silently drop the first one's tokens from ExecutorUsage, from
                // state.json, from the budget and from the dashboard, with the empty-map WARN below
                // staying quiet because the map is not empty. Summing keeps the round's total
                // honest: the placeholder reports what was spent under ids that could not be named,
                // which is the whole of what is still knowable about them.
                tokensByModel.merge(id, tokens, TokenUsage::plus);
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
        // @UntrustedParser warrant (design D11): the init event's model id becomes a telemetry
        //     key, held to ModelIdSyntax — which is what makes it inert enough for state.json and
        //     the dashboard, where it is displayed.
        return Map.of(modelKey(initEvent.model().forParsing()), tokens);
    }

    /**
     * Holds one reported model id to {@link ModelIdSyntax} and leaves a trace when the gate refused
     * it. The substitution is a degraded result — the telemetry, {@code state.json} and the
     * dashboard all key on the placeholder rather than on the id the agent reported — and a
     * degraded result with no log line is what {@code .claude/rules/logging.md} calls a finding.
     *
     * <p>DEBUG, not WARN: the round's cost is still reported in full, so nothing asks the operator
     * to act; this is the per-entry detail that explains an odd key once someone is diagnosing one.
     * The refused candidate itself is never logged — it is exactly the value that failed the gate
     * — so the line names its length instead, which is inert and still tells a truncated id from a
     * flood.
     */
    private String modelKey(String candidate) {
        String id = ModelIdSyntax.of(candidate);
        if (ModelIdSyntax.UNUSABLE.equals(id)) {
            // throwable-not-subject: the candidate was classified by its shape, not thrown.
            log.debug(
                    "stream-json: a reported model id has no accepted shape ({} characters);"
                            + " its tokens are keyed on '{}'",
                    candidate.length(),
                    ModelIdSyntax.UNUSABLE);
        }
        return id;
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
