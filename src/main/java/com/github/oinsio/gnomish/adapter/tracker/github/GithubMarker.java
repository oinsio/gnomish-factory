package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Encodes and decodes the GitHub adapter's structural comment shape (design
 * D9): a leading hidden HTML comment carrying one-line JSON — {@code kind},
 * {@code instance}, {@code at}, format version {@code version} — followed by
 * whatever human-readable text the caller supplies, e.g.:
 *
 * <pre>{@code
 * <!-- gnomish {"kind":"claim","instance":"gnomish-factory-x7k2q1","at":"2026-07-20T12:00:00Z","version":1} -->
 * 🤖 gnomish: claimed by gnomish-factory-x7k2q1
 * }</pre>
 *
 * <p>The structural JSON line carries only coordination metadata — never the
 * human message itself, which is a separate line the caller composes freely.
 * GitHub renders HTML comments invisibly, so a human reading the issue thread
 * sees only the human-readable line, while a fresh adapter instance parses
 * kind/instance/at/version back out of the raw comment body (the "Markers are
 * invisible to humans, visible to machines" scenario of the github-tracker
 * spec).
 *
 * <p>This class is purely string encode/decode: it never posts or reads
 * comments over HTTP (that is tasks 4.11–4.14) and carries no kind-specific
 * payload beyond the four fixed structural fields — kind-specific content
 * (a cause, a decision text, a report body) is carried entirely in the
 * human-readable text, which the caller controls.
 *
 * <p>Implements FR7 of add-tracker-port, design D9, NFR-O1 of add-tracker-port.
 */
public final class GithubMarker {

    /** The structural-JSON format version this codec renders; bump on a breaking wire change. */
    static final int FORMAT_VERSION = 1;

    private static final String COMMENT_PREFIX = "<!-- gnomish ";
    private static final String COMMENT_SUFFIX = " -->";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Matches the first line only; DOTALL not needed since '.' already excludes '\n'.
    private static final Pattern MARKER_LINE =
            Pattern.compile("^<!-- gnomish (?<json>\\{.*}) -->(\\n(?<rest>.*))?$", Pattern.DOTALL);

    private GithubMarker() {}

    /**
     * Renders a structural comment body: the hidden HTML-comment JSON line
     * (kind, instance, at, the fixed {@link #FORMAT_VERSION}), a newline,
     * then {@code humanText} verbatim.
     *
     * @param kind the marker kind
     * @param instanceId the identifier of the instance posting the marker; never blank
     * @param at when the marker is created; never null
     * @param humanText the human-readable text to render below the structural line
     * @return the full comment body ready to post
     */
    public static String render(GithubMarkerKind kind, String instanceId, Instant at, String humanText) {
        return render(kind, instanceId, at, humanText, null);
    }

    /**
     * Renders a structural comment body carrying an optional {@code reason}
     * field alongside kind/instance/at/version. This is used by {@code report}-kind
     * park markers to carry the wire value of a {@link
     * com.github.oinsio.gnomish.app.port.tracker.ParkReason} (design D9's
     * marker-kind vocabulary has no dedicated {@code park} kind; a park is a
     * {@code report}-kind marker whose {@code reason} field this class adds
     * specifically so {@code fetchTask} — task 4.10 — can read it back
     * without inferring it from free-text human wording). Task 4.14, which
     * implements the {@code park} write path, SHOULD post this same field
     * when it posts a park marker, so a fresh instance's {@code fetchTask}
     * can recover the reason.
     *
     * @param reason the wire value of the park reason (e.g. {@code
     *     "escalation"}, {@code "checkpoint"}, {@code "infra"}), or {@code
     *     null} when this marker carries no reason (every kind other than a
     *     park report)
     * @return the full comment body ready to post
     */
    public static String render(
            GithubMarkerKind kind, String instanceId, Instant at, String humanText, @Nullable String reason) {
        String json = writeStructuralJson(kind, instanceId, at, reason);
        return COMMENT_PREFIX + json + COMMENT_SUFFIX + "\n" + humanText;
    }

    /**
     * Parses a comment body previously rendered by {@link #render}, or any
     * comment following the same shape. Returns {@link Optional#empty()} —
     * never throws — when {@code commentBody} does not start with the
     * {@code gnomish} structural HTML comment (e.g. an operator's own reply)
     * or when the embedded JSON is malformed, so callers can distinguish a
     * factory marker from an arbitrary human comment without exception
     * handling.
     *
     * @param commentBody the raw comment body to parse
     * @return the parsed marker, or empty if {@code commentBody} carries no
     *     recognizable {@code gnomish} structural marker
     */
    public static Optional<ParsedMarker> parse(String commentBody) {
        Matcher matcher = MARKER_LINE.matcher(commentBody);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        StructuralFields fields;
        try {
            fields = MAPPER.readValue(matcher.group("json"), StructuralFields.class);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (fields.kind() == null || fields.instance() == null || fields.at() == null) {
            return Optional.empty();
        }
        GithubMarkerKind kind;
        Instant at;
        try {
            kind = GithubMarkerKind.fromWireValue(fields.kind());
            at = Instant.parse(fields.at());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
        String rest = matcher.group("rest");
        String humanText = rest == null ? "" : rest;
        return Optional.of(new ParsedMarker(kind, fields.instance(), at, fields.version(), humanText, fields.reason()));
    }

    private static String writeStructuralJson(
            GithubMarkerKind kind, String instanceId, Instant at, @Nullable String reason) {
        try {
            var fields = new StructuralFields(kind.wireValue(), instanceId, at.toString(), FORMAT_VERSION, reason);
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize gnomish structural marker JSON", e);
        }
    }

    /**
     * Jackson-bound carrier for the structural JSON; field declaration order
     * fixes wire-key order. {@code reason} is omitted from the rendered JSON
     * entirely when {@code null} (via {@link JsonInclude}), so every marker
     * kind other than a park report keeps the original four-field shape.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StructuralFields(
            @Nullable @JsonProperty("kind") String kind,
            @Nullable @JsonProperty("instance") String instance,
            @Nullable @JsonProperty("at") String at,
            @JsonProperty("version") int version,
            @Nullable @JsonProperty("reason") String reason) {}
}
