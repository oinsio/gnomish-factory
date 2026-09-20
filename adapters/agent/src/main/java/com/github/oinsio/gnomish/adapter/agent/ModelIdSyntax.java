package com.github.oinsio.gnomish.adapter.agent;

import java.util.regex.Pattern;

/**
 * The shape a model id must have before the factory uses it as a key of its own telemetry.
 *
 * <p>Why a syntax gate rather than a carrier (design D11 of type-untrusted-text). The id arrives
 * as agent output — {@code AgentEvent.InitEvent.model} is untrusted text, and the {@code
 * modelUsage} keys are whatever the CLI wrote — but it is used as the key of {@code
 * Map<String, TokenUsage>}, a map that flows into the domain's {@code ExecutorUsage}, into {@code
 * state.json} and onto the dashboard, where it is displayed. Typing the key would carry a
 * rendering decision into a wire format and an aggregation; leaving it a bare {@code String}
 * would launder agent output onto a screen. So the id is <em>made</em> parsed: what passes this
 * gate is inert by shape, and what does not is replaced by a bounded placeholder rather than
 * dropped, so a round's telemetry still reports that tokens were spent.
 *
 * <p>What makes an accepted id inert: the character class admits no control character, no ANSI
 * escape, no line separator and no whitespace, and the length bound keeps a hostile answer from
 * flooding a record or a dashboard cell.
 *
 * <p>Implements FR10 of type-untrusted-text.
 */
final class ModelIdSyntax {

    /**
     * What a model id may look like: vendor prefixes, dates, version suffixes and the bracketed
     * variant markers real ids carry ({@code claude-opus-4-8[1m]}), and nothing else. The brackets
     * are a deviation from design D11's proposed class, found by the reference dump at task 3.3 —
     * without them every such round's telemetry would key on the placeholder.
     */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:/\\[\\]-]{1,128}");

    /** What an id outside the gate is reported as — one bounded, factory-authored constant. */
    static final String UNUSABLE = "(unusable model id)";

    private ModelIdSyntax() {}

    /**
     * The id if it has the accepted shape, else {@link #UNUSABLE}.
     *
     * @param candidate the model id as the agent reported it; never null
     * @return an id safe to use as a telemetry key and to display; never null
     */
    static String of(String candidate) {
        return ID.matcher(candidate).matches() ? candidate : UNUSABLE;
    }
}
