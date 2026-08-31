package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Encodes and decodes the GitHub adapter's structural comment shape (design
 * D9): a leading hidden HTML comment carrying one-line JSON — {@code kind},
 * {@code instance}, {@code at}, format version {@code version}, plus the
 * optional {@code reason} and the {@code task}/{@code intent} content
 * identity — followed by whatever human-readable text the caller supplies,
 * e.g.:
 *
 * <pre>{@code
 * <!-- gnomish {"kind":"claim","instance":"gnomish-factory-x7k2q1","at":"2026-07-20T12:00:00Z","version":1} -->
 * 🤖 gnomish: claimed by gnomish-factory-x7k2q1
 * }</pre>
 *
 * <p>The structural JSON line carries only coordination metadata — never the
 * human message itself, which is a separate line the caller composes freely.
 * GitHub renders HTML comments invisibly, so a human reading the issue thread
 * sees only the human-readable line, while a fresh adapter instance parses the
 * structural fields back out of the raw comment body (the "Markers are
 * invisible to humans, visible to machines" scenario of the github-tracker
 * spec).
 *
 * <p>This class is purely string encode/decode: it never posts or reads
 * comments over HTTP — {@link GithubCommentUpsert} owns that — and carries no
 * kind-specific payload beyond the fixed structural fields; kind-specific
 * content (a cause, a decision text, a report body) is carried entirely in the
 * human-readable text, which the caller controls. The JSON codec itself lives
 * in {@link GithubMarkerJson}.
 *
 * <p>Implements FR7 of add-tracker-port, design D9, NFR-O1 of add-tracker-port,
 * FR11 of harden-task-branch-contract.
 */
public final class GithubMarker {

    /** The structural-JSON format version this codec renders; bump on a breaking wire change. */
    static final int FORMAT_VERSION = 1;

    private static final String COMMENT_PREFIX = "<!-- gnomish ";
    private static final String COMMENT_SUFFIX = " -->";

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
     * field. This is used by {@link GithubMarkerKind#PARK} markers to carry the
     * wire value of a {@link com.github.oinsio.gnomish.app.port.tracker.ParkReason},
     * so a fresh instance's {@code fetchTask} recovers the reason from the
     * marker without inferring it from free-text human wording; {@link
     * GithubStateWrites#finish} writes a {@code FINISH} marker carrying no
     * reason. The dedicated {@code park}/{@code finish} kinds replaced the
     * earlier dual-use {@code report} kind (enforce-finish-terminality design
     * D1), so the distinction is structural rather than inferred from whether
     * {@code reason} happens to be present.
     *
     * @param reason the wire value of the park reason (e.g. {@code
     *     "escalation"}, {@code "checkpoint"}, {@code "infra"}), or {@code
     *     null} when this marker carries no reason (every kind other than
     *     {@code PARK})
     * @return the full comment body ready to post
     */
    public static String render(
            GithubMarkerKind kind, String instanceId, Instant at, String humanText, @Nullable String reason) {
        return render(kind, instanceId, at, humanText, reason, null, null);
    }

    /**
     * Renders a structural comment body stamped with its content identity
     * (FR11): the {@code task}/{@code intent} pair {@link GithubCommentUpsert}
     * finds the comment by when the same logical write is re-driven after a
     * crash. Every other {@code render} overload delegates here, so there is
     * exactly one place the wire shape is built.
     *
     * <p>The tenure's claim {@code epoch} is stamped alongside it (FR13), so a
     * reader can tell which tenure authored the write and classify an older
     * one as stale. A {@code claim} marker carries none: its own comment id
     * <em>is</em> the epoch, and it is not assigned until the comment exists.
     *
     * @param identity the content identity to stamp, or {@code null} for a
     *     marker written outside the upsert protocol
     * @param epoch the tenure this write belongs to, or {@code null} when the
     *     writer holds none
     * @return the full comment body ready to post
     */
    public static String render(
            GithubMarkerKind kind,
            String instanceId,
            Instant at,
            String humanText,
            @Nullable String reason,
            @Nullable GithubCommentIdentity identity,
            @Nullable ClaimEpoch epoch) {
        var fields = new GithubMarkerJson(
                kind.wireValue(),
                instanceId,
                at.toString(),
                FORMAT_VERSION,
                reason,
                identity == null ? null : identity.task(),
                identity == null ? null : identity.intent(),
                epoch == null ? null : epoch.token());
        return COMMENT_PREFIX + fields.serialize() + COMMENT_SUFFIX + "\n" + humanText;
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
        Optional<GithubMarkerJson> parsed = GithubMarkerJson.deserialize(matcher.group("json"));
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        GithubMarkerJson fields = parsed.get();
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
        return Optional.of(new ParsedMarker(
                kind,
                fields.instance(),
                at,
                fields.version(),
                humanText,
                fields.reason(),
                fields.identity().orElse(null),
                fields.epoch() == null ? null : new ClaimEpoch(fields.epoch())));
    }
}
