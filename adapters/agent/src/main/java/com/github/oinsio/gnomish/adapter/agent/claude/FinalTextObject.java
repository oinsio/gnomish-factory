package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The tolerant read of the one JSON object an agent's final text carries (design D5 of
 * add-agent-executor; FR3 of extract-agent-round-runner): a markdown code fence stripped if present,
 * then the first balanced {@code {...}} span parsed. The one owner of that read, for the judge
 * round's final message and for the Claude dialect's fallback when the CLI reported no structured
 * output.
 *
 * <p>The scan is a bracket match, not a JSON tokenizer: a brace inside a string literal
 * desynchronizes it, and the span then fails to parse — treated as no answer, never engineered
 * around, since the text is the agent's own reply to an explicit instruction.
 *
 * <p>An {@link UntrustedParser}: the final text becomes a parsed JSON tree, whose texts its readers
 * mint again in the agent's family.
 *
 * <p>Implements FR8, D5 of add-agent-executor; FR3 of extract-agent-round-runner.
 */
@UntrustedParser
public final class FinalTextObject {

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FinalTextObject() {}

    /**
     * Reads the object {@code finalText} carries.
     *
     * @param finalText the agent's final text; never null
     * @return the object, or why there is none; never null
     */
    public static Reading of(UntrustedText finalText) {
        String text = finalText.forParsing();
        Matcher fence = FENCE.matcher(text);
        String span = firstObject(fence.find() ? fence.group(1) : text);
        if (span == null) {
            return new Reading.NoObject();
        }
        try {
            return new Reading.Parsed(MAPPER.readTree(span));
        } catch (JsonProcessingException malformed) {
            return new Reading.NotJson();
        }
    }

    private static @Nullable String firstObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** What the final text held. */
    public sealed interface Reading {

        /** No balanced object: no brace, or one that never closes. */
        record NoObject() implements Reading {}

        /** A balanced span that is not valid JSON. */
        record NotJson() implements Reading {}

        /**
         * The object.
         *
         * @param object the parsed span; never null
         */
        record Parsed(JsonNode object) implements Reading {}
    }
}
